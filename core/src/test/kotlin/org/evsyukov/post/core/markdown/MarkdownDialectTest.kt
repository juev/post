package org.evsyukov.post.core.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** formatting.md F8: Markdown for text/markdown servers, italic always `*x*`. */
class MarkdownDialectTest {

    @Test
    fun `verification scenario for GoToSocial`() {
        assertThat(toMarkdownDialect("**Hello** _there_ [site](https://example.com)"))
            .isEqualTo("**Hello** *there* [site](https://example.com)")
    }

    @Test
    fun `underscore emphasis becomes stars`() {
        assertThat(toMarkdownDialect("_a_ and *b* and __c__ and **d**")).isEqualTo("*a* and *b* and **c** and **d**")
        assertThat(toMarkdownDialect("_multi word italic_")).isEqualTo("*multi word italic*")
    }

    @Test
    fun `underscores that are not emphasis stay`() {
        val source = "snake_case_name and \\_escaped\\_ and `_code_` and _unclosed"
        assertThat(toMarkdownDialect(source)).isEqualTo(source)
        val block = "```\n_x_ __y__\n```"
        assertThat(toMarkdownDialect(block)).isEqualTo(block)
    }

    @Test
    fun `italic directly inside italic does not turn into bold`() {
        assertThat(toMarkdownDialect("*_x_*")).isEqualTo("*x*")
        assertThat(toMarkdownDialect("_*y*_")).isEqualTo("*y*")
        assertThat(toMarkdownDialect("*_a_ b*")).isEqualTo("*a b*")
    }

    @Test
    fun `nested emphasis keeps its meaning`() {
        assertThat(toMarkdownDialect("**_z_**")).isEqualTo("***z***")
        assertThat(toMarkdownDialect("__*w*__")).isEqualTo("***w***")
        assertThat(toMarkdownDialect("*a _b_ c*")).isEqualTo("*a *b* c*")
        for (source in listOf("**_z_**", "__*w*__", "*a _b_ c*", "_a **b** c_", "**bold _it_ more**")) {
            val dialect = toMarkdownDialect(source)
            assertThat(toPlainText(dialect)).isEqualTo(toPlainText(source))
            assertThat(highlight(dialect).map { it.kind }.toSet()).isEqualTo(highlight(source).map { it.kind }.toSet())
        }
    }

    @Test
    fun `replacement that would join a literal star is left out`() {
        // Found by the fuzz test: "@**=b)*>" has no emphasis (the rule of 3), while the source has "=b)" in italic.
        val source = "@*_=b)_> and _ok_"
        assertThat(toMarkdownDialect(source)).isEqualTo("@*_=b)_> and *ok*")
        assertThat(toPlainText(toMarkdownDialect(source))).isEqualTo(toPlainText(source))
    }

    @Test
    fun `everything else stays exactly as typed`() {
        val source = "# Heading\n> quote _it_\n- item\n1) one\n![i](u.png) <b>x</b> ~~s~~ `c`\n\n```kt\nval a = 1\n```\n" +
            "[ref][r]\n\n[r]: https://x.com\n    indented\nПривет 👍🏽"
        assertThat(toMarkdownDialect(source)).isEqualTo(source.replace("_it_", "*it*"))
    }

    @Test
    fun `manual break lines are removed`() {
        assertThat(toMarkdownDialect("a _x_\n⸻\nb")).isEqualTo("a *x*\n\nb")
    }
}
