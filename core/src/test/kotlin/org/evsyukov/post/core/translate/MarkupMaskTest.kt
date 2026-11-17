package org.evsyukov.post.core.translate

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The masking that keeps the draft's Markdown out of a translation (screens.md, Composer). */
class MarkupMaskTest {
    private val draft = """
        **Bold** and *italic* with `code`
        [The link](https://example.com/page) and a bare https://example.org/x.
        - item one, @ann@example.social and #news
        > quoted line
        ⸻
        Second piece.
    """.trimIndent()

    /** What an engine does to prose while leaving placeholders alone: here, upper case. */
    private fun upperCase(engineInput: String): String = engineInput.uppercase()

    @Test
    fun maskingAndUnmaskingGiveTheDraftBack() {
        val masked = MarkupMask.mask(draft)

        assertThat(MarkupMask.unmask(masked, masked.text)).isEqualTo(draft)
    }

    @Test
    fun markupNeverReachesTheEngine() {
        val masked = MarkupMask.mask(draft).text

        assertThat(masked).doesNotContain("**")
        assertThat(masked).doesNotContain("*italic*")
        assertThat(masked).doesNotContain("`code`")
        assertThat(masked).doesNotContain("[The link]")
        assertThat(masked).doesNotContain("https://")
        assertThat(masked).doesNotContain("@ann@example.social")
        assertThat(masked).doesNotContain("#news")
        assertThat(masked).doesNotContain("⸻")
    }

    @Test
    fun proseIsTranslatedAndMarkupIsPutBack() {
        val masked = MarkupMask.mask(draft)

        val result = MarkupMask.unmask(masked, upperCase(masked.text))

        assertThat(result).isNotNull()
        assertThat(result!!).contains("**BOLD** AND *ITALIC* WITH `code`")
        assertThat(result).contains("[THE LINK](https://example.com/page)")
        assertThat(result).contains("- ITEM ONE, @ann@example.social AND #news")
        assertThat(result).contains("> QUOTED LINE")
        assertThat(result).contains("⸻\nSECOND PIECE.")
        assertThat(result).contains("https://example.org/x.")
    }

    @Test
    fun aCodeSpanAndACodeBlockAreLeftUntouched() {
        val source = "Run `rm -rf /` now.\n\n```\nval x = 1\n```\n"
        val masked = MarkupMask.mask(source)

        val result = MarkupMask.unmask(masked, upperCase(masked.text))!!

        assertThat(result).isEqualTo("RUN `rm -rf /` NOW.\n\n```\nval x = 1\n```\n")
    }

    @Test
    fun aSentenceEndingAfterAUrlKeepsItsFullStop() {
        val masked = MarkupMask.mask("See https://example.com/x.")

        assertThat(masked.tokens.map { it.text }).containsExactly("https://example.com/x")
        assertThat(MarkupMask.unmask(masked, "SIEH {{0}}.")).isEqualTo("SIEH https://example.com/x.")
    }

    @Test
    fun aLinkAddressIsKeptWhileItsTextIsTranslated() {
        val masked = MarkupMask.mask("[Hallo](https://example.com) Welt")

        // Tokens: `[`, `](`, the address, `)`.
        val result = MarkupMask.unmask(masked, "{{0}}HELLO{{1}}{{2}}{{3}} WORLD")!!

        assertThat(result).isEqualTo("[HELLO](https://example.com) WORLD")
    }

    @Test
    fun aSpaceTheEngineAddedAroundMarkupIsRemoved() {
        val masked = MarkupMask.mask("**bold** text")

        // The engine spaced the placeholder out; Markdown would no longer see bold.
        val result = MarkupMask.unmask(masked, "{{0}} fett {{1}} TEXT")!!

        assertThat(result).isEqualTo("**fett** TEXT")
    }

    @Test
    fun aMissingPlaceholderRefusesTheTranslation() {
        val masked = MarkupMask.mask("**bold** text")

        assertThat(MarkupMask.unmask(masked, "{{0}} TEXT")).isNull()
    }

    @Test
    fun aRepeatedPlaceholderRefusesTheTranslation() {
        val masked = MarkupMask.mask("**bold** text")

        assertThat(MarkupMask.unmask(masked, "{{0}} TEXT {{0}}")).isNull()
    }

    @Test
    fun placeholdersThatMovedWithTheirWordsAreAccepted() {
        val masked = MarkupMask.mask("**bold** and [link](https://example.com)")

        // A language that puts the link last: the pieces travel with their tokens.
        val result = MarkupMask.unmask(masked, "UND {{0}}FETT{{1}} {{2}}LINK{{3}}{{4}}{{5}}")

        assertThat(result).isEqualTo("UND **FETT** [LINK](https://example.com)")
    }

    @Test
    fun aTextWithTheTokenDelimitersGetsOthers() {
        val masked = MarkupMask.mask("**bold** with {{code}} in it")

        assertThat(masked.text).contains("[[0]]")
        assertThat(MarkupMask.unmask(masked, masked.text)).isEqualTo("**bold** with {{code}} in it")
    }

    @Test
    fun aDraftWithoutMarkupIsTranslatedAsOnePiece() {
        val masked = MarkupMask.mask("Просто текст")

        assertThat(masked.tokens).isEmpty()
        assertThat(MarkupMask.unmask(masked, "Just text")).isEqualTo("Just text")
    }
}
