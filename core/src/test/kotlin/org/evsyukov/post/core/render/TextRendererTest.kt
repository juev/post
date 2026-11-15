package org.evsyukov.post.core.render

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.markdown.MANUAL_BREAK
import org.evsyukov.post.core.render.RenderFixtures.EN
import org.evsyukov.post.core.render.RenderFixtures.assertValid
import org.evsyukov.post.core.render.RenderFixtures.bluesky
import org.evsyukov.post.core.render.RenderFixtures.custom
import org.evsyukov.post.core.render.RenderFixtures.graphemes
import org.evsyukov.post.core.render.RenderFixtures.mastodon
import org.evsyukov.post.core.render.RenderFixtures.render
import org.evsyukov.post.core.render.RenderFixtures.segmenter
import org.junit.Test
import java.util.Locale

/** T1–T17 rules of threads.md that the scenarios do not pin, through [TextRenderer.render]. */
class TextRendererTest {
    private fun words(count: Int, word: String = "word") = List(count) { word }.joinToString(" ")

    @Test
    fun `T2 a text that fits stays one part`() {
        val text = words(100) // 499 characters
        val result = render(text, mastodon())
        assertThat(result.parts.single().text).isEqualTo(text)
        assertThat(result.parts.single().count).isEqualTo(499)
        assertThat(result.issue).isNull()
        assertThat(result.limit).isEqualTo(500)
    }

    @Test
    fun `T2 a text that fits once converted stays one part even where the estimate is high`() {
        // The HTML estimate of a list is above its exact length: <ul><li>a</li><li>b</li><li>c</li></ul>.
        val text = "- a\n- b\n- c"
        val result = render(text, custom(TextFormat.HTML, maxCharacters = 39, counting = Counting.PLEROMA))
        assertThat(result.parts.single().text).isEqualTo("<ul><li>a</li><li>b</li><li>c</li></ul>")
    }

    @Test
    fun `T1 the split depends on the account's limit`() {
        val text = words(150)
        assertThat(render(text, mastodon()).parts).hasSize(2)
        assertThat(render(text, bluesky()).parts).hasSize(3)
        assertThat(render(text, mastodon(capabilities = RenderFixtures.vanillaMastodon.copy(maxCharacters = 5000))).parts).hasSize(1)
    }

    @Test
    fun `N3 the content warning goes with every Mastodon part and counts in it`() {
        val text = words(150)
        val spoiler = "Spoiler of forty characters, give or tk"
        val without = render(text, mastodon())
        val with = render(text, mastodon(), spoiler = spoiler)
        assertValid(text, with)
        for (part in with.parts) {
            assertThat(part.count).isEqualTo(spoiler.length + part.text.length)
        }
        assertThat(with.parts.maxOf { it.text.length }).isAtMost(500 - spoiler.length)
        assertThat(with.parts.size).isAtLeast(without.parts.size)
        // Bluesky has no content warning: the same draft splits as without one.
        assertThat(render(text, bluesky(), spoiler = spoiler).parts.map { it.text })
            .isEqualTo(render(text, bluesky()).parts.map { it.text })
    }

    @Test
    fun `a content warning that leaves no room is an issue`() {
        val spoiler = "x".repeat(500)
        val issue = render("hello", mastodon(), spoiler = spoiler).issue
        assertThat(issue).isEqualTo(TextIssue.SpoilerTooLong(500))
        assertThat(render("", mastodon(), spoiler = spoiler).issue).isNull()
        assertThat(render("", mastodon(), spoiler = spoiler + "x").issue).isEqualTo(TextIssue.SpoilerTooLong(501))
    }

    @Test
    fun `T10 parts never start or end with whitespace and their source ranges tile the piece`() {
        val text = "  First paragraph is here.  \n\n\n   Second one, longer: " + words(80) + ".   \n\n"
        val result = render(text, bluesky())
        assertValid(text, result)
        assertThat(result.parts.size).isAtLeast(2)
        assertThat(result.parts.first().sourceStart).isEqualTo(2)
        assertThat(result.parts.last().sourceEnd).isEqualTo(text.trimEnd().length)
        for ((before, after) in result.parts.zipWithNext()) {
            assertThat(text.substring(before.sourceEnd, after.sourceStart).isBlank()).isTrue()
        }
    }

    @Test
    fun `T4 a paragraph end wins over a sentence end in about the same place`() {
        val first = "One. " + words(40) + "."
        val second = words(40) + ". Two. " + words(10) + "."
        val text = "$first\n\n$second"
        val result = render(text, bluesky())
        assertThat(result.parts.first().text).isEqualTo(first)
    }

    @Test
    fun `T5 inline code, links and mentions are never cut`() {
        val code = "`" + words(20, "code") + "`"
        val link = "[" + words(20, "link") + "](https://example.com/page)"
        val text = words(30) + " " + code + " " + words(30) + " " + link + " " + words(30) + " @someone@example.social end"
        val profile = custom(TextFormat.MARKDOWN, maxCharacters = 160, counting = Counting.GOTOSOCIAL)
        val result = render(text, profile)
        assertValid(text, result)
        val whole = result.parts.map { text.substring(it.sourceStart, it.sourceEnd) }
        assertThat(whole.count { code in it }).isEqualTo(1)
        assertThat(whole.count { link in it }).isEqualTo(1)
        assertThat(whole.count { "@someone@example.social" in it }).isEqualTo(1)
    }

    @Test
    fun `T5 a code block splits between lines and each part closes and reopens the fence`() {
        val lines = List(12) { "    call(number$it) // a line of code" }
        val text = "Code:\n\n```kotlin\n" + lines.joinToString("\n") + "\n```"
        val profile = custom(TextFormat.MARKDOWN, maxCharacters = 200, counting = Counting.PLEROMA, contentType = "text/markdown")
        val result = render(text, profile)
        assertValid(text, result)
        assertThat(result.parts.size).isAtLeast(3)
        val codeParts = result.parts.filter { "call(" in it.text }
        for (part in codeParts) {
            assertThat(part.text.lines().first { it.startsWith("```") }).isEqualTo("```kotlin")
            assertThat(part.text).endsWith("\n```")
        }
        // Every code line arrives once, with its indentation.
        for (line in lines) assertThat(result.parts.count { line in it.text.lines() }).isEqualTo(1)
    }

    @Test
    fun `T5 a code block inside a quote keeps the quote markers on reopened fences`() {
        val lines = List(8) { "> line $it of quoted code here" }
        val text = "> ```\n" + lines.joinToString("\n") + "\n> ```"
        val profile = custom(TextFormat.MARKDOWN, maxCharacters = 120, counting = Counting.PLEROMA)
        val result = render(text, profile)
        assertValid(text, result)
        assertThat(result.parts.size).isAtLeast(2)
        for (part in result.parts) {
            assertThat(part.text.lines().first()).isEqualTo("> ```")
            assertThat(part.text.lines().last()).isEqualTo("> ```")
        }
    }

    @Test
    fun `T5 a part that starts inside a quoted line reopens the quote`() {
        val text = "> " + List(30) { "Sentence number $it is quoted." }.joinToString(" ")
        val result = render(text, bluesky())
        assertValid(text, result)
        assertThat(result.parts.size).isAtLeast(2)
        for (part in result.parts) assertThat(part.text).startsWith("> ")
    }

    @Test
    fun `T5 no break inside a grapheme cluster`() {
        val family = "👨‍👩‍👧" // 👨‍👩‍👧
        val text = List(200) { family }.joinToString(" ")
        val result = render(text, bluesky())
        assertValid(text, result)
        for (part in result.parts) {
            assertThat(text.substring(part.sourceStart, part.sourceEnd).split(" ").all { it == family }).isTrue()
        }
    }

    @Test
    fun `T4 Chinese text without spaces breaks between words`() {
        val text = "我们今天去公园散步，天气非常好。".repeat(30)
        val result = render(text, bluesky(), locale = Locale.CHINESE)
        assertValid(text, result)
        assertThat(result.parts.size).isAtLeast(2)
        assertThat(result.parts.joinToString("") { it.text }).isEqualTo(text)
        assertThat(result.parts.dropLast(1).all { it.text.endsWith("。") || it.text.endsWith("，") }).isTrue()
    }

    @Test
    fun `T11 a single part is never numbered`() {
        val result = render("Short post.", bluesky(numberParts = true))
        assertThat(result.parts.single().text).isEqualTo("Short post.")
    }

    @Test
    fun `T11 a Markdown part that ends with a code block gets the number on its own line`() {
        val text = "Intro.\n\n```\n" + List(12) { "line $it of code" }.joinToString("\n") + "\n```"
        val profile = custom(TextFormat.MARKDOWN, maxCharacters = 120, counting = Counting.GOTOSOCIAL, numberParts = true)
        val result = render(text, profile)
        assertValid(text, result)
        val n = result.parts.size
        assertThat(n).isAtLeast(2)
        result.parts.forEachIndexed { index, part ->
            if (part.text.contains("```")) assertThat(part.text).endsWith("```\n${index + 1}/$n")
        }
    }

    @Test
    fun `T11 a Markdown part whose last line a number would change gets the number on a line of its own`() {
        val profile = custom(TextFormat.MARKDOWN, 60, Counting.GOTOSOCIAL, numberParts = true, contentType = "text/markdown")
        // The second paragraph fits a part alone, the two together do not, and it has only word breaks (T7).
        val next = "\n\nAnother paragraph long enough for a part of its own"
        // `--- 1/2` would no longer underline the heading, `[ref]: url 1/2` would no longer define the link,
        // `# Title # 1/2` would show its closing `#`, and `| a | 1/2` would add a cell to the row.
        for (last in listOf("Heading\n---", "Heading\n===", "[ref]: https://example.com/a", "# Title #", "| a | b |")) {
            val result = render(last + next, profile)
            assertValid(last + next, result)
            assertThat(result.parts.map { it.text }.first()).isEqualTo(if ('|' in last) "$last\n\n1/2" else "$last\n1/2")
        }
        // Text the number does not change keeps it at the end of its last line.
        assertThat(render("Plain words here$next", profile).parts.first().text).isEqualTo("Plain words here 1/2")
    }

    @Test
    fun `T11 an unclosed code block is closed before the number`() {
        val text = "```\n" + List(12) { "line $it of code" }.joinToString("\n")
        val profile = custom(TextFormat.MARKDOWN, maxCharacters = 120, counting = Counting.GOTOSOCIAL, numberParts = true)
        val result = render(text, profile)
        assertValid(text, result)
        assertThat(result.parts.last().text).endsWith("\n```\n${result.parts.size}/${result.parts.size}")
    }

    @Test
    fun `T11 HTML parts stay well-formed with numbers`() {
        val text = List(12) { "Paragraph $it with **bold** text & more." }.joinToString("\n\n") + "\n\n- a list item\n- another item"
        val profile = custom(TextFormat.HTML, maxCharacters = 200, counting = Counting.PLEROMA, numberParts = true, contentType = "text/html")
        val result = render(text, profile)
        assertValid(text, result)
        val n = result.parts.size
        assertThat(n).isAtLeast(2)
        result.parts.forEachIndexed { index, part ->
            assertThat(part.text).startsWith("<")
            assertThat(part.text).contains("${index + 1}/$n")
            assertThat(balanced(part.text)).isTrue()
            // The number is text inside the last block, not after it.
            assertThat(part.text.substringAfterLast(">", "")).isEmpty()
        }
    }

    /** Whether every tag of [html] but `br` is closed in order. */
    private fun balanced(html: String): Boolean {
        val stack = ArrayDeque<String>()
        for (tag in Regex("<(/?)([a-z]+)[^>]*>").findAll(html)) {
            val (slash, name) = tag.destructured
            if (name == "br") continue
            if (slash.isEmpty()) stack.addLast(name) else if (stack.removeLastOrNull() != name) return false
        }
        return stack.isEmpty()
    }

    @Test
    fun `A11 media-only parts are numbered and counted in n`() {
        val text = "Hello world."
        val result = render(text, bluesky(numberParts = true), mediaOnlyParts = mapOf(0 to 2))
        assertThat(result.parts.map { it.text }).containsExactly("Hello world. 1/3", "2/3", "3/3").inOrder()
        assertThat(result.parts.map { it.mediaReply }).containsExactly(false, true, true).inOrder()
        assertThat(render(text, bluesky(), mediaOnlyParts = mapOf(0 to 2)).parts.map { it.text })
            .containsExactly("Hello world.", "", "").inOrder()
    }

    @Test
    fun `A11 media-only replies come right after the first part of their piece`() {
        val text = words(100) + "\n\n" + MANUAL_BREAK + "\n\n" + "Second piece."
        val result = render(text, bluesky(numberParts = true), mediaOnlyParts = mapOf(0 to 1, 1 to 0))
        assertValid(text, result)
        val pieces = result.parts.map { it.piece }
        val replies = result.parts.map { it.mediaReply }
        assertThat(replies[1]).isTrue()
        assertThat(pieces.first()).isEqualTo(0)
        assertThat(pieces.last()).isEqualTo(1)
        assertThat(replies.count { it }).isEqualTo(1)
        val n = result.parts.size
        result.parts.forEachIndexed { index, part -> assertThat(part.text).endsWith("${index + 1}/$n") }
    }

    @Test
    fun `an empty piece with media gets a part of its own`() {
        val text = "Text of the first piece.\n\n$MANUAL_BREAK\n\n"
        val result = render(text, mastodon(), mediaOnlyParts = mapOf(1 to 0))
        assertThat(result.parts.map { it.piece }).containsExactly(0, 1).inOrder()
        assertThat(result.parts[1].text).isEmpty()
        assertThat(render(text, mastodon()).parts).hasSize(1)
    }

    @Test
    fun `a piece that shows nothing once converted gets no empty part`() {
        // An empty code block is no text in plain text: Mastodon refuses an empty status, so no part carries it.
        val text = "Hello there.\n$MANUAL_BREAK\n```\n```"
        for (profile in listOf(bluesky(), mastodon(), bluesky(numberParts = true), mastodon(numberParts = true))) {
            val result = render(text, profile)
            assertThat(result.parts.map { it.text }).containsExactly("Hello there.")
        }
        // With media of its own the piece still has its part, for the media.
        val withMedia = render(text, mastodon(numberParts = true), mediaOnlyParts = mapOf(1 to 0))
        assertThat(withMedia.parts.map { it.text }).containsExactly("Hello there. 1/2", "2/2").inOrder()
        assertThat(withMedia.parts.map { it.piece }).containsExactly(0, 1).inOrder()
        // Markdown shows the empty code block, so it stays a part there.
        val markdown = render(text, custom(TextFormat.MARKDOWN, 500, Counting.GOTOSOCIAL, contentType = "text/markdown"))
        assertThat(markdown.parts.map { it.text }).containsExactly("Hello there.", "```\n```").inOrder()
    }

    @Test
    fun `an empty draft still has one empty part`() {
        val result = render("", mastodon(), spoiler = "CW")
        assertThat(result.parts.single().text).isEmpty()
        assertThat(result.parts.single().count).isEqualTo(2)
    }

    @Test
    fun `T16 ten parts warn and more than fifty are refused`() {
        val ten = render(words(600), bluesky())
        assertThat(ten.parts.size).isAtLeast(10)
        assertThat(ten.manyPartsWarning).isTrue()
        assertThat(render(words(100), bluesky()).manyPartsWarning).isFalse()
        val many = render(words(3200), bluesky())
        assertThat(many.issue).isInstanceOf(TextIssue.TooManyParts::class.java)
        assertThat((many.issue as TextIssue.TooManyParts).count).isGreaterThan(50)
        assertThat(many.parts).isEmpty()
        assertThat(render(words(3200), bluesky(), mediaOnlyParts = emptyMap()).manyPartsWarning).isTrue()
    }

    @Test
    fun `T16 fifty parts are allowed and fifty-one are refused, media-only replies included`() {
        // Each 39-letter word fills a part of 40 alone, so n words make n parts.
        val profile = custom(TextFormat.PLAIN, 40, Counting.GOTOSOCIAL)
        val word = "x".repeat(39)
        val fifty = render(words(50, word), profile)
        assertThat(fifty.issue).isNull()
        assertThat(fifty.parts).hasSize(50)
        assertThat(fifty.manyPartsWarning).isTrue()
        assertThat(render(words(51, word), profile).issue).isEqualTo(TextIssue.TooManyParts(51))
        val withReply = render(words(49, word), profile, mediaOnlyParts = mapOf(0 to 1))
        assertThat(withReply.issue).isNull()
        assertThat(withReply.parts).hasSize(50)
        assertThat(render(words(50, word), profile, mediaOnlyParts = mapOf(0 to 1)).issue).isEqualTo(TextIssue.TooManyParts(51))
        // The warning starts at ten parts.
        assertThat(render(words(9, word), profile).manyPartsWarning).isFalse()
        assertThat(render(words(10, word), profile).manyPartsWarning).isTrue()
    }

    @Test
    fun `T9 a single word longer than the limit is a conflict with its range`() {
        val word = "x".repeat(320)
        val text = "Before. $word after."
        val issue = render(text, bluesky()).issue as TextIssue.TooLongToSplit
        assertThat(text.substring(issue.sourceStart, issue.sourceEnd)).isEqualTo(word)
        // Mastodon counts a URL as 23, so a long URL fits there.
        val url = "https://example.com/" + "y".repeat(600)
        assertThat(render("See $url", mastodon()).parts).hasSize(1)
    }

    @Test
    fun `T9 the range of a conflict is in draft coordinates of the right piece`() {
        val word = "z".repeat(320)
        val text = "First piece.\n$MANUAL_BREAK\nSecond $word"
        val issue = render(text, bluesky()).issue as TextIssue.TooLongToSplit
        assertThat(issue.piece).isEqualTo(1)
        assertThat(text.substring(issue.sourceStart, issue.sourceEnd)).isEqualTo(word)
    }

    @Test
    fun `Bluesky bytes are a limit of their own`() {
        // One grapheme of 21 bytes: a letter with ten combining marks.
        val heavy = "a" + (0x300..0x309).joinToString("") { it.toChar().toString() }
        val text = List(50) { heavy.repeat(5) }.joinToString(" ")
        assertThat(graphemes(text)).isEqualTo(299) // one part by graphemes
        assertThat(text.toByteArray(Charsets.UTF_8).size).isEqualTo(5299) // two by bytes
        val result = render(text, bluesky())
        assertValid(text, result)
        assertThat(result.parts).hasSize(2)
        assertThat(result.parts.all { it.bytes!! <= 3000 }).isTrue()
    }

    @Test
    fun `counts follow the account's family`() {
        val text = "👍🏽 " + words(10)
        assertThat(render(text, custom(TextFormat.PLAIN, 500, Counting.GOTOSOCIAL)).parts.single().count)
            .isEqualTo(text.codePointCount(0, text.length))
        assertThat(render(text, custom(TextFormat.PLAIN, 500, Counting.PLEROMA)).parts.single().count)
            .isEqualTo(graphemes(text))
        assertThat(TextRenderer.render(DraftText(text, "", EN), bluesky(), segmenter).parts.single().bytes)
            .isEqualTo(text.toByteArray(Charsets.UTF_8).size)
    }
}
