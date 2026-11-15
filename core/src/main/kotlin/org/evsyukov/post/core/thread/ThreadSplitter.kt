package org.evsyukov.post.core.thread

/**
 * How long a part would be, for the splitter. The splitter does not convert or count text itself: the
 * caller decides what a part becomes for the account (formatting.md F8–F11) and how its server counts it
 * (networks.md N3, N16), so the splitter can be tested with any measure.
 *
 * Parts are named by nodes: node `0` is the start of the piece, node `k` for `1 ≤ k < nodeCount - 1` is the
 * `k`-th candidate break, and node `nodeCount - 1` is the end of the piece. The part `(from, to)` runs from
 * node `from` to node `to`, with `from < to`.
 */
interface PartMeasure {
    /** The number of nodes: the candidate breaks plus the two ends of the piece. */
    val nodeCount: Int

    /** The estimated length of the part `(from, to)` in the units of the limit; it drives the balance (T7). */
    fun length(from: Int, to: Int): Int

    /** Whether the part `(from, to)` fits the limits. */
    fun fits(from: Int, to: Int): Boolean

    /**
     * Whether the part `(from, to)` and every part `(from, later)` are too long, so the search from [from]
     * can stop. It must not be true for a part that [fits].
     */
    fun exhausted(from: Int, to: Int): Boolean
}

/** A split of one piece into parts, or the reason there is none. */
sealed interface SplitPlan {
    /** The parts: part `i` runs from node `ends[i - 1]` (node `0` for the first) to node `ends[i]`. */
    data class Parts(val ends: List<Int>) : SplitPlan {
        val count: Int get() = ends.size
    }

    /** No part that starts at node [from] fits, not even up to the next break (T9): the conflict of T17. */
    data class TooLong(val from: Int) : SplitPlan
}

/**
 * Splits a piece into thread parts (threads.md, T6–T9).
 *
 * 1. The number of parts is the smallest that fits, found greedily: each part takes as much text as fits,
 *    up to the farthest candidate break (T6). A part that fits nowhere is [SplitPlan.TooLong] (T9).
 * 2. With that number fixed, dynamic programming over the candidate breaks chooses the split with the
 *    lowest cost (T7). The cost of a split adds, for each part, the square of the difference between its
 *    length and the average part length, and for each break a penalty by its rank: `avg²` times
 *    [RANK_PENALTY] (0 for a paragraph, 0.02 for a line, 0.05 for a sentence, 0.3 for a clause, 4 for a
 *    word). For two parts, a sentence end wins over a paragraph end only when the paragraph end leaves
 *    each part about a sixth of the average off, a clause wins over a sentence end only when that is a
 *    third or more off, and a word break wins over a clause only when the clause leaves the parts off by
 *    more than a whole average part, which the limits rarely allow. So a sentence longer than the limit
 *    breaks at a clause, and between words only when no clause gives a split (T9).
 * 3. The last part must hold at least a third of the average part length (T8). When no split of N parts
 *    meets it, N + 1 parts are tried, up to [MAX_EXTRA_PARTS] more; when none does, the balanced split of
 *    N parts without the rule is taken, because a short tail is better than a lost word.
 *
 * Where breaks are denser than about [BUCKETS_PER_LIMIT] per limit, the balance keeps the best-ranked
 * break of each stretch of `limit / BUCKETS_PER_LIMIT`, so the work stays bounded for long texts with
 * high limits. The greedy count always uses every break.
 */
object ThreadSplitter {

    /** The rank penalty of T7, in units of the squared average part length, by [BreakRank.ordinal]. */
    val RANK_PENALTY: List<Double> = listOf(0.0, 0.02, 0.05, 0.3, 4.0)

    const val MAX_EXTRA_PARTS = 3
    const val BUCKETS_PER_LIMIT = 48

    /**
     * The split of the piece whose candidate breaks have [ranks] (one per break, in order) under
     * [measure], with at most [limit] units in a part (used only to thin dense breaks).
     */
    fun split(ranks: List<BreakRank>, measure: PartMeasure, limit: Int): SplitPlan {
        require(measure.nodeCount == ranks.size + 2) { "one rank per break" }
        val greedy = greedy(measure)
        if (greedy !is SplitPlan.Parts || greedy.count == 1) return greedy
        val nodes = thin(ranks, measure, limit, greedy.ends)
        for (parts in greedy.count..greedy.count + MAX_EXTRA_PARTS) {
            balance(nodes, ranks, measure, parts, tailRule = true)?.let { return SplitPlan.Parts(it) }
        }
        return balance(nodes, ranks, measure, greedy.count, tailRule = false)?.let { SplitPlan.Parts(it) } ?: greedy
    }

    /** The greedy split of T6: every part ends at the farthest node that still fits. */
    fun greedy(measure: PartMeasure): SplitPlan {
        val last = measure.nodeCount - 1
        val ends = ArrayList<Int>()
        var from = 0
        while (from < last) {
            var best = -1
            var to = from + 1
            while (to <= last && !measure.exhausted(from, to)) {
                if (measure.fits(from, to)) best = to
                to++
            }
            if (best < 0) return SplitPlan.TooLong(from)
            ends += best
            from = best
        }
        return SplitPlan.Parts(ends)
    }

    /**
     * The cheapest split of the [nodes] (sorted node indices, with both ends) into exactly [parts] parts
     * by the cost of T7, or null when there is none; with [tailRule] the last part holds at least a third
     * of the average (T8).
     */
    fun balance(nodes: IntArray, ranks: List<BreakRank>, measure: PartMeasure, parts: Int, tailRule: Boolean): List<Int>? {
        val m = nodes.size
        val last = nodes[m - 1]
        val average = measure.length(0, last).toDouble() / parts
        val averageSquared = average * average
        // The parts that fit, from every node: targets and their lengths.
        val targets = arrayOfNulls<IntArray>(m)
        val lengths = arrayOfNulls<IntArray>(m)
        for (a in 0 until m - 1) {
            val to = ArrayList<Int>()
            val length = ArrayList<Int>()
            for (b in a + 1 until m) {
                if (measure.exhausted(nodes[a], nodes[b])) break
                if (measure.fits(nodes[a], nodes[b])) {
                    to += b
                    length += measure.length(nodes[a], nodes[b])
                }
            }
            targets[a] = to.toIntArray()
            lengths[a] = length.toIntArray()
        }
        var cost = DoubleArray(m) { Double.POSITIVE_INFINITY }
        cost[0] = 0.0
        val previous = Array(parts) { IntArray(m) { -1 } }
        for (k in 1..parts) {
            val next = DoubleArray(m) { Double.POSITIVE_INFINITY }
            val final = k == parts
            for (a in 0 until m - 1) {
                if (cost[a] == Double.POSITIVE_INFINITY) continue
                val to = targets[a]!!
                val length = lengths[a]!!
                for (t in to.indices) {
                    val b = to[t]
                    if ((b == m - 1) != final) continue
                    val deviation = length[t] - average
                    if (final && tailRule && parts >= 2 && length[t] * 3.0 < average) continue
                    val penalty = if (final) 0.0 else RANK_PENALTY[ranks[nodes[b] - 1].ordinal] * averageSquared
                    val total = cost[a] + deviation * deviation + penalty
                    if (total < next[b]) {
                        next[b] = total
                        previous[k - 1][b] = a
                    }
                }
            }
            cost = next
        }
        if (cost[m - 1] == Double.POSITIVE_INFINITY) return null
        val ends = IntArray(parts)
        var b = m - 1
        for (k in parts downTo 1) {
            ends[k - 1] = nodes[b]
            b = previous[k - 1][b]
        }
        return ends.toList()
    }

    /**
     * The nodes the balance looks at: both ends, every break of [keep], and of the other breaks the best
     * ranked one (the last of equals) in each stretch of `limit / BUCKETS_PER_LIMIT` estimated units.
     */
    private fun thin(ranks: List<BreakRank>, measure: PartMeasure, limit: Int, keep: List<Int>): IntArray {
        val last = measure.nodeCount - 1
        val width = maxOf(1, limit / BUCKETS_PER_LIMIT)
        val chosen = sortedSetOf(0, last)
        chosen += keep
        var bucket = -1
        var best = -1
        for (node in 1 until last) {
            val position = measure.length(0, node) / width
            if (position != bucket) {
                if (best > 0) chosen += best
                bucket = position
                best = node
            } else if (ranks[node - 1] <= ranks[best - 1]) {
                best = node
            }
        }
        if (best > 0) chosen += best
        return chosen.toIntArray()
    }
}
