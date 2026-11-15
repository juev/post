package org.evsyukov.post.core.markdown

/**
 * The manual thread break (threads.md, T13): a line that holds only this character, U+2E3B THREE-EM
 * DASH, with optional spaces around it. It is not Markdown content: it splits the draft into pieces
 * before any conversion, and it is never published.
 */
const val MANUAL_BREAK = "⸻"

/** Whether [line], without its line terminator, is a manual break: [MANUAL_BREAK] with optional whitespace around it. */
fun isManualBreakLine(line: String): Boolean = line.trim() == MANUAL_BREAK

/**
 * Splits [source] at its manual break lines (threads.md, T13) into the pieces between them. Each piece
 * loses the blank lines at its start and end and the line break before the next break; the lines
 * inside it stay as typed. A source without break lines gives one piece.
 *
 * A piece may be empty, for example before a break on the first line or between two adjacent breaks;
 * it is kept, so piece `i` is always the text after break `i - 1`.
 */
fun splitManualBreaks(source: String): List<String> {
    val pieces = ArrayList<String>()
    var pieceStart = 0
    for (line in lines(source)) {
        if (isManualBreakLine(source.substring(line.start, line.end))) {
            pieces += trimBlankLines(source, pieceStart, line.start)
            pieceStart = line.next
        }
    }
    pieces += trimBlankLines(source, pieceStart, source.length)
    return pieces
}

/** A line of a source: its text is `[start, end)`, and the next line starts at [next] (after the terminator). */
internal class Line(val start: Int, val end: Int, val next: Int)

/** The lines of [source], split at `\n`, `\r\n` and `\r` as CommonMark splits them. */
internal fun lines(source: String): List<Line> {
    val result = ArrayList<Line>()
    var start = 0
    var i = 0
    while (i < source.length) {
        val c = source[i]
        if (c == '\n' || c == '\r') {
            val next = if (c == '\r' && i + 1 < source.length && source[i + 1] == '\n') i + 2 else i + 1
            result += Line(start, i, next)
            start = next
            i = next
        } else {
            i++
        }
    }
    result += Line(start, source.length, source.length)
    return result
}

/** The ranges `[start, end)` of the manual break lines of [source], without their line terminators. */
internal fun manualBreakLines(source: String): List<SourceRange> {
    if (!source.contains(MANUAL_BREAK)) return emptyList()
    return lines(source)
        .filter { isManualBreakLine(source.substring(it.start, it.end)) }
        .map { SourceRange(it.start, it.end) }
}

/**
 * The ranges of [source] between its manual break lines, untrimmed, for parsing each piece on its own
 * while keeping offsets into [source].
 */
internal fun pieceRanges(source: String, breaks: List<SourceRange>): List<SourceRange> {
    if (breaks.isEmpty()) return listOf(SourceRange(0, source.length))
    val result = ArrayList<SourceRange>(breaks.size + 1)
    var start = 0
    for (lineBreak in breaks) {
        result += SourceRange(start, lineBreak.start)
        start = lineBreak.end
    }
    result += SourceRange(start, source.length)
    return result
}

/**
 * [source] without its manual break lines, for the converters: the pieces are joined by one blank line,
 * and empty pieces are dropped. A source without break lines is returned unchanged.
 */
internal fun withoutManualBreaks(source: String): String {
    if (manualBreakLines(source).isEmpty()) return source
    return splitManualBreaks(source).filter { it.isNotEmpty() }.joinToString("\n\n")
}

private fun trimBlankLines(source: String, start: Int, end: Int): String {
    val pieceLines = lines(source.substring(start, end))
    val text = source.substring(start, end)
    val first = pieceLines.indexOfFirst { text.substring(it.start, it.end).isNotBlank() }
    if (first < 0) return ""
    val last = pieceLines.indexOfLast { text.substring(it.start, it.end).isNotBlank() }
    return text.substring(pieceLines[first].start, pieceLines[last].end)
}
