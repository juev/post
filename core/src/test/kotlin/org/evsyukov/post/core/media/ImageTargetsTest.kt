package org.evsyukov.post.core.media

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.media.Conversion.ConvertImage
import org.junit.Test

/** A5: images the network does not take, or over its size or resolution limits, are converted. */
class ImageTargetsTest {

    @Test
    fun `A5 an accepted image within the limits needs no conversion`() {
        for (rules in listOf(MASTODON, BLUESKY)) {
            assertThat(ImageTargets.target(1600, 1200, 800_000, "image/jpeg", rules)).isNull()
            assertThat(ImageTargets.target(1600, 1200, 800_000, "image/png", rules)).isNull()
            assertThat(ImageTargets.target(1600, 1200, 800_000, "image/webp", rules)).isNull()
        }
    }

    @Test
    fun `A5 HEIC, HEIF and AVIF become JPEG, and PNG with transparency`() {
        for (mime in listOf("image/heic", "image/heif", "image/avif")) {
            assertThat(ImageTargets.target(1600, 1200, 800_000, mime, MASTODON))
                .isEqualTo(ConvertImage("image/jpeg", null, 7680L * 4320, 16 * MIB, true))
            assertThat(ImageTargets.target(1600, 1200, 800_000, mime, BLUESKY))
                .isEqualTo(ConvertImage("image/jpeg", 4000, null, 2_000_000, true))
            assertThat(ImageTargets.target(1600, 1200, 800_000, mime, BLUESKY, hasTransparency = true)?.targetMime)
                .isEqualTo("image/png")
        }
    }

    @Test
    fun `A5 Bluesky limits are 2 000 000 bytes and 4000 px on the long side`() {
        assertThat(ImageTargets.target(4000, 3000, 2_000_000, "image/jpeg", BLUESKY)).isNull()
        assertThat(ImageTargets.target(4000, 3000, 2_000_001, "image/jpeg", BLUESKY)).isNotNull()
        assertThat(ImageTargets.target(4001, 3000, 1_000_000, "image/jpeg", BLUESKY)).isNotNull()
        assertThat(ImageTargets.target(3000, 4001, 1_000_000, "image/jpeg", BLUESKY)).isNotNull()
    }

    @Test
    fun `A5 Mastodon limits are 16 MB and the 7680x4320 matrix`() {
        assertThat(ImageTargets.target(7680, 4320, 16 * MIB, "image/jpeg", MASTODON)).isNull()
        assertThat(ImageTargets.target(7680, 4320, 16 * MIB + 1, "image/jpeg", MASTODON)).isNotNull()
        assertThat(ImageTargets.target(7681, 4320, 1_000_000, "image/jpeg", MASTODON)).isNotNull()
        // The matrix counts pixels, not sides: a long panorama under the pixel count is fine.
        assertThat(ImageTargets.target(20_000, 1500, 1_000_000, "image/jpeg", MASTODON)).isNull()
    }

    @Test
    fun `A5 a still GIF over the Mastodon GIF matrix becomes JPEG, or PNG with transparency`() {
        assertThat(ImageTargets.target(1280, 720, 1_000_000, "image/gif", MASTODON)).isNull()
        assertThat(ImageTargets.target(1281, 720, 1_000_000, "image/gif", MASTODON))
            .isEqualTo(ConvertImage("image/jpeg", null, 7680L * 4320, 16 * MIB, true))
        assertThat(ImageTargets.target(1920, 1080, 1_000_000, "image/gif", MASTODON, hasTransparency = true)?.targetMime)
            .isEqualTo("image/png")
        // The GIF matrix holds for GIF only: a PNG of that size fits.
        assertThat(ImageTargets.target(1920, 1080, 1_000_000, "image/png", MASTODON)).isNull()
    }

    @Test
    fun `A5 an image over the limits keeps PNG only when it has transparency`() {
        assertThat(ImageTargets.target(6000, 4000, 9_000_000, "image/png", BLUESKY)?.targetMime).isEqualTo("image/jpeg")
        assertThat(ImageTargets.target(6000, 4000, 9_000_000, "image/png", BLUESKY, hasTransparency = true)?.targetMime)
            .isEqualTo("image/png")
    }

    @Test
    fun `A6 the conversion strips metadata unless the setting keeps it`() {
        assertThat(ImageTargets.target(1600, 1200, 800_000, "image/heic", BLUESKY)?.stripMetadata).isTrue()
        assertThat(ImageTargets.target(1600, 1200, 800_000, "image/heic", BLUESKY, stripMetadata = false)?.stripMetadata)
            .isFalse()
    }

    @Test
    fun `an unknown size is not checked against resolution, only bytes and type`() {
        assertThat(ImageTargets.target(null, null, 1_000_000, "image/jpeg", BLUESKY)).isNull()
        assertThat(ImageTargets.target(null, 3000, 3_000_000, "image/jpeg", BLUESKY)).isNotNull()
    }

    @Test
    fun `MIME types are compared without case and parameters`() {
        assertThat(ImageTargets.target(1600, 1200, 800_000, "IMAGE/JPEG", BLUESKY)).isNull()
        assertThat(ImageTargets.target(1600, 1200, 800_000, "image/jpg", BLUESKY)).isNull()
        assertThat(ImageTargets.target(1600, 1200, 800_000, "image/png; charset=binary", MASTODON)).isNull()
    }
}
