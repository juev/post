package org.evsyukov.post.media

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.evsyukov.post.core.media.Conversion.TranscodeVideo
import org.evsyukov.post.core.media.MediaKind
import org.evsyukov.post.data.db.AttachmentEntity
import org.evsyukov.post.data.db.PostDatabase
import org.evsyukov.post.data.db.PostEntity
import org.evsyukov.post.data.db.PostKind
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.media.convert.ConversionException
import org.evsyukov.post.media.convert.DeviceMediaConverter
import org.evsyukov.post.media.convert.VideoTranscoder
import org.evsyukov.post.media.prepare.MediaPreparer
import org.evsyukov.post.media.prepare.VariantRequest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections

/** Video on a device (media.md A7): H.264 within the size target, progress to 100, typed failures, cancellation. */
@RunWith(AndroidJUnit4::class)
class VideoTranscoderDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(context.cacheDir, "video-transcoder-test").apply { deleteRecursively(); mkdirs() }
    private val transcoder = VideoTranscoder(context)

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun aVideoIsTranscodedToH264WithinTheTargetSizeWithProgressToOneHundred() = runBlocking<Unit> {
        val source = File(dir, "source.mp4").also { TestMedia.video(it, 1280, 720, seconds = 3) }
        val output = File(dir, "out.mp4")
        val conversion = TranscodeVideo("video/mp4", maxShortSide = 360, videoBitrate = 400_000, audioBitrate = 128_000, maxBytes = 300_000)
        assertThat(source.length()).isGreaterThan(conversion.maxBytes)
        val progress = Collections.synchronizedList(mutableListOf<Int>())

        val result = transcoder.transcode(source, conversion, output) { progress += it }

        assertThat(output.length()).isAtMost(conversion.maxBytes)
        assertThat(result.sizeBytes).isEqualTo(output.length())
        assertThat(result.mime).isEqualTo("video/mp4")
        val (mime, width, height) = TestMedia.videoTrack(output)
        assertThat(mime).isEqualTo("video/avc")
        assertThat(minOf(width, height)).isEqualTo(360)
        assertThat(result.width to result.height).isEqualTo(width to height)
        assertThat(progress.last()).isEqualTo(100)
        assertThat(progress.all { it in 0..100 }).isTrue()
    }

    @Test
    fun aVideoThatCannotReachTheTargetFailsWithCannotFitAndLeavesNoFile() = runBlocking<Unit> {
        val source = File(dir, "source.mp4").also { TestMedia.video(it, 640, 360, seconds = 2) }
        val output = File(dir, "out.mp4")
        val conversion = TranscodeVideo("video/mp4", maxShortSide = 360, videoBitrate = 400_000, audioBitrate = 128_000, maxBytes = 2_000)

        val error = assertThrows(ConversionException.CannotFit::class.java) {
            runBlocking { transcoder.transcode(source, conversion, output) }
        }

        assertThat(error.maxBytes).isEqualTo(2_000L)
        assertThat(error.actualBytes).isGreaterThan(2_000L)
        assertThat(output.exists()).isFalse()
    }

    @Test
    fun aFileThatIsNotAVideoFailsWithTranscodeFailed() = runBlocking<Unit> {
        val source = File(dir, "broken.mp4").apply { writeText("not a video") }
        val conversion = TranscodeVideo("video/mp4", maxShortSide = 360, videoBitrate = 400_000, audioBitrate = 128_000, maxBytes = 300_000)

        assertThrows(ConversionException.TranscodeFailed::class.java) {
            runBlocking { transcoder.transcode(source, conversion, File(dir, "out.mp4")) }
        }
    }

    @Test
    fun cancellingTheSenderStopsTheTranscoderAndLeavesNoPartialVariant() = runBlocking<Unit> {
        val filesDir = File(dir, "files").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, PostDatabase::class.java).build()
        try {
            val postId = db.postDao().insert(
                PostEntity(
                    kind = PostKind.NEW, state = PostState.QUEUED, text = "", optionsJson = "{}", targetsJson = "[]",
                    scheduleKind = null, slotLocal = null, sendAt = null, queueOrder = 0, historyId = null, createdAt = 0, updatedAt = 0,
                ),
            )
            val sourcePath = "attachments/$postId/clip.mp4"
            val source = File(filesDir, sourcePath).apply { parentFile!!.mkdirs() }
            TestMedia.video(source, 1280, 720, seconds = 10)
            val attachmentId = db.attachmentDao().insert(
                AttachmentEntity(
                    postId = postId, position = 0, pieceIndex = 0, kind = MediaKind.VIDEO, path = sourcePath, mime = "video/mp4",
                    sizeBytes = source.length(), width = 1280, height = 720, durationMs = 10_000, frameRate = 30.0, animated = false,
                    hasTransparency = false, videoCodec = "video/avc", alt = "", focusX = null, focusY = null, createdAt = 0,
                ),
            )
            val preparer = MediaPreparer(db, filesDir, DeviceMediaConverter(context))
            val conversion = TranscodeVideo("video/mp4", maxShortSide = 720, videoBitrate = 2_000_000, audioBitrate = 128_000, maxBytes = 50_000_000)
            val started = CompletableDeferred<Unit>()
            val progress = Collections.synchronizedList(mutableListOf<Int>())

            val job = async(Dispatchers.Default) {
                preparer.prepare(postId, listOf(VariantRequest(attachmentId, "mastodon", conversion))) {
                    progress += it
                    if (it > 0) started.complete(Unit)
                }
            }
            withTimeout(60_000) { started.await() }
            job.cancel()
            withTimeout(10_000) { job.join() }
            val countAtCancel = progress.size
            Thread.sleep(1_500)

            assertThat(job.isCancelled).isTrue()
            assertThat(progress.size).isEqualTo(countAtCancel)
            assertThat(progress).doesNotContain(100)
            assertThat(db.attachmentDao().variant(attachmentId, MediaPreparer.profileKeyOf(conversion))).isNull()
            assertThat(File(filesDir, "variants/$postId").listFiles().orEmpty().toList()).isEmpty()
            assertThat(source.isFile).isTrue()
        } finally {
            db.close()
        }
    }
}
