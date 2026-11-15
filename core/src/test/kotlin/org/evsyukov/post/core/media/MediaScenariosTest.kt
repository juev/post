package org.evsyukov.post.core.media

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.media.Conversion.ConvertImage
import org.evsyukov.post.core.media.Conversion.TranscodeVideo
import org.junit.Test

/** The verification scenarios of media.md, as far as the plan decides them. */
class MediaScenariosTest {

    @Test
    fun `six images - Bluesky gets a gallery of six`() {
        val plan = planOf(images(1L..6L), BLUESKY)
        assertThat(plan.ready).isTrue()
        assertThat(plan.conflicts).isEmpty()
        assertThat(plan.parts()).containsExactly(listOf(1L, 2L, 3L, 4L, 5L, 6L))
    }

    @Test
    fun `six images - Mastodon asks`() {
        val plan = planOf(images(1L..6L), MASTODON)
        assertThat(plan.ready).isFalse()
        assertThat(plan.conflicts).containsExactly(
            Conflict(
                ConflictKind.TOO_MANY_FILES,
                listOf(5L, 6L),
                listOf(FixKind.MOVE_TO_REPLY, FixKind.REMOVE_FILES_FOR_NETWORK, FixKind.REMOVE_ACCOUNT),
            ),
        )
        assertThat(plan.parts()).containsExactly(listOf(1L, 2L, 3L, 4L))
    }

    @Test
    fun `six images - Mastodon with move to a reply gets four images and a reply with two`() {
        val plan = planOf(images(1L..6L), MASTODON, mapOf(ConflictKind.TOO_MANY_FILES to FixKind.MOVE_TO_REPLY))
        assertThat(plan.ready).isTrue()
        assertThat(plan.partsByPiece.getValue(0)).containsExactly(
            PartMedia(0, listOf(1L, 2L, 3L, 4L)),
            PartMedia(1, listOf(5L, 6L)),
        ).inOrder()
        assertThat(plan.resolved).containsExactly(
            ResolvedConflict(Conflict(ConflictKind.TOO_MANY_FILES, listOf(5L, 6L)), FixKind.MOVE_TO_REPLY),
        )
    }

    @Test
    fun `a 20 MB HEIC photo becomes a JPEG under each network's limits without metadata`() {
        val heic = listOf(image(1, mime = "image/heic", size = 20_000_000, width = 8064, height = 6048))

        val mastodon = planOf(heic, MASTODON)
        assertThat(mastodon.ready).isTrue()
        val forMastodon = mastodon.conversions.getValue(1) as ConvertImage
        assertThat(forMastodon.targetMime).isEqualTo("image/jpeg")
        assertThat(forMastodon.maxBytes).isEqualTo(16 * MIB)
        assertThat(forMastodon.maxPixels).isEqualTo(7680L * 4320)
        assertThat(forMastodon.stripMetadata).isTrue()

        val bluesky = planOf(heic, BLUESKY)
        assertThat(bluesky.ready).isTrue()
        assertThat(bluesky.conversions.getValue(1)).isEqualTo(ConvertImage("image/jpeg", 4000, null, 2_000_000, true))

        // A3: the composer notes that the file is converted.
        assertThat(mastodon.notes).hasSize(1)
        assertThat(bluesky.notes).hasSize(1)
        assertThat(bluesky.notes.single()).contains("image/heic")
    }

    @Test
    fun `a 300 MB MOV to Mastodon is transcoded to MP4 under 99 MB`() {
        val mov = video(1, mime = "video/quicktime", size = 300_000_000, width = 3840, height = 2160, durationMs = 3 * MINUTE_MS, frameRate = 60.0)
        val plan = planOf(listOf(mov), MASTODON)
        assertThat(plan.ready).isTrue()
        val target = plan.conversions.getValue(1) as TranscodeVideo
        assertThat(target.targetMime).isEqualTo("video/mp4")
        assertThat(target.maxBytes).isEqualTo(99 * MIB)
        assertThat(target.maxShortSide).isEqualTo(1080)
        assertThat((target.videoBitrate.toLong() + target.audioBitrate) * 180 / 8).isLessThan(99 * MIB)
        assertThat(plan.notes).hasSize(1)
    }

    @Test
    fun `an MP3 - Mastodon gets audio`() {
        val plan = planOf(listOf(audio(1)), MASTODON)
        assertThat(plan.ready).isTrue()
        assertThat(plan.parts()).containsExactly(listOf(1L))
        assertThat(plan.conversions.getValue(1)).isEqualTo(Conversion.AsIs)
    }

    @Test
    fun `an MP3 - Bluesky shows a conflict until send without the file or remove the account`() {
        val mp3 = listOf(audio(1))
        val asking = planOf(mp3, BLUESKY)
        assertThat(asking.ready).isFalse()
        assertThat(asking.conflicts).containsExactly(
            Conflict(ConflictKind.UNSUPPORTED_TYPE, listOf(1L), listOf(FixKind.SEND_WITHOUT_FILE, FixKind.REMOVE_ACCOUNT)),
        )
        assertThat(asking.partsByPiece).isEmpty()

        val without = planOf(mp3, BLUESKY, mapOf(ConflictKind.UNSUPPORTED_TYPE to FixKind.SEND_WITHOUT_FILE))
        assertThat(without.ready).isTrue()
        assertThat(without.excluded).containsExactly(1L, FixKind.SEND_WITHOUT_FILE)
        assertThat(without.partsByPiece).isEmpty()

        val removed = planOf(mp3, BLUESKY, mapOf(ConflictKind.UNSUPPORTED_TYPE to FixKind.REMOVE_ACCOUNT))
        assertThat(removed.accountRemoved).isTrue()
        assertThat(removed.ready).isTrue()
    }
}
