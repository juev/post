package org.evsyukov.post.core.render

import org.evsyukov.post.core.bluesky.FacetInput
import org.evsyukov.post.core.bluesky.IdentityResolver
import org.evsyukov.post.core.bluesky.LinkSpan
import org.evsyukov.post.core.bluesky.MentionSpan
import org.evsyukov.post.core.bluesky.TagSpan
import org.evsyukov.post.core.media.MediaPlan
import org.evsyukov.post.core.media.Network

/**
 * One post of an account's thread, as it is published.
 *
 * @property index the position in the thread, from 0; each part replies to the one before (T15).
 * @property text what is sent as the status or post text; see [TextPart.text].
 * @property contentType the Mastodon `content_type`, or null to send none.
 * @property spoilerText the Mastodon content warning of every part (N3), or null when there is none or the
 *   network is Bluesky.
 * @property mediaItemIds the attachments of this part, in order (T14, A11).
 * @property mediaOnly the part carries no text of the draft: a media-only reply (A11) or the part of a piece
 *   that has media and no text. Its text is empty or only the part number.
 * @property carriesPoll the Mastodon poll goes with this part, the first of the thread.
 * @property links Bluesky link facets, [mentions] by handle and [tags]; empty for Mastodon.
 */
data class RenderedPart(
    val index: Int,
    val piece: Int,
    val text: String,
    val contentType: String?,
    val spoilerText: String?,
    val count: Int,
    val limit: Int,
    val bytes: Int?,
    val mediaItemIds: List<Long>,
    val mediaOnly: Boolean,
    val carriesPoll: Boolean,
    val links: List<LinkSpan>,
    val mentions: List<HandleSpan>,
    val tags: List<TagSpan>,
)

/**
 * Everything one account (one profile) receives: its parts with text and media, or the text conflict that
 * holds it ([textIssue]); [mediaReady] is false while a media conflict waits for a fix (media.md, A3).
 */
data class AccountRender(
    val profileKey: String,
    val network: Network,
    val parts: List<RenderedPart>,
    val textIssue: TextIssue?,
    val mediaReady: Boolean,
    val manyPartsWarning: Boolean,
)

/** Puts the media plan and the poll on the text parts of an account (threads.md T14, media.md A11). */
object PartAssembler {

    /**
     * The `mediaOnlyParts` argument of [TextRenderer.render] for [media]: for every piece with media, the
     * number of media-only replies after its first part (the parts with `partOffset` 1 and up).
     */
    fun mediaOnlyParts(media: MediaPlan?): Map<Int, Int> =
        media?.partsByPiece.orEmpty()
            .filterValues { parts -> parts.isNotEmpty() }
            .mapValues { (_, parts) -> parts.count { it.partOffset >= 1 } }

    /**
     * The account's parts: [text] with the media of [media] and the poll.
     *
     * - The media of a piece at `partOffset` 0 go to the first part of the piece (T14); those at 1 and up go
     *   to the media-only replies right after it, in the order of their offsets (A11). A piece with media and
     *   no text has a part of its own for them, so an empty text with media still gives one part.
     * - The poll goes with the first part of a Mastodon thread, unless a fix removed it for this network
     *   ([MediaPlan.pollRemoved]). Bluesky has no polls.
     * - [spoilerText], the draft's content warning, goes with every Mastodon part.
     *
     * [text] must have been rendered with [mediaOnlyParts] of [media] and with the same content warning;
     * otherwise the part numbers would not match the parts, and this throws [IllegalArgumentException].
     */
    fun assemble(text: TextRender, media: MediaPlan?, hasPoll: Boolean, spoilerText: String): AccountRender {
        val profile = text.profile
        require(media == null || media.network == profile.network) { "The media plan is for another network" }
        require(spoilerText == text.spoilerText) { "The text was rendered with another content warning" }
        val mediaReady = media?.ready ?: true
        if (text.issue != null) {
            return AccountRender(profile.key, profile.network, emptyList(), text.issue, mediaReady, text.manyPartsWarning)
        }
        require(mediaOnlyParts(media) == text.mediaOnlyParts) { "The text was rendered for another media plan" }
        val firsts = HashMap<Int, List<Long>>()
        val replies = HashMap<Int, ArrayDeque<List<Long>>>()
        for ((piece, parts) in media?.partsByPiece.orEmpty()) {
            for (part in parts.sortedBy { it.partOffset }) {
                if (part.partOffset == 0) {
                    firsts[piece] = part.itemIds
                } else {
                    replies.getOrPut(piece) { ArrayDeque() }.addLast(part.itemIds)
                }
            }
        }
        val spoiler = spoilerText.takeIf { profile.network == Network.MASTODON && it.isNotEmpty() }
        val poll = hasPoll && profile.network == Network.MASTODON && media?.pollRemoved != true
        val seen = HashSet<Int>()
        val parts = text.parts.mapIndexed { index, part ->
            val items = if (part.mediaReply) {
                requireNotNull(replies[part.piece]?.removeFirstOrNull()) { "No media for a media-only reply of piece ${part.piece}" }
            } else if (seen.add(part.piece)) {
                firsts.remove(part.piece).orEmpty()
            } else {
                emptyList()
            }
            RenderedPart(
                index = index,
                piece = part.piece,
                text = part.text,
                contentType = profile.contentType,
                spoilerText = spoiler,
                count = part.count,
                limit = text.limit,
                bytes = part.bytes,
                mediaItemIds = items,
                mediaOnly = part.mediaReply || part.sourceStart == part.sourceEnd,
                carriesPoll = poll && index == 0,
                links = part.links,
                mentions = part.mentions,
                tags = part.tags,
            )
        }
        require(firsts.isEmpty() && replies.values.all { it.isEmpty() }) { "Media of the plan found no part" }
        return AccountRender(profile.key, profile.network, parts, null, mediaReady, text.manyPartsWarning)
    }
}

/**
 * The facets of a Bluesky part for [org.evsyukov.post.core.bluesky.buildFacets]: its links and tags, and
 * its mentions whose handle [dids] resolves to a valid DID. A mention without one stays plain text (N17).
 * [dids] is keyed by lowercased handle, as [HandleSpan.handle] holds it.
 */
fun RenderedPart.facetInput(dids: Map<String, String>): FacetInput = FacetInput(
    links = links,
    mentions = mentions.mapNotNull { mention ->
        dids[mention.handle]?.takeIf { IdentityResolver.isValidDid(it) }?.let { MentionSpan(mention.start, mention.end, it) }
    },
    tags = tags,
)
