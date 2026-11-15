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
import org.commonmark.node.StrongEmphasis

/**
 * A styled range of the draft for the editor (formatting.md, F4). [start] and [end] are char offsets
 * into the highlighted source, [end] exclusive.
 */
data class HighlightSpan(val start: Int, val end: Int, val kind: Kind) {
    enum class Kind {
        /** The text between `**` or `__`. */
        BOLD,

        /** The text between `*` or `_`. */
        ITALIC,

        /** The text between `~~`. */
        STRIKE,

        /** The text of an inline code span, between its backticks. */
        CODE,

        /** A fenced code block from its opening fence to its closing fence, or to its end when unclosed. */
        CODE_BLOCK,

        /** A block quote, with its lazy continuation lines. */
        QUOTE,

        /** A list item marker such as `-` or `1.`; it is also reported as [SYNTAX]. */
        LIST_MARKER,

        /** The text of an inline link `[text](url)`. */
        LINK_TEXT,

        /** The destination of an inline link, with its title if any, or the address of an autolink `<url>`. */
        LINK_URL,

        /**
         * Markup characters, which the editor dims but keeps visible: emphasis markers, backticks, `>` of
         * quotes, list markers, the brackets and parentheses of links, `<` `>` of autolinks, and the fence
         * lines of code blocks.
         */
        SYNTAX,

        /** A manual thread break line (threads.md, T13), without its line terminator. */
        BREAK_MARKER,
    }
}

/**
 * The highlighting of the Markdown draft [source] (formatting.md, F3–F4), sorted by start, then end, then kind.
 *
 * Spans may nest: bold text inside a quote gets both [HighlightSpan.Kind.QUOTE] and
 * [HighlightSpan.Kind.BOLD]. Markup that is not closed yet, such as `**bold` while typing, is plain text and
 * gets no span until it is closed. Constructs outside the subset of F3 (headings, tables, images, inline
 * HTML, reference links) get no span, since they are published as typed.
 *
 * The pieces between manual break lines are parsed separately, as they are published separately, so
 * markup never spans a break. The function never throws and runs in linear time for practical input.
 */
fun highlight(source: String): List<HighlightSpan> =
    try {
        // A span outside the text would crash the editor's styling, so the safety net drops it.
        highlightUnguarded(source).filter { it.start < it.end && it.start >= 0 && it.end <= source.length }
    } catch (e: RuntimeException) {
        // Highlighting is cosmetic: on a failure the draft shows as plain text instead of crashing the editor.
        emptyList()
    } catch (e: StackOverflowError) {
        emptyList()
    }

/** [highlight] without the safety net, so tests see a failure or a span out of bounds instead of a filtered result. */
internal fun highlightUnguarded(source: String): List<HighlightSpan> {
    val spans = ArrayList<HighlightSpan>()
    val breaks = manualBreakLines(source)
    breaks.forEach { spans += HighlightSpan(it.start, it.end, HighlightSpan.Kind.BREAK_MARKER) }
    for (piece in pieceRanges(source, breaks)) {
        if (piece.length == 0) continue
        val document = parseMarkdown(source.substring(piece.start, piece.end))
        Highlighter(source, piece.start, spans).visit(document)
    }
    return spans
        .distinct()
        .sortedWith(compareBy<HighlightSpan>({ it.start }, { it.end }, { it.kind.ordinal }))
}

private class Highlighter(
    private val source: String,
    private val offset: Int,
    private val spans: MutableList<HighlightSpan>,
) {
    fun visit(node: Node) {
        when (node) {
            is StrongEmphasis -> delimited(node, node.openingDelimiter, HighlightSpan.Kind.BOLD)
            is Emphasis -> delimited(node, node.openingDelimiter, HighlightSpan.Kind.ITALIC)
            is Strikethrough -> delimited(node, node.openingDelimiter, HighlightSpan.Kind.STRIKE)
            is Code -> code(node)
            is Link -> link(node)
            is Image, is HtmlInline -> Unit
            is FencedCodeBlock -> codeBlock(node)
            is BlockQuote -> {
                quote(node)
                visitChildren(node)
            }
            is ListItem -> {
                listMarker(node)
                visitChildren(node)
            }
            else -> visitChildren(node)
        }
    }

    private fun visitChildren(node: Node) = node.forEachChild { visit(it) }

    private fun add(start: Int, end: Int, kind: HighlightSpan.Kind) {
        if (start < end) spans += HighlightSpan(start, end, kind)
    }

    private fun delimited(node: Node, delimiter: String?, kind: HighlightSpan.Kind) {
        val range = node.range(offset)
        val length = delimiter?.length ?: 0
        if (range != null && length > 0 && range.length >= 2 * length) {
            add(range.start, range.start + length, HighlightSpan.Kind.SYNTAX)
            add(range.start + length, range.end - length, kind)
            add(range.end - length, range.end, HighlightSpan.Kind.SYNTAX)
        }
        visitChildren(node)
    }

    private fun code(node: Code) {
        val range = node.range(offset) ?: return
        var ticks = 0
        while (range.start + ticks < range.end && source[range.start + ticks] == '`') ticks++
        if (ticks == 0 || range.length < 2 * ticks) return
        add(range.start, range.start + ticks, HighlightSpan.Kind.SYNTAX)
        add(range.start + ticks, range.end - ticks, HighlightSpan.Kind.CODE)
        add(range.end - ticks, range.end, HighlightSpan.Kind.SYNTAX)
    }

    private fun link(node: Link) {
        val range = node.range(offset) ?: return
        when (node.form(source, range, offset)) {
            LinkForm.AUTOLINK -> {
                add(range.start, range.start + 1, HighlightSpan.Kind.SYNTAX)
                add(range.start + 1, range.end - 1, HighlightSpan.Kind.LINK_URL)
                add(range.end - 1, range.end, HighlightSpan.Kind.SYNTAX)
            }
            LinkForm.INLINE -> {
                val textEnd = node.inlineLinkTextEnd(source, range, offset)
                add(range.start, range.start + 1, HighlightSpan.Kind.SYNTAX)
                add(range.start + 1, textEnd, HighlightSpan.Kind.LINK_TEXT)
                add(textEnd, textEnd + 2, HighlightSpan.Kind.SYNTAX)
                add(textEnd + 2, range.end - 1, HighlightSpan.Kind.LINK_URL)
                add(range.end - 1, range.end, HighlightSpan.Kind.SYNTAX)
                visitChildren(node)
            }
            LinkForm.REFERENCE -> Unit
        }
    }

    private fun codeBlock(node: FencedCodeBlock) {
        val spans = node.sourceSpans
        val range = node.range(offset) ?: return
        add(range.start, range.end, HighlightSpan.Kind.CODE_BLOCK)
        val opening = spans.first()
        add(opening.inputIndex + offset, opening.inputIndex + opening.length + offset, HighlightSpan.Kind.SYNTAX)
        if (node.closingFenceLength != null && spans.size >= 2) {
            val closing = spans.last()
            add(closing.inputIndex + offset, closing.inputIndex + closing.length + offset, HighlightSpan.Kind.SYNTAX)
        }
    }

    private fun quote(node: BlockQuote) {
        val range = node.deepRange(offset) ?: return
        add(range.start, range.end, HighlightSpan.Kind.QUOTE)
        for (span in node.sourceSpans) {
            val marker = quoteMarker(source, span.inputIndex + offset, span.inputIndex + span.length + offset)
            if (marker >= 0) add(marker, marker + 1, HighlightSpan.Kind.SYNTAX)
        }
    }

    private fun listMarker(node: ListItem) {
        val marker = listMarkerRange(source, node, offset) ?: return
        add(marker.start, marker.end, HighlightSpan.Kind.LIST_MARKER)
        add(marker.start, marker.end, HighlightSpan.Kind.SYNTAX)
    }
}

/**
 * The offset of the `>` that starts the quote line `[start, end)` of [source] after up to three spaces of
 * indentation, or -1 for a lazy continuation line.
 */
internal fun quoteMarker(source: String, start: Int, end: Int): Int {
    var i = start
    while (i < end && i - start < 3 && source[i] == ' ') i++
    return if (i < end && source[i] == '>') i else -1
}

/**
 * The marker of a list item, `-`, `*`, `+`, or digits followed by `.` or `)`, found after the
 * indentation at the start of the item's first span.
 */
internal fun listMarkerRange(source: String, item: ListItem, offset: Int): SourceRange? {
    val range = item.range(offset) ?: return null
    var start = range.start
    while (start < range.end && (source[start] == ' ' || source[start] == '\t')) start++
    if (start >= range.end) return null
    val c = source[start]
    if (c == '-' || c == '*' || c == '+') return SourceRange(start, start + 1)
    var end = start
    while (end < range.end && source[end] in '0'..'9') end++
    if (end == start || end >= range.end) return null
    return if (source[end] == '.' || source[end] == ')') SourceRange(start, end + 1) else null
}
