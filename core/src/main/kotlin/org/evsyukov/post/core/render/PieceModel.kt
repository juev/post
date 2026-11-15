package org.evsyukov.post.core.render

import org.evsyukov.post.core.markdown.AtomicRange
import org.evsyukov.post.core.markdown.EmphasisSpan
import org.evsyukov.post.core.markdown.SourceStructure
import org.evsyukov.post.core.markdown.analyze
import org.evsyukov.post.core.markdown.lines
import org.evsyukov.post.core.text.TextSegmenter
import org.evsyukov.post.core.text.utf8Length
import org.evsyukov.post.core.thread.Break
import org.evsyukov.post.core.thread.BreakRank
import org.evsyukov.post.core.thread.PartMeasure
import org.evsyukov.post.core.thread.Piece
import org.evsyukov.post.core.thread.SplitPlan
import org.evsyukov.post.core.thread.ThreadSplitter
import org.evsyukov.post.core.thread.fencePrefix
import org.evsyukov.post.core.thread.findBreaks
import org.evsyukov.post.core.thread.partFragment
import java.util.Locale

/**
 * One non-empty piece of the draft prepared for splitting for one account: its Markdown structure,
 * candidate breaks, and an additive estimate of the converted length.
 *
 * The estimate gives every source character a weight in the units of the account's limit (graphemes, code
 * points, URL weights, bytes), as the character reaches the converted text: markers the format removes
 * weigh 0, an atomic range (code, link, URL, mention, hashtag) weighs what it converts to on its own, HTML
 * tags and escapes add their length. Prefix sums make the length of any part a subtraction, plus what the
 * breaks at its ends reopen and close. The estimate only steers the search: a piece that fits is checked
 * exactly first (T2), and every part is then converted and counted exactly ([exact]).
 */
internal class PieceModel(
    val piece: Piece,
    source: String,
    private val profile: TextProfile,
    private val segmenter: TextSegmenter,
    private val converter: PartConverter,
    locale: Locale,
) {
    val text: String = source.substring(piece.start, piece.end)
    private val structure: SourceStructure = analyze(text)
    val breaks: List<Break> = findBreaks(text, structure, segmenter, locale)
    val nodeCount: Int = breaks.size + 2
    val last: Int = nodeCount - 1

    private val units: IntArray
    private val bytes: IntArray?
    private val openUnits = IntArray(nodeCount)
    private val closeUnits = IntArray(nodeCount)
    private val exactCache = HashMap<Triple<Int, Int, String?>, ConvertedPart>()

    init {
        val weights = Weights(text, structure, profile, segmenter, converter)
        units = prefixSums(weights.units)
        bytes = weights.bytes?.let { prefixSums(it) }
        breaks.forEachIndexed { index, cut ->
            openUnits[index + 1] = weights.openOverhead(cut)
            closeUnits[index + 1] = weights.closeOverhead(cut)
        }
    }

    fun breakAt(node: Int): Break? = if (node in 1 until last) breaks[node - 1] else null

    /** Where the part that starts at [node] starts in the piece text. */
    fun startOf(node: Int): Int = when (node) {
        0 -> 0
        last -> text.length
        else -> breaks[node - 1].start
    }

    /** Where the part that ends at [node] ends in the piece text. */
    fun endOf(node: Int): Int = when (node) {
        0 -> 0
        last -> text.length
        else -> breaks[node - 1].end
    }

    /** The part `(from, to)` converted, numbered with [label] and counted exactly. */
    fun exact(from: Int, to: Int, label: String?): ConvertedPart = exactCache.getOrPut(Triple(from, to, label)) {
        val fragment = partFragment(text, structure, breakAt(from), breakAt(to), closeCodeBlock = label != null)
        converter.part(fragment, label)
    }

    private fun exactFits(from: Int, to: Int, label: String?): Boolean = exact(from, to, label).fits(profile)

    /**
     * The parts of this piece when every part may take up to `limit - spoiler - reserve - slack` estimated
     * units (and the same for bytes), or by exact checks only when [exactOnly].
     */
    fun plan(digits: Int, spoilerUnits: Int, slack: Int, byteSlack: Int, exactOnly: Boolean, padding: Int = 0): SplitPlan {
        val label = widestLabel(digits)?.let { it + " ".repeat(padding) }
        // T2: a piece that fits as it is stays one part, whatever the estimate says.
        if (exactFits(0, last, label)) return SplitPlan.Parts(listOf(last))
        if (exactOnly) return exactGreedy(label)
        val reserve = numberReserve(profile.format, digits)
        val budget = profile.maxCharacters - spoilerUnits - reserve - slack
        val byteBudget = profile.maxBytes?.let { it - reserve - byteSlack }
        return ThreadSplitter.split(breaks.map { it.rank }, Measure(budget, byteBudget), profile.maxCharacters)
    }

    /**
     * The greedy split of T6 by exact checks: each part ends at the farthest node that fits once converted
     * and counted, found by bisection over the nodes the estimate allows with a wide margin.
     */
    private fun exactGreedy(label: String?): SplitPlan {
        val ends = ArrayList<Int>()
        var from = 0
        val margin = 2 * profile.maxCharacters + 64
        while (from < last) {
            var high = from + 1
            while (high < last && units[endOf(high + 1)] - units[startOf(from)] <= margin) high++
            var best = -1
            if (exactFits(from, from + 1, label)) {
                var low = from + 1
                var top = high
                while (low < top) {
                    val middle = (low + top + 1) / 2
                    if (exactFits(from, middle, label)) low = middle else top = middle - 1
                }
                best = low
            } else {
                // Closing markers can make a short part longer than a longer one; look a little further.
                for (to in from + 2..minOf(high, from + 4)) {
                    if (exactFits(from, to, label)) best = to
                }
            }
            if (best < 0) return SplitPlan.TooLong(from)
            ends += best
            from = best
        }
        return SplitPlan.Parts(ends)
    }

    private inner class Measure(private val budget: Int, private val byteBudget: Int?) : PartMeasure {
        override val nodeCount: Int get() = this@PieceModel.nodeCount

        private fun raw(from: Int, to: Int): Int = units[endOf(to)] - units[startOf(from)]

        private fun rawBytes(from: Int, to: Int): Int {
            val sums = checkNotNull(bytes)
            return sums[endOf(to)] - sums[startOf(from)]
        }

        override fun length(from: Int, to: Int): Int = raw(from, to) + openUnits[from] + closeUnits[to]

        override fun fits(from: Int, to: Int): Boolean =
            length(from, to) <= budget &&
                (byteBudget == null || rawBytes(from, to) + openUnits[from] + closeUnits[to] <= byteBudget)

        override fun exhausted(from: Int, to: Int): Boolean =
            raw(from, to) > budget || (byteBudget != null && rawBytes(from, to) > byteBudget)
    }

    private companion object {
        fun prefixSums(weights: IntArray): IntArray {
            val sums = IntArray(weights.size + 1)
            for (i in weights.indices) sums[i + 1] = sums[i] + weights[i]
            return sums
        }
    }
}

/**
 * The per-character weights of a piece and the overheads of its breaks; see [PieceModel]. The overheads
 * of reopened and closed markup are ASCII, so the same numbers serve as bytes.
 */
private class Weights(
    private val text: String,
    private val structure: SourceStructure,
    private val profile: TextProfile,
    private val segmenter: TextSegmenter,
    private val converter: PartConverter,
) {
    private val n = text.length
    private val format = profile.format
    val units = IntArray(n)
    val bytes: IntArray? = if (profile.maxBytes != null) IntArray(n) else null
    private val inCode = BooleanArray(n)

    init {
        baseWeights()
        for (block in structure.codeBlocks) for (i in block.range.start until block.range.end) inCode[i] = true
        if (format != TextFormat.MARKDOWN) escapes()
        if (format == TextFormat.HTML) htmlStructure()
        emphasis()
        codeFences()
        // After the emphasis: markup inside a link's text is part of the link's own weight.
        for (atom in structure.atomicRanges) atom(atom)
    }

    private fun baseWeights() {
        if (profile.counting.usesGraphemes()) {
            val boundaries = segmenter.graphemeBoundaries(text)
            for (k in 0 until boundaries.size - 1) units[boundaries[k]] = 1
        } else {
            for (i in 0 until n) units[i] = if (text[i].isLowSurrogate() && i > 0 && text[i - 1].isHighSurrogate()) 0 else 1
        }
        val bytes = bytes ?: return
        for (i in 0 until n) {
            val c = text[i]
            bytes[i] = when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() && i + 1 < n && text[i + 1].isLowSurrogate() -> 4
                c.isLowSurrogate() && i > 0 && text[i - 1].isHighSurrogate() -> 0
                c.isSurrogate() -> 1
                else -> 3
            }
        }
    }

    private fun set(i: Int, unitWeight: Int, byteWeight: Int = unitWeight) {
        units[i] = unitWeight
        bytes?.set(i, byteWeight)
    }

    private fun zero(start: Int, end: Int) {
        for (i in start until end) set(i, 0)
    }

    /** A backslash before ASCII punctuation is gone after conversion; HTML escapes `& < > "`. */
    private fun escapes() {
        for (i in 0 until n) {
            if (inCode[i]) continue
            val c = text[i]
            if (c == '\\' && i + 1 < n && text[i + 1].code < 0x80 && isAsciiPunctuation(text[i + 1])) {
                set(i, 0)
            } else if (format == TextFormat.HTML) {
                when (c) {
                    '&' -> set(i, 5)
                    '<', '>' -> set(i, 4)
                    '"' -> set(i, 6)
                }
            }
        }
    }

    /**
     * HTML block tags: `<br>` for a line break inside a block, `<p>…</p>` per paragraph (the blank lines
     * between blocks become nothing), quote and list item tags.
     */
    private fun htmlStructure() {
        val lines = lines(text)
        val blank = lines.map { line -> (line.start until line.end).all { text[it].isWhitespace() || text[it] == '>' } }
        lines.forEachIndexed { k, line ->
            val terminator = if (k + 1 < lines.size && !blank[k] && !blank[k + 1]) 4 else 0
            for (i in line.end until line.next) {
                if (!inCode[i]) set(i, if (i == line.end) terminator else 0)
            }
            if (!blank[k] && (k == 0 || blank[k - 1])) {
                val first = (line.start until line.end).firstOrNull { !text[it].isWhitespace() && !inCode[it] }
                if (first != null) addAt(first, 7)
            }
        }
        for (quote in structure.quotes) {
            for (line in quote.lines) {
                zero(line.start, minOf(line.start + 1, n))
                if (line.start + 1 < n && text[line.start + 1] == ' ') zero(line.start + 1, line.start + 2)
            }
            addAt(quote.range.start, 12)
            addAt(maxOf(quote.range.start, quote.range.end - 1), 13)
        }
        for (item in structure.listItems) {
            zero(item.markerRange.start, item.markerRange.end)
            addAt(item.markerRange.start, 9)
        }
    }

    private fun addAt(i: Int, weight: Int) {
        if (i !in 0 until n) return
        units[i] += weight
        bytes?.let { it[i] += weight }
    }

    /** An atomic range weighs what it becomes on its own, on its first character. */
    private fun atom(atom: AtomicRange) {
        val source = text.substring(atom.start, atom.end)
        val asTyped = format != TextFormat.HTML &&
            (atom.kind == AtomicRange.Kind.URL || atom.kind == AtomicRange.Kind.MENTION || atom.kind == AtomicRange.Kind.HASHTAG)
        var converted = if (asTyped) source else converter.convert(source).trim()
        if (format == TextFormat.HTML) converted = converted.removePrefix("<p>").removeSuffix("</p>")
        zero(atom.start, atom.end)
        set(atom.start, converter.units(converted), utf8Length(converted))
    }

    private fun emphasis() {
        for (span in structure.emphasis) {
            when (format) {
                TextFormat.MARKDOWN -> Unit
                TextFormat.PLAIN, TextFormat.BLUESKY -> {
                    zero(span.openRange.start, span.openRange.end)
                    zero(span.closeRange.start, span.closeRange.end)
                }
                TextFormat.HTML -> {
                    zero(span.openRange.start, span.openRange.end)
                    zero(span.closeRange.start, span.closeRange.end)
                    set(span.openRange.start, openTag(span))
                    set(span.closeRange.start, openTag(span) + 1)
                }
            }
        }
    }

    /** Fence lines go in plain text; HTML has `<pre><code>` and `</code></pre>` instead. */
    private fun codeFences() {
        if (format == TextFormat.MARKDOWN) return
        for (block in structure.codeBlocks) {
            val content = block.contentRange
            val closing = block.closingFenceRange
            zero(block.openingFenceRange.start, content?.start ?: closing?.start ?: block.range.end)
            if (closing != null) zero(content?.end ?: closing.start, closing.end)
            if (format == TextFormat.HTML) {
                set(block.openingFenceRange.start, 11)
                if (closing != null) set(closing.start, 13) else addAt(block.range.end - 1, 13)
            }
        }
    }

    /** The length the part after [cut] gains at its start from what [cut] reopens. */
    fun openOverhead(cut: Break): Int = when (format) {
        TextFormat.MARKDOWN -> converter.units(
            (cut.codeBlock?.let { fencePrefix(text, it) + it.openingFence + "\n" } ?: "") +
                "> ".repeat(cut.quoteDepth) + cut.emphasis.joinToString("") { it.open },
        )
        TextFormat.PLAIN, TextFormat.BLUESKY -> 2 * cut.quoteDepth
        TextFormat.HTML -> cut.emphasis.sumOf { openTag(it) } + paragraphTag(cut, 11, 3) + 15 * cut.quoteDepth +
            (if (inListItem(cut.start)) 8 else 0)
    }

    /** The length the part before [cut] gains at its end from what [cut] closes. */
    fun closeOverhead(cut: Break): Int = when (format) {
        TextFormat.MARKDOWN -> converter.units(
            cut.emphasis.joinToString("") { it.close } +
                (cut.codeBlock?.let { "\n" + fencePrefix(text, it) + it.closingFence } ?: ""),
        )
        TextFormat.PLAIN, TextFormat.BLUESKY -> 0
        TextFormat.HTML -> cut.emphasis.sumOf { openTag(it) + 1 } + paragraphTag(cut, 13, 4) +
            17 * structure.quotes.count { it.range.start < cut.end && cut.end < it.range.end } +
            (if (inListItem(cut.end)) 10 else 0)
    }

    /**
     * The tag a cut block needs: [code] for a code block; [paragraph] for a paragraph cut in its middle; 0
     * at a blank line, where the next paragraph brings its own tags in the weights.
     */
    private fun paragraphTag(cut: Break, code: Int, paragraph: Int): Int = when {
        cut.codeBlock != null -> code
        cut.rank == BreakRank.PARAGRAPH -> 0
        else -> paragraph
    }

    private fun inListItem(position: Int): Boolean = structure.listItems.any { it.range.start < position && position < it.range.end }

    private fun openTag(span: EmphasisSpan): Int = when (span.kind) {
        EmphasisSpan.Kind.BOLD -> "<strong>".length
        EmphasisSpan.Kind.ITALIC -> "<em>".length
        EmphasisSpan.Kind.STRIKE -> "<del>".length
    }

    private fun isAsciiPunctuation(c: Char): Boolean = c in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
}
