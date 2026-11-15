package org.evsyukov.post.core.markdown

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.markdown.AtomicRange.Kind.AUTOLINK
import org.evsyukov.post.core.markdown.AtomicRange.Kind.CODE_SPAN
import org.evsyukov.post.core.markdown.AtomicRange.Kind.HASHTAG
import org.evsyukov.post.core.markdown.AtomicRange.Kind.IMAGE
import org.evsyukov.post.core.markdown.AtomicRange.Kind.LINK
import org.evsyukov.post.core.markdown.AtomicRange.Kind.MENTION
import org.evsyukov.post.core.markdown.AtomicRange.Kind.URL
import org.junit.Test
import kotlin.random.Random

/** The contract of analyze() for the thread splitter: threads.md T4, T5 and T13. */
class AnalyzeTest {

    /** The atomic range of [kind] over the first occurrence of [part] in [source]. */
    private fun atom(source: String, part: String, kind: AtomicRange.Kind): AtomicRange {
        val start = source.indexOf(part)
        check(start >= 0) { "$part is not in the source" }
        return AtomicRange(start, start + part.length, kind)
    }

    @Test
    fun `atomic ranges cover code spans, links, autolinks, urls, mentions, hashtags and images`() {
        val source = "Text `a b` [the link](https://x.com/a \"T\") <https://y.com> https://z.com/p, " +
            "@alice@example.social @bob.bsky.social #tag ![an image](i.png)."
        assertThat(analyze(source).atomicRanges).containsExactly(
            atom(source, "`a b`", CODE_SPAN),
            atom(source, "[the link](https://x.com/a \"T\")", LINK),
            atom(source, "<https://y.com>", AUTOLINK),
            atom(source, "https://z.com/p", URL),
            atom(source, "@alice@example.social", MENTION),
            atom(source, "@bob.bsky.social", MENTION),
            atom(source, "#tag", HASHTAG),
            atom(source, "![an image](i.png)", IMAGE),
        ).inOrder()
    }

    @Test
    fun `no entities inside code, links and code blocks`() {
        val source = "`https://a.com @x.y` [@bob.bsky.social #t](https://b.com)\n```\nhttps://c.com #tag\n```"
        assertThat(analyze(source).atomicRanges).containsExactly(
            atom(source, "`https://a.com @x.y`", CODE_SPAN),
            atom(source, "[@bob.bsky.social #t](https://b.com)", LINK),
        ).inOrder()
    }

    @Test
    fun `code and images inside link text belong to the link range`() {
        val source = "see [`code` and ![i](j.png)](https://x.com) now"
        assertThat(analyze(source).atomicRanges).containsExactly(atom(source, "[`code` and ![i](j.png)](https://x.com)", LINK))
    }

    @Test
    fun `bare entities end at emphasis markers`() {
        val source = "**https://example.com.** and *@alice* and _#tag_"
        assertThat(analyze(source).atomicRanges).containsExactly(
            atom(source, "https://example.com", URL),
            atom(source, "@alice", MENTION),
            atom(source, "#tag", HASHTAG),
        ).inOrder()
    }

    @Test
    fun `overlapping mention forms give one range`() {
        val source = "hi @alice.bsky.social"
        assertThat(analyze(source).atomicRanges).containsExactly(atom(source, "@alice.bsky.social", MENTION))
    }

    @Test
    fun `emphasis spans carry their exact markers and ranges`() {
        val source = "a **bold _it_ end** ~~gone~~ __b__"
        val emphasis = analyze(source).emphasis
        assertThat(emphasis).containsExactly(
            EmphasisSpan(EmphasisSpan.Kind.BOLD, "**", "**", SourceRange(2, 4), SourceRange(17, 19)),
            EmphasisSpan(EmphasisSpan.Kind.ITALIC, "_", "_", SourceRange(9, 10), SourceRange(12, 13)),
            EmphasisSpan(EmphasisSpan.Kind.STRIKE, "~~", "~~", SourceRange(20, 22), SourceRange(26, 28)),
            EmphasisSpan(EmphasisSpan.Kind.BOLD, "__", "__", SourceRange(29, 31), SourceRange(32, 34)),
        ).inOrder()
        assertThat(emphasis[0].range).isEqualTo(SourceRange(2, 19))
        assertThat(emphasis[0].contentRange).isEqualTo(SourceRange(4, 17))
        assertThat(source.substring(emphasis[1].contentRange.start, emphasis[1].contentRange.end)).isEqualTo("it")
    }

    @Test
    fun `emphasis across lines is one span`() {
        val source = "**bold text that\ncrosses the line**"
        val span = analyze(source).emphasis.single()
        assertThat(span.openRange).isEqualTo(SourceRange(0, 2))
        assertThat(span.closeRange).isEqualTo(SourceRange(source.length - 2, source.length))
    }

    @Test
    fun `unclosed emphasis is not a span`() {
        assertThat(analyze("**not closed").emphasis).isEmpty()
    }

    @Test
    fun `fenced code block with fences, info string and content`() {
        val source = "Intro\n```kotlin\nval a = 1\nval b = 2\n```\nafter"
        val block = analyze(source).codeBlocks.single()
        assertThat(block.openingFence).isEqualTo("```kotlin")
        assertThat(block.openingFenceRange).isEqualTo(SourceRange(6, 15))
        assertThat(block.closingFence).isEqualTo("```")
        assertThat(block.closingFenceRange).isEqualTo(SourceRange(36, 39))
        assertThat(block.contentRange).isEqualTo(SourceRange(16, 35))
        assertThat(block.range).isEqualTo(SourceRange(6, 39))
    }

    @Test
    fun `unclosed code block gets the fence that would close it`() {
        val source = "~~~~py\nx = 1"
        val block = analyze(source).codeBlocks.single()
        assertThat(block.openingFence).isEqualTo("~~~~py")
        assertThat(block.closingFence).isEqualTo("~~~~")
        assertThat(block.closingFenceRange).isNull()
        assertThat(block.contentRange).isEqualTo(SourceRange(7, 12))
        assertThat(block.range).isEqualTo(SourceRange(0, 12))
    }

    @Test
    fun `empty code block has no content range`() {
        val block = analyze("```\n```").codeBlocks.single()
        assertThat(block.contentRange).isNull()
        assertThat(block.closingFenceRange).isEqualTo(SourceRange(4, 7))
    }

    @Test
    fun `quote lines and lazy continuation`() {
        val source = "> one\n> two\nlazy\n\nafter"
        val quote = analyze(source).quotes.single()
        assertThat(quote.lines).containsExactly(SourceRange(0, 5), SourceRange(6, 11)).inOrder()
        assertThat(quote.range).isEqualTo(SourceRange(0, 16))
    }

    @Test
    fun `nested quotes are reported separately`() {
        val quotes = analyze("> a\n>> b").quotes
        assertThat(quotes.map { it.range }).containsExactly(SourceRange(0, 8), SourceRange(5, 8)).inOrder()
    }

    @Test
    fun `list items with markers`() {
        val source = "- a\n- b\n  more\n\n3. c\n4. d"
        val items = analyze(source).listItems
        assertThat(items).containsExactly(
            ListItemSpan(SourceRange(0, 3), SourceRange(0, 1), "-", ordered = false),
            ListItemSpan(SourceRange(4, 14), SourceRange(4, 5), "-", ordered = false),
            ListItemSpan(SourceRange(16, 20), SourceRange(16, 18), "3.", ordered = true),
            ListItemSpan(SourceRange(21, 25), SourceRange(21, 23), "4.", ordered = true),
        ).inOrder()
    }

    @Test
    fun `manual breaks are reported and split the parsing`() {
        val source = "a **b\n⸻\nc** `d`"
        val structure = analyze(source)
        assertThat(structure.manualBreaks).containsExactly(SourceRange(6, 7))
        assertThat(structure.emphasis).isEmpty()
        assertThat(structure.atomicRanges).containsExactly(atom(source, "`d`", CODE_SPAN))
    }

    @Test
    fun `offsets after a manual break point into the whole source`() {
        val source = "one\n⸻\n**two** [x](https://x.com)"
        val structure = analyze(source)
        assertThat(structure.emphasis.single().openRange).isEqualTo(SourceRange(6, 8))
        assertThat(structure.atomicRanges).containsExactly(atom(source, "[x](https://x.com)", LINK))
    }

    @Test
    fun `ranges are valid and sorted on random input`() {
        val random = Random(99)
        val alphabet = "*_~`[]()<>!#>-+.:/@ \n1ahtps⸻👍"
        repeat(1500) {
            val text = buildString { repeat(random.nextInt(0, 100)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            val structure = analyze(text)
            val ranges = structure.atomicRanges.map { SourceRange(it.start, it.end) } +
                structure.emphasis.flatMap { listOf(it.openRange, it.closeRange) } +
                structure.codeBlocks.flatMap { listOfNotNull(it.range, it.openingFenceRange, it.closingFenceRange, it.contentRange) } +
                structure.quotes.flatMap { it.lines + it.range } +
                structure.listItems.flatMap { listOf(it.range, it.markerRange) } +
                structure.manualBreaks
            ranges.forEach { assertThat(it.end).isAtMost(text.length) }
            structure.atomicRanges.zipWithNext { a, b -> assertThat(b.start).isAtLeast(a.end) }
            structure.emphasis.forEach {
                assertThat(text.substring(it.openRange.start, it.openRange.end)).isEqualTo(it.open)
                assertThat(text.substring(it.closeRange.start, it.closeRange.end)).isEqualTo(it.close)
            }
        }
    }
}
