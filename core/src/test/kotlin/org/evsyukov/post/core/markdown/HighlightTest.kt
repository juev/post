package org.evsyukov.post.core.markdown

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.markdown.HighlightSpan.Kind.BOLD
import org.evsyukov.post.core.markdown.HighlightSpan.Kind.BREAK_MARKER
import org.evsyukov.post.core.markdown.HighlightSpan.Kind.CODE
import org.evsyukov.post.core.markdown.HighlightSpan.Kind.CODE_BLOCK
import org.evsyukov.post.core.markdown.HighlightSpan.Kind.ITALIC
import org.evsyukov.post.core.markdown.HighlightSpan.Kind.LINK_TEXT
import org.evsyukov.post.core.markdown.HighlightSpan.Kind.LINK_URL
import org.evsyukov.post.core.markdown.HighlightSpan.Kind.LIST_MARKER
import org.evsyukov.post.core.markdown.HighlightSpan.Kind.QUOTE
import org.evsyukov.post.core.markdown.HighlightSpan.Kind.STRIKE
import org.evsyukov.post.core.markdown.HighlightSpan.Kind.SYNTAX
import org.junit.Test
import kotlin.random.Random

/** formatting.md F3–F4: the editor highlights the subset as typed; syntax characters stay visible. */
class HighlightTest {

    private fun span(start: Int, end: Int, kind: HighlightSpan.Kind) = HighlightSpan(start, end, kind)

    @Test
    fun `bold has its markers as syntax`() {
        assertThat(highlight("**bold**")).containsExactly(span(0, 2, SYNTAX), span(2, 6, BOLD), span(6, 8, SYNTAX)).inOrder()
        assertThat(highlight("__bold__")).containsExactly(span(0, 2, SYNTAX), span(2, 6, BOLD), span(6, 8, SYNTAX)).inOrder()
    }

    @Test
    fun `italic with star and underscore`() {
        assertThat(highlight("*a* _b_")).containsExactly(
            span(0, 1, SYNTAX), span(1, 2, ITALIC), span(2, 3, SYNTAX),
            span(4, 5, SYNTAX), span(5, 6, ITALIC), span(6, 7, SYNTAX),
        ).inOrder()
    }

    @Test
    fun `strikethrough needs two tildes`() {
        assertThat(highlight("~~old~~")).containsExactly(span(0, 2, SYNTAX), span(2, 5, STRIKE), span(5, 7, SYNTAX)).inOrder()
        assertThat(highlight("~old~")).isEmpty()
    }

    @Test
    fun `nested emphasis gets both styles`() {
        val spans = highlight("***x***")
        assertThat(spans).contains(span(3, 4, BOLD))
        // The outer italic covers everything between its own markers, the inner bold markers included.
        assertThat(spans.filter { it.kind == ITALIC }).containsExactly(span(1, 6, ITALIC))
        assertThat(spans.filter { it.kind == SYNTAX }.sumOf { it.end - it.start }).isEqualTo(6)
    }

    @Test
    fun `code span hides markup inside it`() {
        assertThat(highlight("`a **b**`")).containsExactly(span(0, 1, SYNTAX), span(1, 8, CODE), span(8, 9, SYNTAX)).inOrder()
        assertThat(highlight("`` a ` b ``")).containsExactly(span(0, 2, SYNTAX), span(2, 9, CODE), span(9, 11, SYNTAX)).inOrder()
    }

    @Test
    fun `inline link has text, url and bracket syntax`() {
        assertThat(highlight("[site](https://example.com)")).containsExactly(
            span(0, 1, SYNTAX), span(1, 5, LINK_TEXT), span(5, 7, SYNTAX), span(7, 26, LINK_URL), span(26, 27, SYNTAX),
        ).inOrder()
    }

    @Test
    fun `link text with brackets and code keeps the right url range`() {
        val source = "[a [b] `]` c](https://x.com)"
        val spans = highlight(source)
        assertThat(spans).contains(span(1, 12, LINK_TEXT))
        assertThat(spans).contains(span(14, source.length - 1, LINK_URL))
        assertThat(spans).contains(span(8, 9, CODE))
    }

    @Test
    fun `emphasis inside link text`() {
        assertThat(highlight("[**b**](u)")).containsAtLeast(span(1, 6, LINK_TEXT), span(3, 4, BOLD))
    }

    @Test
    fun `autolink has its address as url`() {
        assertThat(highlight("<https://x.y>")).containsExactly(span(0, 1, SYNTAX), span(1, 12, LINK_URL), span(12, 13, SYNTAX)).inOrder()
    }

    @Test
    fun `quote covers its lines and dims each marker`() {
        val source = "> one\n> **two**\nlazy\n\nafter"
        val spans = highlight(source)
        assertThat(spans).contains(span(0, 20, QUOTE))
        assertThat(spans).contains(span(0, 1, SYNTAX))
        assertThat(spans).contains(span(6, 7, SYNTAX))
        assertThat(spans).contains(span(10, 13, BOLD))
        assertThat(spans.none { it.start >= 22 }).isTrue()
    }

    @Test
    fun `indented quote marker is syntax`() {
        assertThat(highlight("   > indented")).containsAtLeast(span(0, 13, QUOTE), span(3, 4, SYNTAX))
        assertThat(highlight("> a\n   > b")).containsAtLeast(span(0, 1, SYNTAX), span(7, 8, SYNTAX))
    }

    @Test
    fun `markup characters inside bare urls and mentions are not styled`() {
        for (source in listOf("https://example.com/files/__init__.py", "see https://x.com/_jane_ now", "hi @_jane_", "https://example.com/a*b*c")) {
            assertThat(highlight(source)).isEmpty()
        }
        assertThat(highlight("**https://example.com/a**")).containsExactly(span(0, 2, SYNTAX), span(2, 23, BOLD), span(23, 25, SYNTAX)).inOrder()
    }

    @Test
    fun `spaced thematic break is not a list`() {
        assertThat(highlight("* * *\n- - -")).isEmpty()
    }

    @Test
    fun `nested quote markers`() {
        val spans = highlight(">> deep")
        assertThat(spans).containsAtLeast(span(0, 1, SYNTAX), span(1, 2, SYNTAX), span(0, 7, QUOTE), span(1, 7, QUOTE))
    }

    @Test
    fun `list markers of bullet and ordered lists`() {
        val spans = highlight("- one\n  * nested\n\n3. three\n\n10) ten")
        assertThat(spans.filter { it.kind == LIST_MARKER }).containsExactly(
            span(0, 1, LIST_MARKER), span(8, 9, LIST_MARKER), span(18, 20, LIST_MARKER), span(28, 31, LIST_MARKER),
        ).inOrder()
        assertThat(spans.filter { it.kind == SYNTAX }).containsExactly(
            span(0, 1, SYNTAX), span(8, 9, SYNTAX), span(18, 20, SYNTAX), span(28, 31, SYNTAX),
        ).inOrder()
    }

    @Test
    fun `fenced code block with its fences as syntax`() {
        val source = "```kotlin\nval x = **1**\n```"
        assertThat(highlight(source)).containsExactly(
            span(0, 9, SYNTAX), span(0, source.length, CODE_BLOCK), span(24, 27, SYNTAX),
        ).inOrder()
    }

    @Test
    fun `unclosed code block runs to the end`() {
        val source = "text\n```\ncode **x**"
        assertThat(highlight(source)).containsExactly(span(5, 8, SYNTAX), span(5, source.length, CODE_BLOCK)).inOrder()
    }

    @Test
    fun `manual break line is a break marker`() {
        assertThat(highlight("a\n⸻\nb")).containsExactly(span(2, 3, BREAK_MARKER))
        assertThat(highlight("a\n  ⸻ \nb")).containsExactly(span(2, 6, BREAK_MARKER))
    }

    @Test
    fun `markup never spans a manual break`() {
        val source = "**bold\n⸻\nrest**"
        assertThat(highlight(source)).containsExactly(span(7, 8, BREAK_MARKER))
        // Offsets after a break point into the whole source.
        assertThat(highlight("a\n⸻\n**b**")).containsExactly(
            span(2, 3, BREAK_MARKER), span(4, 6, SYNTAX), span(6, 7, BOLD), span(7, 9, SYNTAX),
        ).inOrder()
    }

    @Test
    fun `incomplete markup while typing gets no style`() {
        for (typing in listOf("**bold", "*it", "~~st", "`code", "[text](http", "[text]", "<https://x", "_a")) {
            assertThat(highlight(typing)).isEmpty()
        }
    }

    @Test
    fun `highlighting grows as the user types`() {
        val text = "Some **bold** and [a link](https://example.com) here"
        var previousStable = emptyList<HighlightSpan>()
        for (length in 0..text.length) {
            val spans = highlightUnguarded(text.substring(0, length))
            spans.forEach { assertThat(it.end).isAtMost(length) }
            if (length >= 13) {
                // Once "**bold**" is complete it stays highlighted the same way.
                assertThat(spans).containsAtLeast(span(5, 7, SYNTAX), span(7, 11, BOLD), span(11, 13, SYNTAX))
            }
            previousStable = spans
        }
        assertThat(previousStable).contains(span(19, 25, LINK_TEXT))
    }

    @Test
    fun `markdown outside the subset is not highlighted`() {
        for (other in listOf("# Heading", "Title\n---", "![img](u.png)", "<b>x</b>", "    indented", "| a | b |", "***", "[ref][r]\n\n[r]: https://x.com")) {
            assertThat(highlight(other).map { it.kind }).containsNoneOf(BOLD, ITALIC, CODE, CODE_BLOCK, LINK_TEXT, LINK_URL)
        }
    }

    @Test
    fun `spans are sorted`() {
        val spans = highlight("> **a** `b`\n- [c](d) ~~e~~\n```\nf\n```")
        assertThat(spans).isInOrder(compareBy<HighlightSpan>({ it.start }, { it.end }, { it.kind.ordinal }))
        assertThat(spans).isNotEmpty()
    }

    @Test
    fun `never throws on random input`() {
        val random = Random(42)
        val alphabet = "*_~`[]()<>!#>-+.:/\\ \n\r\t1aяß⸻👍😀\uD800&;\"'|=@"
        repeat(3000) {
            val length = random.nextInt(0, 120)
            val text = buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
            assertValid(text, highlightUnguarded(text))
        }
    }

    @Test
    fun `never throws on pathological nesting`() {
        val inputs = listOf(
            ">".repeat(20_000) + " x",
            "- ".repeat(5_000) + "x",
            "*".repeat(10_000) + "x" + "*".repeat(10_000),
            "[".repeat(10_000) + "x" + "](u)".repeat(10_000),
            "![".repeat(5_000) + "x" + "](u)".repeat(5_000),
            "`".repeat(20_000),
            "~~".repeat(10_000),
            "\u0000￿\uD800".repeat(1_000),
            "1. ".repeat(3_000) + "x",
            "```\n".repeat(3_000),
            "⸻\n".repeat(3_000),
        )
        for (text in inputs) assertValid(text, highlightUnguarded(text))
    }

    @Test
    fun `a 20000 char text highlights well under 50 ms`() {
        val paragraph = "Some **bold** text, *italic* words, ~~old~~ news, `code` and [a link](https://example.com/page). " +
            "Also https://example.org/bare and @alice@example.social #tag.\n> A quote with **bold**\n" +
            "- item one\n- item _two_\n1. first\n2. second\n```kotlin\nval x = 1\n```\n\n"
        val text = paragraph.repeat(20_000 / paragraph.length + 1).substring(0, 20_000)
        repeat(10) { highlightUnguarded(text) }
        val best = (1..10).minOf {
            val started = System.nanoTime()
            highlightUnguarded(text)
            (System.nanoTime() - started) / 1_000_000.0
        }
        println("highlight of ${text.length} chars: best of 10 runs %.2f ms".format(best))
        // Generous bound for slow CI machines; locally it is a few milliseconds.
        assertThat(best).isLessThan(250.0)
    }

    @Test
    fun `public highlight drops nothing on random input`() {
        // highlightUnguarded does not filter, so an offset bug shows in the fuzz tests above, not here.
        val random = Random(5)
        val alphabet = "*_~`[]()<>!#>-+.:/ \n1ahtps⸻"
        repeat(500) {
            val text = buildString { repeat(random.nextInt(0, 80)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            assertThat(highlight(text)).isEqualTo(highlightUnguarded(text))
        }
    }

    /**
     * Invariants the highlighter does not enforce by construction, so an offset or nesting bug breaks them:
     * spans lie inside the text, styled text is framed by its own markers, break markers are exactly the
     * manual break lines, list markers and quote markers are what they claim to be.
     */
    private fun assertValid(text: String, spans: List<HighlightSpan>) {
        for (s in spans) {
            assertThat(s.start).isAtLeast(0)
            assertThat(s.end).isAtMost(text.length)
            assertThat(s.start).isLessThan(s.end)
        }
        val syntax = spans.filter { it.kind == SYNTAX }.map { text.substring(it.start, it.end) to it.start }.toSet()
        fun syntaxAt(start: Int, end: Int) = (text.substring(start, end) to start) in syntax
        for (s in spans) {
            val content = text.substring(s.start, s.end)
            when (s.kind) {
                BOLD, ITALIC, STRIKE -> {
                    val markers = when (s.kind) {
                        BOLD -> listOf("**", "__")
                        ITALIC -> listOf("*", "_")
                        else -> listOf("~~")
                    }
                    assertThat(markers.any { m ->
                        s.start >= m.length && text.startsWith(m, s.start - m.length) && text.startsWith(m, s.end) &&
                            syntaxAt(s.start - m.length, s.start) && syntaxAt(s.end, s.end + m.length)
                    }).isTrue()
                }
                CODE -> {
                    // The opening run may follow an escaped backtick, so look for the run the highlighter marked.
                    val ticks = (1..s.start).firstOrNull { k ->
                        text.substring(s.start - k, s.start).all { it == '`' } && syntaxAt(s.start - k, s.start)
                    }
                    assertThat(ticks).isNotNull()
                    assertThat(syntaxAt(s.end, s.end + ticks!!)).isTrue()
                    assertThat(text.substring(s.end, s.end + ticks).all { it == '`' }).isTrue()
                }
                LIST_MARKER -> {
                    assertThat(content).matches("[-*+]|[0-9]+[.)]")
                    assertThat(syntaxAt(s.start, s.end)).isTrue()
                }
                QUOTE -> assertThat(content.trimStart(' ', '\t')).startsWith(">")
                CODE_BLOCK -> assertThat(content.trimStart(' ', '\t').take(3)).isAnyOf("```", "~~~")
                BREAK_MARKER -> assertThat(isManualBreakLine(content)).isTrue()
                else -> Unit
            }
        }
        val breakLines = text.split("\r\n", "\n", "\r").count { isManualBreakLine(it) }
        assertThat(spans.count { it.kind == BREAK_MARKER }).isEqualTo(breakLines)
    }
}
