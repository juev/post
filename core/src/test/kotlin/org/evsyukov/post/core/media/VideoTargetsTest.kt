package org.evsyukov.post.core.media

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.evsyukov.post.core.media.Conversion.TranscodeVideo
import org.junit.Test

/**
 * A7: the bitrate comes from the duration and the size limit; 1080p first, then 720p, then the
 * video cannot fit. The limits below put the bitrate exactly on a floor: for one minute,
 * 21 900 000 bytes × 8 × 90 % / 60 s − 128 kbit/s = 2 500 000 bit/s, and 9 400 000 bytes give
 * 1 000 000 bit/s.
 */
class VideoTargetsTest {

    private val minute = MINUTE_MS

    @Test
    fun `A7 a bitrate exactly on the 1080p floor keeps 1080p`() {
        val target = VideoTargets.target(minute, 21_900_000, 3840, 2160)
        assertThat(target).isEqualTo(
            TranscodeVideo("video/mp4", 1080, VideoTargets.MIN_BITRATE_1080, VideoTargets.AUDIO_BITRATE, 21_900_000),
        )
    }

    @Test
    fun `A7 one byte less goes down to 720p at the bitrate that fits`() {
        val target = VideoTargets.target(minute, 21_899_999, 3840, 2160)
        assertThat(target?.maxShortSide).isEqualTo(720)
        assertThat(target?.videoBitrate).isEqualTo(2_499_999)
        assertThat(target?.audioBitrate).isEqualTo(128_000)
    }

    @Test
    fun `A7 a bitrate exactly on the 720p floor still fits, one byte less cannot fit`() {
        assertThat(VideoTargets.target(minute, 9_400_000, 1920, 1080))
            .isEqualTo(TranscodeVideo("video/mp4", 720, VideoTargets.MIN_BITRATE_720, 128_000, 9_400_000))
        assertThat(VideoTargets.target(minute, 9_399_999, 1920, 1080)).isNull()
    }

    @Test
    fun `A7 a 40-minute video cannot fit 99 MB`() {
        assertThat(VideoTargets.target(40 * minute, 99 * MIB, 1920, 1080)).isNull()
    }

    @Test
    fun `A7 the output with audio fits the budget below the limit for any duration that fits`() {
        val maxBytes = 99 * MIB
        for (seconds in listOf(1L, 5, 30, 60, 120, 300, 600, 660)) {
            val target = VideoTargets.target(seconds * 1000, maxBytes, 1920, 1080)
            assertWithMessage("$seconds s").that(target).isNotNull()
            val bytes = (target!!.videoBitrate.toLong() + target.audioBitrate) * seconds / 8
            assertWithMessage("$seconds s").that(bytes).isAtMost(maxBytes * VideoTargets.BUDGET_PERCENT / 100)
        }
    }

    @Test
    fun `A7 a short clip is capped at the bitrate of its resolution`() {
        assertThat(VideoTargets.target(10_000, 99 * MIB, 1920, 1080)?.videoBitrate).isEqualTo(VideoTargets.MAX_BITRATE_1080)
        // A 720p source keeps its size and the 720p cap.
        val small = VideoTargets.target(10_000, 99 * MIB, 1280, 720)
        assertThat(small?.maxShortSide).isEqualTo(720)
        assertThat(small?.videoBitrate).isEqualTo(VideoTargets.MAX_BITRATE_720)
    }

    @Test
    fun `A7 a source of 720p or less is judged by the 720p floor, not the 1080p one`() {
        // 2 000 000 bit/s is under the 1080p floor but enough for a 640×480 source.
        val maxBytes = (2_000_000L + 128_000) * 60 / 8 * 100 / 90 + 1
        val target = VideoTargets.target(minute, maxBytes, 640, 480)
        assertThat(target?.maxShortSide).isEqualTo(720)
        assertThat(target?.videoBitrate).isEqualTo(2_000_000)
    }

    @Test
    fun `A7 the short side decides for vertical video too`() {
        assertThat(VideoTargets.target(minute, 21_900_000, 1080, 1920)?.maxShortSide).isEqualTo(1080)
        assertThat(VideoTargets.target(minute, 21_899_999, 2160, 3840)?.maxShortSide).isEqualTo(720)
    }

    @Test
    fun `A7 the bitrate is capped at the source's own, but not below the floor of the step`() {
        // 30 000 000 bytes in a minute are 4 000 000 bit/s; the limit alone would give the 8 Mbit/s cap.
        assertThat(VideoTargets.target(minute, 99 * MIB, 1920, 1080, sourceBytes = 30_000_000)?.videoBitrate)
            .isEqualTo(4_000_000)
        // 5 000 000 bytes in a minute are under the 1080p floor, so the floor applies.
        assertThat(VideoTargets.target(minute, 99 * MIB, 1920, 1080, sourceBytes = 5_000_000)?.videoBitrate)
            .isEqualTo(VideoTargets.MIN_BITRATE_1080)
        // A 720p source gets the 720p floor.
        assertThat(VideoTargets.target(minute, 99 * MIB, 1280, 720, sourceBytes = 1_000_000)?.videoBitrate)
            .isEqualTo(VideoTargets.MIN_BITRATE_720)
        // A source above what the limit allows changes nothing.
        assertThat(VideoTargets.target(minute, 21_900_000, 3840, 2160, sourceBytes = 1_000_000_000)?.videoBitrate)
            .isEqualTo(VideoTargets.MIN_BITRATE_1080)
        assertThat(VideoTargets.target(minute, 99 * MIB, 1920, 1080, sourceBytes = 1_000_000_000)?.videoBitrate)
            .isEqualTo(VideoTargets.MAX_BITRATE_1080)
        // An unknown source size caps nothing.
        assertThat(VideoTargets.target(minute, 99 * MIB, 1920, 1080, sourceBytes = 0)?.videoBitrate)
            .isEqualTo(VideoTargets.MAX_BITRATE_1080)
    }

    @Test
    fun `N1 a step whose output is over the pixel limit is skipped`() {
        // 12000×1200 at 1080 on the short side is 10800×1080, over 3840×2160; at 720 it is 7200×720.
        val target = VideoTargets.target(10_000, 99 * MIB, 12_000, 1200, maxPixels = 3840L * 2160)
        assertThat(target?.maxShortSide).isEqualTo(720)
        assertThat(VideoTargets.target(10_000, 99 * MIB, 40_000, 1000, maxPixels = 3840L * 2160)).isNull()
    }

    @Test
    fun `no duration gives no bitrate`() {
        assertThat(VideoTargets.target(0, 99 * MIB, 1920, 1080)).isNull()
        assertThat(VideoTargets.target(-1, 99 * MIB, 1920, 1080)).isNull()
    }
}
