package org.evsyukov.post.core.markdown

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.markdown.BlueskyLinkMode.FACET_ON_TEXT
import org.evsyukov.post.core.markdown.BlueskyLinkMode.TEXT_AND_SHORT_URL
import org.junit.Test

/** formatting.md F11: Bluesky gets the plain text with link facets. */
class BlueskyTextTest {

    @Test
    fun `verification scenario with the default setting`() {
        assertThat(toBlueskyText("**Hello** _there_ [site](https://example.com)", FACET_ON_TEXT))
            .isEqualTo(BlueskyText("Hello there site", listOf(LinkSpan(12, 16, "https://example.com"))))
    }

    @Test
    fun `text and short url puts the facet on the address`() {
        assertThat(toBlueskyText("**Hello** _there_ [site](https://example.com)", TEXT_AND_SHORT_URL))
            .isEqualTo(BlueskyText("Hello there site (example.com)", listOf(LinkSpan(18, 29, "https://example.com"))))
    }

    @Test
    fun `long address is shortened like social-app`() {
        val result = toBlueskyText("[docs](https://example.com/some/very/long/path)", TEXT_AND_SHORT_URL)
        assertThat(result.text).isEqualTo("docs (example.com/some/very/lo...)")
        assertThat(result.links).containsExactly(LinkSpan(6, 33, "https://example.com/some/very/long/path"))
    }

    @Test
    fun `link whose text is the url keeps its text in both modes`() {
        for (mode in BlueskyLinkMode.entries) {
            assertThat(toBlueskyText("[example.com](https://example.com)", mode))
                .isEqualTo(BlueskyText("example.com", listOf(LinkSpan(0, 11, "https://example.com"))))
            assertThat(toBlueskyText("<https://example.com/x>", mode))
                .isEqualTo(BlueskyText("https://example.com/x", listOf(LinkSpan(0, 21, "https://example.com/x"))))
            assertThat(toBlueskyText("[](https://example.com)", mode))
                .isEqualTo(BlueskyText("https://example.com", listOf(LinkSpan(0, 19, "https://example.com"))))
        }
    }

    @Test
    fun `bare urls stay as typed and get link spans`() {
        assertThat(toBlueskyText("see https://example.com/x. and (https://b.org)", FACET_ON_TEXT)).isEqualTo(
            BlueskyText(
                "see https://example.com/x. and (https://b.org)",
                listOf(LinkSpan(4, 25, "https://example.com/x"), LinkSpan(32, 45, "https://b.org")),
            ),
        )
    }

    @Test
    fun `bare urls with markup characters keep their text and facet`() {
        for (url in listOf("https://example.com/files/__init__.py", "https://mastodon.social/@_jane_", "https://example.com/a*b*c")) {
            assertThat(toBlueskyText(url, FACET_ON_TEXT)).isEqualTo(BlueskyText(url, listOf(LinkSpan(0, url.length, url))))
        }
        assertThat(toBlueskyText("**https://example.com/a**", FACET_ON_TEXT))
            .isEqualTo(BlueskyText("https://example.com/a", listOf(LinkSpan(0, 21, "https://example.com/a"))))
    }

    @Test
    fun `url in guillemets gets a facet without the quote`() {
        assertThat(toBlueskyText("Ссылка «https://example.com/path» тут", FACET_ON_TEXT).links)
            .containsExactly(LinkSpan(8, 32, "https://example.com/path"))
        assertThat(toBlueskyText("«https://example.com»", FACET_ON_TEXT).links)
            .containsExactly(LinkSpan(1, 20, "https://example.com"))
    }

    @Test
    fun `links to other destinations are plain text without facets`() {
        assertThat(toBlueskyText("[a](/rel) <me@example.com> [x](javascript:alert(1))", FACET_ON_TEXT))
            .isEqualTo(BlueskyText("a (/rel) me@example.com x (javascript:alert(1))", emptyList()))
    }

    @Test
    fun `offsets are char offsets into the converted text`() {
        val result = toBlueskyText("Привет [мир](https://example.com) 👍🏽 https://b.com", FACET_ON_TEXT)
        assertThat(result.text).isEqualTo("Привет мир 👍🏽 https://b.com")
        assertThat(result.links).containsExactly(
            LinkSpan(7, 10, "https://example.com"),
            LinkSpan(16, 29, "https://b.com"),
        ).inOrder()
        for (link in result.links) {
            if (link.url == "https://example.com") assertThat(result.text.substring(link.start, link.end)).isEqualTo("мир")
        }
    }

    @Test
    fun `quotes, lists and code follow the plain text rules`() {
        val result = toBlueskyText("> **q**\n- [a](https://x.com)\n```\nhttps://c.com\n```", FACET_ON_TEXT)
        assertThat(result.text).isEqualTo("> q\n• a\nhttps://c.com")
        assertThat(result.links).containsExactly(LinkSpan(6, 7, "https://x.com"), LinkSpan(8, 21, "https://c.com")).inOrder()
    }

    @Test
    fun `several links keep their order and the facet text`() {
        val result = toBlueskyText("[one](https://a.com/1) and [`two`](https://a.com/2)", TEXT_AND_SHORT_URL)
        assertThat(result.text).isEqualTo("one (a.com/1) and two (a.com/2)")
        assertThat(result.links.map { result.text.substring(it.start, it.end) }).containsExactly("a.com/1", "a.com/2").inOrder()
    }

    @Test
    fun `manual break lines are removed`() {
        val result = toBlueskyText("one\n⸻\n[two](https://x.com)", FACET_ON_TEXT)
        assertThat(result).isEqualTo(BlueskyText("one\n\ntwo", listOf(LinkSpan(5, 8, "https://x.com"))))
    }
}
