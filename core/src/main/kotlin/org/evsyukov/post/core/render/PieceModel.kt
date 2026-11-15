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
import org.evsyukov.post.core.thread.PieceMarkup
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
 * breaks at its ends reopen and close. The estimate only steers the balance: a piece that fits is checked
 * exactly first (T2), the number of parts comes from a greedy split by exact counts (T6), and every part the
 * plan returns has been converted and counted exactly ([exact], [plan]).
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
    private val markup: PieceMarkup = PieceMarkup.of(text, structure)
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

    /** The estimated length of the part `(from, to)`, without the content warning and the number. */
    fun estimate(from: Int, to: Int): Int = units[endOf(to)] - units[startOf(from)] + openUnits[from] + closeUnits[to]

    /** The part `(from, to)` converted, numbered with [label] and counted exactly. */
    fun exact(from: Int, to: Int, label: String?): ConvertedPart = exactCache.getOrPut(Triple(from, to, label)) {
        val fragment = partFragment(text, structure, breakAt(from), breakAt(to), closeCodeBlock = label != null, markup)
        converter.part(fragment, label)
    }

    private fun exactFits(from: Int, to: Int, label: String?): Boolean = exact(from, to, label).fits(profile)

    /**
     * Whether the part `(from, to)` with [label] would fit without the content warning, which counts
     * [spoilerUnits]: then the warning, not the text, leaves no room.
     */
    fun fitsWithoutSpoiler(from: Int, to: Int, label: String?, spoilerUnits: Int): Boolean {
        val part = exact(from, to, label)
        return part.overshoot(profile) - spoilerUnits <= 0 && part.byteOvershoot(profile) <= 0
    }

    /**
     * The parts of this piece when each ends with a number of [digits] digits (none for 0) and the content
     * warning counts [spoilerUnits]. Every part of the result fits once converted and counted with the widest
     * number, padded with [padding] spaces.
     *
     * 1. A piece that fits as it is stays one part (T2).
     * 2. The exact greedy split ([exactGreedy]) gives the smallest number of parts (T6), or the run that fits
     *    nowhere (T9).
     * 3. The splitter balances the parts (T7, T8) over the estimate, guided by exact checks
     *    ([GuidedMeasure]): the parts of the greedy split are known to fit, the estimate budget widens so it
     *    admits them, and every split it returns is converted and counted; a part over the limit is ruled out
     *    and the splitter runs again, up to [MAX_GUIDED_ROUNDS] times, then the greedy split stands.
     * 4. More parts than the greedy split are kept only when the greedy split breaks the tail rule (T8);
     *    otherwise the best balanced split of the greedy count is taken, or the greedy split itself.
     */
    fun plan(digits: Int, spoilerUnits: Int, padding: Int = 0): SplitPlan {
        val label = widestLabel(digits)?.let { it + " ".repeat(padding) }
        if (exactFits(0, last, label)) return SplitPlan.Parts(listOf(last))
        val greedy = exactGreedy(label)
        if (greedy !is SplitPlan.Parts) return greedy
        val reserve = numberReserve(profile.format, digits)
        val measure = GuidedMeasure(
            label,
            profile.maxCharacters - spoilerUnits - reserve,
            profile.maxBytes?.let { it - reserve },
            greedy.ends,
        )
        val ranks = breaks.map { it.rank }
        var chosen: List<Int> = greedy.ends
        for (round in 1..MAX_GUIDED_ROUNDS) {
            val plan = ThreadSplitter.split(ranks, measure, profile.maxCharacters, keep = greedy.ends)
            if (plan !is SplitPlan.Parts) break
            if (measure.verify(plan.ends)) {
                chosen = plan.ends
                break
            }
        }
        if (chosen.size > greedy.count && meetsTailRule(greedy.ends, spoilerUnits)) {
            chosen = greedy.ends
            for (round in 1..MAX_GUIDED_ROUNDS) {
                val balanced = ThreadSplitter.balanced(ranks, measure, profile.maxCharacters, greedy.count, keep = greedy.ends) ?: break
                if (measure.verify(balanced)) {
                    if (meetsTailRule(balanced, spoilerUnits)) chosen = balanced
                    break
                }
            }
        }
        return SplitPlan.Parts(chosen)
    }

    /** Whether the last of the parts ending at [ends] holds a third of their average length (T8), by exact counts. */
    private fun meetsTailRule(ends: List<Int>, spoilerUnits: Int): Boolean {
        if (ends.size < 2) return true
        var from = 0
        val lengths = ends.map { to -> (exact(from, to, null).count - spoilerUnits).also { from = to } }
        return lengths.last() * 3.0 >= lengths.sum().toDouble() / lengths.size
    }

    /**
     * The greedy split of T6 by exact checks: each part ends at the farthest node that fits once converted
     * and counted with [label], found by bisection over the nodes the estimate allows with a wide margin.
     */
    fun exactGreedy(label: String?): SplitPlan {
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

    /**
     * The estimate of [estimate] for the splitter, corrected by exact checks with [label]. A part checked
     * exactly fits or not by that check. Past a part found too long, no longer part from the same node fits.
     * Any other part fits when its estimate is within the budget ([budget] units and [byteBudget] bytes for
     * the text alone), widened so that the estimate admits every part of [known], a split known to fit.
     */
    private inner class GuidedMeasure(
        private val label: String?,
        budget: Int,
        byteBudget: Int?,
        known: List<Int>,
    ) : PartMeasure {
        override val nodeCount: Int get() = this@PieceModel.nodeCount

        private val verdicts = arrayOfNulls<HashMap<Int, Boolean>>(nodeCount)
        private val reach = IntArray(nodeCount) { -1 }
        private val cap = IntArray(nodeCount) { Int.MAX_VALUE }
        private val budget: Int
        private val byteBudget: Int?

        init {
            var widen = 0
            var widenBytes = 0
            var from = 0
            for (to in known) {
                record(from, to, true)
                widen = maxOf(widen, estimate(from, to) - budget)
                if (byteBudget != null) widenBytes = maxOf(widenBytes, rawBytes(from, to) + openUnits[from] + closeUnits[to] - byteBudget)
                from = to
            }
            this.budget = budget + widen
            this.byteBudget = byteBudget?.let { it + widenBytes }
        }

        private fun raw(from: Int, to: Int): Int = units[endOf(to)] - units[startOf(from)]

        private fun rawBytes(from: Int, to: Int): Int {
            val sums = checkNotNull(bytes)
            return sums[endOf(to)] - sums[startOf(from)]
        }

        private fun record(from: Int, to: Int, fits: Boolean) {
            (verdicts[from] ?: HashMap<Int, Boolean>().also { verdicts[from] = it })[to] = fits
            if (fits) reach[from] = maxOf(reach[from], to) else cap[from] = minOf(cap[from], to)
        }

        override fun length(from: Int, to: Int): Int = estimate(from, to)

        override fun fits(from: Int, to: Int): Boolean {
            verdicts[from]?.get(to)?.let { return it }
            if (to >= cap[from]) return false
            return estimate(from, to) <= budget &&
                (byteBudget == null || rawBytes(from, to) + openUnits[from] + closeUnits[to] <= byteBudget)
        }

        override fun exhausted(from: Int, to: Int): Boolean =
            to > reach[from] && (to >= cap[from] || raw(from, to) > budget || (byteBudget != null && rawBytes(from, to) > byteBudget))

        /** Checks every part ending at [ends] exactly; whether all fit. */
        fun verify(ends: List<Int>): Boolean {
            var all = true
            var from = 0
            for (to in ends) {
                val known = verdicts[from]?.get(to)
                val fits = known ?: exactFits(from, to, label).also { record(from, to, it) }
                if (!fits) all = false
                from = to
            }
            return all
        }
    }

    private companion object {
        /** Runs of the splitter checked exactly before the greedy split stands. */
        const val MAX_GUIDED_ROUNDS = 24

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
        } else if (profile.counting == Counting.FULL) {
            units.fill(1) // UTF-16 units
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
        // A part that starts inside a list item continues as a paragraph: the item's tags stay in the part before.
        TextFormat.HTML -> cut.emphasis.sumOf { openTag(it) } + paragraphTag(cut, 11, 3) + 15 * cut.quoteDepth
    }

    /** The length the part before [cut] gains at its end from what [cut] closes. */
    fun closeOverhead(cut: Break): Int = when (format) {
        TextFormat.MARKDOWN -> converter.units(
            cut.emphasis.joinToString("") { it.close } +
                (cut.codeBlock?.let { "\n" + fencePrefix(text, it) + it.closingFence } ?: ""),
        )
        TextFormat.PLAIN, TextFormat.BLUESKY -> 0
        // The closing tags of a list item cut here are in the weights of its marker.
        TextFormat.HTML -> cut.emphasis.sumOf { openTag(it) + 1 } + paragraphTag(cut, 13, 4) +
            17 * structure.quotes.count { it.range.start < cut.end && cut.end < it.range.end }
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

    private fun openTag(span: EmphasisSpan): Int = when (span.kind) {
        EmphasisSpan.Kind.BOLD -> "<strong>".length
        EmphasisSpan.Kind.ITALIC -> "<em>".length
        EmphasisSpan.Kind.STRIKE -> "<del>".length
    }

    private fun isAsciiPunctuation(c: Char): Boolean = c in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
}
