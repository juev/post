package org.evsyukov.post.core.media

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/** The values of the networks.md table and the A10 merge of several accounts of one network. */
class NetworkMediaRulesTest {

    @Test
    fun `Mastodon defaults follow the vanilla column of the networks table`() {
        with(MASTODON) {
            assertThat(network).isEqualTo(Network.MASTODON)
            assertThat(maxAttachments).isEqualTo(4)
            assertThat(imageMimes).containsExactly("image/jpeg", "image/png", "image/gif", "image/webp")
            assertThat(maxImageBytes).isEqualTo(16 * MIB)
            assertThat(maxImagePixels).isEqualTo(7680L * 4320)
            assertThat(videoMimes).containsExactly("video/mp4", "video/webm", "video/quicktime", "video/ogg")
            assertThat(maxVideoBytes).isEqualTo(99 * MIB)
            assertThat(maxVideoPixels).isEqualTo(3840L * 2160)
            assertThat(maxVideoFrameRate).isEqualTo(120)
            assertThat(maxVideoDurationMs).isNull()
            assertThat(audioAllowed).isTrue()
            assertThat(audioMimes).contains("audio/mpeg")
            assertThat(gifIsVideo).isFalse()
            assertThat(videoMustBeAlone).isTrue()
            assertThat(galleryMax).isNull()
            assertThat(maxAltLength).isEqualTo(10_000)
            assertThat(pollAllowedWithMedia).isFalse()
        }
    }

    @Test
    fun `Bluesky rules follow the lexicons and social-app`() {
        with(BLUESKY) {
            assertThat(network).isEqualTo(Network.BLUESKY)
            assertThat(maxAttachments).isEqualTo(4)
            assertThat(galleryMax).isEqualTo(10)
            assertThat(maxImagesPerPart).isEqualTo(10)
            assertThat(maxImageBytes).isEqualTo(2_000_000)
            assertThat(maxImageLongSide).isEqualTo(4000)
            assertThat(maxVideoBytes).isEqualTo(300_000_000)
            assertThat(maxVideoDurationMs).isEqualTo(10 * MINUTE_MS)
            assertThat(audioAllowed).isFalse()
            assertThat(gifIsVideo).isTrue()
            assertThat(videoMustBeAlone).isTrue()
            assertThat(maxAltLength).isEqualTo(2000)
            assertThat(pollAllowedWithMedia).isTrue()
        }
    }

    @Test
    fun `R11 Mastodon defaults carry the GIF matrix limit that the server does not report`() {
        // Attachmentable::GIF_MATRIX_LIMIT: a GIF over 1280×720 gets 422; /api/v2/instance does not list it.
        assertThat(MASTODON.maxGifPixels).isEqualTo(1280L * 720)
        assertThat(BLUESKY.maxGifPixels).isNull()
        assertThat(NetworkMediaRules.strictest(listOf(MASTODON, MASTODON.copy(maxGifPixels = null))).maxGifPixels)
            .isEqualTo(1280L * 720)
        assertThat(NetworkMediaRules.strictest(listOf(MASTODON, MASTODON.copy(maxGifPixels = 640L * 480))).maxGifPixels)
            .isEqualTo(640L * 480)
    }

    @Test
    fun `A10 the strictest rules take the smallest limits and the types every account takes`() {
        val fork = MASTODON.copy(
            maxAttachments = 6,
            imageMimes = setOf("image/jpeg", "image/png", "image/heic"),
            maxImageBytes = 8 * MIB,
            maxImagePixels = null,
            videoMimes = setOf("VIDEO/MP4", "video/x-matroska"),
            maxVideoBytes = 200 * MIB,
            maxVideoFrameRate = 60,
            maxVideoDurationMs = 5 * MINUTE_MS,
            maxAltLength = 1500,
        )
        val merged = NetworkMediaRules.strictest(listOf(MASTODON, fork))
        assertThat(merged.network).isEqualTo(Network.MASTODON)
        assertThat(merged.maxAttachments).isEqualTo(4)
        assertThat(merged.imageMimes).containsExactly("image/jpeg", "image/png")
        assertThat(merged.maxImageBytes).isEqualTo(8 * MIB)
        // A limit only one account has still applies.
        assertThat(merged.maxImagePixels).isEqualTo(7680L * 4320)
        assertThat(merged.videoMimes).containsExactly("video/mp4")
        assertThat(merged.maxVideoBytes).isEqualTo(99 * MIB)
        assertThat(merged.maxVideoFrameRate).isEqualTo(60)
        assertThat(merged.maxVideoDurationMs).isEqualTo(5 * MINUTE_MS)
        assertThat(merged.maxAltLength).isEqualTo(1500)
        assertThat(merged.pollAllowedWithMedia).isFalse()
        assertThat(merged.videoMustBeAlone).isTrue()
    }

    @Test
    fun `A10 one account without a gallery or without audio takes them from the network`() {
        val noGallery = BLUESKY.copy(galleryMax = null)
        assertThat(NetworkMediaRules.strictest(listOf(BLUESKY, noGallery)).galleryMax).isNull()
        assertThat(NetworkMediaRules.strictest(listOf(BLUESKY, BLUESKY.copy(galleryMax = 8))).galleryMax).isEqualTo(8)

        val noAudio = MASTODON.copy(audioAllowed = false)
        assertThat(NetworkMediaRules.strictest(listOf(MASTODON, noAudio)).audioAllowed).isFalse()
    }

    @Test
    fun `A10 audio types narrow to the common ones, and lists with none in common allow no audio`() {
        val anyAudio = MASTODON.copy(audioMimes = emptySet())
        val oggOnly = MASTODON.copy(audioMimes = setOf("audio/ogg"))
        val mpegOnly = MASTODON.copy(audioMimes = setOf("audio/mpeg"))

        val narrowed = NetworkMediaRules.strictest(listOf(anyAudio, oggOnly))
        assertThat(narrowed.audioAllowed).isTrue()
        assertThat(narrowed.audioMimes).containsExactly("audio/ogg")

        assertThat(NetworkMediaRules.strictest(listOf(anyAudio, anyAudio)).audioMimes).isEmpty()
        assertThat(NetworkMediaRules.strictest(listOf(oggOnly, mpegOnly)).audioAllowed).isFalse()
    }

    @Test
    fun `A10 the strictest account decides whether a conflict exists`() {
        val fiveFiles = MASTODON.copy(maxAttachments = 5)
        val merged = NetworkMediaRules.strictest(listOf(fiveFiles, MASTODON))
        val plan = planOf(images(1L..5L), merged)
        assertThat(plan.conflicts).containsExactly(Conflict(ConflictKind.TOO_MANY_FILES, listOf(5L)))

        val smallImages = MASTODON.copy(maxImageBytes = 4 * MIB)
        val strict = NetworkMediaRules.strictest(listOf(MASTODON, smallImages))
        val converted = planOf(listOf(image(1, size = 6 * MIB)), strict).conversions.getValue(1)
        assertThat((converted as Conversion.ConvertImage).maxBytes).isEqualTo(4 * MIB)
    }

    @Test
    fun `strictest needs rules of one network`() {
        assertThrows(IllegalArgumentException::class.java) { NetworkMediaRules.strictest(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { NetworkMediaRules.strictest(listOf(MASTODON, BLUESKY)) }
        assertThat(NetworkMediaRules.strictest(listOf(BLUESKY))).isEqualTo(BLUESKY)
    }
}
