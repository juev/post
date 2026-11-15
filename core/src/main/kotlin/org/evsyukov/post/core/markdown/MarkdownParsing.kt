package org.evsyukov.post.core.markdown

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.Node
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/** A range `[start, end)` of char offsets into a source text. */
data class SourceRange(val start: Int, val end: Int) {
    init {
        require(start in 0..end) { "Invalid range [$start, $end)" }
    }

    val length: Int get() = end - start

    fun contains(offset: Int): Boolean = offset in start until end

    fun overlaps(other: SourceRange): Boolean = start < other.end && other.start < end
}

/**
 * The parser of the Markdown subset of formatting.md, F3: block quotes, fenced code blocks and lists are
 * the only blocks; headings, thematic breaks, HTML blocks and indented code stay paragraph text.
 * Strikethrough needs two tildes. Inline HTML and images are parsed, and the converters write them out
 * as typed. Source spans give every node its offsets in the parsed text.
 *
 * The nesting limits keep the tree shallow enough for recursive walks on any input; deeper markup stays
 * text. A `Parser` may be shared between threads.
 */
internal val markdownParser: Parser = Parser.builder()
    .enabledBlockTypes(setOf(BlockQuote::class.java, FencedCodeBlock::class.java, ListBlock::class.java))
    .extensions(listOf(StrikethroughExtension.builder().requireTwoTildes(true).build()))
    .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
    .maxOpenBlockParsers(MAX_BLOCK_NESTING)
    .maxInlineNesting(MAX_INLINE_NESTING)
    .build()

private const val MAX_BLOCK_NESTING = 32
private const val MAX_INLINE_NESTING = 32

/** The range a node covers in the parsed text, from its first span to the end of its last; null without spans. */
internal fun Node.range(offset: Int = 0): SourceRange? {
    val spans = sourceSpans
    if (spans.isEmpty()) return null
    var start = Int.MAX_VALUE
    var end = Int.MIN_VALUE
    for (span in spans) {
        start = minOf(start, span.inputIndex)
        end = maxOf(end, span.inputIndex + span.length)
    }
    return SourceRange(start + offset, end + offset)
}

/** The range of a block with all its descendants, which reaches lazy continuation lines. */
internal fun Node.deepRange(offset: Int = 0): SourceRange? {
    var result = range(offset)
    var child = firstChild
    while (child != null) {
        val childRange = child.deepRange(offset)
        if (childRange != null) {
            result = if (result == null) {
                childRange
            } else {
                SourceRange(minOf(result.start, childRange.start), maxOf(result.end, childRange.end))
            }
        }
        child = child.next
    }
    return result
}

internal inline fun Node.forEachChild(action: (Node) -> Unit) {
    var child = firstChild
    while (child != null) {
        val next = child.next
        action(child)
        child = next
    }
}

/**
 * How a [Link] node was written. Only inline links `[text](url)` and autolinks `<url>` belong to the
 * subset of F3; a reference link `[text][label]` stays text as typed.
 */
internal enum class LinkForm { INLINE, AUTOLINK, REFERENCE }

internal fun Link.form(source: String, range: SourceRange, offset: Int): LinkForm = when {
    source[range.start] == '<' -> LinkForm.AUTOLINK
    source[range.end - 1] == ')' && inlineLinkTextEnd(source, range, offset) >= 0 -> LinkForm.INLINE
    else -> LinkForm.REFERENCE
}

/**
 * The offset of the `]` that closes the text of the inline link at [range], or -1. The text ends where
 * its last child ends; that is where `](` must follow. [offset] maps the node's spans into [source].
 */
internal fun Link.inlineLinkTextEnd(source: String, range: SourceRange, offset: Int): Int {
    val textEnd = lastChild?.range(offset)?.end ?: (range.start + 1)
    if (textEnd + 1 < range.end && source[textEnd] == ']' && source[textEnd + 1] == '(') return textEnd
    // Fallback for a last child without spans: the first "](" after the opening bracket.
    val found = source.indexOf("](", range.start + 1)
    return if (found in range.start + 1 until range.end - 1) found else -1
}

/** Whether a link destination may become a link in HTML or a Bluesky facet: `http`, `https`, or `mailto` when allowed. */
internal fun isLinkable(destination: String, allowMailto: Boolean): Boolean {
    val lower = destination.lowercase()
    return (lower.startsWith("http://") && lower.length > 7) ||
        (lower.startsWith("https://") && lower.length > 8) ||
        (allowMailto && lower.startsWith("mailto:") && lower.length > 7)
}
