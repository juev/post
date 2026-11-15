package org.evsyukov.post.core.thread

import org.evsyukov.post.core.markdown.CodeBlockSpan
import org.evsyukov.post.core.markdown.EmphasisSpan
import org.evsyukov.post.core.markdown.SourceStructure
import org.evsyukov.post.core.markdown.lines
import org.evsyukov.post.core.text.TextSegmenter
import java.util.Locale

/** The rank of a candidate break, best first (threads.md, T4). */
enum class BreakRank {
    /** A blank line between paragraphs (or between code lines). */
    PARAGRAPH,

    /** A line break: list items, quote lines, a line break inside a paragraph or between code lines. */
    LINE,

    /** The end of a sentence, as the post's [TextSegmenter] finds it for the post's language. */
    SENTENCE,

    /** After `;`, `:`, `,` (also their full-width forms and `、`) or a dash between words. */
    CLAUSE,

    /** A space between words, or a word boundary in a script written without spaces (Chinese, Japanese, Thai). */
    WORD,
}

/**
 * A place in a piece where one part may end and the next begin. Offsets are UTF-16 char offsets into the
 * piece text. The part before the break ends at [end] (exclusive) and the next part starts at [start];
 * `[end, start)` holds only whitespace and the `>` markers of blank quote lines, which the split drops
 * (threads.md, T10). A break in a script without spaces has `end == start`.
 *
 * @property emphasis bold, italic and strikethrough spans the break crosses (T5), outermost first: the part
 *   before closes them innermost first, the part after reopens them outermost first.
 * @property codeBlock the fenced code block the break falls in, between two of its lines; the part before
 *   closes the fence and the part after reopens it with its info string (T5).
 * @property quoteDepth how many levels of block quote the part after the break must reopen with `> `,
 *   because it starts in the middle of a quoted line or on a lazy continuation line.
 */
data class Break(
    val end: Int,
    val start: Int,
    val rank: BreakRank,
    val emphasis: List<EmphasisSpan> = emptyList(),
    val codeBlock: CodeBlockSpan? = null,
    val quoteDepth: Int = 0,
) {
    init {
        require(end in 0..start) { "Invalid break [$end, $start)" }
        require(quoteDepth >= 0) { "quoteDepth must not be negative" }
    }
}

/**
 * Every candidate break of the Markdown piece [text] (threads.md, T4–T5), sorted by position, with the best
 * rank a place qualifies for. [structure] is `analyze(text)`.
 *
 * Kept out: breaks inside an atomic range (inline code, links, autolinks, images, bare URLs, mentions,
 * hashtags), inside a grapheme cluster, inside a code line or next to a fence line, between a line's quote
 * or list markers and its text, and at the ends of the piece. Sentences and words come from [segmenter]
 * for [locale]; the sentence rules run on the text with the Markdown markers blanked, so `**Done.** Next`
 * ends a sentence before `Next`. Spaces that do not break (U+00A0 and the like) are never a break.
 */
fun findBreaks(text: String, structure: SourceStructure, segmenter: TextSegmenter, locale: Locale): List<Break> {
    if (text.isEmpty()) return emptyList()
    return BreakFinder(text, structure, segmenter, locale).find()
}

private class BreakFinder(
    private val text: String,
    private val structure: SourceStructure,
    private val segmenter: TextSegmenter,
    private val locale: Locale,
) {
    private val n = text.length
    private val graphemeStart = BooleanArray(n + 1)
    private val codeBlockAt = arrayOfNulls<CodeBlockSpan>(n)
    private val atomAt = IntArray(n) { -1 }
    private val gapChar = BooleanArray(n)
    private val lines = lines(text)
    private val lineOf = IntArray(n + 1)
    private val listMarkerEnd = HashMap<Int, Int>()

    init {
        for (boundary in segmenter.graphemeBoundaries(text)) graphemeStart[boundary] = true
        for (block in structure.codeBlocks) {
            for (i in block.range.start until block.range.end) codeBlockAt[i] = block
        }
        structure.atomicRanges.forEachIndexed { index, atom ->
            for (i in atom.start until atom.end) atomAt[i] = index
        }
        for (item in structure.listItems) listMarkerEnd[item.markerRange.start] = item.markerRange.end
        lines.forEachIndexed { index, line ->
            for (i in line.start until line.next) lineOf[i] = index
            val blankQuoteLine = (line.start until line.end).all { text[it].isWhitespace() || isQuoteMarker(it) }
            for (i in line.start until line.end) {
                gapChar[i] = text[i].isWhitespace() || (blankQuoteLine && isQuoteMarker(i))
            }
            for (i in line.end until line.next) gapChar[i] = true
        }
        lineOf[n] = lines.lastIndex
    }

    private fun isQuoteMarker(i: Int): Boolean = text[i] == '>' && codeBlockAt[i] == null

    fun find(): List<Break> {
        val sentence = sentenceBoundaries()
        val result = ArrayList<Break>()
        var i = 0
        while (i < n) {
            if (!gapChar[i]) {
                i++
                continue
            }
            var j = i
            while (j < n && gapChar[j]) j++
            if (i > 0 && j < n) gap(i, j, sentence)?.let { result += it }
            i = j
        }
        result += zeroWidthBreaks(sentence)
        result.sortBy { it.end }
        return result
    }

    /** The break at the gap `[a, b)`, or null when no break may fall there. */
    private fun gap(a: Int, b: Int, sentence: BooleanArray): Break? {
        val newlines = (a until b).count { text[it] == '\n' || (text[it] == '\r' && (it + 1 >= n || text[it + 1] != '\n')) }
        val block = codeBlockAt[a]
        if (newlines == 0) {
            if (block != null || (a until b).none { Character.isWhitespace(text[it]) }) return null
            if (a <= prefixEnd(lines[lineOf[a]])) return null
            val rank = when {
                sentenceAt(a, b, sentence) -> BreakRank.SENTENCE
                isClauseEnd(a) -> BreakRank.CLAUSE
                else -> BreakRank.WORD
            }
            return make(a, b, rank)
        }
        val rank = if (newlines >= 2) BreakRank.PARAGRAPH else BreakRank.LINE
        if (block == null) return make(a, b, rank)
        // Inside a code block only between two code lines; the next part keeps the indentation of its line.
        val content = block.contentRange ?: return null
        if (a <= content.start || b >= content.end) return null
        return make(a, lines[lineOf[b]].start, rank, block)
    }

    private fun make(end: Int, start: Int, rank: BreakRank, block: CodeBlockSpan? = null): Break? {
        if (!graphemeStart[end] || !graphemeStart[start]) return null
        if (cutsAtom(end, start)) return null
        val emphasis = if (block != null) emptyList() else crossingEmphasis(end, start)
        val quoteDepth = if (block != null || text[start] == '>') 0 else quoteDepthAt(start)
        return Break(end, start, rank, emphasis, block, quoteDepth)
    }

    /** Whether a break with the gap `[end, start)` would cut an atomic range. */
    private fun cutsAtom(end: Int, start: Int): Boolean {
        if (end == start) return end > 0 && end < n && atomAt[end - 1] >= 0 && atomAt[end - 1] == atomAt[end]
        return (end until start).any { atomAt[it] >= 0 }
    }

    private fun crossingEmphasis(end: Int, start: Int): List<EmphasisSpan> =
        structure.emphasis
            .filter { it.openRange.end <= end && it.closeRange.start >= start }
            .sortedWith(compareBy<EmphasisSpan>({ it.openRange.start }, { -it.closeRange.end }))

    private fun quoteDepthAt(position: Int): Int =
        structure.quotes.count { it.range.start < position && position < it.range.end }

    /**
     * Where the container prefix of [line] ends: indentation, quote markers and list markers. A gap inside
     * the prefix would separate `> ` or `- ` from the text it introduces.
     */
    private fun prefixEnd(line: org.evsyukov.post.core.markdown.Line): Int {
        var i = line.start
        while (true) {
            while (i < line.end && (text[i] == ' ' || text[i] == '\t')) i++
            if (i >= line.end) return i
            if (isQuoteMarker(i)) {
                i++
                continue
            }
            val markerEnd = listMarkerEnd[i] ?: return i
            i = markerEnd
        }
    }

    /**
     * Sentence boundaries of the text with emphasis, list and quote markers blanked, so markup next to the
     * end of a sentence does not hide it from the segmenter.
     */
    private fun sentenceBoundaries(): BooleanArray {
        val neutral = text.toCharArray()
        fun blank(start: Int, end: Int) {
            for (i in start until end) neutral[i] = ' '
        }
        for (span in structure.emphasis) {
            blank(span.openRange.start, span.openRange.end)
            blank(span.closeRange.start, span.closeRange.end)
        }
        for (item in structure.listItems) blank(item.markerRange.start, item.markerRange.end)
        for (quote in structure.quotes) for (line in quote.lines) blank(line.start, line.start + 1)
        val result = BooleanArray(n + 1)
        for (boundary in segmenter.sentenceBoundaries(String(neutral), locale)) result[boundary] = true
        this.neutral = neutral
        return result
    }

    private var neutral: CharArray = CharArray(0)

    /** Whether a sentence ends at the gap `[a, b)`, widened over the markers blanked next to it. */
    private fun sentenceAt(a: Int, b: Int, sentence: BooleanArray): Boolean {
        var from = a
        while (from > 0 && neutral[from - 1].isWhitespace()) from--
        var to = b
        while (to < n && neutral[to].isWhitespace()) to++
        return (from..to).any { sentence[it] }
    }

    /** Whether the text before the gap at [a] ends a clause: `;`, `:`, `,` or a dash, before closing markers. */
    private fun isClauseEnd(a: Int): Boolean {
        var k = a - 1
        while (k >= 0 && (text[k] == '*' || text[k] == '_' || text[k] == '~')) k--
        if (k < 0) return false
        val c = text[k]
        return c in CLAUSE_PUNCTUATION || c in DASHES || (c == '-' && k > 0 && text[k - 1].isWhitespace())
    }

    /** Word boundaries without a space between letters of scripts written without spaces. */
    private fun zeroWidthBreaks(sentence: BooleanArray): List<Break> {
        if (text.codePoints().noneMatch { isNoSpaceScript(it) }) return emptyList()
        val result = ArrayList<Break>()
        for (x in segmenter.wordBoundaries(text, locale)) {
            if (x <= 0 || x >= n || gapChar[x - 1] || gapChar[x] || codeBlockAt[x] != null) continue
            val before = text.codePointBefore(x)
            val after = text.codePointAt(x)
            if (!isNoSpaceScript(after)) continue
            val rank = when {
                before in CJK_SENTENCE_END -> BreakRank.SENTENCE
                before in CJK_CLAUSE_END -> BreakRank.CLAUSE
                !isNoSpaceScript(before) -> continue
                sentence[x] -> BreakRank.SENTENCE
                else -> BreakRank.WORD
            }
            make(x, x, rank)?.let { result += it }
        }
        return result
    }

    private companion object {
        val CLAUSE_PUNCTUATION = setOf(',', ';', ':', '，', '；', '：', '、')
        val DASHES = setOf('—', '–', '―')
        val CJK_SENTENCE_END = setOf('。'.code, '！'.code, '？'.code)
        val CJK_CLAUSE_END = setOf('，'.code, '、'.code, '；'.code, '：'.code)
        val NO_SPACE_SCRIPTS = setOf(
            Character.UnicodeScript.HAN,
            Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.THAI,
            Character.UnicodeScript.LAO,
            Character.UnicodeScript.KHMER,
            Character.UnicodeScript.MYANMAR,
        )

        fun isNoSpaceScript(cp: Int): Boolean =
            Character.isLetter(cp) && Character.UnicodeScript.of(cp) in NO_SPACE_SCRIPTS
    }
}
