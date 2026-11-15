package org.evsyukov.post.media.importer

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.core.media.MediaKind
import org.evsyukov.post.data.posts.Attachment
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The metadata of every fixture with the platform's own decoders (media.md A1): image sizes turned by EXIF,
 * transparency, GIF animation, and the duration, size, rotation, frame rate and codec of video and audio,
 * which the JVM tests cannot read. The fixtures are the assets of the test APK (app/src/test/resources/media).
 */
@RunWith(AndroidJUnit4::class)
class AttachmentMetadataDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target: Context = instrumentation.targetContext
    private lateinit var root: File
    private lateinit var importer: AttachmentImporter

    @Before
    fun setUp() {
        root = File(target.cacheDir, "metadata-test").apply { deleteRecursively(); mkdirs() }
        importer = AttachmentImporter(target.contentResolver, File(root, "files"))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** The fixture [name] copied out of the test APK, as a picked file outside app storage. */
    private fun source(name: String): File {
        val file = File(root, "source-$name")
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    private fun import(name: String): Attachment {
        val result = runBlocking { importer.import(listOf(Uri.fromFile(source(name))), 0) }
        assertThat(result.failures).isEmpty()
        return result.attachments.single()
    }

    @Test
    fun aJpegIsSizedAsShownAfterItsOrientation() {
        val photo = import("photo_exif_rot90.jpg")
        assertThat(photo.kind).isEqualTo(MediaKind.IMAGE)
        assertThat(photo.mime).isEqualTo("image/jpeg")
        assertThat(photo.width to photo.height).isEqualTo(48 to 64)
        assertThat(photo.hasTransparency).isFalse()
    }

    @Test
    fun transparencyIsReadFromThePixels() {
        assertThat(import("alpha.png").hasTransparency).isTrue()
        assertThat(import("opaque_rgba.png").hasTransparency).isFalse()
    }

    @Test
    fun anAnimatedGifIsAGifAndAStillOneAnImage() {
        val animated = import("animated.gif")
        assertThat(animated.kind).isEqualTo(MediaKind.GIF)
        assertThat(animated.animated).isTrue()
        assertThat(animated.width to animated.height).isEqualTo(32 to 24)
        val still = import("still.gif")
        assertThat(still.kind).isEqualTo(MediaKind.IMAGE)
        assertThat(still.mime).isEqualTo("image/gif")
        assertThat(still.animated).isFalse()
    }

    @Test
    fun aVideoHasItsDurationFrameRateRotationAndCodec() {
        // 160x96 H.264 at 30 fps, 45 frames, with a rotation of 90 degrees.
        val clip = import("clip.mp4")
        assertThat(clip.kind).isEqualTo(MediaKind.VIDEO)
        assertThat(clip.mime).isEqualTo("video/mp4")
        assertThat(clip.width to clip.height).isEqualTo(96 to 160)
        assertThat(clip.durationMs!!).isIn(1_400L..1_600L)
        assertThat(clip.frameRate!!).isWithin(1.5).of(30.0)
        assertThat(clip.videoCodec).isEqualTo("video/avc")

        val probed = MediaProbe().probe(importer.file(clip.path), clip.mime)!!
        assertThat(probed.rotation).isEqualTo(90)
    }

    @Test
    fun anMp3HasItsDuration() {
        // 48 frames of 1152 samples at 44.1 kHz: about 1.25 s.
        val tone = import("tone.mp3")
        assertThat(tone.kind).isEqualTo(MediaKind.AUDIO)
        assertThat(tone.mime).isEqualTo("audio/mpeg")
        assertThat(tone.durationMs!!).isIn(1_150L..1_350L)
    }

    @Test
    fun theCopyOutlivesItsSource() {
        val original = source("alpha.png")
        val bytes = original.readBytes()
        val attachment = runBlocking { importer.import(listOf(Uri.fromFile(original)), 0) }.attachments.single()
        assertThat(original.delete()).isTrue()
        assertThat(importer.file(attachment.path).readBytes()).isEqualTo(bytes)
    }
}
