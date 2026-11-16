package org.evsyukov.post.media.importer

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.core.media.MediaKind
import org.evsyukov.post.data.posts.Attachment
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The importer copies each file into app storage at once (R9) and reads its metadata (media.md A1). Video
 * and audio durations, frame rates and codecs need the platform decoders and are checked on the device
 * (`AttachmentMetadataDeviceTest`); here they are only typed.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentImporterTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var filesDir: File
    private lateinit var importer: AttachmentImporter

    @Before
    fun setUp() {
        filesDir = temp.newFolder("files")
        importer = AttachmentImporter(context.contentResolver, filesDir)
    }

    /** A copy of the fixture [name] outside app storage, as a picker would hand it over. */
    private fun source(name: String, as_: String = name): File {
        val file = File(temp.newFolder(), as_)
        requireNotNull(javaClass.getResourceAsStream("/media/$name")).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun importOne(name: String, piece: Int = 0): Attachment {
        val result = runBlocking { importer.import(listOf(Uri.fromFile(source(name))), piece) }
        assertThat(result.failures).isEmpty()
        return result.attachments.single()
    }

    private fun stored(): List<String> = File(filesDir, AttachmentImporter.DIR).list().orEmpty().sorted()

    @Test
    fun anImportCancelledHalfwayLeavesNoCopiesBehind() {
        // The Share sheet is closed while its files are copied: the copies made so far belong to no post (R9).
        val first = Uri.fromFile(source("photo_exif_rot90.jpg"))
        val second = Uri.parse("content://org.evsyukov.post.test/second.jpg")
        lateinit var job: kotlinx.coroutines.Job
        val bytes = source("photo_exif_rot90.jpg").readBytes()
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStream(
            second,
            object : java.io.ByteArrayInputStream(bytes) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    job.cancel()
                    return super.read(b, off, len)
                }
            },
        )

        runBlocking {
            job = launch(kotlinx.coroutines.Dispatchers.Default) { importer.import(listOf(first, second), 0) }
            job.join()
        }

        assertThat(job.isCancelled).isTrue()
        assertThat(stored()).isEmpty()
    }

    @Test
    fun aFileIsCopiedAtOnceAndOutlivesItsSource() {
        val original = source("photo_exif_rot90.jpg")
        val bytes = original.readBytes()
        val result = runBlocking { importer.import(listOf(Uri.fromFile(original)), 0) }
        val attachment = result.attachments.single()

        // The source and its grant are gone; the copy is still there, byte for byte.
        assertThat(original.delete()).isTrue()
        assertThat(attachment.path).matches("attachments/[0-9a-f-]{36}\\.jpg")
        val copy = importer.file(attachment.path)
        assertThat(copy.readBytes()).isEqualTo(bytes)
        assertThat(attachment.id).isEqualTo(0)
        assertThat(attachment.sizeBytes).isEqualTo(bytes.size.toLong())
    }

    @Test
    fun aJpegIsShownTurnedByItsOrientationAndKeepsItsGpsUntilUpload() {
        val photo = importOne("photo_exif_rot90.jpg")
        assertThat(photo.kind).isEqualTo(MediaKind.IMAGE)
        assertThat(photo.mime).isEqualTo("image/jpeg")
        // 64x48 pixels with orientation 6 (rotate 90): shown 48 wide and 64 high.
        assertThat(photo.width).isEqualTo(48)
        assertThat(photo.height).isEqualTo(64)
        assertThat(photo.hasTransparency).isFalse()
        assertThat(photo.animated).isFalse()
        // The copy is the original: removing GPS is the upload's job (A6), not the import's.
        val exif = ExifInterface(importer.file(photo.path))
        assertThat(exif.latLong).isNotNull()
        assertThat(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)).isEqualTo(ExifInterface.ORIENTATION_ROTATE_90)
    }

    @Test
    fun aPngWithTranslucentPixelsHasTransparencyAndAnOpaqueOneHasNot() {
        val alpha = importOne("alpha.png")
        assertThat(alpha.kind).isEqualTo(MediaKind.IMAGE)
        assertThat(alpha.mime).isEqualTo("image/png")
        assertThat(alpha.width to alpha.height).isEqualTo(40 to 30)
        assertThat(alpha.hasTransparency).isTrue()

        val opaque = importOne("opaque_rgba.png")
        assertThat(opaque.hasTransparency).isFalse()
    }

    @Test
    fun onlyAnAnimatedGifIsAGif() {
        val animated = importOne("animated.gif")
        assertThat(animated.kind).isEqualTo(MediaKind.GIF)
        assertThat(animated.mime).isEqualTo("image/gif")
        assertThat(animated.animated).isTrue()
        assertThat(animated.width to animated.height).isEqualTo(32 to 24)

        val still = importOne("still.gif")
        assertThat(still.kind).isEqualTo(MediaKind.IMAGE)
        assertThat(still.mime).isEqualTo("image/gif")
        assertThat(still.animated).isFalse()
    }

    @Test
    fun videoAndAudioAreTypedByTheirBytes() {
        val clip = importOne("clip.mp4")
        assertThat(clip.kind).isEqualTo(MediaKind.VIDEO)
        assertThat(clip.mime).isEqualTo("video/mp4")
        assertThat(clip.path).endsWith(".mp4")

        val tone = importOne("tone.mp3")
        assertThat(tone.kind).isEqualTo(MediaKind.AUDIO)
        assertThat(tone.mime).isEqualTo("audio/mpeg")
        assertThat(tone.path).endsWith(".mp3")
    }

    @Test
    fun everyAttachmentBelongsToThePieceItWasAddedIn() {
        val uris = listOf("alpha.png", "still.gif").map { Uri.fromFile(source(it)) }
        val result = runBlocking { importer.import(uris, pieceIndex = 2) }
        assertThat(result.attachments.map { it.pieceIndex }).containsExactly(2, 2)
        assertThat(result.attachments.map { it.mime }).containsExactly("image/png", "image/gif").inOrder()
    }

    @Test
    fun aFileThatFailsLeavesNothingAndTheOthersStillCome() {
        val missing = Uri.fromFile(File(temp.root, "gone.jpg"))
        val text = Uri.fromFile(File(temp.newFolder(), "notes.txt").apply { writeText("plain text, not media") })
        val good = Uri.fromFile(source("alpha.png"))

        val result = runBlocking { importer.import(listOf(missing, text, good), 0) }

        assertThat(result.failures).containsExactly(
            AttachmentImporter.Failure(missing, AttachmentImporter.Reason.UNREADABLE),
            AttachmentImporter.Failure(text, AttachmentImporter.Reason.UNSUPPORTED),
        ).inOrder()
        assertThat(result.attachments).hasSize(1)
        // Only the good file is in storage: no partial copy of the others.
        assertThat(stored()).containsExactly(File(result.attachments.single().path).name)
    }

    @Test
    fun aSharedFileUriIntoTheAppsOwnStorageIsRefused() {
        // Another app shares file:///data/data/org.evsyukov.post/…: Post must not copy its own private files.
        val dataDir = temp.newFolder("data")
        val guarded = AttachmentImporter(context.contentResolver, File(dataDir, "files").apply { mkdirs() }, privateDir = dataDir)
        val secret = File(dataDir, "files/attachments").apply { mkdirs() }.let { source("alpha.png").copyTo(File(it, "other-draft.png")) }
        val sneaky = Uri.parse("file://" + File(dataDir, "files/../files/attachments/other-draft.png").path)
        val outside = Uri.fromFile(source("still.gif"))

        val result = runBlocking { guarded.import(listOf(Uri.fromFile(secret), sneaky, outside), 0) }

        assertThat(result.failures.map { it.reason }).containsExactly(AttachmentImporter.Reason.UNREADABLE, AttachmentImporter.Reason.UNREADABLE)
        assertThat(result.attachments.map { it.mime }).containsExactly("image/gif")
    }

    @Test
    fun aContentUriWithoutAProviderIsUnreadable() {
        val uri = Uri.parse("content://org.evsyukov.post.nothing/1")
        val result = runBlocking { importer.import(listOf(uri), 0) }
        assertThat(result.attachments).isEmpty()
        assertThat(result.failures.single().reason).isEqualTo(AttachmentImporter.Reason.UNREADABLE)
        assertThat(stored()).isEmpty()
    }
}
