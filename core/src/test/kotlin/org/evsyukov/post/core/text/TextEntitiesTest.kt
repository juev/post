package org.evsyukov.post.core.text

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.text.TextEntity.Kind.HASHTAG
import org.evsyukov.post.core.text.TextEntity.Kind.MENTION
import org.evsyukov.post.core.text.TextEntity.Kind.URL
import org.junit.Test

class TextEntitiesTest {

    private fun urls(text: String) = TextEntities.urls(text).map { it.value }

    @Test
    fun `url offsets and value are the URL as typed`() {
        val text = "see https://example.com/a?b=c#d now"
        assertThat(TextEntities.urls(text)).containsExactly(TextEntity(4, 31, URL, "https://example.com/a?b=c#d"))
    }

    @Test
    fun `url needs an http or https scheme in any case`() {
        assertThat(urls("HTTPS://Example.COM/x and http://example.org")).containsExactly(
            "HTTPS://Example.COM/x",
            "http://example.org",
        ).inOrder()
        assertThat(urls("example.com www.example.com ftp://example.com mailto:a@example.com")).isEmpty()
    }

    @Test
    fun `trailing punctuation is not part of the url`() {
        for (punctuation in listOf(".", ",", ";", ":", "!", "?", "'", "\"", "...", "?!")) {
            assertThat(urls("Go to https://example.com/page$punctuation Next")).containsExactly("https://example.com/page")
        }
        assertThat(urls("\"https://example.com/q\"")).containsExactly("https://example.com/q")
    }

    @Test
    fun `punctuation inside the url stays`() {
        assertThat(urls("https://example.com/a.b,c;d:e!f?g=1 x")).containsExactly("https://example.com/a.b,c;d:e!f?g=1")
    }

    @Test
    fun `closing parenthesis stays only when the url opened one`() {
        assertThat(urls("(see https://example.com/page)")).containsExactly("https://example.com/page")
        assertThat(urls("https://en.wikipedia.org/wiki/Foo_(bar)")).containsExactly("https://en.wikipedia.org/wiki/Foo_(bar)")
        assertThat(urls("(https://en.wikipedia.org/wiki/Foo_(bar))")).containsExactly("https://en.wikipedia.org/wiki/Foo_(bar)")
        assertThat(urls("(https://en.wikipedia.org/wiki/Foo_(bar)).")).containsExactly("https://en.wikipedia.org/wiki/Foo_(bar)")
        assertThat(urls("[https://example.com/x]")).containsExactly("https://example.com/x")
    }

    @Test
    fun `url ends at whitespace and angle brackets`() {
        assertThat(urls("<https://example.com/x>")).containsExactly("https://example.com/x")
        assertThat(urls("https://example.com/a\nhttps://example.org/b")).containsExactly(
            "https://example.com/a",
            "https://example.org/b",
        ).inOrder()
    }

    @Test
    fun `url must not start inside a word or a path`() {
        assertThat(urls("xhttps://example.com")).isEmpty()
        assertThat(urls("1https://example.com")).isEmpty()
        assertThat(urls("a/https://example.com")).isEmpty()
        assertThat(urls("словоhttps://example.com")).isEmpty()
        // UTS #58 lets CJK text run straight into a scheme.
        assertThat(TextEntities.urls("テストhttps://example.com")).containsExactly(TextEntity(3, 22, URL, "https://example.com"))
    }

    @Test
    fun `host must be a dotted name with valid labels and port`() {
        assertThat(urls("https://localhost/x")).isEmpty()
        assertThat(urls("https://-bad.example.com")).isEmpty()
        assertThat(urls("https://bad-.example.com")).isEmpty()
        assertThat(urls("https://a..b.com")).isEmpty()
        assertThat(urls("https://user@example.com")).isEmpty()
        assertThat(urls("https://example.com:99999/x")).isEmpty()
        assertThat(urls("https://example.com:8080/x")).containsExactly("https://example.com:8080/x")
        assertThat(urls("https://")).isEmpty()
    }

    @Test
    fun `quotes and brackets of other scripts end the url`() {
        // UTS #58 Link_Term, as Mastodon main counts: the host ends at the first character that is not part of
        // it, and closing quotes and sentence punctuation of any script are soft at the end.
        assertThat(urls("Ссылка «https://example.com» тут")).containsExactly("https://example.com")
        assertThat(urls("Ссылка «https://example.com/path» тут")).containsExactly("https://example.com/path")
        assertThat(urls("“https://example.com/a”")).containsExactly("https://example.com/a")
        assertThat(urls("“https://example.com”")).containsExactly("https://example.com")
        assertThat(urls("見て https://example.com/a。")).containsExactly("https://example.com/a")
        assertThat(urls("見て https://example.com。")).containsExactly("https://example.com")
        assertThat(urls("（https://example.com/a）")).containsExactly("https://example.com/a")
        assertThat(urls("https://example.com）")).containsExactly("https://example.com")
        assertThat(urls("https://t.co».")).containsExactly("https://t.co")
        assertThat(urls("‘https://example.com/x’,")).containsExactly("https://example.com/x")
    }

    @Test
    fun `text right after the host is not part of the url`() {
        assertThat(urls("https://example.com,and more")).containsExactly("https://example.com")
        assertThat(urls("see https://example.com…")).containsExactly("https://example.com")
        assertThat(urls("https://example.com:abc")).containsExactly("https://example.com")
        assertThat(urls("https://example.com.")).containsExactly("https://example.com")
        // A dot after the host stays when a path follows, as in UTS #58.
        assertThat(urls("https://example.com./a")).containsExactly("https://example.com./a")
    }

    @Test
    fun `soft punctuation inside the path stays and at the end goes`() {
        assertThat(urls("https://example.com/a,b")).containsExactly("https://example.com/a,b")
        assertThat(urls("https://example.com/a!)")).containsExactly("https://example.com/a")
        assertThat(urls("(see https://example.com/a.)")).containsExactly("https://example.com/a")
        assertThat(urls("Is it https://example.com/page?")).containsExactly("https://example.com/page")
        assertThat(urls("https://example.com/a…")).containsExactly("https://example.com/a…")
    }

    @Test
    fun `brackets pair inside each path segment`() {
        assertThat(urls("https://example.com/a(b)c/d")).containsExactly("https://example.com/a(b)c/d")
        assertThat(urls("https://example.com/a[1]")).containsExactly("https://example.com/a[1]")
        assertThat(urls("「https://example.com/a」")).containsExactly("https://example.com/a")
        // A bracket opened in one segment does not close in the next.
        assertThat(urls("https://example.com/(a/b)")).containsExactly("https://example.com/(a/b")
    }

    @Test
    fun `query and fragment`() {
        assertThat(urls("https://example.com/s?q=(a)&b=c#frag-1 x")).containsExactly("https://example.com/s?q=(a)&b=c#frag-1")
        assertThat(urls("https://example.com/#:~:text=a,b x")).containsExactly("https://example.com/#:~:text=a,b")
        // "=" and "&" start a new part of the query, where brackets pair anew.
        assertThat(urls("https://example.com/s?a=(b&c=d)")).containsExactly("https://example.com/s?a=(b&c=d")
    }

    @Test
    fun `ideographic full stop separates host labels`() {
        assertThat(urls("見て https://example。com/a")).containsExactly("https://example。com/a")
    }

    @Test
    fun `host needs a plausible top-level domain`() {
        assertThat(urls("https://192.168.0.1/x")).isEmpty()
        assertThat(urls("https://example.c/x")).isEmpty()
        assertThat(urls("https://example.invalid/x")).isEmpty()
        assertThat(urls("https://example.xn--p1ai/x")).containsExactly("https://example.xn--p1ai/x")
        assertThat(urls("https://example.com:0/x")).isEmpty()
    }

    @Test
    fun `an underscore may precede a url but a letter number may not`() {
        assertThat(urls("_https://example.com_")).containsExactly("https://example.com")
        assertThat(urls("Ⅻhttps://example.com")).isEmpty()
        assertThat(urls("aテhttps://example.com")).isEmpty()
    }

    @Test
    fun `long runs of brackets and punctuation are linear`() {
        val parens = "https://a.co/" + ")".repeat(50_000)
        val links = "](https://u.co)".repeat(20_000)
        val dots = "https://a.co/a" + ".".repeat(50_000) + "b"
        val started = System.nanoTime()
        assertThat(urls(parens)).containsExactly("https://a.co/")
        assertThat(TextEntities.urls(links)).hasSize(20_000)
        assertThat(urls(dots)).containsExactly(dots)
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(1_000L)
    }

    @Test
    fun `internationalized host and path`() {
        assertThat(urls("Ссылка: https://пример.рф/путь/страница, дальше")).containsExactly("https://пример.рф/путь/страница")
    }

    @Test
    fun `mastodon mentions local and remote`() {
        assertThat(TextEntities.mastodonMentions("hi @alice and @bob@example.social.")).containsExactly(
            TextEntity(3, 9, MENTION, "alice"),
            TextEntity(14, 33, MENTION, "bob@example.social"),
        ).inOrder()
    }

    @Test
    fun `mastodon mention must not follow a word character, equals or slash`() {
        assertThat(TextEntities.mastodonMentions("mail bob@example.com")).isEmpty()
        assertThat(TextEntities.mastodonMentions("https://mastodon.social/@alice")).isEmpty()
        assertThat(TextEntities.mastodonMentions("a=@alice")).isEmpty()
        assertThat(TextEntities.mastodonMentions("Привет@alice")).isEmpty()
        assertThat(TextEntities.mastodonMentions("(@alice)").map { it.value }).containsExactly("alice")
    }

    @Test
    fun `mastodon mention followed by an accent or a scheme is discarded`() {
        // twitter-text's end_mention_match: "@noël" is not "@no".
        assertThat(TextEntities.mastodonMentions("@noël@example.com")).isEmpty()
        assertThat(TextEntities.mastodonMentions("@alice://x")).isEmpty()
        assertThat(TextEntities.mastodonMentions("@Alice_1.x-y hi").map { it.value }).containsExactly("Alice_1.x-y")
    }

    @Test
    fun `mastodon mention with an overlong domain is not a mention`() {
        val domain = "a".repeat(250) + ".com"
        assertThat(TextEntities.mastodonMentions("@alice@$domain")).isEmpty()
        assertThat(TextEntities.mastodonMentions("@alice@${"a".repeat(245)}.com")).hasSize(1)
    }

    @Test
    fun `bluesky mentions need a domain handle`() {
        assertThat(TextEntities.blueskyMentions("hi @alice.bsky.social!")).containsExactly(
            TextEntity(3, 21, MENTION, "alice.bsky.social"),
        )
        assertThat(TextEntities.blueskyMentions("(@bob.example.com.)").map { it.value }).containsExactly("bob.example.com")
        assertThat(TextEntities.blueskyMentions("@alice")).isEmpty()
        assertThat(TextEntities.blueskyMentions("@1.2")).isEmpty()
        assertThat(TextEntities.blueskyMentions("@a-.example.com")).isEmpty()
    }

    @Test
    fun `bluesky mention excludes e-mail and Mastodon addresses`() {
        assertThat(TextEntities.blueskyMentions("me@alice.bsky.social")).isEmpty()
        assertThat(TextEntities.blueskyMentions("@alice@mastodon.social")).isEmpty()
        assertThat(TextEntities.blueskyMentions("@alice.bsky.social@x.com")).isEmpty()
        assertThat(TextEntities.blueskyMentions("Привет @alice.bsky.social").map { it.start }).containsExactly(7)
    }

    @Test
    fun `hashtags allow Unicode letters, digits and underscore`() {
        assertThat(TextEntities.hashtags("#hello world #привет #日本語 #snake_case #2026year ＃wide")).containsExactly(
            TextEntity(0, 6, HASHTAG, "hello"),
            TextEntity(13, 20, HASHTAG, "привет"),
            TextEntity(21, 25, HASHTAG, "日本語"),
            TextEntity(26, 37, HASHTAG, "snake_case"),
            TextEntity(38, 47, HASHTAG, "2026year"),
            TextEntity(48, 53, HASHTAG, "wide"),
        ).inOrder()
    }

    @Test
    fun `hashtag must contain a letter`() {
        assertThat(TextEntities.hashtags("#2026 #1 #_1 #__")).isEmpty()
    }

    @Test
    fun `hashtag ends at punctuation and emoji and keeps combining marks`() {
        assertThat(TextEntities.hashtags("#tag. #tag🎉 #café #हिन्दी").map { it.value })
            .containsExactly("tag", "tag", "café", "हिन्दी").inOrder()
    }

    @Test
    fun `hashtag must not follow a word character`() {
        assertThat(TextEntities.hashtags("C#sharp a#b 1#x")).isEmpty()
        assertThat(TextEntities.hashtags("(#tag)").map { it.value }).containsExactly("tag")
    }
}
