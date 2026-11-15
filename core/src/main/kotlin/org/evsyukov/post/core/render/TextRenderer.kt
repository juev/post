package org.evsyukov.post.core.render

import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.text.TextSegmenter
import org.evsyukov.post.core.thread.Piece
import org.evsyukov.post.core.thread.SplitPlan
import org.evsyukov.post.core.thread.pieces

/**
 * Renders the text of a draft for one account profile: the parts of its thread, converted, numbered and
 * counted (threads.md T1–T17, formatting.md F8–F12).
 *
 * 1. Manual breaks split the draft into pieces (T13); everything below runs inside each piece.
 * 2. A piece that fits the limit once converted is one part (T2). Otherwise the splitter
 *    ([org.evsyukov.post.core.thread.ThreadSplitter]) finds the smallest number of parts by an estimate of
 *    the converted length and balances them (T4–T9), breaking only where the Markdown allows (T5).
 * 3. Every part is then converted and counted exactly. When one is over the limit, its piece is split again
 *    with the budget tightened by the excess; after [MAX_TIGHTENING] tries, or when the estimate finds a
 *    run that fits nowhere, the piece is split greedily by exact counts alone. So no part ever exceeds the
 *    character limit or the byte limit, and a run that does not fit even alone is a
 *    [TextIssue.TooLongToSplit] (T9, T17), never cut.
 * 4. With "Number thread parts" on and two or more parts, ` i/n` ends every part (T11), counting media-only
 *    replies too (media.md A11). The width of the number is reserved before splitting, and the split
 *    repeats with a wider reserve until the number of digits is stable.
 *
 * The content warning goes with every Mastodon part and counts in it (N3). Bluesky has none.
 */
object TextRenderer {
    /** More parts than this for one account are refused (T16). */
    const val MAX_PARTS = 50

    /** From this many parts the composer warns (T16). */
    const val WARN_PARTS = 10

    /** Tightened retries of a piece before it is split by exact counts alone. */
    const val MAX_TIGHTENING = 4

    /**
     * The parts of [draft] for an account with [profile].
     *
     * @param mediaOnlyParts for each piece that has media, the number of media-only replies the media plan
     *   adds after the piece's first part ([PartAssembler.mediaOnlyParts]). A piece listed here gets a part
     *   even when it has no text, to carry its media.
     */
    fun render(
        draft: DraftText,
        profile: TextProfile,
        segmenter: TextSegmenter,
        mediaOnlyParts: Map<Int, Int> = emptyMap(),
    ): TextRender {
        require(mediaOnlyParts.all { (piece, replies) -> piece >= 0 && replies >= 0 }) { "Invalid media-only parts" }
        val spoiler = if (profile.network == Network.BLUESKY) "" else draft.spoilerText
        val converter = PartConverter(profile, segmenter, spoiler)
        val draftPieces = pieces(draft.source)
        val pieceCount = maxOf(draftPieces.size, (mediaOnlyParts.keys.maxOrNull() ?: -1) + 1)
        val allPieces = (0 until pieceCount).map { draftPieces.getOrNull(it) ?: Piece(it, draft.source.length, draft.source.length) }

        fun result(parts: List<TextPart>, issue: TextIssue?, total: Int) = TextRender(
            profile = profile,
            parts = parts,
            issue = issue,
            manyPartsWarning = total >= WARN_PARTS,
            limit = profile.maxCharacters,
            mediaOnlyParts = mediaOnlyParts,
            spoilerText = draft.spoilerText,
        )

        val spoilerUnits = if (spoiler.isEmpty()) 0 else profile.count("", spoiler, segmenter)
        val hasText = allPieces.any { !it.isEmpty }
        if (spoilerUnits > profile.maxCharacters || (hasText && spoilerUnits >= profile.maxCharacters)) {
            return result(emptyList(), TextIssue.SpoilerTooLong(spoilerUnits), 0)
        }

        val models = allPieces.filter { !it.isEmpty }
            .associate { it.index to PieceModel(it, draft.source, profile, segmenter, converter, draft.locale) }
        val states = models.mapValues { PieceState() }
        var digits = 0
        var rounds = 0
        while (true) {
            check(++rounds <= MAX_ROUNDS) { "The split did not settle" }
            val plans = HashMap<Int, List<Int>>()
            var replan = false
            for ((index, model) in models) {
                val state = states.getValue(index)
                when (val plan = state.plan(model, digits, spoilerUnits)) {
                    is SplitPlan.Parts -> plans[index] = plan.ends
                    is SplitPlan.TooLong -> {
                        if (!state.exactOnly) {
                            state.exactOnly = true
                            replan = true
                        } else {
                            val piece = model.piece
                            return result(
                                emptyList(),
                                TextIssue.TooLongToSplit(
                                    piece.index,
                                    piece.start + model.startOf(plan.from),
                                    piece.start + model.endOf(plan.from + 1),
                                ),
                                0,
                            )
                        }
                    }
                }
            }
            if (replan) continue

            var total = allPieces.sumOf { piece ->
                val textParts = plans[piece.index]?.size ?: if (piece.index in mediaOnlyParts) 1 else 0
                textParts + (mediaOnlyParts[piece.index] ?: 0)
            }
            if (total == 0) total = 1
            if (total > MAX_PARTS) return result(emptyList(), TextIssue.TooManyParts(total), total)
            val numbered = profile.numberParts && total >= 2
            val needed = if (numbered) digitsOf(total) else 0
            if (needed > digits) {
                digits = needed
                continue
            }

            val parts = ArrayList<TextPart>(total)
            val over = HashMap<Int, ConvertedPart>()
            fun label(): String? = if (numbered) "${parts.size + 1}/$total" else null
            fun bare(piece: Int, at: Int, reply: Boolean) {
                parts += converter.bare(label()).toTextPart(piece, at, at, reply)
            }
            for (piece in allPieces) {
                val replies = mediaOnlyParts[piece.index] ?: 0
                val ends = plans[piece.index]
                if (ends == null) {
                    if (piece.index in mediaOnlyParts) {
                        bare(piece.index, piece.start, reply = false)
                        repeat(replies) { bare(piece.index, piece.start, reply = true) }
                    }
                    continue
                }
                val model = models.getValue(piece.index)
                var from = 0
                ends.forEachIndexed { k, to ->
                    val converted = model.exact(from, to, label())
                    if (!converted.fits(profile)) {
                        val worst = over[piece.index]
                        if (worst == null || converted.overshoot(profile) > worst.overshoot(profile) ||
                            converted.byteOvershoot(profile) > worst.byteOvershoot(profile)
                        ) {
                            over[piece.index] = converted
                        }
                    }
                    val start = piece.start + model.startOf(from)
                    val end = piece.start + model.endOf(to)
                    parts += converted.toTextPart(piece.index, start, end, reply = false)
                    if (k == 0) repeat(replies) { bare(piece.index, end, reply = true) }
                    from = to
                }
            }
            if (over.isNotEmpty()) {
                for ((index, part) in over) states.getValue(index).tighten(part, profile)
                continue
            }
            if (parts.isEmpty()) bare(allPieces.firstOrNull()?.index ?: 0, allPieces.firstOrNull()?.start ?: 0, reply = false)
            return result(parts, null, parts.size)
        }
    }

    /** A bound on the rounds of the loop: digits grow at most twice, and each piece tightens a few times. */
    private const val MAX_ROUNDS = 10_000

    private fun ConvertedPart.toTextPart(piece: Int, start: Int, end: Int, reply: Boolean) = TextPart(
        piece = piece,
        sourceStart = start,
        sourceEnd = end,
        text = text,
        count = count,
        bytes = bytes,
        links = links,
        mentions = mentions,
        tags = tags,
        mediaReply = reply,
    )

    /** How a piece is split this round: the slack taken off the estimate, and whether only exact checks remain. */
    private class PieceState {
        var slack = 0
        var byteSlack = 0
        var tightenings = 0
        var exactOnly = false

        /** Spaces added to the widest number label of exact checks, in case a number still overflows. */
        var padding = 0
        private var cachedKey: List<Int>? = null
        private var cached: SplitPlan? = null

        fun plan(model: PieceModel, digits: Int, spoilerUnits: Int): SplitPlan {
            val key = listOf(digits, slack, byteSlack, if (exactOnly) 1 else 0, padding)
            if (key != cachedKey) {
                cached = model.plan(digits, spoilerUnits, slack, byteSlack, exactOnly, padding)
                cachedKey = key
            }
            return cached!!
        }

        fun tighten(part: ConvertedPart, profile: TextProfile) {
            if (exactOnly) {
                // Exact checks use the widest number, so this is a number that counts wider than its digits.
                padding += maxOf(1, part.overshoot(profile), part.byteOvershoot(profile))
                return
            }
            if (++tightenings > MAX_TIGHTENING) {
                exactOnly = true
                return
            }
            val over = part.overshoot(profile)
            val overBytes = part.byteOvershoot(profile)
            if (over > 0) slack += over
            if (overBytes > 0) byteSlack += overBytes
        }
    }
}
