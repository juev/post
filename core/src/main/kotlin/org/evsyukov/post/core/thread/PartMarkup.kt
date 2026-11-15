package org.evsyukov.post.core.thread

import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlInline
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.evsyukov.post.core.markdown.AtomicRange
import org.evsyukov.post.core.markdown.CodeBlockSpan
import org.evsyukov.post.core.markdown.EmphasisSpan
import org.evsyukov.post.core.markdown.LiteralLine
import org.evsyukov.post.core.markdown.SourceRange
import org.evsyukov.post.core.markdown.SourceStructure
import org.evsyukov.post.core.markdown.analyze
import org.evsyukov.post.core.markdown.forEachChild
import org.evsyukov.post.core.markdown.listMarkerRange
import org.evsyukov.post.core.markdown.parseMarkdown
import org.evsyukov.post.core.markdown.deepRange
import org.evsyukov.post.core.markdown.range

/**
 * The Markdown of one thread part, ready for the account's converter.
 *
 * @property markdown the part's source with what its breaks cut closed and reopened (threads.md, T5).
 * @property endsWithCodeBlock the last line is a closing code fence, so a part number goes on a line of its
 *   own after it (T11).
 */
data class PartFragment(val markdown: String, val endsWithCodeBlock: Boolean)

/**
 * What [partFragment] needs to know of a piece beyond [SourceStructure]: the items of its ordered lists
 * with the numbers they show (a list counts up from the number of its first item, whatever the later items
 * say: `1.` `1.` `1.` shows 1, 2, 3), the backslashes that make hard line breaks, and the `*`, `_` and `~`
 * that are text, not emphasis markers.
 */
class PieceMarkup internal constructor(
    internal val items: List<OrderedItem>,
    internal val hardBreakBackslashes: Set<Int>,
    /**
     * The offsets of the `*`, `_` and `~` that are text in the piece: no emphasis or list marker, not
     * escaped, not in an atomic range, a code block or inline HTML. In a part they could pair with other
     * markers, which they do not in the piece, so a part escapes them.
     */
    internal val literalDelimiters: IntArray,
) {
    /**
     * An item of list [list] (lists are numbered in the order of their first items): its marker starts at
     * [digitsStart]; for an ordered list its digits are `[digitsStart, digitsEnd)` of the piece text,
     * followed by `.` or `)`, and it shows [number]; a bullet has no [number] and `digitsEnd == digitsStart`.
     * Its line starts at [lineStart].
     */
    internal class OrderedItem(
        val list: Int,
        val digitsStart: Int,
        val digitsEnd: Int,
        val lineStart: Int,
        val number: Long?,
        /** Where the item with its content ends. */
        val end: Int,
        /** The column of the item's content on its first line: its continuation lines are indented as far. */
        val contentColumn: Int,
        /** The item has nothing after its marker on its line. */
        val empty: Boolean,
    )

    companion object {
        /** The markup of the Markdown piece [text] with the structure [structure], `analyze(text)`. */
        fun of(text: String, structure: SourceStructure = analyze(text)): PieceMarkup {
            val maybeLists = structure.listItems.isNotEmpty()
            val delimiters = text.any { it == '*' || it == '_' || it == '~' }
            if (!maybeLists && !delimiters && text.indexOf('\\') < 0) return PieceMarkup(emptyList(), emptySet(), IntArray(0))
            val items = ArrayList<OrderedItem>()
            val backslashes = HashSet<Int>()
            val html = ArrayList<SourceRange>()
            var lists = 0
            fun visit(node: Node) {
                // Converters write these as typed, so a backslash in them would show.
                if (node is HtmlInline || node is LinkReferenceDefinition || node is LiteralLine) node.range()?.let { html += it }
                if (node is HardLineBreak) {
                    node.range()?.let { range -> if (text[range.start] == '\\') backslashes += range.start }
                }
                if (node is ListBlock) {
                    val list = lists++
                    var index = 0L
                    val first = if (node is OrderedList) (node.markerStartNumber ?: 1).toLong() else null
                    node.forEachChild { child ->
                        if (child is ListItem) {
                            val marker = listMarkerRange(text, child, 0)
                            if (marker != null) {
                                var lineStart = marker.start
                                while (lineStart > 0 && text[lineStart - 1] != '\n' && text[lineStart - 1] != '\r') lineStart--
                                var content = marker.end
                                while (content < text.length && content - marker.end < 4 && text[content] == ' ') content++
                                val empty = content >= text.length || text[content] == '\n' || text[content] == '\r'
                                if (content - marker.end >= 4 || content - marker.end == 0) content = marker.end + 1
                                val end = child.deepRange()?.end ?: marker.end
                                val digitsEnd = if (first != null) marker.end - 1 else marker.start
                                items += OrderedItem(list, marker.start, digitsEnd, lineStart, first?.plus(index), end, content - lineStart, empty)
                            }
                            index++
                        }
                    }
                }
                node.forEachChild { visit(it) }
            }
            visit(parseMarkdown(text))
            val literals = if (delimiters) literalDelimiters(text, structure, html) else IntArray(0)
            return PieceMarkup(items.sortedBy { it.digitsStart }, backslashes, literals)
        }

        /**
         * The `*`, `_` and `~` of [text] that are text: not an emphasis or list marker, not escaped, not an
         * underscore inside a word, not in an atomic range, a code block or what converters write as typed
         * ([html]: inline HTML, link reference definitions, spaced thematic breaks), and not after a bare
         * URL before the next space, where a backslash would join the URL.
         */
        private fun literalDelimiters(text: String, structure: SourceStructure, html: List<SourceRange>): IntArray {
            val markup = BooleanArray(text.length)
            fun mark(start: Int, end: Int) {
                for (i in start until minOf(end, text.length)) markup[i] = true
            }
            for (span in structure.emphasis) {
                mark(span.openRange.start, span.openRange.end)
                mark(span.closeRange.start, span.closeRange.end)
            }
            for (atom in structure.atomicRanges) {
                mark(atom.start, atom.end)
                if (atom.kind == AtomicRange.Kind.URL) {
                    // A URL takes in a backslash and what follows it, up to the next space.
                    var k = atom.end
                    while (k < text.length && !text[k].isWhitespace()) markup[k++] = true
                }
            }
            for (block in structure.codeBlocks) mark(block.range.start, block.range.end)
            for (item in structure.listItems) mark(item.markerRange.start, item.markerRange.end)
            for (range in html) mark(range.start, range.end)
            val result = ArrayList<Int>()
            var backslashes = 0
            for (i in text.indices) {
                val c = text[i]
                // An underscore between letters or digits never opens or closes emphasis, so it stays as typed.
                val intraword = c == '_' && i > 0 && i + 1 < text.length && text[i - 1].isLetterOrDigit() && text[i + 1].isLetterOrDigit()
                if ((c == '*' || c == '_' || c == '~') && !markup[i] && !intraword && backslashes % 2 == 0) result += i
                backslashes = if (c == '\\') backslashes + 1 else 0
            }
            return result.toIntArray()
        }
    }
}

/**
 * The Markdown of the part of the piece [text] between the breaks [from] and [to] (null for the start and
 * the end of the piece); [structure] is `analyze(text)` and [markup] is `PieceMarkup.of(text, structure)`.
 *
 * The part must read as the same text it is in the piece (T17):
 * - A code block cut by [from] is reopened with its opening fence and info string; one cut by [to] gets its
 *   closing fence. Inside a quote or list the fence keeps the quote markers of its line, and other prefix
 *   characters become spaces, so the fence stays in the same container.
 * - A part that starts in the middle of a quoted line, or on a lazy continuation line, gets `> ` for each
 *   quote level it is in.
 * - Emphasis cut by [from] is reopened with its exact markers, outermost first, after the quote markers of
 *   the first line; emphasis cut by [to] is closed with its exact markers, innermost first. A span inside
 *   another span with the same markers (bold in bold, strikethrough in strikethrough) that a break of the
 *   part cuts is neither reopened nor closed, and its markers in the part go ([redundantSpans]): next to
 *   the outer span's markers they would make one run that Markdown pairs differently.
 * - Text at the start of the part that Markdown would read as the start of a block, but that is text in
 *   the piece (`+ 2`, `1984. was`, a `>` in the middle of a line, a fence, a link reference definition), gets
 *   a backslash, so it stays text. So does every `*`, `_` and `~` of the part that is text in the piece
 *   ([PieceMarkup.literalDelimiters]): with fewer markers around, and the reopened ones, it could pair.
 * - The first item of an ordered list that began before the part shows the number it has in the piece, and
 *   a blank line keeps an item numbered other than 1 a list item where the part puts it after a paragraph.
 * - A part that starts inside list items ([CutItems]: their markers are in an earlier part) has no markers
 *   for them, so its lines inside them lose the indentation those items gave them: a nested item is then an
 *   item of the part's top level, with its own children under it, not text of the item before it. Where a
 *   list that was nested in a cut item is followed at the part's top level by another list of the same kind
 *   and marker, the nested one gets the other delimiter (`.` and `)`) or bullet (`+`, `-`, `*`), so the two
 *   stay two lists, each numbered as in the piece, instead of one that counts on.
 * - A backslash that made a line break at the end of the part goes, and a backslash before closing
 *   markers is doubled, so it does not escape them.
 * - A code block left unclosed in the piece, which its container closes, gets its closing fence where it
 *   ends inside the part, since the part may not have that container. With [closeCodeBlock], a part that
 *   ends inside such a block gets the closing fence too, so that a line appended after it is not code.
 */
fun partFragment(
    text: String,
    structure: SourceStructure,
    from: Break?,
    to: Break?,
    closeCodeBlock: Boolean = false,
    markup: PieceMarkup = PieceMarkup.of(text, structure),
): PartFragment {
    val start = from?.start ?: 0
    val end = to?.end ?: text.length
    require(start <= end) { "The part [$start, $end) is reversed" }
    val out = StringBuilder(end - start + 32)
    val cutItems = if (from != null) CutItems.of(text, markup, start) else CutItems.NONE
    from?.codeBlock?.let { block -> out.append(partFencePrefix(text, block, cutItems, emptyList())).append(block.openingFence).append('\n') }
    val redundant = redundantSpans(structure, from, to, start, end)
    val openers = from?.emphasis.orEmpty().filter { it !in redundant }.joinToString("") { it.open }
    val closers = to?.emphasis.orEmpty().filter { it !in redundant }.asReversed().joinToString("") { it.close }
    // Where the text of the first line starts, after the quote markers the part copies.
    val textStart = when {
        from == null || from.quoteDepth > 0 -> start
        start in quoteLineStarts(structure) -> quoteMarkersEnd(text, start, end)
        else -> start
    }
    val edits = ArrayList<FragmentEdit>()
    val widened = ArrayList<Pair<PieceMarkup.OrderedItem, Int>>()
    // Offsets that get a backslash before them, so they stay text.
    val escapes = sortedSetOf<Int>()
    // Where the text of the first line starts once the indentation the part drops is gone.
    var firstText = textStart
    if (from != null) {
        val dedents = cutItems.dedents(text, start, textStart, end)
        dedents.firstOrNull { it.position == textStart }?.let { firstText = textStart + it.remove }
        edits += dedents
        if (from.codeBlock == null) {
            // Up to three spaces of indentation still let a line start a block.
            var k = firstText
            while (k < end && k - firstText < 3 && text[k] == ' ') k++
            escapes += blockStartEscapes(text, structure, k, end)
        }
        edits += listNumbering(text, markup, start, textStart, end, widened, dedents)
        edits += cutItems.separations(text, markup, textStart, end)
    }
    if (from != null || to != null) {
        // A part has fewer markers to pair with than the piece and gains the reopened and closing ones, so a
        // `*`, `_` or `~` that is text in the piece could pair in the part.
        val literals = markup.literalDelimiters
        var k = literals.binarySearch(textStart).let { if (it < 0) -it - 1 else it }
        while (k < literals.size && literals[k] < end) escapes += literals[k++]
    }
    for (span in redundant) {
        // Without its markers, a span that opened a line could leave text there that starts a block (`~~+ x`).
        val open = span.openRange
        if (open.start > textStart && open.end <= end && opensLine(text, open.start, textStart)) {
            escapes += blockStartEscapes(text, structure, open.end, end)
        }
    }
    for (position in escapes) edits += FragmentEdit(position, 0, "\\")
    // A code block left unclosed ends with its container (a list item or quote); the part may lose that
    // container, so the block gets its closing fence where it ends.
    for (block in structure.codeBlocks) {
        if (block.closingFenceRange == null && block.range.end in textStart + 1 until end) {
            edits += FragmentEdit(block.range.end, 0, "\n" + partFencePrefix(text, block, cutItems, widened) + block.closingFence)
        }
    }
    for (span in redundant) {
        if (span.openRange.start >= textStart && span.openRange.end <= end) edits += FragmentEdit(span.openRange.start, span.openRange.length, "")
        if (span.closeRange.start >= textStart && span.closeRange.end <= end) edits += FragmentEdit(span.closeRange.start, span.closeRange.length, "")
    }
    if (to != null && to.codeBlock == null) trailingBackslash(text, markup, start, end, closers.isNotEmpty())?.let { edits += it }
    edits.sortWith(compareBy({ it.position }, { it.order })) // stable: otherwise in the order they were made

    if (from != null && from.quoteDepth > 0) repeat(from.quoteDepth) { out.append("> ") } else out.append(text, start, textStart)
    out.append(openers)
    appendEdited(out, text, textStart, end, edits)
    out.append(closers)
    var endsWithCodeBlock = false
    val cut = to?.codeBlock
    if (cut != null) {
        out.append('\n').append(partFencePrefix(text, cut, cutItems, widened)).append(cut.closingFence)
        endsWithCodeBlock = true
    } else {
        val block = structure.codeBlocks.firstOrNull { it.range.start < end && end <= it.range.end }
        val closing = block?.closingFenceRange
        if (block != null && closing != null && closing.end == end) {
            endsWithCodeBlock = true
        } else if (block != null && closing == null && closeCodeBlock) {
            out.append('\n').append(partFencePrefix(text, block, cutItems, widened)).append(block.closingFence)
            endsWithCodeBlock = true
        }
    }
    return PartFragment(out.toString(), endsWithCodeBlock)
}

/**
 * The emphasis spans the part `[start, end)` drops: each lies inside another span with the same markers
 * (strikethrough in strikethrough, bold in bold) that the part's break [from] or [to] cuts. Such a span adds
 * nothing to the outer one, but its markers next to the reopened or closing ones would make one run of
 * markers that Markdown pairs otherwise: `~~` reopened before an inner `~~` at the start of a line is the
 * `~~~~` of a code fence that swallows the part, and an inner `~~` closed with the outer one is a `~~~~`
 * that closes nothing. So the part does not reopen or close such a span and leaves out its markers.
 */
private fun redundantSpans(structure: SourceStructure, from: Break?, to: Break?, start: Int, end: Int): Set<EmphasisSpan> {
    val cut = from?.emphasis.orEmpty() + to?.emphasis.orEmpty()
    if (cut.isEmpty()) return emptySet()
    val result = HashSet<EmphasisSpan>()
    for (inner in structure.emphasis) {
        if (inner.openRange.start >= end) break // sorted by start
        if (inner.closeRange.end <= start) continue
        val nested = cut.any { outer ->
            outer != inner && outer.open == inner.open &&
                outer.openRange.end <= inner.openRange.start && inner.closeRange.end <= outer.closeRange.start
        }
        if (nested) result += inner
    }
    return result
}

/**
 * Whether only indentation and quote markers come before [position] on its line, and that line is not the
 * first line of a part whose text starts at [textStart] (where reopened markers come first).
 */
private fun opensLine(text: String, position: Int, textStart: Int): Boolean {
    var k = position - 1
    while (k >= textStart && (text[k] == ' ' || text[k] == '\t' || text[k] == '>')) k--
    return k >= textStart && (text[k] == '\n' || text[k] == '\r')
}

/**
 * Replaces `[position, position + remove)` of the source by [insert]. Of edits at one offset, those with a
 * lower [order] go first: a blank line before a line goes before the indentation added to that line.
 */
private class FragmentEdit(val position: Int, val remove: Int, val insert: String, val order: Int = 1)

private fun appendEdited(out: StringBuilder, text: String, start: Int, end: Int, edits: List<FragmentEdit>) {
    var cursor = start
    for (edit in edits) {
        if (edit.position < cursor || edit.position > end) continue
        out.append(text, cursor, edit.position).append(edit.insert)
        cursor = minOf(end, edit.position + edit.remove)
    }
    out.append(text, cursor, end)
}

/**
 * The offsets that need a backslash so the text at [position], the start of the first line of a part, does
 * not start a block; none when it is no block start or is one in the piece too.
 */
private fun blockStartEscapes(text: String, structure: SourceStructure, position: Int, end: Int): List<Int> {
    if (position >= end) return emptyList()
    if (structure.listItems.any { it.markerRange.start == position }) return emptyList()
    // At the top level the opening fence range holds the fence's indentation; the part starts after it.
    if (structure.codeBlocks.any { block ->
            var fence = block.openingFenceRange.start
            while (fence < position && (text[fence] == ' ' || text[fence] == '\t')) fence++
            fence == position
        }
    ) {
        return emptyList()
    }
    var lineEnd = position
    while (lineEnd < end && text[lineEnd] != '\n' && text[lineEnd] != '\r') lineEnd++
    fun spaceOrEnd(i: Int) = i >= lineEnd || text[i] == ' ' || text[i] == '\t'
    val c = text[position]
    return when {
        c == '>' -> listOf(position)
        (c == '-' || c == '+' || c == '*') && spaceOrEnd(position + 1) -> listOf(position)
        c in '0'..'9' -> {
            var k = position
            while (k < lineEnd && text[k] in '0'..'9') k++
            if (k - position <= 9 && k < lineEnd && (text[k] == '.' || text[k] == ')') && spaceOrEnd(k + 1)) listOf(k) else emptyList()
        }
        c == '`' || c == '~' -> {
            var k = position
            while (k < lineEnd && text[k] == c) k++
            val fence = k - position >= 3 && (c == '~' || (k until lineEnd).none { text[it] == '`' })
            if (fence) (position until k).toList() else emptyList()
        }
        // Also a reference link `[label]` followed by `:`, which shows as typed anyway.
        c == '[' && LINK_DEFINITION.containsMatchIn(text.substring(position, lineEnd)) -> listOf(position)
        else -> emptyList()
    }
}

/**
 * Edits that keep the ordered lists of the part `[start, end)` as they are in the piece. The first item of
 * a list that began before the part shows its number in the piece. The first item in the part of any list,
 * when it is empty or numbered other than 1 and the line before it in the part holds text, gets a blank line
 * before it: such an item cannot interrupt a paragraph, and in the part the line before it may be one (the
 * rest of an item cut by the break, or a quoted line that followed a list item in the piece).
 */
private fun listNumbering(
    text: String,
    markup: PieceMarkup,
    start: Int,
    textStart: Int,
    end: Int,
    widened: MutableList<Pair<PieceMarkup.OrderedItem, Int>>,
    dedents: List<FragmentEdit>,
): List<FragmentEdit> {
    if (markup.items.isEmpty()) return emptyList()
    val edits = ArrayList<FragmentEdit>()
    val begunBefore = markup.items.filter { it.digitsStart < start }.mapTo(HashSet()) { it.list }
    val seen = HashSet<Int>()
    for (item in markup.items) {
        if (item.digitsStart < textStart || item.digitsStart >= end || !seen.add(item.list)) continue
        // The blank line goes first: at the start of a line it shares its offset with the digits.
        // Only an item that opens its line after quote markers can follow a paragraph; one that follows other
        // markers on its line (`1. + 5. x`) is the first block of its container.
        val opensLine = (item.lineStart until item.digitsStart).all { text[it] == ' ' || text[it] == '\t' || text[it] == '>' }
        // An empty item, or an ordered one numbered other than 1, cannot interrupt a paragraph.
        val interrupts = item.empty || (item.number != null && item.number != 1L)
        if (interrupts && item.lineStart > start && opensLine) {
            var previous = item.lineStart - 1
            if (text[previous] == '\n' && previous > 0 && text[previous - 1] == '\r') previous--
            var lineStart = previous
            while (lineStart > start && text[lineStart - 1] != '\n' && text[lineStart - 1] != '\r') lineStart--
            // The quote markers of a line are no text; on the first line of the part they end at textStart,
            // and a `>` after them is text (escaped in the part).
            var from = maxOf(lineStart, textStart)
            if (lineStart > start) while (from < previous && (text[from] == ' ' || text[from] == '\t' || text[from] == '>')) from++
            val holdsText = (from until previous).any { !text[it].isWhitespace() }
            if (holdsText) {
                // The blank line keeps the quote markers of the item's line and the indentation before them,
                // as the line has it in the part, so it stays in the same containers.
                val removed = dedents.firstOrNull { it.position in item.lineStart until item.digitsStart }
                val prefix = StringBuilder()
                for (i in item.lineStart until item.digitsStart) {
                    if (removed != null && i >= removed.position && i < removed.position + removed.remove) continue
                    prefix.append(text[i])
                }
                val lastMarker = prefix.lastIndexOf(">")
                edits += FragmentEdit(item.lineStart, 0, prefix.substring(0, lastMarker + 1) + "\n", order = 0)
            }
        }
        val number = item.number?.toString() ?: continue
        if (item.list in begunBefore && text.substring(item.digitsStart, item.digitsEnd) != number) {
            edits += FragmentEdit(item.digitsStart, item.digitsEnd - item.digitsStart, number)
            // A wider number moves the item's content right: its continuation lines follow, or they would
            // leave the item (`19. a` takes a nested `   - b` out of the list).
            val growth = number.length - (item.digitsEnd - item.digitsStart)
            if (growth > 0) {
                edits += continuationIndent(text, item, growth, textStart, end)
                widened += item to growth
            }
        }
    }
    return edits
}

/** [growth] spaces more on each continuation line of [item] inside `[from, to)` that is indented to its content. */
private fun continuationIndent(text: String, item: PieceMarkup.OrderedItem, growth: Int, from: Int, to: Int): List<FragmentEdit> {
    val edits = ArrayList<FragmentEdit>()
    val prefix = item.digitsStart - item.lineStart
    var lineStart = text.indexOf('\n', item.digitsStart).let { if (it < 0) text.length else it + 1 }
    while (lineStart < minOf(item.end, to)) {
        var lineEnd = lineStart
        while (lineEnd < text.length && text[lineEnd] != '\n' && text[lineEnd] != '\r') lineEnd++
        val indentEnd = lineStart + item.contentColumn
        val indented = lineStart >= from && indentEnd <= lineEnd &&
            (lineStart until lineStart + prefix).all { text[it] == ' ' || text[it] == '>' } &&
            (lineStart + prefix until indentEnd).all { text[it] == ' ' }
        if (indented) edits += FragmentEdit(lineStart + prefix, 0, " ".repeat(growth))
        lineStart = text.indexOf('\n', lineEnd).let { if (it < 0) text.length else it + 1 }
    }
    return edits
}

/**
 * At the end `[.., end)` of a part: a backslash that made a hard line break goes, since the line break is
 * dropped; another backslash that would escape the closing markers that follow is doubled.
 */
private fun trailingBackslash(text: String, markup: PieceMarkup, start: Int, end: Int, closing: Boolean): FragmentEdit? {
    if (end <= start || text[end - 1] != '\\') return null
    if (end - 1 in markup.hardBreakBackslashes) return FragmentEdit(end - 1, 1, "")
    var run = 0
    while (end - 1 - run >= start && text[end - 1 - run] == '\\') run++
    return if (run % 2 == 1 && closing) FragmentEdit(end, 0, "\\") else null
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

/**
 * The prefix of a fence line the part adds to [block], reopening or closing it: that of its opening fence
 * ([fencePrefix]) without the columns of the list items the part starts inside of ([cut]), whose markers it
 * leaves out, and wider by the digits the part added to the number of a list item the block is in
 * ([widened]).
 */
private fun partFencePrefix(
    text: String,
    block: CodeBlockSpan,
    cut: CutItems,
    widened: List<Pair<PieceMarkup.OrderedItem, Int>>,
): String {
    val fenceStart = block.openingFenceRange.start
    var lineStart = fenceStart
    while (lineStart > 0 && text[lineStart - 1] != '\n' && text[lineStart - 1] != '\r') lineStart--
    val removed = cut.columns(text, lineStart)
    val growths = widened.filter { (item, _) -> fenceStart >= item.lineStart && fenceStart < item.end }
        .map { (item, growth) -> (item.digitsStart - item.lineStart) to growth }
    val prefix = StringBuilder(fenceStart - lineStart + 4)
    for (i in lineStart until fenceStart) {
        for ((column, growth) in growths) if (column == i - lineStart) repeat(growth) { prefix.append(' ') }
        if (removed != null && i >= removed.first && i < removed.second) continue
        prefix.append(if (text[i] == '>') '>' else ' ')
    }
    for ((column, growth) in growths) if (column >= fenceStart - lineStart) repeat(growth) { prefix.append(' ') }
    return prefix.toString()
}

/**
 * The list items a part starts inside of ([items], outermost first): each has its marker before the part and
 * content in it. The part has no markers for them, so what they hold must not stay indented as their content:
 * a nested item would read as text of the item before it (`4. Put` after `3. Make` in a part is a lazy line,
 * not an item), and a deeper one, 4 columns in, as text at all.
 *
 * A part that starts at a list marker also lost the marker's own indentation in its container to the break
 * ([lead] columns); the lines of the other items of that list ([siblings], each with its own marker
 * indentation) lose as much, but no more than their item's marker indentation, or a sibling indented like the
 * first item would fall inside its content.
 */
private class CutItems(
    private val items: List<PieceMarkup.OrderedItem>,
    private val lead: Int = 0,
    private val siblings: List<Pair<PieceMarkup.OrderedItem, Int>> = emptyList(),
) {
    val isEmpty: Boolean get() = items.isEmpty() && lead == 0

    /** The lead columns a line of the first list loses: as many as the item holding it, at most [lead]. */
    private fun extra(lineStart: Int): Int {
        if (lead == 0) return 0
        val holder = siblings.lastOrNull { (item, _) -> item.lineStart <= lineStart && lineStart < item.end } ?: return 0
        return minOf(lead, holder.second)
    }

    /**
     * `[first, second)` of the line that starts at [lineStart]: the columns the cut items give it, from where
     * the outermost one's container begins (after the quote markers outside it) to the content column of
     * the innermost one that holds the line, and the [lead] columns after them on a line of the first list;
     * null when there are none, or when the line does not begin with the quote markers and spaces of the
     * containers outside the items (a lazy line).
     */
    fun columns(text: String, lineStart: Int): Pair<Int, Int>? {
        val holders = items.filter { it.lineStart <= lineStart && lineStart < it.end }
        val extra = extra(lineStart)
        if (holders.isEmpty()) return if (extra > 0) lineStart to lineStart + extra else null
        val outer = holders.first()
        val outside = quoteMarkersEnd(text, outer.lineStart, outer.digitsStart) - outer.lineStart
        for (i in lineStart until lineStart + outside) {
            if (i >= text.length || (text[i] != ' ' && text[i] != '\t' && text[i] != '>')) return null
        }
        return (lineStart + outside) to (lineStart + holders.last().contentColumn + extra)
    }

    /**
     * Edits that take out the indentation the cut items give each line of the part `[start, end)`, whose text
     * starts at [textStart]: the spaces within [columns]. The first line counts only when the part starts at
     * its beginning (a code line, or a quote line whose markers it copies); otherwise its indentation lies in
     * the break the part starts after.
     */
    fun dedents(text: String, start: Int, textStart: Int, end: Int): List<FragmentEdit> {
        if (isEmpty) return emptyList()
        val result = ArrayList<FragmentEdit>()
        var lineStart = if (start == 0 || text[start - 1] == '\n' || text[start - 1] == '\r') start else nextLineStart(text, start)
        while (lineStart < end) {
            val range = columns(text, lineStart)
            if (range != null && range.first >= textStart) {
                var k = range.first
                while (k < range.second && k < end && text[k] == ' ') k++
                if (k > range.first) result += FragmentEdit(range.first, k - range.first, "")
            }
            lineStart = nextLineStart(text, lineStart)
        }
        return result
    }

    /**
     * Edits that keep apart the lists that the part puts next to each other at its top level although they
     * are different lists in the piece: a list nested in a cut item, then the items of a list around it.
     * Of two such lists of the same kind and marker, the earlier, nested one gets another delimiter or bullet;
     * the lists of the piece's top level keep theirs. Lists of the part's own items stay as they are.
     */
    fun separations(text: String, markup: PieceMarkup, textStart: Int, end: Int): List<FragmentEdit> {
        if (items.isEmpty()) return emptyList()
        val inPart = markup.items.filter { it.digitsStart in textStart until end }
        // Items at the part's top level: no item of the part holds them.
        val top = inPart.filter { item -> inPart.none { it.digitsStart < item.digitsStart && item.digitsStart < it.end } }
        val lists = ArrayList<PieceMarkup.OrderedItem>() // the first item of each list, in order
        for (item in top) if (lists.lastOrNull()?.list != item.list) lists += item
        if (lists.size < 2) return emptyList()
        val markers = CharArray(lists.size) { markerChar(text, lists[it]) }
        val nested = BooleanArray(lists.size) { k -> items.any { lists[k].digitsStart < it.end } }
        for (k in lists.size - 2 downTo 0) {
            val ordered = lists[k].number != null
            if ((nested[k] || nested[k + 1]) && ordered == (lists[k + 1].number != null) && markers[k] == markers[k + 1]) {
                markers[k] = if (ordered) {
                    if (markers[k + 1] == '.') ')' else '.'
                } else {
                    BULLETS.first { it != markers[k + 1] }
                }
            }
        }
        val edits = ArrayList<FragmentEdit>()
        lists.forEachIndexed { k, first ->
            if (markers[k] == markerChar(text, first)) return@forEachIndexed
            for (item in top) {
                if (item.list != first.list) continue
                val at = if (item.number != null) item.digitsEnd else item.digitsStart
                edits += FragmentEdit(at, 1, markers[k].toString())
            }
        }
        return edits
    }

    companion object {
        val NONE = CutItems(emptyList())

        /** `+` first: unlike `-` and `*` it never makes a thematic break with the text after it. */
        private val BULLETS = listOf('+', '-', '*')

        /** The cut items of the part that starts at [start] of the piece [text] with the markup [markup]. */
        fun of(text: String, markup: PieceMarkup, start: Int): CutItems {
            val items = markup.items.filter { it.digitsStart < start && start < it.end }
            val first = markup.items.firstOrNull { it.digitsStart == start }
            // Only whitespace is before the marker on its line: the break took it.
            if (first == null || first.lineStart >= start || (first.lineStart until start).any { text[it] != ' ' }) return CutItems(items)
            val container = items.lastOrNull()?.contentColumn ?: 0
            val lead = start - first.lineStart - container
            if (lead <= 0) return CutItems(items)
            val siblings = markup.items.filter { it.list == first.list && it.digitsStart >= start }
                .map { it to maxOf(0, it.digitsStart - it.lineStart - container) }
            return CutItems(items, lead, siblings)
        }

        /** The delimiter of an ordered item (`.` or `)`) or the bullet of a bullet item. */
        fun markerChar(text: String, item: PieceMarkup.OrderedItem): Char =
            if (item.number != null) text[item.digitsEnd] else text[item.digitsStart]
    }
}

private fun nextLineStart(text: String, from: Int): Int {
    var i = from
    while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
    if (i < text.length) i += if (text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n') 2 else 1
    return i
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
