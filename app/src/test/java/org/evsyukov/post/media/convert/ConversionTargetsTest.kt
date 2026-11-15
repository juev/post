package org.evsyukov.post.media.convert

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The arithmetic of the converters: image size limits (A5), the video retry (A7), Mastodon's frame cut. */
class ConversionTargetsTest {

    @Test
    fun anImageIsDownscaledToTheLongSideOfBluesky() {
        assertThat(ImageConverter.fitSize(8000, 6000, maxLongSide = 4000, maxPixels = null)).isEqualTo(4000 to 3000)
        assertThat(ImageConverter.fitSize(3000, 9000, maxLongSide = 4000, maxPixels = null)).isEqualTo(1333 to 4000)
    }

    @Test
    fun anImageIsDownscaledToTheMatrixLimitOfMastodon() {
        val limit = 8_294_400L // 3840 × 2160, the vanilla image_matrix_limit
        val (width, height) = ImageConverter.fitSize(8000, 6000, maxLongSide = null, maxPixels = limit)

        assertThat(width.toLong() * height).isAtMost(limit)
        assertThat(width.toLong() * height).isAtLeast(limit * 99 / 100)
        assertThat(width.toDouble() / height).isWithin(0.01).of(8000.0 / 6000)
    }

    @Test
    fun theStricterOfBothLimitsWinsAndASmallImageKeepsItsSize() {
        assertThat(ImageConverter.fitSize(4000, 4000, maxLongSide = 3000, maxPixels = 1_000_000)).isEqualTo(1000 to 1000)
        assertThat(ImageConverter.fitSize(800, 600, maxLongSide = 4000, maxPixels = 8_294_400)).isEqualTo(800 to 600)
        assertThat(ImageConverter.fitSize(800, 600, maxLongSide = null, maxPixels = null)).isEqualTo(800 to 600)
    }

    @Test
    fun theRetryLowersTheBitrateByTheOvershootWithAMargin() {
        // 25 % over the limit: 2 Mbit/s × 1/1.25 × 0.85.
        assertThat(VideoTranscoder.lowerBitrate(2_000_000, actualBytes = 125, maxBytes = 100)).isEqualTo(1_360_000)
        assertThat(VideoTranscoder.lowerBitrate(2_000_000, actualBytes = 10_000, maxBytes = 100)).isEqualTo(VideoTranscoder.MIN_VIDEO_BITRATE)
    }

    @Test
    fun mastodonCutsALongVideoThatIsNotH264() {
        // 36 000 frames are 20 minutes at 30 fps.
        assertThat(MastodonVideoCheck.mustTranscode("video/hevc", durationMs = 20 * 60_000L + 1_000, frameRate = 30.0)).isTrue()
        assertThat(MastodonVideoCheck.mustTranscode("video/hevc", durationMs = 20 * 60_000L, frameRate = 30.0)).isFalse()
        assertThat(MastodonVideoCheck.mustTranscode("video/x-vnd.on2.vp9", durationMs = 11 * 60_000L, frameRate = 60.0)).isTrue()
    }

    @Test
    fun anH264VideoIsKeptWhateverItsLength() {
        assertThat(MastodonVideoCheck.mustTranscode("video/avc", durationMs = 60 * 60_000L, frameRate = 60.0)).isFalse()
        assertThat(MastodonVideoCheck.mustTranscode("VIDEO/AVC", durationMs = 60 * 60_000L, frameRate = 60.0)).isFalse()
    }

    @Test
    fun anUnknownCodecOrFrameRateIsTreatedAsTheRiskyCase() {
        // An unknown frame rate is taken as 60 fps: 10 minutes 1 second is over 36 000 frames.
        assertThat(MastodonVideoCheck.mustTranscode(null, durationMs = 10 * 60_000L + 1_000, frameRate = null)).isTrue()
        assertThat(MastodonVideoCheck.mustTranscode("video/hevc", durationMs = 9 * 60_000L, frameRate = null)).isFalse()
        assertThat(MastodonVideoCheck.mustTranscode("video/hevc", durationMs = null, frameRate = 30.0)).isFalse()
    }
}
