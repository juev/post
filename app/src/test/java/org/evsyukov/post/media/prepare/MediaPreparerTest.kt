package org.evsyukov.post.media.prepare

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.core.media.Conversion
import org.evsyukov.post.core.media.MediaKind
import org.evsyukov.post.data.db.AttachmentEntity
import org.evsyukov.post.data.db.PostDatabase
import org.evsyukov.post.data.db.PostEntity
import org.evsyukov.post.data.db.PostKind
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.inMemoryDatabase
import org.evsyukov.post.data.rows
import org.evsyukov.post.media.convert.ConversionException
import org.evsyukov.post.media.convert.ConvertedFile
import org.evsyukov.post.media.convert.MediaConverter
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/** Variants (media.md A2, A4): made once per attachment and conversion, reused, shared, and deleted with their files. */
@RunWith(AndroidJUnit4::class)
class MediaPreparerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: PostDatabase
    private lateinit var filesDir: File
    private val converter = FakeConverter()
    private lateinit var preparer: MediaPreparer

    private val blueskyImage = Conversion.ConvertImage("image/jpeg", maxLongSide = 4000, maxPixels = null, maxBytes = 2_000_000, stripMetadata = true)
    private val mastodonImage = Conversion.ConvertImage("image/jpeg", maxLongSide = null, maxPixels = 8_294_400, maxBytes = 16_777_216, stripMetadata = true)
    private val video = Conversion.TranscodeVideo("video/mp4", maxShortSide = 1080, videoBitrate = 4_000_000, audioBitrate = 128_000, maxBytes = 100_000_000)

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        filesDir = temp.newFolder("files")
        preparer = MediaPreparer(db, filesDir, converter)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun aConversionMakesAVariantRowAndFileUnderThePostDirectory() = runBlocking<Unit> {
        val post = post()
        val photo = attachment(post, "photo.heic", "image/heic")

        val prepared = preparer.prepare(post, listOf(VariantRequest(photo, "bluesky", blueskyImage))).single()

        assertThat(prepared.attachmentId).isEqualTo(photo)
        assertThat(prepared.profileKey).isEqualTo("bluesky")
        assertThat(prepared.path).startsWith("variants/$post/")
        assertThat(prepared.path).endsWith(".jpg")
        assertThat(prepared.mime).isEqualTo("image/jpeg")
        assertThat(prepared.width).isEqualTo(40)
        assertThat(File(filesDir, prepared.path).readText()).isEqualTo("converted photo.heic")
        assertThat(prepared.size).isEqualTo(File(filesDir, prepared.path).length())
        val variant = db.attachmentDao().variant(photo, MediaPreparer.profileKeyOf(blueskyImage))!!
        assertThat(variant.path).isEqualTo(prepared.path)
        assertThat(File(filesDir, "variants/$post").listFiles()!!.map { it.name }).containsExactly(File(prepared.path).name)
    }

    @Test
    fun theOriginalGoesForAsIsAndAGifGoesToBlueskyAsGifWithoutAVariant() = runBlocking<Unit> {
        val post = post()
        val photo = attachment(post, "photo.jpg", "image/jpeg", width = 800, height = 600)
        val gif = attachment(post, "cat.gif", "image/gif", kind = MediaKind.GIF)

        val prepared = preparer.prepare(
            post,
            listOf(
                VariantRequest(photo, "mastodon", Conversion.AsIs),
                VariantRequest(gif, "mastodon", Conversion.AsIs),
                VariantRequest(gif, "bluesky", Conversion.GifAsVideo),
            ),
        )

        assertThat(prepared.map { it.path }).containsExactly("attachments/$post/photo.jpg", "attachments/$post/cat.gif", "attachments/$post/cat.gif").inOrder()
        assertThat(prepared.map { it.mime }).containsExactly("image/jpeg", "image/gif", "image/gif").inOrder()
        assertThat(prepared[0].width).isEqualTo(800)
        assertThat(converter.calls).isEmpty()
        assertThat(db.rows("SELECT * FROM variants")).isEmpty()
    }

    @Test
    fun accountsOfOneNetworkWithTheSameConversionShareOneVariant() = runBlocking<Unit> {
        val post = post()
        val photo = attachment(post, "photo.heic", "image/heic")

        val prepared = preparer.prepare(
            post,
            listOf(
                VariantRequest(photo, "mastodon:1", mastodonImage),
                VariantRequest(photo, "mastodon:2", mastodonImage),
                VariantRequest(photo, "bluesky:3", blueskyImage),
            ),
        )

        assertThat(converter.calls).hasSize(2)
        assertThat(prepared.map { it.profileKey }).containsExactly("mastodon:1", "mastodon:2", "bluesky:3").inOrder()
        assertThat(prepared[0].path).isEqualTo(prepared[1].path)
        assertThat(prepared[2].path).isNotEqualTo(prepared[0].path)
        assertThat(db.rows("SELECT attachmentId FROM variants")).hasSize(2)
    }

    @Test
    fun aSecondCallReusesTheVariantsItMade() = runBlocking<Unit> {
        val post = post()
        val photo = attachment(post, "photo.heic", "image/heic")
        val clip = attachment(post, "clip.mov", "video/quicktime", kind = MediaKind.VIDEO)
        val requests = listOf(VariantRequest(photo, "bluesky", blueskyImage), VariantRequest(clip, "bluesky", video))
        val first = preparer.prepare(post, requests)

        val second = preparer.prepare(post, requests)

        assertThat(second).isEqualTo(first)
        assertThat(converter.calls).hasSize(2)
        assertThat(db.rows("SELECT id FROM variants")).hasSize(2)
    }

    @Test
    fun aVariantWhoseFileIsGoneIsMadeAgain() = runBlocking<Unit> {
        val post = post()
        val photo = attachment(post, "photo.heic", "image/heic")
        val request = listOf(VariantRequest(photo, "bluesky", blueskyImage))
        val first = preparer.prepare(post, request).single()
        File(filesDir, first.path).delete()

        val second = preparer.prepare(post, request).single()

        assertThat(converter.calls).hasSize(2)
        assertThat(File(filesDir, second.path).isFile).isTrue()
        assertThat(db.rows("SELECT path FROM variants")).containsExactly(listOf(second.path))
    }

    @Test
    fun aChangedConversionMakesANewVariantInsteadOfReusingAStaleOne() = runBlocking<Unit> {
        val post = post()
        val photo = attachment(post, "photo.heic", "image/heic")
        val first = preparer.prepare(post, listOf(VariantRequest(photo, "mastodon", mastodonImage))).single()

        val smaller = mastodonImage.copy(maxBytes = 8_000_000)
        val second = preparer.prepare(post, listOf(VariantRequest(photo, "mastodon", smaller))).single()

        assertThat(second.path).isNotEqualTo(first.path)
        assertThat(converter.calls).hasSize(2)
        assertThat(converter.calls.last().second).isEqualTo(smaller)
    }

    @Test
    fun aFailedConversionLeavesNeitherARowNorAFile() = runBlocking<Unit> {
        val post = post()
        val photo = attachment(post, "photo.heic", "image/heic")
        converter.failure = ConversionException.CannotFit(2_000_000, 3_000_000)

        assertThrows(ConversionException.CannotFit::class.java) {
            runBlocking { preparer.prepare(post, listOf(VariantRequest(photo, "bluesky", blueskyImage))) }
        }

        assertThat(db.rows("SELECT * FROM variants")).isEmpty()
        assertThat(File(filesDir, "variants/$post").listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun progressReachesOneHundredOverAllConversions() = runBlocking<Unit> {
        val post = post()
        val photo = attachment(post, "photo.heic", "image/heic")
        val clip = attachment(post, "clip.mov", "video/quicktime", kind = MediaKind.VIDEO)
        val progress = mutableListOf<Int>()

        preparer.prepare(post, listOf(VariantRequest(photo, "b", blueskyImage), VariantRequest(clip, "b", video))) { progress += it }

        assertThat(progress).isInOrder()
        assertThat(progress).contains(50)
        assertThat(progress.last()).isEqualTo(100)
    }

    @Test
    fun anAttachmentOfAnotherPostIsRejected() = runBlocking<Unit> {
        val post = post()
        val other = post()
        val photo = attachment(other, "photo.heic", "image/heic")

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { preparer.prepare(post, listOf(VariantRequest(photo, "bluesky", blueskyImage))) }
        }
        assertThat(converter.calls).isEmpty()
    }

    @Test
    fun deleteVariantsRemovesTheRowsAndFilesOfOnePostAndKeepsTheOriginals() = runBlocking<Unit> {
        val post = post()
        val other = post()
        val photo = attachment(post, "photo.heic", "image/heic")
        val otherPhoto = attachment(other, "photo.heic", "image/heic")
        val mine = preparer.prepare(post, listOf(VariantRequest(photo, "bluesky", blueskyImage))).single()
        val theirs = preparer.prepare(other, listOf(VariantRequest(otherPhoto, "bluesky", blueskyImage))).single()

        preparer.deleteVariants(post)

        assertThat(db.rows("SELECT path FROM variants")).containsExactly(listOf(theirs.path))
        assertThat(File(filesDir, mine.path).exists()).isFalse()
        assertThat(File(filesDir, "variants/$post").exists()).isFalse()
        assertThat(File(filesDir, theirs.path).isFile).isTrue()
        assertThat(File(filesDir, "attachments/$post/photo.heic").isFile).isTrue()
        assertThat(db.attachmentDao().get(photo)).isNotNull()
    }

    @Test
    fun theProfileKeyDependsOnTheWholeConversion() {
        assertThat(MediaPreparer.profileKeyOf(blueskyImage)).isEqualTo(MediaPreparer.profileKeyOf(blueskyImage.copy()))
        assertThat(MediaPreparer.profileKeyOf(blueskyImage)).isNotEqualTo(MediaPreparer.profileKeyOf(blueskyImage.copy(stripMetadata = false)))
        assertThat(MediaPreparer.profileKeyOf(video)).isNotEqualTo(MediaPreparer.profileKeyOf(video.copy(maxFrameRate = 60)))
        assertThat(MediaPreparer.profileKeyOf(blueskyImage)).startsWith("image-")
        assertThat(MediaPreparer.profileKeyOf(video)).startsWith("video-")
    }

    private suspend fun post(): Long = db.postDao().insert(
        PostEntity(
            kind = PostKind.NEW,
            state = PostState.DRAFT,
            text = "",
            optionsJson = "{}",
            targetsJson = "[]",
            scheduleKind = null,
            slotLocal = null,
            sendAt = null,
            queueOrder = 0,
            historyId = null,
            createdAt = 0,
            updatedAt = 0,
        ),
    )

    private suspend fun attachment(
        post: Long,
        name: String,
        mime: String,
        kind: MediaKind = MediaKind.IMAGE,
        width: Int? = null,
        height: Int? = null,
    ): Long {
        val path = "attachments/$post/$name"
        File(filesDir, path).apply { parentFile!!.mkdirs(); writeText(name) }
        return db.attachmentDao().insert(
            AttachmentEntity(
                postId = post,
                position = 0,
                pieceIndex = 0,
                kind = kind,
                path = path,
                mime = mime,
                sizeBytes = name.length.toLong(),
                width = width,
                height = height,
                durationMs = null,
                frameRate = null,
                animated = kind == MediaKind.GIF,
                hasTransparency = false,
                videoCodec = null,
                alt = "",
                focusX = null,
                focusY = null,
                createdAt = 0,
            ),
        )
    }

    /** Writes "converted <name>" and remembers each call; reports half and full progress. */
    private class FakeConverter : MediaConverter {
        val calls = mutableListOf<Pair<File, Conversion>>()
        var failure: Exception? = null

        override suspend fun convert(source: File, conversion: Conversion, output: File, onProgress: (Int) -> Unit): ConvertedFile {
            calls += source to conversion
            onProgress(50)
            output.writeText("partial")
            failure?.let { throw it }
            output.writeText("converted ${source.name}")
            onProgress(100)
            val mime = if (conversion is Conversion.TranscodeVideo) "video/mp4" else "image/jpeg"
            return ConvertedFile(mime, output.length(), 40, 30)
        }
    }
}
