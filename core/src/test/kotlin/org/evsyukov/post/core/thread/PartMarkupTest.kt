package org.evsyukov.post.core.thread

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.markdown.analyze
import org.evsyukov.post.core.testing.IcuTextSegmenter
import org.junit.Test
import java.util.Locale

/** T5: what a break cuts is closed at the end of one part and reopened at the start of the next. */
class PartMarkupTest {
    private val segmenter = IcuTextSegmenter()

    /** The two fragments around the break whose part before ends right after [before]. */
    private fun around(text: String, before: String, closeCodeBlock: Boolean = false): Pair<PartFragment, PartFragment> {
        val structure = analyze(text)
        val cut = findBreaks(text, structure, segmenter, Locale.ENGLISH).single { it.end == text.indexOf(before) + before.length }
        return partFragment(text, structure, null, cut) to partFragment(text, structure, cut, null, closeCodeBlock)
    }

    @Test
    fun `the whole piece without breaks is the piece itself`() {
        val text = "**a** b"
        assertThat(partFragment(text, analyze(text), null, null)).isEqualTo(PartFragment(text, endsWithCodeBlock = false))
    }

    @Test
    fun `emphasis is closed innermost first and reopened outermost first with its exact markers`() {
        val (first, second) = around("__bold ~~struck *it here* more~~ end__", "it")
        assertThat(first.markdown).isEqualTo("__bold ~~struck *it*~~__")
        assertThat(second.markdown).isEqualTo("__~~*here* more~~ end__")
    }

    @Test
    fun `reopened emphasis goes after the quote markers of the first line`() {
        val (first, second) = around("> **bold\n> more bold**", "bold")
        assertThat(first.markdown).isEqualTo("> **bold**")
        assertThat(second.markdown).isEqualTo("> **more bold**")
    }

    @Test
    fun `a part that starts inside a quoted line reopens the quote`() {
        val (_, second) = around("> > One. *Two words*", "One.")
        assertThat(second.markdown).isEqualTo("> > *Two words*")
    }

    @Test
    fun `a code block is closed and reopened with its info string`() {
        val text = "```kotlin\nval a = 1\n    val b = 2\n```"
        val (first, second) = around(text, "val a = 1")
        assertThat(first).isEqualTo(PartFragment("```kotlin\nval a = 1\n```", endsWithCodeBlock = true))
        assertThat(second).isEqualTo(PartFragment("```kotlin\n    val b = 2\n```", endsWithCodeBlock = true))
    }

    @Test
    fun `fences inside a quote keep its markers and fences in a list item keep its indentation`() {
        val (quoted, quotedNext) = around("> ~~~\n> one\n> two\n> ~~~", "one")
        assertThat(quoted.markdown).isEqualTo("> ~~~\n> one\n> ~~~")
        assertThat(quotedNext.markdown).isEqualTo("> ~~~\n> two\n> ~~~")
        val (item, itemNext) = around("- ```\n  one\n  two\n  ```", "one")
        assertThat(item.markdown).isEqualTo("- ```\n  one\n  ```")
        assertThat(itemNext.markdown).isEqualTo("  ```\n  two\n  ```")
    }

    @Test
    fun `an unclosed code block is closed only when asked`() {
        val text = "```\none\ntwo"
        val (_, open) = around(text, "one")
        assertThat(open.markdown).isEqualTo("```\ntwo")
        assertThat(open.endsWithCodeBlock).isFalse()
        val (_, closed) = around(text, "one", closeCodeBlock = true)
        assertThat(closed).isEqualTo(PartFragment("```\ntwo\n```", endsWithCodeBlock = true))
    }
}
