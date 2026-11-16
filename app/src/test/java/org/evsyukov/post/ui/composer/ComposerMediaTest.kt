package org.evsyukov.post.ui.composer

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.markdown.MANUAL_BREAK
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.media.ConflictKind
import org.evsyukov.post.core.media.Conversion
import org.evsyukov.post.core.media.FixKind
import org.evsyukov.post.core.media.MediaKind
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.media.PartMedia
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.accounts.AccountLimits
import org.evsyukov.post.data.accounts.AccountOptions
import org.evsyukov.post.data.db.AccountState
import org.evsyukov.post.data.posts.Attachment
import org.evsyukov.post.data.posts.PollDraft
import org.evsyukov.post.data.settings.Settings
import org.evsyukov.post.data.settings.TooManyFilesDefault
import org.evsyukov.post.data.settings.UnsupportedTypeDefault
import org.junit.Test
import java.time.Instant

/**
 * The media plan of the composer: strictest rules per network (A10), chosen fixes over the defaults of the
 * settings (A12), metadata (A6), pieces (T14), and what blocks sending: conflicts without a fix (R11), alt
 * text (R10, A9) and an empty post.
 */
class ComposerMediaTest {
    private fun mastodon(id: Long, caps: MastodonCapabilities = MastodonCapabilities()) = Account(
        id, Network.MASTODON, "https://m$id.example", "u@m$id.example", "$id", "M$id", null, "", 0,
        AccountState.OK, AccountLimits.Mastodon(caps), Instant.EPOCH, AccountOptions(), Instant.EPOCH,
    )

    private fun bluesky(id: Long) = Account(
        id, Network.BLUESKY, "https://pds.example", "b$id.bsky.social", "did:plc:b$id", "B$id", null, "", 0,
        AccountState.OK, null, null, AccountOptions(), Instant.EPOCH,
    )

    private fun image(id: Long, piece: Int = 0, alt: String = "") =
        Attachment(id = id, kind = MediaKind.IMAGE, path = "attachments/$id.jpg", mime = "image/jpeg", sizeBytes = 50_000, width = 800, height = 600, pieceIndex = piece, alt = alt)

    private fun audio(id: Long) = Attachment(id = id, kind = MediaKind.AUDIO, path = "attachments/$id.mp3", mime = "audio/mpeg", sizeBytes = 20_000, durationMs = 1_000)

    private val six = (1L..6L).map { image(it) }

    @Test
    fun sixImagesGoInOneBlueskyGalleryAndAskMastodon() {
        val media = planMedia(six, 1, listOf(mastodon(1), bluesky(2)), Settings(), emptyMap(), hasPoll = false)
        assertThat(media.map { it.network }).containsExactly(Network.MASTODON, Network.BLUESKY).inOrder()
        val (m, b) = media
        assertThat(b.plan.ready).isTrue()
        assertThat(b.plan.partsByPiece).containsExactly(0, listOf(PartMedia(0, (1L..6L).toList())))
        assertThat(m.plan.ready).isFalse()
        assertThat(m.blocking.single().kind).isEqualTo(ConflictKind.TOO_MANY_FILES)
        assertThat(m.blocking.single().itemIds).containsExactly(5L, 6L).inOrder()
        assertThat(conflictedIds(media)).containsExactly(5L, 6L)

        val fixed = planMedia(six, 1, listOf(mastodon(1), bluesky(2)), Settings(), mapOf(Network.MASTODON to mapOf(ConflictKind.TOO_MANY_FILES to FixKind.MOVE_TO_REPLY)), false)
        assertThat(fixed[0].plan.ready).isTrue()
        assertThat(fixed[0].plan.partsByPiece.getValue(0)).containsExactly(PartMedia(0, listOf(1L, 2L, 3L, 4L)), PartMedia(1, listOf(5L, 6L))).inOrder()
        assertThat(fixed[0].plan.resolved.single().fix).isEqualTo(FixKind.MOVE_TO_REPLY)
    }

    @Test
    fun theStrictestAccountOfANetworkDecides() {
        val roomy = mastodon(1, MastodonCapabilities(maxMediaAttachments = 4, descriptionLimit = 1500))
        val tight = mastodon(3, MastodonCapabilities(maxMediaAttachments = 2, descriptionLimit = 400))
        val three = (1L..3L).map { image(it) }

        assertThat(planMedia(three, 1, listOf(roomy), Settings(), emptyMap(), false).single().plan.ready).isTrue()
        val both = planMedia(three, 1, listOf(roomy, tight), Settings(), emptyMap(), false).single()
        assertThat(both.accounts.map { it.id }).containsExactly(1L, 3L)
        assertThat(both.rules.maxAttachments).isEqualTo(2)
        assertThat(both.rules.maxAltLength).isEqualTo(400)
        assertThat(both.blocking.single().itemIds).containsExactly(3L)
    }

    @Test
    fun aChosenFixWinsOverTheDefaultOfTheSettingsAndADefaultResolvesAlone() {
        val defaults = Settings(defaultFixTooManyFiles = TooManyFilesDefault.MOVE_TO_REPLY, defaultFixUnsupportedType = UnsupportedTypeDefault.SEND_WITHOUT_FILE)
        val plans = planMedia(six + audio(7), 1, listOf(mastodon(1), bluesky(2)), defaults, emptyMap(), false)
        // Default fixes: Mastodon moves the extras to a reply (audio then needs its own fix), Bluesky sends without the audio.
        val bluesky = plans.single { it.network == Network.BLUESKY }.plan
        assertThat(bluesky.ready).isTrue()
        assertThat(bluesky.excluded).containsExactly(7L, FixKind.SEND_WITHOUT_FILE)

        val chosen = mapOf(Network.MASTODON to mapOf(ConflictKind.TOO_MANY_FILES to FixKind.REMOVE_FILES_FOR_NETWORK, ConflictKind.MIXED_TYPES to FixKind.REMOVE_VIDEO))
        val mastodon = planMedia(six + audio(7), 1, listOf(mastodon(1)), defaults, chosen, false).single().plan
        assertThat(mastodon.ready).isTrue()
        assertThat(mastodon.excluded).containsExactly(5L, FixKind.REMOVE_FILES_FOR_NETWORK, 6L, FixKind.REMOVE_FILES_FOR_NETWORK, 7L, FixKind.REMOVE_VIDEO)
        assertThat(fixesFor(Network.MASTODON, defaults, chosen)[ConflictKind.TOO_MANY_FILES]).isEqualTo(FixKind.REMOVE_FILES_FOR_NETWORK)
        assertThat(fixesFor(Network.BLUESKY, defaults, chosen)[ConflictKind.TOO_MANY_FILES]).isEqualTo(FixKind.MOVE_TO_REPLY)
    }

    @Test
    fun metadataIsStrippedUnlessTheSettingsKeepIt() {
        val stripped = planMedia(listOf(image(1)), 1, listOf(mastodon(1)), Settings(), emptyMap(), false).single().plan
        assertThat((stripped.conversions.getValue(1L) as Conversion.ConvertImage).stripMetadata).isTrue()
        val kept = planMedia(listOf(image(1)), 1, listOf(mastodon(1)), Settings(keepImageMetadata = true), emptyMap(), false).single().plan
        assertThat(kept.conversions.getValue(1L)).isEqualTo(Conversion.AsIs)
    }

    @Test
    fun aPollWithMediaAsksMastodonOnly() {
        val plans = planMedia(listOf(image(1)), 1, listOf(mastodon(1), bluesky(2)), Settings(), emptyMap(), hasPoll = true)
        assertThat(plans[0].blocking.single().kind).isEqualTo(ConflictKind.POLL_WITH_MEDIA)
        assertThat(plans[1].plan.ready).isTrue()
    }

    @Test
    fun theCursorPieceCountsTheBreakLinesAboveIt() {
        val text = "first\n$MANUAL_BREAK\nsecond\n  $MANUAL_BREAK  \nthird"
        assertThat(pieceCount(text)).isEqualTo(3)
        assertThat(pieceAt(text, 0)).isEqualTo(0)
        assertThat(pieceAt(text, text.indexOf(MANUAL_BREAK))).isEqualTo(0)
        assertThat(pieceAt(text, text.indexOf("second"))).isEqualTo(1)
        assertThat(pieceAt(text, text.length)).isEqualTo(2)
        assertThat(pieceAt("no breaks", 4)).isEqualTo(0)
        assertThat(pieceCount("")).isEqualTo(1)
    }

    @Test
    fun anAttachmentOfARemovedPieceGoesWithTheLastOne() {
        assertThat(effectivePiece(image(1, piece = 3), pieces = 2)).isEqualTo(1)
        assertThat(effectivePiece(image(1, piece = 1), pieces = 2)).isEqualTo(1)
        val plan = planMedia(listOf(image(1, piece = 0), image(2, piece = 4)), 2, listOf(bluesky(1)), Settings(), emptyMap(), false).single().plan
        assertThat(plan.partsByPiece.keys).containsExactly(0, 1)
    }

    @Test
    fun aConflictWithoutAFixBlocksAndAFixedOneDoesNot() {
        val limits = PollLimits.of(emptyList())
        val accounts = listOf(mastodon(1), bluesky(2))
        val open = planMedia(six, 1, accounts, Settings(), emptyMap(), false)
        val blocked = blockers(listOf(1, 2), "text", emptyList(), null, limits, six, open)
        assertThat(blocked).containsExactly(Blocker.Media(Network.MASTODON, open[0].blocking.single()))

        val fixed = planMedia(six, 1, accounts, Settings(), mapOf(Network.MASTODON to mapOf(ConflictKind.TOO_MANY_FILES to FixKind.MOVE_TO_REPLY)), false)
        assertThat(blockers(listOf(1, 2), "text", emptyList(), null, limits, six, fixed)).isEmpty()
    }

    @Test
    fun attachmentsAloneAreAPost() {
        val limits = PollLimits.of(emptyList())
        assertThat(blockers(listOf(1), " ", emptyList(), null, limits)).containsExactly(Blocker.EmptyPost)
        val one = listOf(image(1))
        val media = planMedia(one, 1, listOf(bluesky(1)), Settings(), emptyMap(), false)
        assertThat(blockers(listOf(1), " ", emptyList(), null, limits, one, media)).isEmpty()
    }

    @Test
    fun aNetworkThatWouldGetNothingMakesAnEmptyPost() {
        val limits = PollLimits.of(emptyList())
        val accounts = listOf(mastodon(1), bluesky(2))
        // A poll alone: Bluesky has no polls, so its post would be empty.
        val poll = PollDraft(listOf("yes", "no"), 86_400)
        val noFiles = planMedia(emptyList(), 1, accounts, Settings(), emptyMap(), hasPoll = true)
        assertThat(blockers(listOf(1, 2), "", emptyList(), poll, limits, emptyList(), noFiles)).containsExactly(Blocker.EmptyPost)
        // The poll alone is a Mastodon post.
        val mastodonOnly = planMedia(emptyList(), 1, listOf(mastodon(1)), Settings(), emptyMap(), hasPoll = true)
        assertThat(blockers(listOf(1), "", emptyList(), poll, limits, emptyList(), mastodonOnly)).isEmpty()

        // An MP3 alone, sent to Bluesky without the file (media.md, "An MP3 to Mastodon and Bluesky").
        val mp3 = listOf(audio(1))
        val withoutFile = planMedia(mp3, 1, listOf(bluesky(2)), Settings(), mapOf(Network.BLUESKY to mapOf(ConflictKind.UNSUPPORTED_TYPE to FixKind.SEND_WITHOUT_FILE)), false)
        assertThat(withoutFile.single().plan.excluded.keys).containsExactly(1L)
        assertThat(blockers(listOf(2), " ", emptyList(), null, limits, mp3, withoutFile)).containsExactly(Blocker.EmptyPost)
        // With text the post is not empty.
        assertThat(blockers(listOf(2), "Listen", emptyList(), null, limits, mp3, withoutFile)).isEmpty()
    }

    @Test
    fun requireAltTextBlocksUntilEveryAttachmentHasOne() {
        val limits = PollLimits.of(emptyList())
        val files = listOf(image(1, alt = "a cat"), image(2), image(3, alt = "   "))
        val media = planMedia(files, 1, listOf(bluesky(1)), Settings(), emptyMap(), false)
        assertThat(blockers(listOf(1), "t", emptyList(), null, limits, files, media, requireAltText = false)).isEmpty()
        assertThat(blockers(listOf(1), "t", emptyList(), null, limits, files, media, requireAltText = true))
            .containsExactly(Blocker.AltMissing(listOf(2L, 3L)))
        val described = files.map { it.copy(alt = "described") }
        assertThat(blockers(listOf(1), "t", emptyList(), null, limits, described, media, requireAltText = true)).isEmpty()
    }

    @Test
    fun altTextOverAMastodonLimitBlocksButOverBlueskysOnlyMarks() {
        val limits = PollLimits.of(emptyList())
        val short = mastodon(1, MastodonCapabilities(descriptionLimit = 10))
        val files = listOf(image(1, alt = "a".repeat(11)), image(2, alt = "a".repeat(10)))
        val media = planMedia(files, 1, listOf(short, bluesky(2)), Settings(), emptyMap(), false)
        assertThat(blockers(listOf(1, 2), "t", emptyList(), null, limits, files, media)).containsExactly(Blocker.AltTooLong(listOf(1L), 10))

        // Characters are code points: ten emoji fit a limit of ten.
        val emoji = listOf(image(1, alt = "😀".repeat(10)))
        assertThat(blockers(listOf(1), "t", emptyList(), null, limits, emoji, planMedia(emoji, 1, listOf(short), Settings(), emptyMap(), false))).isEmpty()

        val long = listOf(image(1, alt = "a".repeat(2001)))
        val sky = planMedia(long, 1, listOf(bluesky(2)), Settings(), emptyMap(), false)
        assertThat(sky.single().plan.conflicts.single().kind).isEqualTo(ConflictKind.ALT_TOO_LONG)
        assertThat(conflictedIds(sky)).containsExactly(1L)
        assertThat(blockers(listOf(2), "t", emptyList(), null, limits, long, sky)).isEmpty()
    }

    @Test
    fun aFileLeftOutForMastodonDoesNotBlockByItsAltText() {
        val limits = PollLimits.of(emptyList())
        val short = mastodon(1, MastodonCapabilities(descriptionLimit = 5))
        val files = (1L..5L).map { image(it, alt = if (it == 5L) "too long here" else "ok") }
        val media = planMedia(files, 1, listOf(short), Settings(), mapOf(Network.MASTODON to mapOf(ConflictKind.TOO_MANY_FILES to FixKind.REMOVE_FILES_FOR_NETWORK)), false)
        assertThat(blockers(listOf(1), "t", emptyList(), null, limits, files, media)).isEmpty()
    }

    @Test
    fun pollProblemsStillBlockWithAttachments() {
        val limits = PollLimits.of(emptyList())
        val poll = PollDraft(listOf("a", ""), 86_400)
        assertThat(blockers(listOf(1), "", emptyList(), poll, limits, listOf(image(1)))).containsExactly(Blocker.Poll(PollProblem.EMPTY_OPTION))
    }

    @Test
    fun aCopyStillRunningHoldsSendAndIsNotAnEmptyPost() {
        val limits = PollLimits.of(emptyList())
        // R9: the picked files are part of the post; it waits for them, and it is not empty while they come.
        assertThat(blockers(listOf(1), " ", emptyList(), null, limits, importing = true)).containsExactly(Blocker.Importing)
        assertThat(blockers(listOf(1), "text", emptyList(), null, limits, importing = true)).containsExactly(Blocker.Importing)
        assertThat(blockers(listOf(1), "text", emptyList(), null, limits, importing = false)).isEmpty()
    }

    @Test
    fun theReadBackOfASaveOnlyGivesIdsAndKeepsWhatCameInMeanwhile() {
        // Saved: -1 (as a new row, now 7) and 3 (an old row). Meanwhile -1 got alt text and -2 was imported.
        val current = listOf(image(3), image(-1, alt = "typed during the save"), image(-2))
        val savedBack = listOf(image(3), image(-1).copy(id = 7))
        val merged = ComposerModel.withSavedIds(current, savedBack)
        assertThat(merged.map { it.id }).containsExactly(3L, 7L, -2L).inOrder()
        assertThat(merged[1].alt).isEqualTo("typed during the save")
        assertThat(merged[1].path).isEqualTo(image(-1).path)
    }
}
