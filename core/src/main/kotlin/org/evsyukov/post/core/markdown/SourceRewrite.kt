package org.evsyukov.post.core.markdown

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text

/**
 * A replacement of `[start, end)` of the source by [text]. When [linkUrl] is set, `[linkStart, linkEnd)`
 * of [text] links to it.
 */
internal class Edit(
    val start: Int,
    val end: Int,
    val text: String,
    val linkUrl: String? = null,
    val linkStart: Int = 0,
    val linkEnd: Int = 0,
)

internal class Rewritten(val text: String, val links: List<LinkSpan>)

/** Applies non-overlapping [edits] to [source]; an edit that overlaps an earlier one is skipped. */
internal fun applyEdits(source: String, edits: List<Edit>): Rewritten {
    val out = StringBuilder(source.length)
    val links = ArrayList<LinkSpan>()
    var cursor = 0
    for (edit in edits.sortedWith(compareBy<Edit>({ it.start }, { it.end }))) {
        if (edit.start < cursor || edit.end < edit.start || edit.end > source.length) continue
        out.append(source, cursor, edit.start)
        val url = edit.linkUrl
        if (url != null && edit.linkStart < edit.linkEnd) {
            links += LinkSpan(out.length + edit.linkStart, out.length + edit.linkEnd, url)
        }
        out.append(edit.text)
        cursor = edit.end
    }
    out.append(source, cursor, source.length)
    return Rewritten(out.toString(), links)
}

/**
 * Turns a Markdown source into plain text by editing it in place (formatting.md, F10): markup goes, and
 * everything else, including line breaks, blank lines and indentation, stays as typed.
 *
 * [linkEdit] decides what an inline link or autolink becomes; it gets the link's range, its plain text
 * and its destination. Reference links, images and inline HTML stay as typed.
 */
internal class PlainRewriter(
    private val source: String,
    private val linkEdit: (range: SourceRange, text: String, destination: String) -> Edit,
) {
    val edits = ArrayList<Edit>()

    fun visit(node: Node) {
        when (node) {
            is Text -> text(node)
            is Code -> replace(node, node.literal)
            is StrongEmphasis -> delimited(node, node.openingDelimiter)
            is Emphasis -> delimited(node, node.openingDelimiter)
            is Strikethrough -> delimited(node, node.openingDelimiter)
            is HardLineBreak -> hardLineBreak(node)
            is Link -> link(node)
            is Image, is HtmlInline, is LinkReferenceDefinition, is SoftLineBreak -> Unit
            is ListItem -> {
                listItem(node)
                node.forEachChild { visit(it) }
            }
            is FencedCodeBlock -> fencedCodeBlock(node)
            else -> node.forEachChild { visit(it) }
        }
    }

    private fun replace(node: Node, text: String) {
        val range = node.range() ?: return
        edits += Edit(range.start, range.end, text)
    }

    private fun text(node: Text) {
        val range = node.range() ?: return
        // A text node covers one line; spans with a gap between them would hide markup, so leave them alone.
        if (node.sourceSpans.sumOf { it.length } != range.length) return
        val literal = node.literal.orEmpty()
        if (literal.length == range.length && source.regionMatches(range.start, literal, 0, range.length)) return
        edits += Edit(range.start, range.end, literal)
    }

    private fun delimited(node: Node, delimiter: String?) {
        val range = node.range()
        val length = delimiter?.length ?: 0
        if (range != null && length > 0 && range.length >= 2 * length) {
            edits += Edit(range.start, range.start + length, "")
            edits += Edit(range.end - length, range.end, "")
        }
        node.forEachChild { visit(it) }
    }

    /** `"  \n"` or `"\\\n"`: the spaces or the backslash before the line break go, the break stays. */
    private fun hardLineBreak(node: HardLineBreak) {
        val previousEnd = node.previous?.range()?.end ?: return
        var end = previousEnd
        while (end < source.length && (source[end] == ' ' || source[end] == '\t' || source[end] == '\\')) end++
        if (end > previousEnd && (end == source.length || source[end] == '\n' || source[end] == '\r')) {
            edits += Edit(previousEnd, end, "")
        }
    }

    private fun link(node: Link) {
        val range = node.range() ?: return
        if (node.form(source, range, 0) == LinkForm.REFERENCE) return
        edits += linkEdit(range, inlinePlainText(node, source), node.destination.orEmpty())
    }

    /** `- `, `* `, `+ ` become `• `; `1. ` or `1) ` become the item's number and `. `. */
    private fun listItem(node: ListItem) {
        val marker = listMarkerRange(source, node, 0) ?: return
        val list = node.parent
        val bullet = if (list is OrderedList) {
            var index = 0
            var sibling = list.firstChild
            while (sibling != null && sibling !== node) {
                index++
                sibling = sibling.next
            }
            "${(list.markerStartNumber ?: 1) + index}."
        } else {
            "•"
        }
        val spaced = marker.end < source.length && (source[marker.end] == ' ' || source[marker.end] == '\t')
        edits += if (spaced) Edit(marker.start, marker.end + 1, "$bullet ") else Edit(marker.start, marker.end, bullet)
    }

    /**
     * The fence lines go and the code stays as typed. A fence line that holds only the fence, after quote
     * markers and indentation, goes whole with its line break. An opening fence that follows a list
     * marker goes up to the first code line, so the code takes the marker's line.
     */
    private fun fencedCodeBlock(node: FencedCodeBlock) {
        val spans = node.sourceSpans
        if (spans.isEmpty()) return
        val open = spans.first()
        val openStart = open.inputIndex
        val openEnd = openStart + open.length
        val close = if (node.closingFenceLength != null && spans.size >= 2) spans.last() else null
        val contentSpans = spans.subList(1, if (close != null) spans.size - 1 else spans.size)
        val openLineStart = lineStart(openStart)
        val wholeLine = (openLineStart until openStart).all { source[it] == ' ' || source[it] == '\t' || source[it] == '>' }
        val deleteFrom = if (wholeLine) openLineStart else openStart
        if (close != null && contentSpans.isEmpty()) {
            edits += Edit(deleteFrom, close.inputIndex + close.length, "")
            return
        }
        val deleteTo = if (wholeLine) nextLineStart(openEnd) else contentSpans.firstOrNull()?.inputIndex ?: nextLineStart(openEnd)
        edits += Edit(deleteFrom, deleteTo, "")
        if (close != null) {
            val closeLineStart = lineStart(close.inputIndex)
            val terminator = if (closeLineStart >= 2 && source[closeLineStart - 2] == '\r' && source[closeLineStart - 1] == '\n') 2 else 1
            val from = maxOf(closeLineStart - terminator, deleteTo)
            edits += Edit(from, close.inputIndex + close.length, "")
        }
    }

    private fun lineStart(offset: Int): Int {
        var i = offset
        while (i > 0 && source[i - 1] != '\n' && source[i - 1] != '\r') i--
        return i
    }

    private fun nextLineStart(offset: Int): Int {
        var i = offset
        while (i < source.length && source[i] != '\n' && source[i] != '\r') i++
        if (i < source.length) {
            i += if (source[i] == '\r' && i + 1 < source.length && source[i + 1] == '\n') 2 else 1
        }
        return i
    }
}

/** The plain text of a node's inline children: markup removed, images and inline HTML as typed. */
internal fun inlinePlainText(node: Node, source: String): String {
    val out = StringBuilder()
    fun walk(parent: Node) {
        parent.forEachChild { child ->
            when (child) {
                is Text -> out.append(child.literal)
                is Code -> out.append(child.literal)
                is SoftLineBreak, is HardLineBreak -> out.append('\n')
                is Image -> out.append(child.range()?.let { source.substring(it.start, it.end) }.orEmpty())
                is HtmlInline -> out.append(child.literal.orEmpty())
                else -> walk(child)
            }
        }
    }
    walk(node)
    return out.toString()
}

/**
 * Whether a link's text shows its destination, so the text alone is enough (formatting.md, F10): equal
 * ignoring case, a leading `http://`, `https://` or `mailto:`, and one trailing `/`.
 */
internal fun textShowsUrl(text: String, destination: String): Boolean {
    fun normalize(value: String): String {
        var result = value.trim()
        for (scheme in listOf("https://", "http://", "mailto:")) {
            if (result.startsWith(scheme, ignoreCase = true)) {
                result = result.substring(scheme.length)
                break
            }
        }
        return result.removeSuffix("/")
    }
    val normalized = normalize(destination)
    return normalized.isNotEmpty() && normalize(text).equals(normalized, ignoreCase = true)
}
