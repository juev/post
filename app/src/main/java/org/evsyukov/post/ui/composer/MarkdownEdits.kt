package org.evsyukov.post.ui.composer

import org.evsyukov.post.core.markdown.MANUAL_BREAK

/** The text after an edit, with the selection `[selectionStart, selectionEnd)` to put in the field. */
data class EditResult(val text: String, val selectionStart: Int, val selectionEnd: Int = selectionStart)

/**
 * The edits of the formatting buttons (formatting.md F5) and of manual breaks (threads.md T13), on the text
 * and its selection `[start, end)`. They are plain functions, so the editor applies the result in one step
 * and the rules are tested without a screen.
 */
object MarkdownEdits {
    /**
     * Wraps the selection in [marker] on both sides and keeps it selected; with no selection, inserts an
     * empty pair and puts the cursor inside (F5).
     */
    fun wrap(text: String, start: Int, end: Int, marker: String): EditResult {
        val (from, to) = ordered(text, start, end)
        val result = text.substring(0, from) + marker + text.substring(from, to) + marker + text.substring(to)
        return EditResult(result, from + marker.length, to + marker.length)
    }

    /**
     * Toggles [prefix] (`> ` for a quote, `- ` for a list) at the start of every line the selection touches:
     * when every non-blank line has it, it is removed from them; otherwise it is added to the lines that lack
     * it. Blank lines inside a selection of several lines stay as they are.
     */
    fun toggleLinePrefix(text: String, start: Int, end: Int, prefix: String): EditResult {
        val (from, to) = ordered(text, start, end)
        val firstLine = text.lastIndexOf('\n', from - 1) + 1
        // A selection that ends right after a line break does not touch the next line.
        val lastPoint = if (to > from && text[to - 1] == '\n') to - 1 else to
        val lastEnd = text.indexOf('\n', lastPoint).let { if (it < 0) text.length else it }
        val lines = text.substring(firstLine, lastEnd).split('\n')
        val several = lines.size > 1
        val counted = lines.filter { !several || it.isNotBlank() }
        val remove = counted.isNotEmpty() && counted.all { it.startsWith(prefix) }
        var newFrom = from
        var newTo = to
        var offset = firstLine
        val changed = lines.map { line ->
            val skip = several && line.isBlank()
            val next = when {
                skip -> line
                remove -> line.removePrefix(prefix)
                line.startsWith(prefix) -> line
                else -> prefix + line
            }
            val delta = next.length - line.length
            if (delta != 0) {
                // A point inside the removed prefix lands at the line start; a point after it moves by delta.
                if (from >= offset) newFrom = if (delta < 0 && from - offset < -delta) newFrom - (from - offset) else newFrom + delta
                if (to >= offset) newTo = if (delta < 0 && to - offset < -delta) newTo - (to - offset) else newTo + delta
            }
            offset += line.length + 1
            next
        }
        val result = text.substring(0, firstLine) + changed.joinToString("\n") + text.substring(lastEnd)
        return EditResult(result, newFrom.coerceIn(0, result.length), newTo.coerceIn(0, result.length))
    }

    /**
     * A Markdown link to [url]: the selection becomes its text and the cursor goes after the link; with no
     * selection, an empty `[]` goes before the address and the cursor inside it, ready for the text (F5).
     */
    fun link(text: String, start: Int, end: Int, url: String): EditResult {
        val (from, to) = ordered(text, start, end)
        val label = text.substring(from, to)
        val link = "[$label](${url.trim()})"
        val result = text.substring(0, from) + link + text.substring(to)
        return if (label.isEmpty()) EditResult(result, from + 1) else EditResult(result, from + link.length)
    }

    /**
     * Inserts a manual break (T13) at [cursor] on a line of its own, adding line breaks around it where the
     * cursor is inside a line, and puts the cursor at the start of the line after it.
     */
    fun insertBreak(text: String, cursor: Int): EditResult {
        val at = cursor.coerceIn(0, text.length)
        val before = text.substring(0, at)
        val after = text.substring(at)
        val lead = if (before.isEmpty() || before.endsWith('\n')) "" else "\n"
        val inserted = lead + MANUAL_BREAK + "\n"
        val rest = if (after.startsWith('\n')) after.substring(1) else after
        val result = before + inserted + rest
        return EditResult(result, before.length + inserted.length)
    }

    /**
     * Removes the manual break line `[lineStart, lineEnd)` (without its terminator) with one line break, so
     * the text around it joins as it was before the break was added; the cursor goes where the line was.
     */
    fun removeBreak(text: String, lineStart: Int, lineEnd: Int): EditResult {
        val (from, to) = when {
            lineEnd < text.length && text[lineEnd] == '\n' -> lineStart to lineEnd + 1
            lineStart > 0 && text[lineStart - 1] == '\n' -> lineStart - 1 to lineEnd
            else -> lineStart to lineEnd
        }
        val result = text.substring(0, from) + text.substring(to)
        return EditResult(result, from.coerceAtMost(result.length))
    }

    private fun ordered(text: String, start: Int, end: Int): Pair<Int, Int> {
        val a = start.coerceIn(0, text.length)
        val b = end.coerceIn(0, text.length)
        return minOf(a, b) to maxOf(a, b)
    }
}
