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

    /** The fragment of the part after the break that ends right after [before]. */
    private fun after(text: String, before: String): String {
        val structure = analyze(text)
        val end = text.indexOf(before) + before.length
        val cut = findBreaks(text, structure, segmenter, Locale.ENGLISH).single { it.end == end }
        return partFragment(text, structure, cut, null).markdown
    }

    @Test
    fun `T17 text that would start a block at the start of a part gets a backslash`() {
        assertThat(after("Sum: 2 + 2 = 4", "2")).isEqualTo("\\+ 2 = 4")
        assertThat(after("Orwell 1984. was the year\n5. item", "Orwell")).isEqualTo("1984\\. was the year\n5. item")
        assertThat(after("Use 3) for this", "Use")).isEqualTo("3\\) for this")
        assertThat(after("a > b", "a")).isEqualTo("\\> b")
        assertThat(after("say ```js now", "say")).isEqualTo("\\`\\`\\`js now")
        assertThat(after("say ~~~ now", "say")).isEqualTo("\\~\\~\\~ now")
        assertThat(after("see [ref]: https://example.com/a", "see")).isEqualTo("\\[ref]: https://example.com/a")
        // Real blocks stay: a list item or a quote line at the start of a part.
        assertThat(after("Intro.\n- item", "Intro.")).isEqualTo("- item")
        assertThat(after("Intro.\n> quoted", "Intro.")).isEqualTo("> quoted")
    }

    @Test
    fun `a literal greater-than sign at the start of a part gets reopened markers before it`() {
        val text = "~~struck >~~ tail"
        assertThat(after(text, "struck")).isEqualTo("~~\\>~~ tail")
    }

    @Test
    fun `the first item of a list cut by a part shows its number in the piece`() {
        // Every item typed as 1. shows 1, 2, 3 in the piece.
        assertThat(after("1. a\n1. b\n1. c", "a")).isEqualTo("2. b\n1. c")
        assertThat(after("3. a\n7. b", "a")).isEqualTo("4. b")
    }

    @Test
    fun `an item after the rest of a cut item gets a blank line so it stays a list item`() {
        assertThat(after("1. first item text\n1. second", "first")).isEqualTo("item text\n\n2. second")
        assertThat(after("> 1. first item text\n> 1. second", "first")).isEqualTo("> item text\n>\n> 2. second")
        // An empty item cannot interrupt a paragraph either.
        assertThat(after("- first item text\n-\nlast", "first")).isEqualTo("item text\n\n-\nlast")
    }

    @Test
    fun `a wider number moves the continuation lines of its item`() {
        val items = (1..9).joinToString("") { "1. item\n" } + "1. ten\n   - nested"
        val fragment = after(items, (1..9).joinToString("") { "1. item\n" }.trimEnd())
        assertThat(fragment).isEqualTo("10. ten\n    - nested")
        assertThat(org.evsyukov.post.core.markdown.toPlainText(fragment)).isEqualTo("10. ten\n    • nested")
        // A code block the item closes gets its closing fence as far in as the item's content.
        val code = (1..9).joinToString("") { "1. item\n" } + "1. ```\n   one\n   two\n1. next"
        val withCode = after(code, (1..9).joinToString("") { "1. item\n" }.trimEnd())
        assertThat(withCode).isEqualTo("10. ```\n    one\n    two\n    ```\n1. next")
        assertThat(org.evsyukov.post.core.markdown.toPlainText(withCode)).isEqualTo("10. one\n    two\n11. next")
    }

    @Test
    fun `a code block that its list item closes is closed where the item ends`() {
        val text = "- ```\n  one\n  two\n- next"
        assertThat(after(text, "one")).isEqualTo("  ```\n  two\n  ```\n- next")
    }

    @Test
    fun `stars that are text are escaped in a part and emphasis still closes`() {
        val text = "**a * b c**"
        val (first, second) = around(text, "b")
        assertThat(first.markdown).isEqualTo("**a \\* b**")
        assertThat(second.markdown).isEqualTo("**c**")
        // An underscore inside a word never pairs and stays as typed.
        assertThat(around("**snake_case and more**", "snake_case").first.markdown).isEqualTo("**snake_case**")
    }

    @Test
    fun `a backslash at the end of a part goes when it made a line break and doubles before closing markers`() {
        assertThat(around("one\\\ntwo", "one\\").first.markdown).isEqualTo("one")
        assertThat(around("**a\\ b**", "a\\").first.markdown).isEqualTo("**a\\\\**")
    }

    @Test
    fun `bold inside bold is reopened once and its inner closing markers go`() {
        val text = "**a **b c** d**"
        assertThat(around(text, "b").second.markdown).isEqualTo("**c d**")
    }
}
