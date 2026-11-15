package org.evsyukov.post.core.markdown

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.StrongEmphasis
import org.evsyukov.post.core.text.TextEntities
import org.evsyukov.post.core.text.TextEntity

/**
 * The structure of a Markdown source that the thread splitter needs (threads.md, T4–T5, T13). Every
 * offset is a UTF-16 char offset into the analyzed source; every range is `[start, end)`.
 *
 * The contract for the splitter:
 * - A break never falls strictly inside an [AtomicRange]: the range is either wholly in one part or
 *   the break is at its start or end. Atomic ranges never overlap; code or an image in the text of a
 *   link is covered by the link's range.
 * - A break inside an [EmphasisSpan] content closes the span at the end of the part with
 *   [EmphasisSpan.close] and reopens it at the start of the next part with [EmphasisSpan.open]; nested
 *   spans close innermost first and reopen outermost first.
 * - A break inside a [CodeBlockSpan] falls only between its content lines; the part before it ends with
 *   [CodeBlockSpan.closingFence] on a line of its own, and the next part starts with
 *   [CodeBlockSpan.openingFence], so both parts are complete code blocks.
 * - [QuoteSpan.lines] and [ListItemSpan] ranges mark line-break candidates of rank 2 (T4); a part that
 *   starts inside a quote keeps the `>` prefix of its lines as typed.
 * - [manualBreaks] are the manual break lines, which always split (T13). Markdown is parsed separately
 *   between them, so no other range crosses one.
 *
 * All lists are sorted by start offset, then by end offset.
 */
data class SourceStructure(
    val atomicRanges: List<AtomicRange>,
    val emphasis: List<EmphasisSpan>,
    val codeBlocks: List<CodeBlockSpan>,
    val quotes: List<QuoteSpan>,
    val listItems: List<ListItemSpan>,
    val manualBreaks: List<SourceRange>,
)

/** A range a split must never cut. */
data class AtomicRange(val start: Int, val end: Int, val kind: Kind) {
    enum class Kind {
        /** Inline code with its backticks. */
        CODE_SPAN,

        /** An inline link `[text](url)` or a reference link, from `[` to its end. */
        LINK,

        /** An autolink `<url>` with its angle brackets. */
        AUTOLINK,

        /** An image `![alt](url)`, which is published as typed. */
        IMAGE,

        /** A bare `http` or `https` URL ([TextEntities.urls]). */
        URL,

        /** A Mastodon or Bluesky mention ([TextEntities.mastodonMentions], [TextEntities.blueskyMentions]). */
        MENTION,

        /** A hashtag ([TextEntities.hashtags]). */
        HASHTAG,
    }
}

/**
 * Bold, italic or strikethrough markup: [open] at [openRange], the content, then [close] at [closeRange].
 * The markers are exactly as typed, such as `**`, `__`, `*`, `_` or `~~`.
 */
data class EmphasisSpan(
    val kind: Kind,
    val open: String,
    val close: String,
    val openRange: SourceRange,
    val closeRange: SourceRange,
) {
    enum class Kind { BOLD, ITALIC, STRIKE }

    /** The whole span, markers included. */
    val range: SourceRange get() = SourceRange(openRange.start, closeRange.end)

    /** The text between the markers. */
    val contentRange: SourceRange get() = SourceRange(openRange.end, closeRange.start)
}

/**
 * A fenced code block.
 *
 * @property range from the opening fence to the end of the closing fence, or to the end of the last code
 *   line when the block is not closed.
 * @property openingFence the opening fence line as typed from the fence on, with its info string,
 *   such as `` ```kotlin ``.
 * @property closingFence the closing fence as typed, or the fence that would close the block when it has
 *   none: the fence character repeated as many times as in the opening fence.
 * @property closingFenceRange null when the block runs to the end of its container unclosed.
 * @property contentRange from the start of the first code line to the end of the last one; null for a
 *   block without code lines. Lines inside a quote or list keep their container prefixes here.
 */
data class CodeBlockSpan(
    val range: SourceRange,
    val openingFence: String,
    val openingFenceRange: SourceRange,
    val closingFence: String,
    val closingFenceRange: SourceRange?,
    val contentRange: SourceRange?,
)

/**
 * A block quote. [range] covers it with its lazy continuation lines; [lines] are the lines that start with
 * its `>` marker, each from the marker to the end of the line. A nested quote is a quote of its own.
 */
data class QuoteSpan(val range: SourceRange, val lines: List<SourceRange>)

/**
 * A list item: [range] from its marker indentation to the end of its content, [markerRange] its marker as
 * typed ([marker], such as `-` or `3.`), and whether the list is [ordered].
 */
data class ListItemSpan(
    val range: SourceRange,
    val markerRange: SourceRange,
    val marker: String,
    val ordered: Boolean,
)

/**
 * The structure of the Markdown [source] for the thread splitter; see [SourceStructure] for the contract.
 *
 * Bare URLs, mentions and hashtags are found in the text outside code blocks, code spans, links, images
 * and inline HTML. Markup characters inside them are text, not emphasis ([parseMarkdown]), so
 * `https://example.com/files/__init__.py` and `@_jane_` are one range each. A bare entity that runs into an
 * emphasis marker (`**https://example.com**`) ends at the marker. Where entities overlap, the one that
 * starts first wins, then the longer one (`@alice.b_c.com` is one Mastodon mention, not `@alice.b`).
 */
fun analyze(source: String): SourceStructure {
    val breaks = manualBreakLines(source)
    val collector = StructureCollector(source)
    for (piece in pieceRanges(source, breaks)) {
        if (piece.length == 0) continue
        collector.offset = piece.start
        collector.visit(parseMarkdown(source.substring(piece.start, piece.end)))
    }
    val blanked = collector.atomic.map { SourceRange(it.start, it.end) } +
        collector.codeBlocks.map { it.range } + collector.opaque + breaks +
        collector.emphasis.flatMap { listOf(it.openRange, it.closeRange) }
    val entities = bareEntities(source, blanked)
    return SourceStructure(
        atomicRanges = outermost(collector.atomic + entities),
        emphasis = collector.emphasis.sortedWith(compareBy({ it.openRange.start }, { it.closeRange.end })),
        codeBlocks = collector.codeBlocks.sortedWith(compareBy({ it.range.start }, { it.range.end })),
        quotes = collector.quotes.sortedWith(compareBy({ it.range.start }, { it.range.end })),
        listItems = collector.listItems.sortedWith(compareBy({ it.range.start }, { it.range.end })),
        manualBreaks = breaks,
    )
}

/**
 * Bare entities, found in a copy of [source] where the [blanked] ranges (markup markers and ranges that
 * hold no bare entities) are spaces of the same length. Offsets stay those of [source], an entity ends
 * where markup starts (`**https://example.com.**`), and markup before it does not hide it (`_#tag_`).
 */
private fun bareEntities(source: String, blanked: List<SourceRange>): List<AtomicRange> {
    val text = StringBuilder(source)
    for (range in blanked) {
        for (i in range.start until range.end) text.setCharAt(i, ' ')
    }
    val neutral = text.toString()
    val found: List<Pair<TextEntity, AtomicRange.Kind>> =
        TextEntities.urls(neutral).map { it to AtomicRange.Kind.URL } +
            TextEntities.mastodonMentions(neutral).map { it to AtomicRange.Kind.MENTION } +
            TextEntities.blueskyMentions(neutral).map { it to AtomicRange.Kind.MENTION } +
            TextEntities.hashtags(neutral).map { it to AtomicRange.Kind.HASHTAG }
    return outermost(found.map { (entity, kind) -> AtomicRange(entity.start, entity.end, kind) })
}

/**
 * [ranges] without overlaps, sorted: of overlapping ranges the one that starts first wins, then the
 * longer one. Code or an image inside the text of a link is part of the link's range.
 */
private fun outermost(ranges: List<AtomicRange>): List<AtomicRange> {
    val kept = ArrayList<AtomicRange>()
    var end = 0
    for (range in ranges.sortedWith(compareBy<AtomicRange>({ it.start }, { -it.end }))) {
        if (range.start >= end) {
            kept += range
            end = range.end
        }
    }
    return kept
}

private class StructureCollector(private val source: String) {
    var offset = 0
    val atomic = ArrayList<AtomicRange>()
    val emphasis = ArrayList<EmphasisSpan>()
    val codeBlocks = ArrayList<CodeBlockSpan>()
    val quotes = ArrayList<QuoteSpan>()
    val listItems = ArrayList<ListItemSpan>()

    /** Inline HTML: not atomic, but no entity is looked for inside it. */
    val opaque = ArrayList<SourceRange>()

    fun visit(node: Node) {
        when (node) {
            is StrongEmphasis -> delimited(node, node.openingDelimiter, node.closingDelimiter, EmphasisSpan.Kind.BOLD)
            is Emphasis -> delimited(node, node.openingDelimiter, node.closingDelimiter, EmphasisSpan.Kind.ITALIC)
            is Strikethrough -> delimited(node, node.openingDelimiter, node.closingDelimiter, EmphasisSpan.Kind.STRIKE)
            is Code -> node.range(offset)?.let { atomic += AtomicRange(it.start, it.end, AtomicRange.Kind.CODE_SPAN) }
            is Link -> link(node)
            is Image -> node.range(offset)?.let { atomic += AtomicRange(it.start, it.end, AtomicRange.Kind.IMAGE) }
            is HtmlInline -> node.range(offset)?.let { opaque += it }
            is FencedCodeBlock -> codeBlock(node)
            is BlockQuote -> {
                quote(node)
                children(node)
            }
            is ListItem -> {
                listItem(node)
                children(node)
            }
            else -> children(node)
        }
    }

    private fun children(node: Node) = node.forEachChild { visit(it) }

    private fun delimited(node: Node, open: String?, close: String?, kind: EmphasisSpan.Kind) {
        val range = node.range(offset)
        if (range != null && !open.isNullOrEmpty() && !close.isNullOrEmpty() && range.length >= open.length + close.length) {
            emphasis += EmphasisSpan(
                kind = kind,
                open = source.substring(range.start, range.start + open.length),
                close = source.substring(range.end - close.length, range.end),
                openRange = SourceRange(range.start, range.start + open.length),
                closeRange = SourceRange(range.end - close.length, range.end),
            )
        }
        children(node)
    }

    private fun link(node: Link) {
        val range = node.range(offset) ?: return
        val kind = if (node.form(source, range, offset) == LinkForm.AUTOLINK) AtomicRange.Kind.AUTOLINK else AtomicRange.Kind.LINK
        atomic += AtomicRange(range.start, range.end, kind)
        // Emphasis inside the link text is still markup the splitter must know, though no break falls there.
        children(node)
    }

    private fun codeBlock(node: FencedCodeBlock) {
        val spans = node.sourceSpans
        val range = node.range(offset) ?: return
        val open = spans.first()
        val openingFenceRange = SourceRange(open.inputIndex + offset, open.inputIndex + open.length + offset)
        val closed = node.closingFenceLength != null && spans.size >= 2
        val closingFenceRange = if (closed) {
            val close = spans.last()
            SourceRange(close.inputIndex + offset, close.inputIndex + close.length + offset)
        } else {
            null
        }
        val content = spans.subList(1, if (closed) spans.size - 1 else spans.size)
        val contentRange = if (content.isEmpty()) {
            null
        } else {
            SourceRange(content.first().inputIndex + offset, content.last().let { it.inputIndex + it.length } + offset)
        }
        val fence = node.fenceCharacter.orEmpty().repeat(node.openingFenceLength ?: 3)
        codeBlocks += CodeBlockSpan(
            range = range,
            openingFence = source.substring(openingFenceRange.start, openingFenceRange.end),
            openingFenceRange = openingFenceRange,
            closingFence = closingFenceRange?.let { source.substring(it.start, it.end) } ?: fence,
            closingFenceRange = closingFenceRange,
            contentRange = contentRange,
        )
    }

    private fun quote(node: BlockQuote) {
        val range = node.deepRange(offset) ?: return
        val lines = node.sourceSpans.mapNotNull {
            val end = it.inputIndex + it.length + offset
            val marker = quoteMarker(source, it.inputIndex + offset, end)
            if (marker >= 0) SourceRange(marker, end) else null
        }
        quotes += QuoteSpan(range, lines)
    }

    private fun listItem(node: ListItem) {
        val range = node.deepRange(offset) ?: return
        val marker = listMarkerRange(source, node, offset) ?: return
        listItems += ListItemSpan(range, marker, source.substring(marker.start, marker.end), node.parent is OrderedList)
    }
}
