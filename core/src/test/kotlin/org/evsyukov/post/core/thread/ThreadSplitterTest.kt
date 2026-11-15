package org.evsyukov.post.core.thread

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.thread.BreakRank.CLAUSE
import org.evsyukov.post.core.thread.BreakRank.LINE
import org.evsyukov.post.core.thread.BreakRank.PARAGRAPH
import org.evsyukov.post.core.thread.BreakRank.SENTENCE
import org.evsyukov.post.core.thread.BreakRank.WORD
import org.junit.Test

/** T6–T9: the splitter on its own, with a measure that adds segment lengths. */
class ThreadSplitterTest {

    /** Segments between consecutive nodes; a part is the sum of its segments, plus [overhead] per inner break. */
    private class Segments(private val lengths: List<Int>, private val limit: Int) : PartMeasure {
        override val nodeCount: Int = lengths.size + 1

        override fun length(from: Int, to: Int): Int = lengths.subList(from, to).sum()

        override fun fits(from: Int, to: Int): Boolean = length(from, to) <= limit

        override fun exhausted(from: Int, to: Int): Boolean = length(from, to) > limit
    }

    private fun split(lengths: List<Int>, ranks: List<BreakRank>, limit: Int): SplitPlan =
        ThreadSplitter.split(ranks, Segments(lengths, limit), limit)

    private fun ends(plan: SplitPlan) = (plan as SplitPlan.Parts).ends

    @Test
    fun `T6 the greedy count is the smallest that fits`() {
        val measure = Segments(List(10) { 10 }, limit = 35)
        assertThat(ends(ThreadSplitter.greedy(measure))).containsExactly(3, 6, 9, 10).inOrder()
    }

    @Test
    fun `T2 a piece that fits is one part`() {
        assertThat(ends(split(listOf(10, 10, 10), listOf(WORD, WORD), limit = 30))).containsExactly(3)
    }

    @Test
    fun `T9 a segment that fits nowhere is too long`() {
        assertThat(split(listOf(10, 50, 10), listOf(WORD, WORD), limit = 40)).isEqualTo(SplitPlan.TooLong(1))
    }

    @Test
    fun `T7 a paragraph break near the middle wins over a sentence end in the middle`() {
        // 45 | 55 at a paragraph against 50 | 50 at a sentence end.
        val plan = split(listOf(45, 5, 50), listOf(PARAGRAPH, SENTENCE), limit = 60)
        assertThat(ends(plan)).containsExactly(1, 3).inOrder()
    }

    @Test
    fun `T7 a slightly worse break that evens the parts beats a good break in an uneven place`() {
        // 20 | 80 at a paragraph against 50 | 50 at a sentence end.
        val plan = split(listOf(20, 30, 50), listOf(PARAGRAPH, SENTENCE), limit = 80)
        assertThat(ends(plan)).containsExactly(2, 3).inOrder()
    }

    @Test
    fun `T7 balancing evens many parts`() {
        val plan = split(List(20) { 10 }, List(19) { WORD }, limit = 70)
        // 200 units need 3 parts; balanced they are 70, 70, 60 or better, not 70, 70, 60 by accident only.
        val parts = ends(plan)
        assertThat(parts).hasSize(3)
        val sizes = (listOf(0) + parts).zipWithNext { a, b -> (b - a) * 10 }
        assertThat(sizes.max() - sizes.min()).isAtMost(10)
    }

    @Test
    fun `T9 a long sentence breaks at a clause before a word`() {
        // One sentence of 150: a comma at 60, words every 10.
        val lengths = List(15) { 10 }
        val ranks = List(14) { if (it == 5) CLAUSE else WORD }
        assertThat(ends(split(lengths, ranks, limit = 100))).containsExactly(6, 15).inOrder()
    }

    @Test
    fun `T8 the last part holds at least a third of the average`() {
        // Greedily 95 | 95 | 10; the balance moves a break so the tail is not 10.
        val lengths = listOf(60, 35, 60, 35, 10)
        val parts = ends(split(lengths, List(4) { WORD }, limit = 100))
        assertThat(parts).hasSize(3)
        val tail = lengths.subList(parts[1], 5).sum()
        assertThat(tail * 3.0).isAtLeast(lengths.sum() / 3.0)
    }

    @Test
    fun `T8 a paragraph end that leaves a short tail loses to a word break`() {
        // 85 | 15 at a paragraph costs less than 50 | 50 at a word, but 15 is under a third of the average 50.
        val plan = split(listOf(50, 35, 15), listOf(WORD, PARAGRAPH), limit = 90)
        assertThat(ends(plan)).containsExactly(1, 3).inOrder()
    }

    @Test
    fun `T8 a short tail stays when no split avoids it`() {
        // 95 cannot be split; the 5 after it is a tail whatever the count.
        assertThat(ends(split(listOf(95, 5), listOf(SENTENCE), limit = 100))).containsExactly(2)
        assertThat(ends(split(listOf(96, 5), listOf(SENTENCE), limit = 100))).containsExactly(1, 2).inOrder()
    }

    @Test
    fun `the balance never returns a part that does not fit`() {
        val lengths = listOf(30, 5, 40, 25, 10, 35, 20, 15, 30, 5)
        val ranks = listOf(PARAGRAPH, WORD, SENTENCE, LINE, WORD, CLAUSE, PARAGRAPH, WORD, SENTENCE)
        val parts = ends(split(lengths, ranks, limit = 60))
        val sizes = (listOf(0) + parts).zipWithNext { a, b -> lengths.subList(a, b).sum() }
        assertThat(sizes.all { it <= 60 }).isTrue()
        assertThat(sizes.sum()).isEqualTo(lengths.sum())
    }

    @Test
    fun `dense breaks are thinned without losing a fitting split`() {
        val lengths = List(2000) { 1 }
        val ranks = List(1999) { if (it % 97 == 0) SENTENCE else WORD }
        val parts = ends(split(lengths, ranks, limit = 480))
        assertThat(parts).hasSize(5)
        val sizes = (listOf(0) + parts).zipWithNext { a, b -> b - a }
        assertThat(sizes.all { it <= 480 }).isTrue()
        // Sentence ends exist every 97 units, so every break is one.
        assertThat(parts.dropLast(1).all { (it - 1) % 97 == 0 }).isTrue()
    }
}
