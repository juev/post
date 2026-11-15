package org.evsyukov.post.core.text

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.testing.IcuTextSegmenter
import org.junit.Test

/** Cases from networks.md N3, formatting.md (verification scenarios) and Mastodon's StatusLengthValidator. */
class MastodonCounterTest {
    private val counter = MastodonCounter(IcuTextSegmenter())

    @Test
    fun `plain text counts graphemes`() {
        assertThat(counter.count("hello")).isEqualTo(5)
        assertThat(counter.count("Привет, мир!")).isEqualTo(12)
        assertThat(counter.count("你好世界")).isEqualTo(4)
    }

    @Test
    fun `emoji sequences, flags, skin tones and combining marks count one each`() {
        assertThat(counter.count("👨‍👩‍👧‍👦")).isEqualTo(1)
        assertThat(counter.count("🇷🇺🇺🇦")).isEqualTo(2)
        assertThat(counter.count("👍🏽!")).isEqualTo(2)
        assertThat(counter.count("éé")).isEqualTo(2)
    }

    @Test
    fun `every url counts as 23 characters whatever its length`() {
        assertThat(counter.count("https://a.co")).isEqualTo(23)
        assertThat(counter.count("Look https://example.com/a/very/long/path?with=query&and=more")).isEqualTo(5 + 23)
        assertThat(counter.count("https://example.com/1 https://example.com/2")).isEqualTo(23 + 1 + 23)
    }

    @Test
    fun `url in parentheses with trailing punctuation counts the punctuation separately`() {
        // "(" + URL + ")."
        assertThat(counter.count("(https://en.wikipedia.org/wiki/Foo_(bar)).")).isEqualTo(1 + 23 + 2)
    }

    @Test
    fun `characters reserved per url come from the instance`() {
        assertThat(MastodonCounter(IcuTextSegmenter(), charactersReservedPerUrl = 30).count("x https://a.co")).isEqualTo(32)
    }

    @Test
    fun `text without a scheme is not a url and counts in full`() {
        assertThat(counter.count("example.com/some/long/path")).isEqualTo(26)
    }

    @Test
    fun `url longer than 4096 code points counts in full`() {
        val url = "https://example.com/" + "a".repeat(4100)
        assertThat(counter.count(url)).isEqualTo(url.length)
    }

    @Test
    fun `remote mention counts without its domain`() {
        assertThat(counter.count("@alice@example.com hi")).isEqualTo("@alice hi".length)
        assertThat(counter.count("@alice hi")).isEqualTo(9)
    }

    @Test
    fun `mention inside a url is part of the url`() {
        assertThat(counter.count("https://mastodon.social/@alice")).isEqualTo(23)
    }

    @Test
    fun `content warning is joined with the text without a separator`() {
        assertThat(counter.count("text", spoilerText = "CW")).isEqualTo(6)
        assertThat(counter.count("", spoilerText = "Spoiler 👍🏽")).isEqualTo(9)
    }

    @Test
    fun `content warning is counted as typed`() {
        assertThat(counter.count("x", spoilerText = "https://example.com/long/path")).isEqualTo(29 + 1)
    }

    @Test
    fun `blank text counts as empty`() {
        assertThat(counter.count("")).isEqualTo(0)
        assertThat(counter.count("   \n\t")).isEqualTo(0)
        assertThat(counter.count("  ", spoilerText = "CW")).isEqualTo(2)
    }

    @Test
    fun `verification scenario of formatting md counts the url as 23`() {
        // `**Hello** _there_ [site](https://example.com)` to vanilla Mastodon is this plain text.
        assertThat(counter.count("Hello there site (https://example.com)")).isEqualTo(18 + 23 + 1)
    }

    @Test
    fun `combined text joins graphemes across the content warning boundary`() {
        // The validator counts graphemes of the joined string: "e" + combining acute is one grapheme.
        assertThat(counter.count("́x", spoilerText = "e")).isEqualTo(2)
    }
}
