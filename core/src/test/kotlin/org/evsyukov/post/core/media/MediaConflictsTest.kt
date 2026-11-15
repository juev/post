package org.evsyukov.post.core.media

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.media.ConflictKind.ALT_TOO_LONG
import org.evsyukov.post.core.media.ConflictKind.CANNOT_FIT
import org.evsyukov.post.core.media.ConflictKind.MIXED_TYPES
import org.evsyukov.post.core.media.ConflictKind.POLL_WITH_MEDIA
import org.evsyukov.post.core.media.ConflictKind.TOO_MANY_FILES
import org.evsyukov.post.core.media.ConflictKind.UNSUPPORTED_TYPE
import org.evsyukov.post.core.media.ConflictKind.VIDEO_QUOTA
import org.evsyukov.post.core.media.ConflictKind.VIDEO_TOO_LONG
import org.evsyukov.post.core.media.FixKind.MOVE_MEDIA_TO_REPLY
import org.evsyukov.post.core.media.FixKind.MOVE_TO_REPLY
import org.evsyukov.post.core.media.FixKind.MOVE_VIDEO_TO_REPLY
import org.evsyukov.post.core.media.FixKind.REMOVE_ACCOUNT
import org.evsyukov.post.core.media.FixKind.REMOVE_FILES_FOR_NETWORK
import org.evsyukov.post.core.media.FixKind.REMOVE_IMAGES
import org.evsyukov.post.core.media.FixKind.REMOVE_MEDIA
import org.evsyukov.post.core.media.FixKind.REMOVE_POLL
import org.evsyukov.post.core.media.FixKind.REMOVE_VIDEO
import org.evsyukov.post.core.media.FixKind.SEND_LATER
import org.evsyukov.post.core.media.FixKind.SEND_WITHOUT_FILE
import org.junit.Test

/** Every conflict of the media.md table with each fix it offers (A10–A13). */
class MediaConflictsTest {

    @Test
    fun `the fixes of each conflict follow the media table`() {
        assertThat(TOO_MANY_FILES.fixes).containsExactly(MOVE_TO_REPLY, REMOVE_FILES_FOR_NETWORK, REMOVE_ACCOUNT).inOrder()
        assertThat(UNSUPPORTED_TYPE.fixes).containsExactly(SEND_WITHOUT_FILE, REMOVE_ACCOUNT).inOrder()
        assertThat(MIXED_TYPES.fixes).containsExactly(MOVE_VIDEO_TO_REPLY, REMOVE_VIDEO, REMOVE_IMAGES).inOrder()
        assertThat(VIDEO_TOO_LONG.fixes).containsExactly(REMOVE_VIDEO, REMOVE_ACCOUNT).inOrder()
        assertThat(VIDEO_QUOTA.fixes).containsExactly(SEND_LATER, SEND_WITHOUT_FILE, REMOVE_ACCOUNT).inOrder()
        assertThat(POLL_WITH_MEDIA.fixes).containsExactly(REMOVE_POLL, REMOVE_MEDIA, MOVE_MEDIA_TO_REPLY).inOrder()
        assertThat(CANNOT_FIT.fixes).containsExactly(REMOVE_VIDEO, REMOVE_ACCOUNT).inOrder()
        assertThat(ALT_TOO_LONG.fixes).isEmpty()
        assertThat(ConflictKind.entries.filter { !it.blocking }).containsExactly(ALT_TOO_LONG)
        // Every fix is offered by some conflict.
        assertThat(ConflictKind.entries.flatMap { it.fixes }.toSet()).containsExactlyElementsIn(FixKind.entries)
    }

    // Too many files

    @Test
    fun `A11 move to a reply keeps the order and fills each reply up to the limit`() {
        val plan = planOf(images(1L..9L), MASTODON, mapOf(TOO_MANY_FILES to MOVE_TO_REPLY))
        assertThat(plan.parts()).containsExactly(listOf(1L, 2L, 3L, 4L), listOf(5L, 6L, 7L, 8L), listOf(9L)).inOrder()
        assertThat(plan.partsByPiece.getValue(0).map { it.partOffset }).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `remove files for this network leaves the extra files out`() {
        val plan = planOf(images(1L..6L), MASTODON, mapOf(TOO_MANY_FILES to REMOVE_FILES_FOR_NETWORK))
        assertThat(plan.ready).isTrue()
        assertThat(plan.parts()).containsExactly(listOf(1L, 2L, 3L, 4L))
        assertThat(plan.excluded).containsExactly(5L, REMOVE_FILES_FOR_NETWORK, 6L, REMOVE_FILES_FOR_NETWORK).inOrder()
    }

    @Test
    fun `remove the account sends nothing to the network`() {
        val items = images(1L..6L)
        val plan = planOf(items, MASTODON, mapOf(TOO_MANY_FILES to REMOVE_ACCOUNT))
        assertThat(plan.accountRemoved).isTrue()
        assertThat(plan.ready).isTrue()
        assertThat(plan.partsByPiece).isEmpty()
        assertThat(plan.conversions).isEmpty()
        assertThat(plan.excluded.keys).containsExactlyElementsIn(items.map { it.id })
        assertThat(plan.resolved).containsExactly(ResolvedConflict(Conflict(TOO_MANY_FILES, listOf(5L, 6L)), REMOVE_ACCOUNT))
    }

    @Test
    fun `A11 Bluesky takes 5 to 10 images in one gallery and asks from 11`() {
        for (count in 5L..10L) {
            val plan = planOf(images(1L..count), BLUESKY)
            assertThat(plan.conflicts).isEmpty()
            assertThat(plan.parts()).containsExactly((1L..count).toList())
        }
        val eleven = planOf(images(1L..11L), BLUESKY)
        assertThat(eleven.conflicts).containsExactly(Conflict(TOO_MANY_FILES, listOf(11L)))
        assertThat(eleven.parts()).containsExactly((1L..10L).toList())

        val moved = planOf(images(1L..11L), BLUESKY, mapOf(TOO_MANY_FILES to MOVE_TO_REPLY))
        assertThat(moved.parts()).containsExactly((1L..10L).toList(), listOf(11L)).inOrder()
    }

    @Test
    fun `a second video is one file too many where a video must be alone`() {
        val twoVideos = listOf(video(1), video(2))
        val asking = planOf(twoVideos, MASTODON)
        assertThat(asking.conflicts).containsExactly(Conflict(TOO_MANY_FILES, listOf(2L)))
        assertThat(asking.parts()).containsExactly(listOf(1L))

        val moved = planOf(twoVideos, MASTODON, mapOf(TOO_MANY_FILES to MOVE_TO_REPLY))
        assertThat(moved.parts()).containsExactly(listOf(1L), listOf(2L)).inOrder()

        // For Bluesky a GIF is a video, so a video and a GIF are two videos.
        val videoAndGif = planOf(listOf(video(1), gif(2)), BLUESKY)
        assertThat(videoAndGif.conflicts).containsExactly(Conflict(TOO_MANY_FILES, listOf(2L)))
    }

    // Type not supported

    @Test
    fun `an audio type the instance does not list is not supported`() {
        val plan = planOf(listOf(audio(1, mime = "audio/x-ms-wma")), MASTODON)
        assertThat(plan.conflicts).containsExactly(Conflict(UNSUPPORTED_TYPE, listOf(1L)))
    }

    @Test
    fun `a GIF type the instance does not take is not supported`() {
        val noGif = MASTODON.copy(imageMimes = setOf("image/jpeg", "image/png"))
        assertThat(planOf(listOf(gif(1)), noGif).conflicts).containsExactly(Conflict(UNSUPPORTED_TYPE, listOf(1L)))
    }

    @Test
    fun `A12 send without the file keeps the other files of the post`() {
        val plan = planOf(listOf(image(1), audio(2), image(3)), BLUESKY, mapOf(UNSUPPORTED_TYPE to SEND_WITHOUT_FILE))
        assertThat(plan.parts()).containsExactly(listOf(1L, 3L))
        assertThat(plan.excluded).containsExactly(2L, SEND_WITHOUT_FILE)
    }

    // Mixed types

    @Test
    fun `a video with images is a mixed conflict in both networks`() {
        val items = listOf(image(1), video(2), image(3))
        for (rules in listOf(MASTODON, BLUESKY)) {
            val plan = planOf(items, rules)
            assertThat(plan.conflicts).containsExactly(Conflict(MIXED_TYPES, listOf(1L, 2L, 3L)))
            assertThat(plan.partsByPiece).isEmpty()
            assertThat(plan.ready).isFalse()
        }
    }

    @Test
    fun `move the video to a reply puts it after the images`() {
        val plan = planOf(listOf(video(1), image(2), image(3)), MASTODON, mapOf(MIXED_TYPES to MOVE_VIDEO_TO_REPLY))
        assertThat(plan.ready).isTrue()
        assertThat(plan.parts()).containsExactly(listOf(2L, 3L), listOf(1L)).inOrder()
    }

    @Test
    fun `remove the video or remove the images for this network`() {
        val items = listOf(image(1), video(2), image(3))
        val noVideo = planOf(items, BLUESKY, mapOf(MIXED_TYPES to REMOVE_VIDEO))
        assertThat(noVideo.parts()).containsExactly(listOf(1L, 3L))
        assertThat(noVideo.excluded).containsExactly(2L, REMOVE_VIDEO)

        val noImages = planOf(items, BLUESKY, mapOf(MIXED_TYPES to REMOVE_IMAGES))
        assertThat(noImages.parts()).containsExactly(listOf(2L))
        assertThat(noImages.excluded).containsExactly(1L, REMOVE_IMAGES, 3L, REMOVE_IMAGES).inOrder()
    }

    @Test
    fun `A8 a GIF with images is mixed for Bluesky and allowed for Mastodon`() {
        val items = listOf(image(1), gif(2), image(3))
        assertThat(planOf(items, BLUESKY).conflicts).containsExactly(Conflict(MIXED_TYPES, listOf(1L, 2L, 3L)))

        val mastodon = planOf(items, MASTODON)
        assertThat(mastodon.conflicts).isEmpty()
        assertThat(mastodon.parts()).containsExactly(listOf(1L, 2L, 3L))
        assertThat(mastodon.conversions.getValue(2)).isEqualTo(Conversion.AsIs)
    }

    @Test
    fun `A8 a GIF alone goes to Bluesky as video`() {
        val plan = planOf(listOf(gif(1)), BLUESKY)
        assertThat(plan.conversions.getValue(1)).isEqualTo(Conversion.GifAsVideo)
        assertThat(plan.ready).isTrue()
    }

    @Test
    fun `audio with images is mixed for Mastodon`() {
        assertThat(planOf(listOf(audio(1), image(2)), MASTODON).conflicts)
            .containsExactly(Conflict(MIXED_TYPES, listOf(1L, 2L)))
    }

    @Test
    fun `after moving the videos of a mixed part each video gets its own reply`() {
        val plan = planOf(listOf(image(1), video(2), video(3)), MASTODON, mapOf(MIXED_TYPES to MOVE_VIDEO_TO_REPLY))
        assertThat(plan.parts()).containsExactly(listOf(1L), listOf(2L), listOf(3L)).inOrder()
        assertThat(plan.conflicts).isEmpty()
    }

    // Video longer than allowed

    @Test
    fun `a 14-minute video is too long for Bluesky, 10 minutes is not`() {
        val long = listOf(video(1, durationMs = 14 * MINUTE_MS))
        val asking = planOf(long, BLUESKY)
        assertThat(asking.conflicts).containsExactly(Conflict(VIDEO_TOO_LONG, listOf(1L), listOf(REMOVE_VIDEO, REMOVE_ACCOUNT)))
        assertThat(asking.partsByPiece).isEmpty()

        assertThat(planOf(listOf(video(1, durationMs = 10 * MINUTE_MS)), BLUESKY).conflicts).isEmpty()

        // Mastodon has no duration cap.
        assertThat(planOf(long, MASTODON).conflicts).isEmpty()
    }

    @Test
    fun `remove the video resolves a video that is too long`() {
        val plan = planOf(listOf(image(1, piece = 1), video(2, durationMs = 14 * MINUTE_MS)), BLUESKY, mapOf(VIDEO_TOO_LONG to REMOVE_VIDEO))
        assertThat(plan.ready).isTrue()
        assertThat(plan.excluded).containsExactly(2L, REMOVE_VIDEO)
        assertThat(plan.parts(1)).containsExactly(listOf(1L))

        val removed = planOf(listOf(video(1, durationMs = 14 * MINUTE_MS)), BLUESKY, mapOf(VIDEO_TOO_LONG to REMOVE_ACCOUNT))
        assertThat(removed.accountRemoved).isTrue()
    }

    // Video quota

    @Test
    fun `an exhausted quota holds every file for the video service`() {
        val asking = planOf(listOf(video(1)), BLUESKY, videoQuotaExhausted = true)
        assertThat(asking.conflicts).containsExactly(Conflict(VIDEO_QUOTA, listOf(1L), listOf(SEND_LATER, SEND_WITHOUT_FILE, REMOVE_ACCOUNT)))
        assertThat(asking.partsByPiece).isEmpty()

        // A Bluesky GIF goes to the video service too; images do not.
        assertThat(planOf(listOf(gif(1)), BLUESKY, videoQuotaExhausted = true).conflicts)
            .containsExactly(Conflict(VIDEO_QUOTA, listOf(1L)))
        assertThat(planOf(images(1L..3L), BLUESKY, videoQuotaExhausted = true).conflicts).isEmpty()
    }

    @Test
    fun `send later keeps the video, send without the file drops it`() {
        val later = planOf(listOf(video(1)), BLUESKY, mapOf(VIDEO_QUOTA to SEND_LATER), videoQuotaExhausted = true)
        assertThat(later.ready).isTrue()
        assertThat(later.sendLater).isTrue()
        assertThat(later.parts()).containsExactly(listOf(1L))

        val without = planOf(listOf(video(1)), BLUESKY, mapOf(VIDEO_QUOTA to SEND_WITHOUT_FILE), videoQuotaExhausted = true)
        assertThat(without.ready).isTrue()
        assertThat(without.sendLater).isFalse()
        assertThat(without.excluded).containsExactly(1L, SEND_WITHOUT_FILE)

        val removed = planOf(listOf(video(1)), BLUESKY, mapOf(VIDEO_QUOTA to REMOVE_ACCOUNT), videoQuotaExhausted = true)
        assertThat(removed.accountRemoved).isTrue()
    }

    // Poll with media

    @Test
    fun `a poll with media conflicts for Mastodon and not for Bluesky`() {
        val items = images(1L..2L)
        val asking = planOf(items, MASTODON, hasPoll = true)
        assertThat(asking.conflicts).containsExactly(Conflict(POLL_WITH_MEDIA, listOf(1L, 2L), listOf(REMOVE_POLL, REMOVE_MEDIA, MOVE_MEDIA_TO_REPLY)))
        assertThat(asking.partsByPiece).isEmpty()

        assertThat(planOf(items, BLUESKY, hasPoll = true).conflicts).isEmpty()
        assertThat(planOf(items, MASTODON, hasPoll = false).conflicts).isEmpty()
    }

    @Test
    fun `remove the poll keeps the media, remove the media keeps the poll`() {
        val items = images(1L..2L)
        val noPoll = planOf(items, MASTODON, mapOf(POLL_WITH_MEDIA to REMOVE_POLL), hasPoll = true)
        assertThat(noPoll.ready).isTrue()
        assertThat(noPoll.pollRemoved).isTrue()
        assertThat(noPoll.parts()).containsExactly(listOf(1L, 2L))

        val noMedia = planOf(items, MASTODON, mapOf(POLL_WITH_MEDIA to REMOVE_MEDIA), hasPoll = true)
        assertThat(noMedia.ready).isTrue()
        assertThat(noMedia.pollRemoved).isFalse()
        assertThat(noMedia.partsByPiece).isEmpty()
        assertThat(noMedia.excluded).containsExactly(1L, REMOVE_MEDIA, 2L, REMOVE_MEDIA).inOrder()
    }

    @Test
    fun `move the media to a reply leaves the first part to the poll`() {
        val plan = planOf(images(1L..2L), MASTODON, mapOf(POLL_WITH_MEDIA to MOVE_MEDIA_TO_REPLY), hasPoll = true)
        assertThat(plan.ready).isTrue()
        assertThat(plan.partsByPiece.getValue(0)).containsExactly(PartMedia(1, listOf(1L, 2L)))

        val sixImages = planOf(
            images(1L..6L),
            MASTODON,
            mapOf(TOO_MANY_FILES to MOVE_TO_REPLY, POLL_WITH_MEDIA to MOVE_MEDIA_TO_REPLY),
            hasPoll = true,
        )
        assertThat(sixImages.partsByPiece.getValue(0)).containsExactly(
            PartMedia(1, listOf(1L, 2L, 3L, 4L)),
            PartMedia(2, listOf(5L, 6L)),
        ).inOrder()
    }

    @Test
    fun `media of a later piece do not share the part with the poll`() {
        val plan = planOf(images(1L..2L, piece = 1), MASTODON, hasPoll = true)
        assertThat(plan.conflicts).isEmpty()
        assertThat(plan.parts(1)).containsExactly(listOf(1L, 2L))
    }

    // Transcoding cannot fit the limit

    @Test
    fun `a 40-minute video cannot fit 99 MB`() {
        val long = listOf(video(1, size = 2_000_000_000, durationMs = 40 * MINUTE_MS))
        val asking = planOf(long, MASTODON)
        assertThat(asking.conflicts).containsExactly(Conflict(CANNOT_FIT, listOf(1L), listOf(REMOVE_VIDEO, REMOVE_ACCOUNT)))
        assertThat(asking.ready).isFalse()

        val without = planOf(long, MASTODON, mapOf(CANNOT_FIT to REMOVE_VIDEO))
        assertThat(without.ready).isTrue()
        assertThat(without.excluded).containsExactly(1L, REMOVE_VIDEO)

        assertThat(planOf(long, MASTODON, mapOf(CANNOT_FIT to REMOVE_ACCOUNT)).accountRemoved).isTrue()
    }

    @Test
    fun `files the app cannot convert do not fit when they are over the limit`() {
        // The app does not convert GIF or audio.
        assertThat(planOf(listOf(gif(1, size = 20 * MIB)), MASTODON).conflicts).containsExactly(Conflict(CANNOT_FIT, listOf(1L)))
        assertThat(planOf(listOf(audio(1, size = 120 * MIB)), MASTODON).conflicts).containsExactly(Conflict(CANNOT_FIT, listOf(1L)))
        assertThat(planOf(listOf(gif(1, size = 310_000_000)), BLUESKY).conflicts).containsExactly(Conflict(CANNOT_FIT, listOf(1L)))
        // Without a duration the bitrate cannot be chosen.
        assertThat(planOf(listOf(video(1, mime = "video/x-matroska", durationMs = null)), MASTODON).conflicts)
            .containsExactly(Conflict(CANNOT_FIT, listOf(1L)))
    }

    @Test
    fun `R11 a GIF over 1280x720 cannot fit Mastodon, which rejects it, and goes to Bluesky as video`() {
        assertThat(planOf(listOf(gif(1, width = 1280, height = 720)), MASTODON).conversions.getValue(1)).isEqualTo(Conversion.AsIs)
        for ((width, height) in listOf(1281 to 720, 720 to 1281, 1920 to 1080, 1000 to 1000)) {
            assertThat(planOf(listOf(gif(1, width = width, height = height)), MASTODON).conflicts)
                .containsExactly(Conflict(CANNOT_FIT, listOf(1L)))
        }
        val items = listOf(image(1), gif(2, width = 1920, height = 1080))
        val without = planOf(items, MASTODON, mapOf(CANNOT_FIT to REMOVE_VIDEO))
        assertThat(without.parts()).containsExactly(listOf(1L))
        assertThat(without.excluded).containsExactly(2L, REMOVE_VIDEO)

        // Bluesky has no such limit: the GIF goes to the video service.
        assertThat(planOf(listOf(gif(1, width = 1920, height = 1080)), BLUESKY).conversions.getValue(1))
            .isEqualTo(Conversion.GifAsVideo)
    }

    @Test
    fun `a file whose conversion gives a type the network does not take is a conflict, not a variant it rejects`() {
        val webmOnly = MASTODON.copy(videoMimes = setOf("video/webm"))
        assertThat(planOf(listOf(video(1, mime = "video/x-matroska")), webmOnly).conflicts)
            .containsExactly(Conflict(UNSUPPORTED_TYPE, listOf(1L)))
        assertThat(planOf(listOf(video(1, mime = "video/webm", size = 120 * MIB)), webmOnly).conflicts)
            .containsExactly(Conflict(CANNOT_FIT, listOf(1L)))
        assertThat(planOf(listOf(video(1, mime = "video/webm")), webmOnly).conversions.getValue(1)).isEqualTo(Conversion.AsIs)

        val webpOnly = MASTODON.copy(imageMimes = setOf("image/webp"))
        assertThat(planOf(listOf(image(1, mime = "image/heic")), webpOnly).conflicts)
            .containsExactly(Conflict(UNSUPPORTED_TYPE, listOf(1L)))
        assertThat(planOf(listOf(image(1, mime = "image/webp", size = 20 * MIB)), webpOnly).conflicts)
            .containsExactly(Conflict(CANNOT_FIT, listOf(1L)))
        assertThat(planOf(listOf(image(1, mime = "image/webp")), webpOnly).conversions.getValue(1))
            .isEqualTo(Conversion.ConvertImage("image/webp", null, null, 16 * MIB, true))

        // Transparency needs PNG; JPEG alone would lose it.
        val jpegOnly = MASTODON.copy(imageMimes = setOf("image/jpeg"))
        assertThat(planOf(listOf(image(1, mime = "image/heic", transparency = true)), jpegOnly).conflicts)
            .containsExactly(Conflict(UNSUPPORTED_TYPE, listOf(1L)))

        // A server that takes HEIC but neither JPEG nor PNG gets the HEIC as it is.
        val heicOnly = MASTODON.copy(imageMimes = setOf("image/heic"))
        assertThat(planOf(listOf(image(1, mime = "image/heic")), heicOnly).conversions.getValue(1)).isEqualTo(Conversion.AsIs)
    }

    @Test
    fun `a network that takes no files supports none of them`() {
        val noFiles = MASTODON.copy(maxAttachments = 0)
        val items = listOf(image(1), video(2, piece = 1))
        assertThat(planOf(items, noFiles).conflicts).containsExactly(Conflict(UNSUPPORTED_TYPE, listOf(1L, 2L)))
        val without = planOf(items, noFiles, mapOf(UNSUPPORTED_TYPE to SEND_WITHOUT_FILE))
        assertThat(without.partsByPiece).isEmpty()
        assertThat(without.excluded.keys).containsExactly(1L, 2L)
    }

    // Alt text

    @Test
    fun `A9 alt text over the limit is marked but does not hold the post`() {
        val long = "a".repeat(2001)
        val plan = planOf(listOf(image(1, alt = long), image(2, alt = "a".repeat(2000))), BLUESKY)
        assertThat(plan.conflicts).containsExactly(Conflict(ALT_TOO_LONG, listOf(1L), emptyList()))
        assertThat(plan.ready).isTrue()
        assertThat(plan.parts()).containsExactly(listOf(1L, 2L))

        assertThat(planOf(listOf(image(1, alt = long)), MASTODON).conflicts).isEmpty()
    }

    @Test
    fun `A9 alt text is counted in characters, not UTF-16 units`() {
        val emoji = "😀".repeat(2000)
        assertThat(planOf(listOf(image(1, alt = emoji)), BLUESKY).conflicts).isEmpty()
        assertThat(planOf(listOf(image(1, alt = emoji + "a")), BLUESKY).conflicts).containsExactly(Conflict(ALT_TOO_LONG, listOf(1L)))
    }

    // Choices

    @Test
    fun `a fix the conflict does not offer is no choice`() {
        val plan = planOf(images(1L..6L), MASTODON, mapOf(TOO_MANY_FILES to REMOVE_POLL))
        assertThat(plan.conflicts).containsExactly(Conflict(TOO_MANY_FILES, listOf(5L, 6L)))
        assertThat(plan.resolved).isEmpty()
        assertThat(plan.ready).isFalse()
    }

    @Test
    fun `A12 a default fix for a conflict that does not happen changes nothing`() {
        val defaults = mapOf(TOO_MANY_FILES to MOVE_TO_REPLY, UNSUPPORTED_TYPE to SEND_WITHOUT_FILE)
        val plan = planOf(images(1L..3L), MASTODON, defaults)
        assertThat(plan).isEqualTo(planOf(images(1L..3L), MASTODON))
    }

    @Test
    fun `several conflicts are listed together, each with its files`() {
        val items = listOf(audio(1), image(2), image(3), video(4, durationMs = 14 * MINUTE_MS))
        val plan = planOf(items, BLUESKY)
        assertThat(plan.conflicts).containsExactly(
            Conflict(UNSUPPORTED_TYPE, listOf(1L)),
            Conflict(VIDEO_TOO_LONG, listOf(4L)),
        ).inOrder()
        assertThat(plan.parts()).containsExactly(listOf(2L, 3L))
    }
}
