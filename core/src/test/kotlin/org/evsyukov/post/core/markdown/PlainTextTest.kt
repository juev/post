package org.evsyukov.post.core.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** formatting.md F10: plain text for servers without formatting. */
class PlainTextTest {

    @Test
    fun `verification scenario for vanilla Mastodon`() {
        assertThat(toPlainText("**Hello** _there_ [site](https://example.com)"))
            .isEqualTo("Hello there site (https://example.com)")
    }

    @Test
    fun `emphasis, strikethrough and code markers go and the text stays`() {
        assertThat(toPlainText("**bold** *it* _it_ __b__ ~~old~~ `code`")).isEqualTo("bold it it b old code")
        assertThat(toPlainText("***both*** **bold _inner_ bold**")).isEqualTo("both bold inner bold")
    }

    @Test
    fun `code span keeps markup inside it as text`() {
        assertThat(toPlainText("use `a **b** _c_` here")).isEqualTo("use a **b** _c_ here")
        assertThat(toPlainText("``a ` b``")).isEqualTo("a ` b")
    }

    @Test
    fun `unclosed and unsupported markup stays as typed`() {
        assertThat(toPlainText("**bold and ~one~ and snake_case_name")).isEqualTo("**bold and ~one~ and snake_case_name")
        assertThat(toPlainText("# Title\nText")).isEqualTo("# Title\nText")
        assertThat(toPlainText("Title\n---")).isEqualTo("Title\n---")
        assertThat(toPlainText("    indented")).isEqualTo("    indented")
        assertThat(toPlainText("| a | b |\n|---|---|")).isEqualTo("| a | b |\n|---|---|")
    }

    @Test
    fun `images and inline html stay literal`() {
        assertThat(toPlainText("![alt *x*](https://x.com/i.png) and <b>bold</b>"))
            .isEqualTo("![alt *x*](https://x.com/i.png) and <b>bold</b>")
    }

    @Test
    fun `escapes and entities become their characters`() {
        assertThat(toPlainText("\\*not italic\\* &amp; &copy;")).isEqualTo("*not italic* & ©")
    }

    @Test
    fun `block quote keeps its prefix`() {
        assertThat(toPlainText("> quote with **bold**\n> second line")).isEqualTo("> quote with bold\n> second line")
        assertThat(toPlainText("> > nested _it_")).isEqualTo("> > nested it")
    }

    @Test
    fun `bullet list items become bullet lines`() {
        assertThat(toPlainText("- one\n- **two**\n* three\n+ four")).isEqualTo("• one\n• two\n• three\n• four")
    }

    @Test
    fun `numbered list items are numbered from the list start`() {
        assertThat(toPlainText("1. one\n2. two")).isEqualTo("1. one\n2. two")
        assertThat(toPlainText("1. a\n1. b\n1. c")).isEqualTo("1. a\n2. b\n3. c")
        assertThat(toPlainText("3) a\n4) b")).isEqualTo("3. a\n4. b")
    }

    @Test
    fun `nested lists and lists in quotes keep their indentation`() {
        assertThat(toPlainText("- a\n  - b\n    1. c")).isEqualTo("• a\n  • b\n    1. c")
        assertThat(toPlainText("> - quoted item")).isEqualTo("> • quoted item")
        assertThat(toPlainText("- a\n\n  continued")).isEqualTo("• a\n\n  continued")
    }

    @Test
    fun `link becomes text and url in parentheses`() {
        assertThat(toPlainText("[docs](https://example.com/docs \"Title\")")).isEqualTo("docs (https://example.com/docs)")
        assertThat(toPlainText("[**bold** link](https://x.com)")).isEqualTo("bold link (https://x.com)")
        assertThat(toPlainText("[a [b] c](https://x.com)")).isEqualTo("a [b] c (https://x.com)")
        assertThat(toPlainText("[a](/relative)")).isEqualTo("a (/relative)")
    }

    @Test
    fun `link whose text is the url becomes only the url`() {
        assertThat(toPlainText("[https://example.com](https://example.com)")).isEqualTo("https://example.com")
        assertThat(toPlainText("[example.com](https://example.com)")).isEqualTo("https://example.com")
        assertThat(toPlainText("[Example.com/](http://example.com)")).isEqualTo("http://example.com")
        assertThat(toPlainText("[](https://example.com)")).isEqualTo("https://example.com")
    }

    @Test
    fun `autolinks lose their angle brackets`() {
        assertThat(toPlainText("<https://example.com/x> and <me@example.com>")).isEqualTo("https://example.com/x and me@example.com")
    }

    @Test
    fun `reference links stay as typed`() {
        val source = "[a][r] and [r]\n\n[r]: https://x.com"
        assertThat(toPlainText(source)).isEqualTo(source)
    }

    @Test
    fun `code fences go and the code stays`() {
        assertThat(toPlainText("```kotlin\nval x = **1**\n```")).isEqualTo("val x = **1**")
        assertThat(toPlainText("before\n```\ncode\n  more\n```\nafter")).isEqualTo("before\ncode\n  more\nafter")
        assertThat(toPlainText("text\n```\nunclosed")).isEqualTo("text\nunclosed")
        assertThat(toPlainText("a\n```\n```\nb")).isEqualTo("a\n\nb")
        // A blank first code line is code too and stays.
        assertThat(toPlainText("```\n\ncode\n```")).isEqualTo("\ncode")
        assertThat(toPlainText("> ```\n>\n> code\n> ```")).isEqualTo(">\n> code")
    }

    @Test
    fun `code blocks inside quotes and lists`() {
        assertThat(toPlainText("> ```\n> code\n> ```\n> after")).isEqualTo("> code\n> after")
        assertThat(toPlainText("- ```\n  code\n  ```")).isEqualTo("• code")
    }

    @Test
    fun `hard line breaks become plain line breaks`() {
        assertThat(toPlainText("a  \nb")).isEqualTo("a\nb")
        assertThat(toPlainText("a\\\nb")).isEqualTo("a\nb")
        assertThat(toPlainText("a\nb")).isEqualTo("a\nb")
    }

    @Test
    fun `blank lines and line endings stay as typed`() {
        assertThat(toPlainText("a\n\n\nb")).isEqualTo("a\n\n\nb")
        assertThat(toPlainText("**a**\r\n- b\r\n")).isEqualTo("a\r\n• b\r\n")
    }

    @Test
    fun `manual break lines are removed`() {
        assertThat(toPlainText("first **one**\n\n⸻\n\nsecond")).isEqualTo("first one\n\nsecond")
        assertThat(toPlainText("⸻")).isEmpty()
    }

    @Test
    fun `non-latin text and emoji are untouched`() {
        assertThat(toPlainText("Привет, **мир** 👨‍👩‍👧‍👦 _日本_")).isEqualTo("Привет, мир 👨‍👩‍👧‍👦 日本")
    }
}
