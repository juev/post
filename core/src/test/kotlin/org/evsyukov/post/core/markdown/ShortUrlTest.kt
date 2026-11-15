package org.evsyukov.post.core.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Cases derived from social-app's toShortUrl and the WHATWG URL serialization it relies on. */
class ShortUrlTest {

    @Test
    fun `root path and scheme go`() {
        assertThat(toShortUrl("https://example.com")).isEqualTo("example.com")
        assertThat(toShortUrl("https://example.com/")).isEqualTo("example.com")
        assertThat(toShortUrl("http://example.com/abc")).isEqualTo("example.com/abc")
    }

    @Test
    fun `path, query and hash longer than 15 chars are cut to 13 plus ellipsis`() {
        assertThat(toShortUrl("https://bsky.app/profile/alice.bsky.social/post/3k"))
            .isEqualTo("bsky.app/profile/alic...")
        // Exactly 15 chars stay.
        assertThat(toShortUrl("https://x.com/abcdefghijklmn")).isEqualTo("x.com/abcdefghijklmn")
        assertThat(toShortUrl("https://x.com/abcdefghijklmno")).isEqualTo("x.com/abcdefghijkl...")
        assertThat(toShortUrl("https://example.com/?q=1#x")).isEqualTo("example.com?q=1#x")
    }

    @Test
    fun `empty query and fragment go`() {
        assertThat(toShortUrl("https://example.com/a?#")).isEqualTo("example.com/a")
    }

    @Test
    fun `host is lowercased, default port and user info go`() {
        assertThat(toShortUrl("HTTP://Example.COM:80/a")).isEqualTo("example.com/a")
        assertThat(toShortUrl("https://example.com:443")).isEqualTo("example.com")
        assertThat(toShortUrl("https://example.com:8443/a")).isEqualTo("example.com:8443/a")
        assertThat(toShortUrl("https://user:pw@example.com/a")).isEqualTo("example.com/a")
    }

    @Test
    fun `internationalized host becomes punycode and the path is percent-encoded`() {
        assertThat(toShortUrl("https://пример.рф")).isEqualTo("xn--e1afmkfd.xn--p1ai")
        assertThat(toShortUrl("https://пример.рф/путь")).isEqualTo("xn--e1afmkfd.xn--p1ai/%D0%BF%D1%83...")
        assertThat(toShortUrl("https://x.com/a b")).isEqualTo("x.com/a%20b")
    }

    @Test
    fun `other schemes and broken urls are returned unchanged`() {
        assertThat(toShortUrl("ftp://example.com/x")).isEqualTo("ftp://example.com/x")
        assertThat(toShortUrl("mailto:me@example.com")).isEqualTo("mailto:me@example.com")
        assertThat(toShortUrl("https://")).isEqualTo("https://")
        assertThat(toShortUrl("https://exa mple.com/")).isEqualTo("https://exa mple.com/")
        assertThat(toShortUrl("https://example.com:99999/")).isEqualTo("https://example.com:99999/")
        assertThat(toShortUrl("not a url")).isEqualTo("not a url")
    }
}
