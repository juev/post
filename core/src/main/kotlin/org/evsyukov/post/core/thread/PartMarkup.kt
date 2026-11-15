package org.evsyukov.post.core.thread

import org.evsyukov.post.core.markdown.CodeBlockSpan
import org.evsyukov.post.core.markdown.SourceStructure

/**
 * The Markdown of one thread part, ready for the account's converter.
 *
 * @property markdown the part's source with what its breaks cut closed and reopened (threads.md, T5).
 * @property endsWithCodeBlock the last line is a closing code fence, so a part number goes on a line of its
 *   own after it (T11).
 */
data class PartFragment(val markdown: String, val endsWithCodeBlock: Boolean)

/**
 * The Markdown of the part of the piece [text] between the breaks [from] and [to] (null for the start and
 * the end of the piece); [structure] is `analyze(text)`.
 *
 * - A code block cut by [from] is reopened with its opening fence and info string; one cut by [to] gets its
 *   closing fence. Inside a quote or list the fence keeps the quote markers of its line, and other prefix
 *   characters become spaces, so the fence stays in the same container.
 * - A part that starts in the middle of a quoted line, or on a lazy continuation line, gets `> ` for each
 *   quote level it is in.
 * - Emphasis cut by [from] is reopened with its exact markers, outermost first, after the quote markers of
 *   the first line; emphasis cut by [to] is closed with its exact markers, innermost first.
 * - With [closeCodeBlock], a part that ends inside a code block left unclosed in the source gets the closing
 *   fence too, so that a line appended after it is not code.
 */
fun partFragment(
    text: String,
    structure: SourceStructure,
    from: Break?,
    to: Break?,
    closeCodeBlock: Boolean = false,
): PartFragment {
    val start = from?.start ?: 0
    val end = to?.end ?: text.length
    require(start <= end) { "The part [$start, $end) is reversed" }
    val out = StringBuilder(end - start + 32)
    from?.codeBlock?.let { block -> out.append(fencePrefix(text, block)).append(block.openingFence).append('\n') }
    val openers = from?.emphasis.orEmpty().joinToString("") { it.open }
    if (from != null && from.quoteDepth > 0) {
        repeat(from.quoteDepth) { out.append("> ") }
        out.append(openers).append(text, start, end)
    } else {
        val markersEnd = quoteMarkersEnd(text, start, end)
        out.append(text, start, markersEnd).append(openers).append(text, markersEnd, end)
    }
    to?.emphasis.orEmpty().asReversed().forEach { out.append(it.close) }
    var endsWithCodeBlock = false
    val cut = to?.codeBlock
    if (cut != null) {
        out.append('\n').append(fencePrefix(text, cut)).append(cut.closingFence)
        endsWithCodeBlock = true
    } else {
        val block = structure.codeBlocks.firstOrNull { it.range.start < end && end <= it.range.end }
        val closing = block?.closingFenceRange
        if (block != null && closing != null && closing.end == end) {
            endsWithCodeBlock = true
        } else if (block != null && closing == null && closeCodeBlock) {
            out.append('\n').append(fencePrefix(text, block)).append(block.closingFence)
            endsWithCodeBlock = true
        }
    }
    return PartFragment(out.toString(), endsWithCodeBlock)
}

/** The end of the quote markers (`>` with optional spaces) at [start], or [start] when there are none. */
private fun quoteMarkersEnd(text: String, start: Int, end: Int): Int {
    var i = start
    var markersEnd = start
    while (i < end) {
        while (i < end && text[i] == ' ') i++
        if (i < end && text[i] == '>') {
            i++
            if (i < end && text[i] == ' ') i++
            markersEnd = i
        } else {
            break
        }
    }
    return markersEnd
}

/** The prefix a fence line of [block] needs to stay in its container: quote markers kept, the rest spaces. */
internal fun fencePrefix(text: String, block: CodeBlockSpan): String {
    val fenceStart = block.openingFenceRange.start
    var lineStart = fenceStart
    while (lineStart > 0 && text[lineStart - 1] != '\n' && text[lineStart - 1] != '\r') lineStart--
    val prefix = StringBuilder(fenceStart - lineStart)
    for (i in lineStart until fenceStart) prefix.append(if (text[i] == '>') '>' else ' ')
    return prefix.toString()
}
