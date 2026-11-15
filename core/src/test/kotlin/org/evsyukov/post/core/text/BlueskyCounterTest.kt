package org.evsyukov.post.core.text

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.testing.IcuTextSegmenter
import org.junit.Test

/** networks.md N16: 300 graphemes and 3000 UTF-8 bytes. */
class BlueskyCounterTest {
    private val counter = BlueskyCounter(IcuTextSegmenter())

    @Test
    fun `graphemes and bytes of mixed text`() {
        val text = "Привет 👨‍👩‍👧‍👦 https://example.com"
        assertThat(counter.graphemes(text)).isEqualTo(7 + 1 + 1 + 19)
        assertThat(counter.bytes(text)).isEqualTo(13 + 25 + 1 + 19)
    }

    @Test
    fun `urls count in full`() {
        val url = "https://example.com/" + "a".repeat(100)
        assertThat(counter.graphemes(url)).isEqualTo(120)
    }

    @Test
    fun `fits at exactly 300 graphemes and not at 301`() {
        assertThat(counter.fits("a".repeat(300))).isTrue()
        assertThat(counter.fits("a".repeat(301))).isFalse()
        assertThat(counter.fits("я".repeat(300))).isTrue()
        assertThat(counter.fits("👍🏽".repeat(300))).isTrue()
    }

    @Test
    fun `byte limit applies when graphemes fit`() {
        // 300 family emoji: 300 graphemes, 7500 bytes.
        val text = "👨‍👩‍👧‍👦".repeat(300)
        assertThat(counter.graphemes(text)).isEqualTo(300)
        assertThat(counter.fits(text)).isFalse()
        // 120 family emoji: 3000 bytes exactly fit, 121 do not.
        assertThat(counter.fits("👨‍👩‍👧‍👦".repeat(120))).isTrue()
        assertThat(counter.fits("👨‍👩‍👧‍👦".repeat(121))).isFalse()
    }

    @Test
    fun `custom limits`() {
        val small = BlueskyCounter(IcuTextSegmenter(), maxGraphemes = 5, maxBytes = 8)
        assertThat(small.fits("abcde")).isTrue()
        assertThat(small.fits("abcdef")).isFalse()
        assertThat(small.fits("яяяяя")).isFalse()
        assertThat(small.fits("яяяя")).isTrue()
    }
}
