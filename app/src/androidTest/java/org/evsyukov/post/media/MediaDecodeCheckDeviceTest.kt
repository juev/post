package org.evsyukov.post.media

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.media.probe.DeviceMediaDecodeCheck
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/** The probe that runs before a post is queued (media.md A4), against the real platform decoders. */
@RunWith(AndroidJUnit4::class)
class MediaDecodeCheckDeviceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val check = DeviceMediaDecodeCheck()

    @Test
    fun anImageTheDeviceCanDecodePasses() {
        val file = File(temp.newFolder(), "photo.jpg")
        file.outputStream().use { TestMedia.markedNoise(320, 240).compress(Bitmap.CompressFormat.JPEG, 90, it) }

        assertThat(check.problem(file, "image/jpeg")).isNull()
    }

    @Test
    fun anImageTheDeviceCannotDecodeIsReported() {
        val file = File(temp.newFolder(), "broken.jpg").apply { writeBytes(ByteArray(4096) { it.toByte() }) }

        assertThat(check.problem(file, "image/jpeg")).isNotNull()
    }

    @Test
    fun aVideoWithADecoderForEveryTrackPasses() {
        val file = File(temp.newFolder(), "clip.mp4")
        TestMedia.video(file, width = 320, height = 240, seconds = 1, fps = 15)

        assertThat(check.problem(file, "video/mp4")).isNull()
    }

    @Test
    fun aFileThatIsNoVideoIsReported() {
        val file = File(temp.newFolder(), "not-a-video.mp4").apply { writeBytes(ByteArray(4096)) }

        assertThat(check.problem(file, "video/mp4")).isNotNull()
    }
}
