package org.evsyukov.post.core.render

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.markdown.MANUAL_BREAK
import org.evsyukov.post.core.media.Conflict
import org.evsyukov.post.core.media.ConflictKind
import org.evsyukov.post.core.media.FixKind
import org.evsyukov.post.core.media.MediaPlan
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.media.PartMedia
import org.evsyukov.post.core.media.ResolvedConflict
import org.evsyukov.post.core.render.RenderFixtures.bluesky
import org.evsyukov.post.core.render.RenderFixtures.mastodon
import org.evsyukov.post.core.render.RenderFixtures.render
import org.junit.Assert.assertThrows
import org.junit.Test

/** T14, A11 and the poll: media and poll placed on the parts of an account. */
class PartAssemblerTest {
    private fun plan(
        network: Network,
        parts: Map<Int, List<PartMedia>>,
        conflicts: List<Conflict> = emptyList(),
        resolved: List<ResolvedConflict> = emptyList(),
    ) = MediaPlan(network, parts, emptyMap(), conflicts, emptyList(), resolved)

    private fun assembled(
        source: String,
        profile: TextProfile,
        media: MediaPlan?,
        hasPoll: Boolean = false,
        spoiler: String = "",
    ): AccountRender {
        val text = render(source, profile, spoiler = spoiler, mediaOnlyParts = PartAssembler.mediaOnlyParts(media))
        return PartAssembler.assemble(text, media, hasPoll, spoiler)
    }

    private val long = List(150) { "word" }.joinToString(" ")

    @Test
    fun `media of a piece go to its first part and replies follow it in order`() {
        val media = plan(Network.MASTODON, mapOf(0 to listOf(PartMedia(0, listOf(1, 2, 3, 4)), PartMedia(1, listOf(5, 6)))))
        val result = assembled(long, mastodon(numberParts = true), media)
        assertThat(result.parts.map { it.mediaItemIds })
            .containsExactly(listOf(1L, 2L, 3L, 4L), listOf(5L, 6L), emptyList<Long>()).inOrder()
        assertThat(result.parts.map { it.mediaOnly }).containsExactly(false, true, false).inOrder()
        assertThat(result.parts[1].text).isEqualTo("2/3")
        assertThat(result.parts.map { it.index }).containsExactly(0, 1, 2).inOrder()
        assertThat(result.profileKey).isEqualTo(mastodon(numberParts = true).key)
    }

    @Test
    fun `media added below a manual break go to the first part of that piece`() {
        val source = "First piece.\n\n$MANUAL_BREAK\n\n$long"
        val media = plan(Network.BLUESKY, mapOf(1 to listOf(PartMedia(0, listOf(7)))))
        val result = assembled(source, bluesky(), media)
        val firstOfPiece1 = result.parts.first { it.piece == 1 }
        assertThat(firstOfPiece1.mediaItemIds).containsExactly(7L)
        assertThat(result.parts.filter { it !== firstOfPiece1 }.all { it.mediaItemIds.isEmpty() }).isTrue()
    }

    @Test
    fun `an empty text with media is one part`() {
        val media = plan(Network.BLUESKY, mapOf(0 to listOf(PartMedia(0, listOf(1)))))
        val result = assembled("", bluesky(), media)
        assertThat(result.parts).hasSize(1)
        assertThat(result.parts.single().mediaItemIds).containsExactly(1L)
        assertThat(result.parts.single().mediaOnly).isTrue()
        assertThat(result.parts.single().text).isEmpty()
    }

    @Test
    fun `six images without text split into a part and a numbered reply`() {
        val media = plan(Network.MASTODON, mapOf(0 to listOf(PartMedia(0, listOf(1, 2, 3, 4)), PartMedia(1, listOf(5, 6)))))
        val result = assembled("", mastodon(numberParts = true), media)
        assertThat(result.parts.map { it.text }).containsExactly("1/2", "2/2").inOrder()
        assertThat(result.parts.map { it.mediaItemIds.size }).containsExactly(4, 2).inOrder()
    }

    @Test
    fun `the poll goes with the first Mastodon part unless a fix removed it`() {
        assertThat(assembled(long, mastodon(), null, hasPoll = true).parts.map { it.carriesPoll })
            .containsExactly(true, false).inOrder()
        val removed = plan(
            Network.MASTODON,
            emptyMap(),
            resolved = listOf(ResolvedConflict(Conflict(ConflictKind.POLL_WITH_MEDIA, listOf(1)), FixKind.REMOVE_POLL)),
        )
        assertThat(assembled(long, mastodon(), removed, hasPoll = true).parts.none { it.carriesPoll }).isTrue()
        assertThat(assembled(long, bluesky(), null, hasPoll = true).parts.none { it.carriesPoll }).isTrue()
    }

    @Test
    fun `media moved off the poll's part become replies after it`() {
        val media = plan(Network.MASTODON, mapOf(0 to listOf(PartMedia(1, listOf(1, 2)))))
        val result = assembled("Vote, please.", mastodon(), media, hasPoll = true)
        assertThat(result.parts.map { it.carriesPoll }).containsExactly(true, false).inOrder()
        assertThat(result.parts.map { it.mediaItemIds }).containsExactly(emptyList<Long>(), listOf(1L, 2L)).inOrder()
    }

    @Test
    fun `the content warning goes with every Mastodon part and never to Bluesky`() {
        val media = plan(Network.MASTODON, mapOf(0 to listOf(PartMedia(0, listOf(1)), PartMedia(1, listOf(2)))))
        val mastodonParts = assembled(long, mastodon(), media, spoiler = "spoilers").parts
        assertThat(mastodonParts.map { it.spoilerText }.toSet()).containsExactly("spoilers")
        assertThat(mastodonParts.map { it.contentType }.toSet()).containsExactly(null)
        assertThat(mastodonParts[1].count).isEqualTo("spoilers".length)
        assertThat(assembled(long, bluesky(), null, spoiler = "spoilers").parts.all { it.spoilerText == null }).isTrue()
        assertThat(assembled(long, mastodon(), null).parts.all { it.spoilerText == null }).isTrue()
    }

    @Test
    fun `media readiness follows the plan`() {
        val blocked = plan(Network.MASTODON, emptyMap(), conflicts = listOf(Conflict(ConflictKind.UNSUPPORTED_TYPE, listOf(1))))
        assertThat(assembled("Hi", mastodon(), blocked).mediaReady).isFalse()
        assertThat(assembled("Hi", mastodon(), null).mediaReady).isTrue()
    }

    @Test
    fun `a text issue leaves no parts`() {
        val text = render("x".repeat(400), bluesky())
        val result = PartAssembler.assemble(text, null, hasPoll = false, spoilerText = "")
        assertThat(result.parts).isEmpty()
        assertThat(result.textIssue).isInstanceOf(TextIssue.TooLongToSplit::class.java)
        assertThat(result.network).isEqualTo(Network.BLUESKY)
    }

    @Test
    fun `text rendered for another media plan or content warning is rejected`() {
        val media = plan(Network.MASTODON, mapOf(0 to listOf(PartMedia(0, listOf(1)), PartMedia(1, listOf(2)))))
        val text = render("Hi", mastodon())
        assertThrows(IllegalArgumentException::class.java) { PartAssembler.assemble(text, media, false, "") }
        assertThrows(IllegalArgumentException::class.java) { PartAssembler.assemble(text, null, false, "other") }
        val bluesky = plan(Network.BLUESKY, emptyMap())
        assertThrows(IllegalArgumentException::class.java) { PartAssembler.assemble(text, bluesky, false, "") }
    }

    @Test
    fun `media-only parts per piece come from the plan`() {
        val media = plan(
            Network.BLUESKY,
            mapOf(0 to listOf(PartMedia(0, listOf(1)), PartMedia(1, listOf(2)), PartMedia(2, listOf(3))), 2 to listOf(PartMedia(0, listOf(4)))),
        )
        assertThat(PartAssembler.mediaOnlyParts(media)).containsExactly(0, 2, 2, 0)
        assertThat(PartAssembler.mediaOnlyParts(null)).isEmpty()
    }
}
