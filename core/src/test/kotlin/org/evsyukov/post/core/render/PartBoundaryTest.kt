package org.evsyukov.post.core.render

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.bluesky.LinkSpan
import org.evsyukov.post.core.render.RenderFixtures.assertValid
import org.evsyukov.post.core.render.RenderFixtures.bluesky
import org.evsyukov.post.core.render.RenderFixtures.custom
import org.evsyukov.post.core.render.RenderFixtures.graphemes
import org.evsyukov.post.core.render.RenderFixtures.mastodon
import org.evsyukov.post.core.render.RenderFixtures.render
import org.junit.Test

/**
 * What happens at the edges of parts, through [TextRenderer.render]: a part reads as the same text it is in
 * the draft (T17), its numbers and facets survive the split, and no part, media-only ones included, is over
 * the limit.
 */
class PartBoundaryTest {
    private val plain40 = custom(TextFormat.PLAIN, 40, Counting.GOTOSOCIAL)
    private val markdown40 = custom(TextFormat.MARKDOWN, 40, Counting.GOTOSOCIAL, contentType = "text/markdown")
    private val html50 = custom(TextFormat.HTML, 50, Counting.PLEROMA, contentType = "text/html")

    private fun texts(result: TextRender) = result.parts.map { it.text }

    @Test
    fun `T17 a number with a dot that starts a part stays text and the next line keeps its number`() {
        val text = "x".repeat(296) + " 1984. was the year\n5. item"
        val result = render(text, bluesky())
        assertValid(text, result)
        assertThat(texts(result)).containsExactly("x".repeat(296), "1984. was the year\n5. item").inOrder()
    }

    @Test
    fun `T17 a plus sign that starts a part stays a plus sign`() {
        val text = "x".repeat(39) + " + 2 = 4"
        assertThat(texts(render(text, plain40))).containsExactly("x".repeat(39), "+ 2 = 4").inOrder()
        assertThat(texts(render(text, markdown40))).containsExactly("x".repeat(39), "\\+ 2 = 4").inOrder()
        assertThat(texts(render(text, html50))[1]).isEqualTo("<p>+ 2 = 4</p>")
    }

    @Test
    fun `T17 a greater-than sign inside strikethrough at the start of a part is never lost`() {
        val text = "~~" + "z".repeat(36) + " >~~ tail"
        val plain = render(text, plain40)
        assertValid(text, plain)
        assertThat(texts(plain)).containsExactly("z".repeat(36), "> tail").inOrder()
        assertThat(plain.parts[1].count).isEqualTo(6)
        // In Markdown the reopened strikethrough goes before the escaped sign, not after it: no `~~~~` fence.
        assertThat(texts(render(text, markdown40))).containsExactly("~~" + "z".repeat(36) + "~~", "~~\\>~~ tail").inOrder()
        assertThat(texts(render("x".repeat(39) + " > b c", markdown40))[1]).isEqualTo("\\> b c")
    }

    /**
     * Strikethrough inside strikethrough whose inner span opens a line, with the break before that line:
     * the reopened `~~` and the inner `~~` must not make a `~~~~` fence that swallows the part (T5, T17).
     */
    private val nestedStrike = "~~" + List(30) { "crossed" }.joinToString(" ") + "\n~~Old plan~~ " +
        List(30) { "removed" }.joinToString(" ") + "~~ and the rest."

    @Test
    fun `T17 strikethrough reopened before strikethrough nested in it keeps the part's text`() {
        val crossed = List(30) { "crossed" }.joinToString(" ")
        val rest = "Old plan " + List(30) { "removed" }.joinToString(" ") + " and the rest."
        for (profile in listOf(bluesky(), custom(TextFormat.PLAIN, 300, Counting.MASTODON))) {
            val result = render(nestedStrike, profile)
            assertValid(nestedStrike, result)
            assertThat(texts(result)).containsExactly(crossed, rest).inOrder()
            assertThat(result.parts[1].count).isEqualTo(graphemes(rest))
        }
        val markdown = render(nestedStrike, custom(TextFormat.MARKDOWN, 300, Counting.GOTOSOCIAL, contentType = "text/markdown"))
        assertValid(nestedStrike, markdown)
        assertThat(texts(markdown)[1]).isEqualTo("~~Old plan " + List(30) { "removed" }.joinToString(" ") + "~~ and the rest.")
        val html = render(nestedStrike, custom(TextFormat.HTML, 300, Counting.PLEROMA, contentType = "text/html"))
        assertValid(nestedStrike, html)
        assertThat(texts(html)[1]).isEqualTo("<p><del>Old plan " + List(30) { "removed" }.joinToString(" ") + "</del> and the rest.</p>")
    }

    @Test
    fun `T5 nested strikethrough cut inside or right after the inner span closes and reopens as one`() {
        val words = "w".repeat(30)
        val tail = "x".repeat(20)
        // The break falls inside both spans, and right after the inner span closes inside the outer one.
        for (text in listOf("~~one ~~$words $tail~~ end~~", "~~one ~~$words~~ $tail end~~")) {
            val plain = render(text, plain40)
            assertValid(text, plain)
            assertThat(texts(plain).joinToString(" ")).isEqualTo("one $words $tail end")
            val markdown = render(text, markdown40)
            assertValid(text, markdown)
            assertThat(markdown.parts.size).isAtLeast(2)
            for (part in markdown.parts) {
                // Every part is struck through as a whole, and no tilde shows.
                val html = org.evsyukov.post.core.markdown.toHtml(part.text)
                assertThat(html).startsWith("<p><del>")
                assertThat(html.replace("<del>", "").replace("</del>", "")).doesNotContain("~")
            }
        }
    }

    @Test
    fun `T5 a URL that ends with a tilde inside strikethrough is not followed by the closing tildes`() {
        // The whole text is 64 characters; the URL and the x's after it are 53.
        val text = "~~" + "w".repeat(10) + " https://example.com/a~ " + "x".repeat(30) + "~~"
        val plain = render(text, custom(TextFormat.PLAIN, 60, Counting.GOTOSOCIAL))
        assertValid(text, plain)
        assertThat(texts(plain)).containsExactly("w".repeat(10), "https://example.com/a~ " + "x".repeat(30)).inOrder()
        val markdown = render(text, custom(TextFormat.MARKDOWN, 60, Counting.GOTOSOCIAL, contentType = "text/markdown"))
        assertValid(text, markdown)
        assertThat(texts(markdown)).containsExactly("~~" + "w".repeat(10) + "~~", "~~https://example.com/a~ " + "x".repeat(30) + "~~").inOrder()
        // With no room for the URL and the next word together, that run is the conflict; nothing is cut.
        val tight = render(text, plain40).issue as TextIssue.TooLongToSplit
        assertThat(text.substring(tight.sourceStart, tight.sourceEnd)).isEqualTo("https://example.com/a~ " + "x".repeat(30) + "~~")
    }

    @Test
    fun `T17 a part that starts at an indented fence keeps its code block`() {
        val code = "code \\# x <y@z.co> **b**"
        val text = "word word word word word word\n   ```\n$code"
        val result = render(text, custom(TextFormat.PLAIN, 30, Counting.GOTOSOCIAL))
        assertValid(text, result)
        // The code shows as typed: no escape eaten, no autolink or emphasis taken out.
        assertThat(texts(result)).containsExactly("word word word word word word", code).inOrder()
    }

    @Test
    fun `T5 T17 no part starts inside a list item whose code block is a block only inside the item`() {
        // The nested item's content starts 4 columns in: its fences are fences only inside both items.
        val list = "- a\n  - b\n    ```\n    one one one one one\n    two two two two two\n    ```"
        val text = "Intro words here and more.\n\n$list"
        val plain = custom(TextFormat.PLAIN, 60, Counting.GOTOSOCIAL)
        val result = render(text, plain)
        assertValid(text, result)
        assertThat(texts(result)).containsExactly(
            "Intro words here and more.",
            org.evsyukov.post.core.markdown.toPlainText(list).trim(),
        ).inOrder()
        // When the item does not fit, it is the conflict: no part reads the fence as text.
        val tight = render(text, custom(TextFormat.PLAIN, 40, Counting.GOTOSOCIAL)).issue as TextIssue.TooLongToSplit
        assertThat(text.substring(tight.sourceStart, tight.sourceEnd)).isEqualTo(list)
        // A list item 10 and over puts its content 4 columns in too.
        val ten = (1..9).joinToString("\n") { "$it. item" } + "\n10. ```\n    one one one one one one\n    two two two two two two\n    ```"
        val numbered = render(ten, custom(TextFormat.PLAIN, 70, Counting.GOTOSOCIAL))
        assertValid(ten, numbered)
        assertThat(numbered.parts.none { "```" in it.text }).isTrue()
    }

    @Test
    fun `lazy numbering continues from part to part`() {
        val text = (1..12).joinToString("\n") { "1. item number $it here" }
        val result = render(text, custom(TextFormat.PLAIN, 80, Counting.GOTOSOCIAL))
        assertValid(text, result)
        assertThat(result.parts.size).isAtLeast(3)
        val numbers = result.parts.flatMap { part -> part.text.lines().map { it.substringBefore('.').toInt() } }
        assertThat(numbers).isEqualTo((1..12).toList())
    }

    @Test
    fun `ordered items after a cut item stay list items with their numbers`() {
        // The first item is longer than the limit, so a part ends inside it.
        val text = "1. " + List(30) { "first" }.joinToString(" ") + "\n1. second item\n1. third item"
        val result = render(text, custom(TextFormat.HTML, 120, Counting.PLEROMA, contentType = "text/html"))
        assertValid(text, result)
        val second = result.parts.single { "second item" in it.text }.text
        assertThat(second).contains("<ol start=\"2\"><li>second item</li>")
        val third = result.parts.single { "third item" in it.text }.text
        if (third != second) assertThat(third).contains("<ol start=\"3\"><li>third item</li>")
        assertThat(result.parts.none { "<p>2. second" in it.text || "<br>" in it.text }).isTrue()
    }

    @Test
    fun `Bluesky keeps a link facet when the link text has spaces at the ends of the post`() {
        val single = render("[ docs ](https://example.com/docs) are here", bluesky())
        assertThat(single.parts.single().text).isEqualTo("docs  are here")
        assertThat(single.parts.single().links).containsExactly(LinkSpan(0, 4, "https://example.com/docs"))
        val trailing = render("Read the [docs ](https://example.com/docs)", bluesky())
        assertThat(trailing.parts.single().text).isEqualTo("Read the docs")
        assertThat(trailing.parts.single().links).containsExactly(LinkSpan(9, 13, "https://example.com/docs"))
    }

    @Test
    fun `media-only parts count against the limit with the content warning and the number`() {
        val profile = mastodon(numberParts = true)
        val tooLong = render("", profile, spoiler = "w".repeat(499), mediaOnlyParts = mapOf(0 to 1))
        assertThat(tooLong.issue).isEqualTo(TextIssue.SpoilerTooLong(499))
        assertThat(tooLong.parts).isEmpty()
        val fits = render("", profile, spoiler = "w".repeat(497), mediaOnlyParts = mapOf(0 to 1))
        assertThat(fits.issue).isNull()
        assertThat(fits.parts.map { it.text }).containsExactly("1/2", "2/2").inOrder()
        assertThat(fits.parts.map { it.count }).containsExactly(500, 500)
    }

    @Test
    fun `a content warning that leaves a short word no room is the content warning's conflict`() {
        assertThat(render("Hi", mastodon(), spoiler = "w".repeat(499)).issue).isEqualTo(TextIssue.SpoilerTooLong(499))
        assertThat(render("Hi", mastodon(), spoiler = "w".repeat(498)).parts.single().count).isEqualTo(500)
        // A word too long even without the warning is still the word's conflict.
        val word = "y".repeat(45)
        val issue = render("Hi $word", plain40, spoiler = "cw").issue as TextIssue.TooLongToSplit
        assertThat("Hi $word".substring(issue.sourceStart, issue.sourceEnd)).isEqualTo(word)
    }

    @Test
    fun `T8 when no split of N parts leaves a long enough tail N + 1 parts are tried`() {
        // Two parts end either after the a's (a tail of 17, under a third of the average 56) or after "xy"
        // (a tail of 14); three parts leave a tail of 14, over a third of the average 37.
        val text = "a".repeat(95) + " xy " + "q".repeat(14)
        val result = render(text, custom(TextFormat.PLAIN, 100, Counting.GOTOSOCIAL))
        assertValid(text, result)
        assertThat(texts(result)).containsExactly("a".repeat(95), "xy", "q".repeat(14)).inOrder()
    }

    @Test
    fun `T8 a tail of a third of the average is long enough, not only one of half`() {
        // Found by comparing renders of random drafts with the tail rule at a third and at a half: the
        // estimate makes the splitter ask for more parts, and the exact split of 10 parts ends with a tail of
        // 79 against an average of 235, enough by T8, so it stands; a rule of half the average would take 12.
        val text =
            "3. _x  naïve  <https://auto.example/link>  https://example.com  [ref]:  éclair  1.  end.  ![alt text](https://img.example/i.png)  «цитата»  https://example.com/path/__init__.py?q=1&r=2_,\n" +
            "\n" +
            "**> ёлка «цитата» [ spaced ](https://example.com/s) @alice かな + \\*not bold\\* — semi; endx **5) - 1. Привет semi;x** another * т. е. `a*b*c` かなx**.\n" +
            "\n" +
            "```kotlin\n" +
            "    line(0) // a b\n" +
            "    line(1) // かな\n" +
            "    line(2) // 1.\n" +
            "    line(3) // ёлка\n" +
            "```\n" +
            "\n" +
            "1. **мир**,\n" +
            "1. ~~&amp; мир why? another <span> ёлка — 🇺🇦 [ref]: word semi;~~?\n" +
            "1. *another longerwordhere another &amp; #tag ```js Привет 5) «цитата» (paren) т. е. a b*.\n" +
            "1. + \\ 🇺🇦 end. * `a*b*c` e.g. https://example.com \\*not bold\\* @alice\n" +
            "1. *stop! — longerwordhere + naïve ![alt text](https://img.example/i.png) word https://example.com/path/__init__.py?q=1&r=2 another #tag naïve*?\n" +
            "1. éclair \\ мир longerwordhere > https://example.com/path/__init__.py?q=1&r=2 [ref]: ![alt text](https://img.example/i.png) * + word,\n" +
            "1. ~~word \\*not bold\\* т. е. мир + (paren)~~.\n" +
            "1. <span>  https://example.com  -  e.g.  word  e.g..\n" +
            "\n" +
            "\n" +
            "\n" +
            "1. 1. @alice@example.social\n" +
            "   - **word Привет 漢字 https://example.com/path/__init__.py?q=1&r=2 <https://auto.example/link>**!\n" +
            "   - [ref]: > 🇺🇦 [ref]: \\ https://example.org/longlonglonglonglonglonglonglonglonglonglonglong <span> 🇺🇦 > &amp; 👍🏽 «цитата»,\n" +
            "2. [https://a.example](https://a.example)!"
        val result = render(text, custom(TextFormat.HTML, 300, Counting.PLEROMA, contentType = "text/html"))
        assertValid(text, result)
        assertThat(result.parts).hasSize(10)
        val counts = result.parts.map { it.count }
        assertThat(counts.last() * 3.0).isAtLeast(counts.average())
        assertThat(counts.last() * 2.0).isLessThan(counts.average())
    }

    @Test
    fun `T6 an estimate that is off does not add a part the exact greedy split does without`() {
        // HTML lists and quotes make the estimate of a part's length wrong by tens of characters. Found by a
        // search over random drafts: the estimate alone balanced this draft into 5 parts, while 4 fit by exact
        // counts and their last part holds more than a third of the average.
        val text = "- naïve naïve word end..\n- **мир Привет x Привет stop! semi;**.\n\n1. мир end. — —.\n" +
            "1. **end. longerwordhere word мир — semi; word мир**.\n1. **stop! — semi; semi; мир**.\n\n" +
            "> **x comma, word x**.\n> x x (paren) comma, semi; end. Привет.\n\n1. — naïve.\n" +
            "1. semi; longerwordhere — мир end. stop! x (paren).\n" +
            "1. naïve semi; longerwordhere word another — comma, мир x another semi;.\n" +
            "1. **(paren) semi; naïve naïve stop! another — мир**.\n1. **naïve Привет end. stop! naïve word (paren) Привет end.**.\n\n" +
            "- (paren) longerwordhere naïve — longerwordhere end. — x comma, x.\n" +
            "- (paren) мир stop! comma, Привет end. (paren) naïve naïve Привет.\n\n" +
            "(paren) end. Привет end. stop! x word —. semi; x naïve. word end. Привет мир end. longerwordhere (paren) x x.\n\n" +
            "1. stop! word x (paren) another — —.\n1. longerwordhere x longerwordhere stop! end..\n1. Привет stop!.\n1. **Привет**."
        val result = render(text, custom(TextFormat.HTML, 300, Counting.PLEROMA, contentType = "text/html"))
        assertValid(text, result)
        assertThat(result.parts).hasSize(4)
        val counts = result.parts.map { it.count }
        assertThat(counts.last() * 3.0).isAtLeast(counts.average())
    }
}
