package org.evsyukov.post.core.render

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.evsyukov.post.core.markdown.toHtml
import org.evsyukov.post.core.render.RenderFixtures.assertValid
import org.evsyukov.post.core.render.RenderFixtures.bluesky
import org.evsyukov.post.core.render.RenderFixtures.custom
import org.evsyukov.post.core.render.RenderFixtures.render
import org.junit.Test

/**
 * T5, T17: a part that starts inside a nested list item has no marker of the items around it. Its lines
 * must still read as they do in the draft: nested items keep their numbers and bullets, and the items of
 * the outer list after them keep theirs instead of continuing the nested list's numbering.
 */
class NestedListPartsTest {
    /** The verifier's reproduction: a numbered list with numbered sub-items, cut inside the sub-list. */
    private val steps = listOf(
        "1. Prepare the workspace before you start anything else today.",
        "   1. Clear the desk and put away everything you will not need.",
        "   2. Open the window so the room gets some fresh air.",
        "   3. Make a cup of tea and set it down where you can reach it.",
        "   4. Put the phone in another room to avoid distractions.",
        "2. Write the first draft without stopping to fix small mistakes.",
        "3. Read it again tomorrow and cut what is not needed.",
    ).joinToString("\n")

    private val plain = custom(TextFormat.PLAIN, 260, Counting.MASTODON)
    private val markdown = custom(TextFormat.MARKDOWN, 260, Counting.GOTOSOCIAL, contentType = "text/markdown")
    private val html = custom(TextFormat.HTML, 400, Counting.PLEROMA, contentType = "text/html")

    /**
     * The list markers a reader sees in [text] of [format], in order: `n.` for numbered items and `•` for
     * bullets, each with the first word of its item.
     */
    private fun shown(text: String, format: TextFormat): List<String> = when (format) {
        TextFormat.PLAIN, TextFormat.BLUESKY -> text.lines().mapNotNull { line ->
            Regex("^\\s*(\\d+\\.|•) (\\S+)").find(line)?.let { "${it.groupValues[1]} ${it.groupValues[2]}" }
        }
        TextFormat.MARKDOWN -> htmlItems(toHtml(text))
        TextFormat.HTML -> htmlItems(text)
    }

    private fun htmlItems(html: String): List<String> {
        val result = ArrayList<String>()
        val lists = ArrayDeque<Int>()
        for (match in Regex("<(/?)(ol|ul|li)([^>]*)>").findAll(html)) {
            val (slash, tag, attributes) = match.destructured
            // The first word of the item, after any tags that open its text (`<p>`, `<strong>`).
            val word = Regex("^(?:<[a-z]+>)*([^<\\s]*)").find(html.substring(match.range.last + 1))!!.groupValues[1]
            when {
                slash.isNotEmpty() && tag != "li" -> lists.removeLast()
                tag == "ul" && slash.isEmpty() -> lists.addLast(-1)
                tag == "ol" && slash.isEmpty() ->
                    lists.addLast(Regex("start=\"(\\d+)\"").find(attributes)?.groupValues?.get(1)?.toInt() ?: 1)
                tag == "li" && slash.isEmpty() -> {
                    val number = lists.last()
                    if (number < 0) {
                        result += "• $word"
                    } else {
                        result += "$number. $word"
                        lists[lists.lastIndex] = number + 1
                    }
                }
            }
        }
        return result
    }

    /** Renders [text] for [profile] and checks that the parts together show the markers of the whole. */
    private fun assertMarkersKept(text: String, profile: TextProfile, minParts: Int = 2): TextRender {
        val result = render(text, profile)
        assertValid(text, result)
        assertWithMessage("parts of ${profile.format}").that(result.parts.size).isAtLeast(minParts)
        val whole = shown(PartConverter(profile, RenderFixtures.segmenter, "").convert(text), profile.format)
        val joined = result.parts.flatMap { shown(it.text, profile.format) }
        assertWithMessage("${profile.format} parts:\n${result.parts.joinToString("\n---\n") { it.text }}")
            .that(joined).isEqualTo(whole)
        return result
    }

    @Test
    fun `items of the outer list after a cut nested list keep their numbers in every format`() {
        val expected = listOf("1. Prepare", "1. Clear", "2. Open", "3. Make", "4. Put", "2. Write", "3. Read")
        for (profile in listOf(bluesky(), plain, markdown, html)) {
            val result = assertMarkersKept(steps, profile)
            assertThat(result.parts.flatMap { shown(it.text, profile.format) }).isEqualTo(expected)
        }
    }

    @Test
    fun `a nested item in a later part is a list item, not text of the item before it`() {
        val result = render(steps, html)
        // Not `3. Make …<br>4. Put …`: the fourth sub-item stays an item of its own.
        assertThat(result.parts.none { "<br>" in it.text }).isTrue()
        val bullets = steps.replace(Regex("(?m)^   \\d+\\. "), "   - ")
        for (profile in listOf(bluesky(), plain, markdown, html)) assertMarkersKept(bullets, profile)
        // Bullets in the part keep their bullets and never show as typed dashes.
        assertThat(render(bullets, bluesky()).parts.none { part -> part.text.lines().any { it.trimStart().startsWith("- ") } }).isTrue()
    }

    @Test
    fun `lists two levels deep keep every level's markers`() {
        val deep = listOf(
            "1. First step of the plan, described in a sentence or two here.",
            "   1. A sub-step with some words to make it long enough to matter.",
            "      - a detail of the sub-step that also has a few words in it",
            "      - another detail of the sub-step with a few more words in it",
            "      - the last detail of the sub-step, again with some words",
            "   2. The second sub-step that follows the details of the first one.",
            "2. Second step of the plan, described in a sentence or two here.",
            "3. Third step of the plan, the last one in this list of steps.",
        ).joinToString("\n")
        for (profile in listOf(bluesky(), custom(TextFormat.PLAIN, 200, Counting.MASTODON), custom(TextFormat.MARKDOWN, 200, Counting.GOTOSOCIAL), custom(TextFormat.HTML, 330, Counting.PLEROMA))) {
            assertMarkersKept(deep, profile)
        }
        // Each limit cuts the list somewhere else: every cut keeps the markers.
        for (limit in 70..300 step 7) {
            for (format in TextFormat.entries.filter { it != TextFormat.BLUESKY }) {
                val result = render(deep, custom(format, if (format == TextFormat.HTML) limit + 60 else limit, Counting.GOTOSOCIAL))
                if (result.issue != null) continue
                val whole = shown(PartConverter(result.profile, RenderFixtures.segmenter, "").convert(deep), format)
                assertWithMessage("$format $limit:\n${result.parts.joinToString("\n---\n") { it.text }}")
                    .that(result.parts.flatMap { shown(it.text, format) }).isEqualTo(whole)
            }
        }
    }

    @Test
    fun `a code block in a cut list item stays a code block`() {
        val text = "1. Run these commands one after another in the project directory:\n" +
            "   ```sh\n" + List(8) { "   ./gradlew task$it --console=plain" }.joinToString("\n") + "\n   ```\n" +
            "   Then check the output.\n2. Commit the result."
        val result = render(text, custom(TextFormat.PLAIN, 120, Counting.GOTOSOCIAL))
        assertValid(text, result)
        assertThat(result.parts.size).isAtLeast(3)
        // Code lines come out without the fence and without the item's indentation, never as `sh` or a fence.
        val lines = result.parts.flatMap { it.text.lines() }
        for (k in 0 until 8) assertThat(lines.count { it.trim() == "./gradlew task$k --console=plain" }).isEqualTo(1)
        assertThat(lines.none { "```" in it || it.trim() == "sh" }).isTrue()
        assertThat(lines.last()).isEqualTo("2. Commit the result.")
    }

    @Test
    fun `a fence in a quote of a nested item keeps its info string out of the text`() {
        val text = "1. Outer item with a few words in it to take up some room.\n" +
            "   - Inner item, also with a few words in it to take up room.\n" +
            "     > ~~~info\n" +
            "     > quoted code line one\n" +
            "     > quoted code line two\n" +
            "     > ~~~\n" +
            "2. Last item."
        for (limit in 40..160 step 5) {
            val result = render(text, custom(TextFormat.PLAIN, limit, Counting.GOTOSOCIAL))
            if (result.issue != null) continue
            assertValid(text, result)
            assertWithMessage("limit $limit: ${result.parts.map { it.text }}").that(result.parts.none { "info" in it.text || "~~~" in it.text }).isTrue()
        }
    }
}
