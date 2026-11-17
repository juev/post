package org.evsyukov.post.data.posts

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.core.media.MediaKind
import org.evsyukov.post.data.MutableClock
import org.evsyukov.post.data.RecordingFiles
import org.evsyukov.post.data.db.PostDatabase
import org.evsyukov.post.data.inMemoryDatabase
import org.evsyukov.post.data.settings.HistoryRetention
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration
import java.time.Instant

/**
 * Copies and variants that nothing refers to go once they are a day old (media.md A1): a process killed while a draft
 * had unsaved attachments leaves them behind, and nothing else would ever delete them.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentSweeperTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val clock = MutableClock(Instant.parse("2026-11-17T12:00:00Z"))
    private val db: PostDatabase = inMemoryDatabase()
    private val repo = RoomPostsRepository(db, RecordingFiles(), { HistoryRetention.DAYS_30 }, clock)
    private val root: File by lazy { temp.newFolder("files") }
    private val inUse = FilesInUse()
    private val sweeper by lazy { AttachmentSweeper(db.attachmentDao(), root, inUse, clock) }

    private val dayAndASecond: Duration = Duration.ofHours(24).plusSeconds(1)

    @After
    fun tearDown() = db.close()

    /** A file at [path] under the files directory, last changed [age] ago. */
    private fun file(path: String, age: Duration): File = File(root, path).apply {
        parentFile!!.mkdirs()
        writeText(path)
        setLastModified(clock.now.minus(age).toEpochMilli())
    }

    private fun File.aged(age: Duration): File = apply { setLastModified(clock.now.minus(age).toEpochMilli()) }

    private fun image(path: String) = Attachment(kind = MediaKind.IMAGE, path = path, mime = "image/jpeg", sizeBytes = 1, width = 1, height = 1)

    @Test
    fun aDayOldCopyThatNoRowRefersToIsDeleted() = runBlocking<Unit> {
        val orphan = file("attachments/orphan.jpg", dayAndASecond)
        val partial = file("attachments/cut-short.part", dayAndASecond)

        assertThat(sweeper.sweep()).isEqualTo(2)

        assertThat(orphan.exists()).isFalse()
        assertThat(partial.exists()).isFalse()
        assertThat(File(root, "attachments").isDirectory).isTrue()
    }

    @Test
    fun aCopyYoungerThanADayStays() = runBlocking<Unit> {
        // The composer restored after process death may still bring it back.
        val young = file("attachments/young.jpg", Duration.ofHours(23))

        assertThat(sweeper.sweep()).isEqualTo(0)

        assertThat(young.exists()).isTrue()
    }

    @Test
    fun theFilesOfSavedAttachmentsAndVariantsStayWhateverTheirAge() = runBlocking<Unit> {
        val id = repo.saveDraft(DraftInput(text = "kept", attachments = listOf(image("attachments/saved.jpg"))))
        val attachment = repo.get(id)!!.attachments.single()
        repo.putVariant(Variant(attachmentId = attachment.id, profileKey = "p", path = "variants/$id/1-p.jpg", mime = "image/jpeg", sizeBytes = 1))
        val original = file("attachments/saved.jpg", Duration.ofDays(30))
        val variant = file("variants/$id/1-p.jpg", Duration.ofDays(30))
        val staleVariant = file("variants/$id/1-old.jpg", Duration.ofDays(30))

        assertThat(sweeper.sweep()).isEqualTo(1)

        assertThat(original.exists()).isTrue()
        assertThat(variant.exists()).isTrue()
        assertThat(staleVariant.exists()).isFalse()
    }

    @Test
    fun theCopiesOfAnOpenComposerStayUntilItLetsGo() = runBlocking<Unit> {
        // The composer was left open for more than a day with an attachment it never saved.
        val unsaved = file("attachments/unsaved.jpg", Duration.ofDays(2))
        val owner = Any()
        inUse.hold(owner) { listOf("attachments/unsaved.jpg") }

        assertThat(sweeper.sweep()).isEqualTo(0)
        assertThat(unsaved.exists()).isTrue()

        inUse.release(owner)
        assertThat(sweeper.sweep()).isEqualTo(1)
        assertThat(unsaved.exists()).isFalse()
    }

    @Test
    fun theVariantDirectoryOfAPostThatIsGoneIsRemovedOnceEmpty() = runBlocking<Unit> {
        val id = repo.saveDraft(DraftInput(text = "kept", attachments = listOf(image("attachments/saved.jpg"))))
        file("attachments/saved.jpg", Duration.ofDays(3))
        val ownDir = File(root, "variants/$id").apply { mkdirs() }.aged(Duration.ofDays(3))
        val goneDir = File(root, "variants/999").apply { mkdirs() }.aged(Duration.ofDays(3))
        val goneWithFile = file("variants/998/1-p.jpg", Duration.ofDays(3)).parentFile!!.aged(Duration.ofDays(3))
        val youngDir = File(root, "variants/997").apply { mkdirs() }

        sweeper.sweep()

        // A conversion of the post with attachments may be about to write into its directory.
        assertThat(ownDir.isDirectory).isTrue()
        assertThat(goneDir.exists()).isFalse()
        // Deleting the file just changed the directory; the next sweep takes it.
        assertThat(File(goneWithFile, "1-p.jpg").exists()).isFalse()
        assertThat(youngDir.isDirectory).isTrue()
        assertThat(File(root, "variants").isDirectory).isTrue()
    }

    @Test
    fun filesOutsideTheAttachmentAndVariantDirectoriesAreNeverTouched() = runBlocking<Unit> {
        val other = file("mastodon-sign-in.json", Duration.ofDays(30))
        val nested = file("datastore/settings.preferences_pb", Duration.ofDays(30))
        val elsewhere = temp.newFolder("elsewhere")
        val target = File(elsewhere, "secret.jpg").apply { writeText("keep") }.aged(Duration.ofDays(30))
        File(root, "attachments").mkdirs()
        java.nio.file.Files.createSymbolicLink(File(root, "attachments/link").toPath(), elsewhere.toPath())

        assertThat(sweeper.sweep()).isEqualTo(0)

        assertThat(other.exists()).isTrue()
        assertThat(nested.exists()).isTrue()
        assertThat(target.exists()).isTrue()
    }

    @Test
    fun withoutTheDirectoriesThereIsNothingToDo() = runBlocking<Unit> {
        assertThat(sweeper.sweep()).isEqualTo(0)
    }
}
