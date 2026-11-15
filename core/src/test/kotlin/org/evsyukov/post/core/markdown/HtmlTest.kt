package org.evsyukov.post.core.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/** formatting.md F9: HTML with only the tags of F3, everything else escaped. */
class HtmlTest {

    @Test
    fun `verification text as html`() {
        assertThat(toHtml("**Hello** _there_ [site](https://example.com)"))
            .isEqualTo("<p><strong>Hello</strong> <em>there</em> <a href=\"https://example.com\">site</a></p>")
    }

    @Test
    fun `paragraphs and line breaks`() {
        assertThat(toHtml("a\nb")).isEqualTo("<p>a<br>b</p>")
        assertThat(toHtml("a  \nb")).isEqualTo("<p>a<br>b</p>")
        assertThat(toHtml("a\n\nb")).isEqualTo("<p>a</p><p>b</p>")
        assertThat(toHtml("")).isEmpty()
    }

    @Test
    fun `strikethrough and code`() {
        assertThat(toHtml("~~old~~ `<b>`")).isEqualTo("<p><del>old</del> <code>&lt;b&gt;</code></p>")
        assertThat(toHtml("```html\n<b>x</b> & y\n```")).isEqualTo("<pre><code>&lt;b&gt;x&lt;/b&gt; &amp; y\n</code></pre>")
    }

    @Test
    fun `quotes and lists`() {
        assertThat(toHtml("> quote **b**")).isEqualTo("<blockquote><p>quote <strong>b</strong></p></blockquote>")
        assertThat(toHtml("- a\n- b")).isEqualTo("<ul><li>a</li><li>b</li></ul>")
        assertThat(toHtml("3. a\n4. b")).isEqualTo("<ol><li>a</li><li>b</li></ol>")
        assertThat(toHtml("- a\n\n- b")).isEqualTo("<ul><li><p>a</p></li><li><p>b</p></li></ul>")
        assertThat(toHtml("- a\n  - b")).isEqualTo("<ul><li>a<ul><li>b</li></ul></li></ul>")
    }

    @Test
    fun `html in the draft is escaped`() {
        assertThat(toHtml("<script>alert(1)</script> & \"q\" 'x'"))
            .isEqualTo("<p>&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;q&quot; 'x'</p>")
        assertThat(toHtml("<div>\nblock\n</div>")).isEqualTo("<p>&lt;div&gt;<br>block<br>&lt;/div&gt;</p>")
    }

    @Test
    fun `markdown outside the subset is escaped text`() {
        assertThat(toHtml("# Title")).isEqualTo("<p># Title</p>")
        assertThat(toHtml("![alt](https://x.com/i.png)")).isEqualTo("<p>![alt](https://x.com/i.png)</p>")
        assertThat(toHtml("***")).isEqualTo("<p>***</p>")
        assertThat(toHtml("[a][r]\n\n[r]: https://x.com")).isEqualTo("<p>[a][r]</p><p>[r]: https://x.com</p>")
    }

    @Test
    fun `links to http, https and mailto become anchors`() {
        assertThat(toHtml("<https://x.com/?a=1&b=2>")).isEqualTo("<p><a href=\"https://x.com/?a=1&amp;b=2\">https://x.com/?a=1&amp;b=2</a></p>")
        assertThat(toHtml("<me@example.com>")).isEqualTo("<p><a href=\"mailto:me@example.com\">me@example.com</a></p>")
        assertThat(toHtml("[https://example.com](https://example.com)"))
            .isEqualTo("<p><a href=\"https://example.com\">https://example.com</a></p>")
    }

    @Test
    fun `links to other destinations become text`() {
        assertThat(toHtml("[x](javascript:alert(1))")).isEqualTo("<p>x (javascript:alert(1))</p>")
        assertThat(toHtml("[x](/relative)")).isEqualTo("<p>x (/relative)</p>")
        assertThat(toHtml("[x](\"><script>)")).doesNotContain("<script")
    }

    @Test
    fun `bare urls become anchors`() {
        assertThat(toHtml("see https://example.com/a."))
            .isEqualTo("<p>see <a href=\"https://example.com/a\">https://example.com/a</a>.</p>")
    }

    @Test
    fun `bare urls with markup characters become one anchor`() {
        assertThat(toHtml("https://example.com/files/__init__.py")).isEqualTo(
            "<p><a href=\"https://example.com/files/__init__.py\">https://example.com/files/__init__.py</a></p>",
        )
        assertThat(toHtml("https://mastodon.social/@_jane_")).isEqualTo(
            "<p><a href=\"https://mastodon.social/@_jane_\">https://mastodon.social/@_jane_</a></p>",
        )
        assertThat(toHtml("**https://example.com/a**")).isEqualTo(
            "<p><strong><a href=\"https://example.com/a\">https://example.com/a</a></strong></p>",
        )
        assertThat(toHtml("«https://example.com/path»")).isEqualTo(
            "<p>«<a href=\"https://example.com/path\">https://example.com/path</a>»</p>",
        )
    }

    @Test
    fun `spaced thematic break is a paragraph as typed`() {
        assertThat(toHtml("* * *")).isEqualTo("<p>* * *</p>")
        assertThat(toHtml("a\n- - -\nb")).isEqualTo("<p>a</p><p>- - -</p><p>b</p>")
    }

    @Test
    fun `manual break lines are removed`() {
        assertThat(toHtml("a\n⸻\nb")).isEqualTo("<p>a</p><p>b</p>")
    }

    @Test
    fun `only allowed tags and attributes on any input`() {
        val corpus = listOf(
            "**b** _i_ ~~s~~ `c` [l](https://x.com) <https://y.com> > q",
            "> - a\n>   1. b\n```js\n<x>\n```\n<img src=x onerror=alert(1)>",
            "[a](https://x.com \"t\" onclick=1) ![i](j.png) <a href=\"javascript:x\">x</a>",
        )
        val random = Random(7)
        val alphabet = "*_~`[]()<>!#-+.:/\\ \n\"'&=ahrefsciptwx1⸻"
        val inputs = corpus + List(2000) {
            buildString { repeat(random.nextInt(0, 80)) { append(alphabet[random.nextInt(alphabet.length)]) } }
        }
        val tag = Regex("<(/?)([a-zA-Z]+)([^>]*)>")
        for (input in inputs) {
            val html = toHtml(input)
            for (match in tag.findAll(html)) {
                val (_, name, attributes) = match.destructured
                assertThat(name).isIn(ALLOWED)
                if (name == "a" && match.groupValues[1].isEmpty()) {
                    assertThat(attributes).matches(" href=\"[^\"<>]*\"")
                } else {
                    assertThat(attributes).isEmpty()
                }
            }
            // No stray angle bracket outside the tags.
            assertThat(tag.replace(html, "")).doesNotContainMatch("[<>]")
        }
    }

    private companion object {
        val ALLOWED = setOf("p", "br", "strong", "em", "del", "code", "pre", "blockquote", "ul", "ol", "li", "a")
    }
}
