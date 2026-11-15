package org.evsyukov.post.core.media

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.media.Conversion.AsIs
import org.evsyukov.post.core.media.Conversion.ConvertImage
import org.evsyukov.post.core.media.Conversion.TranscodeVideo
import org.junit.Test

/** A5–A8: adaptation without a choice, and pieces from manual breaks (T13, T14). */
class MediaAdaptationTest {

    @Test
    fun `A6 a fitting image still loses its metadata, keeping its type, without a note`() {
        val plan = planOf(listOf(image(1, mime = "image/webp")), BLUESKY)
        assertThat(plan.conversions.getValue(1)).isEqualTo(ConvertImage("image/webp", null, null, 2_000_000, true))
        assertThat(plan.notes).isEmpty()
    }

    @Test
    fun `A6 an accepted type the device cannot write back is converted to remove metadata`() {
        val heicServer = MASTODON.copy(imageMimes = MASTODON.imageMimes + "image/heic")
        val plan = planOf(listOf(image(1, mime = "image/heic"), image(2, mime = "image/heic", transparency = true)), heicServer)
        assertThat(plan.conversions.getValue(1)).isEqualTo(ConvertImage("image/jpeg", null, 7680L * 4320, 16 * MIB, true))
        assertThat((plan.conversions.getValue(2) as ConvertImage).targetMime).isEqualTo("image/png")
        assertThat(plan.notes).hasSize(2)
        // A static GIF has no EXIF block and goes as it is.
        assertThat(planOf(listOf(image(3, mime = "image/gif")), MASTODON).conversions.getValue(3)).isEqualTo(AsIs)
        // Without metadata removal the accepted HEIC goes as it is.
        assertThat(planOf(listOf(image(1, mime = "image/heic")), heicServer, stripMetadata = false).conversions.getValue(1))
            .isEqualTo(AsIs)
    }

    @Test
    fun `A6 with the setting to keep metadata a fitting image goes as it is`() {
        val plan = planOf(listOf(image(1)), MASTODON, stripMetadata = false)
        assertThat(plan.conversions.getValue(1)).isEqualTo(AsIs)
        val converted = planOf(listOf(image(1, mime = "image/heic")), MASTODON, stripMetadata = false)
        assertThat((converted.conversions.getValue(1) as ConvertImage).stripMetadata).isFalse()
    }

    @Test
    fun `A3 nothing is noted when every file goes as it is`() {
        val plan = planOf(listOf(video(1), image(2, piece = 1), audio(3, piece = 2)), MASTODON, stripMetadata = false)
        assertThat(plan.conversions.values.toSet()).containsExactly(AsIs)
        assertThat(plan.notes).isEmpty()
        assertThat(plan.ready).isTrue()
    }

    @Test
    fun `A7 a video within the limits goes as it is`() {
        assertThat(planOf(listOf(video(1)), MASTODON).conversions.getValue(1)).isEqualTo(AsIs)
        // Bluesky takes QuickTime and 4K at 60 fps; its server transcodes.
        val mov = video(1, mime = "video/quicktime", size = 250_000_000, width = 3840, height = 2160, durationMs = 3 * MINUTE_MS, frameRate = 60.0)
        assertThat(planOf(listOf(mov), BLUESKY).conversions.getValue(1)).isEqualTo(AsIs)
    }

    @Test
    fun `A7 a video type the network does not take is transcoded`() {
        val mkv = video(1, mime = "video/x-matroska", size = 30 * MIB)
        for (rules in listOf(MASTODON, BLUESKY)) {
            val target = planOf(listOf(mkv), rules).conversions.getValue(1) as TranscodeVideo
            assertThat(target.targetMime).isEqualTo("video/mp4")
            assertThat(target.maxBytes).isEqualTo(rules.maxVideoBytes)
        }
    }

    @Test
    fun `A7 a video over the frame rate or resolution limit of Mastodon is transcoded`() {
        val fast = planOf(listOf(video(1, frameRate = 240.0)), MASTODON).conversions.getValue(1) as TranscodeVideo
        assertThat(fast.maxFrameRate).isEqualTo(120)
        assertThat(planOf(listOf(video(1, frameRate = 120.0)), MASTODON).conversions.getValue(1)).isEqualTo(AsIs)

        val eightK = planOf(listOf(video(1, width = 7680, height = 4320)), MASTODON).conversions.getValue(1)
        assertThat((eightK as TranscodeVideo).maxShortSide).isEqualTo(1080)
        assertThat(planOf(listOf(video(1, width = 3840, height = 2160)), MASTODON).conversions.getValue(1)).isEqualTo(AsIs)
    }

    @Test
    fun `A7 a video over the size limit is transcoded to fit it`() {
        val big = video(1, size = 350_000_000, durationMs = 5 * MINUTE_MS)
        val target = planOf(listOf(big), BLUESKY).conversions.getValue(1) as TranscodeVideo
        assertThat(target.maxBytes).isEqualTo(300_000_000)
        assertThat((target.videoBitrate.toLong() + target.audioBitrate) * 300 / 8).isLessThan(300_000_000L)
        assertThat(planOf(listOf(video(1, size = 99 * MIB)), MASTODON).conversions.getValue(1)).isEqualTo(AsIs)
        assertThat(planOf(listOf(video(1, size = 99 * MIB + 1)), MASTODON).conversions.getValue(1))
            .isInstanceOf(TranscodeVideo::class.java)
    }

    @Test
    fun `T14 pieces from manual breaks keep their own media`() {
        val items = images(1L..3L, piece = 0) + images(4L..6L, piece = 1)
        val plan = planOf(items, MASTODON)
        assertThat(plan.conflicts).isEmpty()
        assertThat(plan.parts(0)).containsExactly(listOf(1L, 2L, 3L))
        assertThat(plan.parts(1)).containsExactly(listOf(4L, 5L, 6L))
    }

    @Test
    fun `T14 the limit counts per piece and a move adds replies only to that piece`() {
        val items = images(1L..3L, piece = 0) + images(4L..8L, piece = 1)
        val asking = planOf(items, MASTODON)
        assertThat(asking.conflicts).containsExactly(Conflict(ConflictKind.TOO_MANY_FILES, listOf(8L)))

        val moved = planOf(items, MASTODON, mapOf(ConflictKind.TOO_MANY_FILES to FixKind.MOVE_TO_REPLY))
        assertThat(moved.parts(0)).containsExactly(listOf(1L, 2L, 3L))
        assertThat(moved.parts(1)).containsExactly(listOf(4L, 5L, 6L, 7L), listOf(8L)).inOrder()
    }

    @Test
    fun `A10 one fix covers the conflicts of its kind in every piece`() {
        val items = images(1L..6L, piece = 0) + images(7L..12L, piece = 2)
        val asking = planOf(items, MASTODON)
        assertThat(asking.conflicts).containsExactly(Conflict(ConflictKind.TOO_MANY_FILES, listOf(5L, 6L, 11L, 12L)))

        val moved = planOf(items, MASTODON, mapOf(ConflictKind.TOO_MANY_FILES to FixKind.MOVE_TO_REPLY))
        assertThat(moved.parts(0)).containsExactly(listOf(1L, 2L, 3L, 4L), listOf(5L, 6L)).inOrder()
        assertThat(moved.parts(2)).containsExactly(listOf(7L, 8L, 9L, 10L), listOf(11L, 12L)).inOrder()
        assertThat(moved.partsByPiece.keys).containsExactly(0, 2)
    }

    @Test
    fun `a video in one piece and images in another are not mixed`() {
        val plan = planOf(listOf(video(1, piece = 0), image(2, piece = 1)), BLUESKY)
        assertThat(plan.conflicts).isEmpty()
        assertThat(plan.parts(0)).containsExactly(listOf(1L))
        assertThat(plan.parts(1)).containsExactly(listOf(2L))
    }

    @Test
    fun `the order of the attachments decides the order in the parts`() {
        val items = listOf(image(30), image(10), image(20), image(5), image(7))
        val plan = planOf(items, MASTODON, mapOf(ConflictKind.TOO_MANY_FILES to FixKind.MOVE_TO_REPLY))
        assertThat(plan.parts()).containsExactly(listOf(30L, 10L, 20L, 5L), listOf(7L)).inOrder()
    }
}
