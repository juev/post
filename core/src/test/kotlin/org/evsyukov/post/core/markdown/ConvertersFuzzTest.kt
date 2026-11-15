package org.evsyukov.post.core.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/** Every converter handles any draft: no exception, and the result keeps its invariants. */
class ConvertersFuzzTest {

    private val random = Random(2026)
    private val alphabet = "*_~`[]()<>!#>-+.:/\\@ \n\r\t1ahtpsx⸻я👍\"&="

    private fun randomText(): String =
        buildString { repeat(random.nextInt(0, 100)) { append(alphabet[random.nextInt(alphabet.length)]) } }

    @Test
    fun `converters never throw and link spans stay inside the text`() {
        repeat(3000) {
            val source = randomText()
            toPlainText(source)
            toMarkdownDialect(source)
            for (mode in BlueskyLinkMode.entries) {
                val result = toBlueskyText(source, mode)
                result.links.zipWithNext { a, b -> assertThat(b.start).isAtLeast(a.end) }
                result.links.forEach {
                    assertThat(it.start).isAtLeast(0)
                    assertThat(it.end).isAtMost(result.text.length)
                    assertThat(it.start).isLessThan(it.end)
                }
            }
        }
    }

    @Test
    fun `converted text never holds a manual break`() {
        repeat(1000) {
            val source = randomText()
            for (converted in listOf(toPlainText(source), toMarkdownDialect(source), toHtml(source), toBlueskyText(source, BlueskyLinkMode.FACET_ON_TEXT).text)) {
                assertThat(converted.lines().none { isManualBreakLine(it) }).isTrue()
            }
        }
    }

    @Test
    fun `text without markup characters passes unchanged`() {
        val plainAlphabet = "abc яж 日本 👍🏽,;?'\n"
        repeat(500) {
            val text = buildString { repeat(random.nextInt(0, 60)) { append(plainAlphabet[random.nextInt(plainAlphabet.length)]) } }
            // Trailing spaces of a line are Markdown line-break syntax, so only they may go.
            fun trimLineEnds(value: String) = value.lines().joinToString("\n") { it.trimEnd() }
            assertThat(trimLineEnds(toPlainText(text))).isEqualTo(trimLineEnds(text))
            assertThat(toMarkdownDialect(text)).isEqualTo(text)
            val bluesky = toBlueskyText(text, BlueskyLinkMode.FACET_ON_TEXT)
            assertThat(trimLineEnds(bluesky.text)).isEqualTo(trimLineEnds(text))
            assertThat(bluesky.links).isEmpty()
        }
    }

    @Test
    fun `dialect conversion keeps the text the reader sees`() {
        repeat(3000) {
            val source = randomText()
            assertThat(toPlainText(toMarkdownDialect(source))).isEqualTo(toPlainText(source))
        }
    }
}
