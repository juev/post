package org.evsyukov.post.core.thread

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.markdown.EmphasisSpan
import org.evsyukov.post.core.markdown.analyze
import org.evsyukov.post.core.testing.IcuTextSegmenter
import org.junit.Test
import java.util.Locale

/** T4–T5: where a text may break, and how good each place is. */
class BreaksTest {
    private val segmenter = IcuTextSegmenter()

    private fun breaks(text: String, locale: Locale = Locale.ENGLISH) = findBreaks(text, analyze(text), segmenter, locale)

    /** The rank of the break whose part before ends right after [before]. */
    private fun rankAfter(text: String, before: String): BreakRank? {
        val end = text.indexOf(before) + before.length
        return breaks(text).firstOrNull { it.end == end }?.rank
    }

    @Test
    fun `T4 every rank from paragraph to word`() {
        val text = "Para one.\n\nLine one\nline two. Next sentence; a clause, a comma — a dash then words."
        assertThat(rankAfter(text, "Para one.")).isEqualTo(BreakRank.PARAGRAPH)
        assertThat(rankAfter(text, "Line one")).isEqualTo(BreakRank.LINE)
        assertThat(rankAfter(text, "line two.")).isEqualTo(BreakRank.SENTENCE)
        assertThat(rankAfter(text, "sentence;")).isEqualTo(BreakRank.CLAUSE)
        assertThat(rankAfter(text, "a clause,")).isEqualTo(BreakRank.CLAUSE)
        assertThat(rankAfter(text, "a comma —")).isEqualTo(BreakRank.CLAUSE)
        assertThat(rankAfter(text, "a dash")).isEqualTo(BreakRank.WORD)
        assertThat(rankAfter(text, "a comma")).isEqualTo(BreakRank.WORD)
    }

    @Test
    fun `T4 markup next to a sentence end does not hide it`() {
        val text = "It is **done.** Next one starts. _Really._ Yes"
        assertThat(rankAfter(text, "**done.**")).isEqualTo(BreakRank.SENTENCE)
        assertThat(rankAfter(text, "_Really._")).isEqualTo(BreakRank.SENTENCE)
    }

    @Test
    fun `T10 the whitespace and blank quote lines of a break are dropped`() {
        val text = "> one\n>\n> two"
        val cut = breaks(text).single { it.rank == BreakRank.PARAGRAPH }
        assertThat(text.substring(0, cut.end)).isEqualTo("> one")
        assertThat(text.substring(cut.start)).isEqualTo("> two")
    }

    @Test
    fun `T5 no break inside links, code spans, URLs, mentions or hashtags`() {
        val text = "a [link with words](https://example.com) b `code with spaces` c https://example.com/x d @alice@example.social e #tag f"
        val structure = analyze(text)
        for (cut in breaks(text)) {
            for (atom in structure.atomicRanges) {
                assertThat(atom.start < cut.end && cut.end < atom.end).isFalse()
                assertThat(atom.start < cut.start && cut.start < atom.end).isFalse()
            }
        }
        assertThat(breaks(text).map { text.substring(0, it.end).substringAfterLast(' ') })
            .containsExactly("a", "words](https://example.com)", "b", "spaces`", "c", "https://example.com/x", "d", "@alice@example.social", "e", "#tag")
            .inOrder()
    }

    @Test
    fun `no break between a quote or list marker and its text`() {
        val text = "> quoted words\n- item words\n1. numbered words"
        val starts = breaks(text).map { text.substring(it.start).split(" ", "\n").first() }
        assertThat(starts).containsExactly("words", "-", "words", "1.", "words").inOrder()
    }

    @Test
    fun `T5 a code block breaks only between its lines and keeps their indentation`() {
        val text = "Intro\n```kotlin\nfun a() {\n    b()\n\n}\n```\nOutro"
        val inside = breaks(text).filter { it.codeBlock != null }
        assertThat(inside.map { text.substring(it.start).lineSequence().first() }).containsExactly("    b()", "}").inOrder()
        assertThat(inside.map { it.rank }).containsExactly(BreakRank.LINE, BreakRank.PARAGRAPH).inOrder()
        assertThat(inside.first().codeBlock!!.openingFence).isEqualTo("```kotlin")
        // Next to the fences there is no break: a part would hold a fence alone.
        val outside = breaks(text).filter { it.codeBlock == null }.map { text.substring(it.start).lineSequence().first() }
        assertThat(outside).containsExactly("```kotlin", "Outro").inOrder()
    }

    @Test
    fun `T5 crossing emphasis is listed outermost first`() {
        val text = "~~struck **bold words** more~~"
        val cut = breaks(text).single { text.substring(0, it.end).endsWith("bold") }
        assertThat(cut.emphasis.map { it.kind }).containsExactly(EmphasisSpan.Kind.STRIKE, EmphasisSpan.Kind.BOLD).inOrder()
        assertThat(breaks(text).single { text.substring(0, it.end).endsWith("struck") }.emphasis.map { it.kind })
            .containsExactly(EmphasisSpan.Kind.STRIKE)
    }

    @Test
    fun `a part that starts inside a quoted line reopens its quotes`() {
        val text = "> > deep one. deep two\n> > next line"
        val midLine = breaks(text).single { text.substring(0, it.end).endsWith("one.") }
        assertThat(midLine.quoteDepth).isEqualTo(2)
        val lineStart = breaks(text).single { text.substring(0, it.end).endsWith("two") }
        assertThat(lineStart.quoteDepth).isEqualTo(0)
    }

    @Test
    fun `T5 no break inside a grapheme cluster or at a space that does not break`() {
        val combining = "a ́b c"
        assertThat(breaks(combining).map { it.end }).containsExactly(4)
        val nbsp = "a b c"
        assertThat(breaks(nbsp).map { it.end }).containsExactly(3)
    }

    @Test
    fun `T4 scripts without spaces break between words, best after their punctuation`() {
        val text = "我们今天去公园散步，天气非常好。明天再见"
        val found = breaks(text, Locale.CHINESE)
        assertThat(found.all { it.end == it.start }).isTrue()
        assertThat(found.first { text[it.end - 1] == '，' }.rank).isEqualTo(BreakRank.CLAUSE)
        assertThat(found.first { text[it.end - 1] == '。' }.rank).isEqualTo(BreakRank.SENTENCE)
        assertThat(found.count { it.rank == BreakRank.WORD }).isAtLeast(3)
    }

    @Test
    fun `a part that starts at a literal greater-than sign still reopens the quotes it is in`() {
        val text = "> one > two three"
        val cut = breaks(text).single { text.substring(0, it.end).endsWith("one") }
        assertThat(text[cut.start]).isEqualTo('>')
        assertThat(cut.quoteDepth).isEqualTo(1)
        val plain = "one > two"
        assertThat(breaks(plain).single { plain.substring(0, it.end).endsWith("one") }.quoteDepth).isEqualTo(0)
    }

    @Test
    fun `T17 no break before a code span that a part would read as a fence`() {
        val text = "Start ```js code\nmore``` end"
        assertThat(rankAfter(text, "Start")).isNull()
        assertThat(rankAfter(text, "more```")).isEqualTo(BreakRank.WORD)
    }

    @Test
    fun `T17 no break inside a line that reads otherwise when cut short`() {
        // With its backticks, the first line is no fence; cut before them, it would be one and hide its text.
        val fenceLike = "```js and more ``` here\nnext line"
        assertThat(breaks(fenceLike).map { fenceLike.substring(0, it.end) })
            .containsExactly("```js and more ``` here", "```js and more ``` here\nnext").inOrder()
        // Cut after the URL, the line would be a link reference definition and show nothing.
        val definition = "Text.\n\n[ref]: https://example.com/a and more"
        assertThat(breaks(definition).map { definition.substring(0, it.end) }).containsExactly("Text.")
    }

    @Test
    fun `an empty text has no breaks`() {
        assertThat(breaks("")).isEmpty()
        assertThat(breaks("single")).isEmpty()
    }
}
