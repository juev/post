package org.evsyukov.post.core.thread

import org.evsyukov.post.core.markdown.manualBreakLines
import org.evsyukov.post.core.markdown.pieceRanges

/**
 * A piece of the draft between manual breaks (threads.md, T13): `[start, end)` of the draft source,
 * without the whitespace and blank lines at its ends (T10).
 *
 * [index] counts every piece, empty ones too, so piece `i` is the text after manual break `i - 1`, the
 * same numbering attachments use for the piece they were added in (T14, `MediaItem.pieceIndex`).
 */
data class Piece(val index: Int, val start: Int, val end: Int) {
    init {
        require(index >= 0 && start in 0..end) { "Invalid piece $index [$start, $end)" }
    }

    val isEmpty: Boolean get() = start == end
}

/**
 * The pieces of the draft [source], in order. A source without manual break lines is one piece. The
 * manual break lines themselves belong to no piece: they are never published.
 */
fun pieces(source: String): List<Piece> =
    pieceRanges(source, manualBreakLines(source)).mapIndexed { index, range ->
        var start = range.start
        var end = range.end
        while (start < end && source[start].isWhitespace()) start++
        while (end > start && source[end - 1].isWhitespace()) end--
        Piece(index, start, end)
    }
