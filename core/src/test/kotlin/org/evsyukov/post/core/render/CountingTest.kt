package org.evsyukov.post.core.render

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.render.RenderFixtures.segmenter
import org.junit.Test
import kotlin.random.Random

/**
 * The counting families of [Counting], with cases taken from the server sources cited in its KDoc:
 * Mastodon's StatusLengthValidator, Pleroma/Akkoma's `String.length(String.trim(status <> summary))`,
 * GoToSocial's `len([]rune(status)) + len([]rune(spoiler))`.
 */
class CountingTest {
    private val thumbsUpMedium = "👍🏽" // 👍🏽: one grapheme, two code points
    private val eAcute = "é" // é decomposed: one grapheme, two code points
    private val longUrl = "https://example.com/" + "a".repeat(40)

    private fun count(counting: Counting, text: String, spoiler: String = "") = counting.count(text, spoiler, segmenter)

    @Test
    fun `Mastodon counts graphemes, URLs as 23 and mentions without their domain`() {
        assertThat(count(Counting.MASTODON, "$thumbsUpMedium$eAcute")).isEqualTo(2)
        assertThat(count(Counting.MASTODON, "see $longUrl")).isEqualTo(4 + 23)
        assertThat(count(Counting.MASTODON, "hi @alice@example.com")).isEqualTo(3 + "@alice".length)
        assertThat(count(Counting.MASTODON, "text", spoiler = "CW")).isEqualTo(6)
    }

    @Test
    fun `Pleroma and Akkoma count graphemes of text and spoiler with URLs and mentions in full`() {
        assertThat(count(Counting.PLEROMA, "$thumbsUpMedium$eAcute")).isEqualTo(2)
        assertThat(count(Counting.PLEROMA, "see $longUrl")).isEqualTo(4 + longUrl.length)
        assertThat(count(Counting.PLEROMA, "hi @alice@example.com")).isEqualTo(3 + "@alice@example.com".length)
        assertThat(count(Counting.PLEROMA, "text", spoiler = "CW")).isEqualTo(6)
    }

    @Test
    fun `GoToSocial counts code points of text and spoiler with URLs and mentions in full`() {
        assertThat(count(Counting.GOTOSOCIAL, "$thumbsUpMedium$eAcute")).isEqualTo(4)
        assertThat(count(Counting.GOTOSOCIAL, "see $longUrl")).isEqualTo(4 + longUrl.length)
        assertThat(count(Counting.GOTOSOCIAL, "hi @alice@example.com")).isEqualTo(3 + "@alice@example.com".length)
        assertThat(count(Counting.GOTOSOCIAL, "текст", spoiler = "CW $eAcute")).isEqualTo(5 + 5)
    }

    @Test
    fun `the conservative count takes code points and at least 23 per URL`() {
        assertThat(count(Counting.FULL, "$thumbsUpMedium$eAcute")).isEqualTo(4)
        assertThat(count(Counting.FULL, "see https://a.co")).isEqualTo(4 + 23)
        assertThat(count(Counting.FULL, "see $longUrl")).isEqualTo(4 + longUrl.length)
        assertThat(count(Counting.FULL, "hi @alice@example.com", spoiler = "CW")).isEqualTo(2 + 3 + "@alice@example.com".length)
    }

    @Test
    fun `Bluesky counts graphemes of the text and ignores a content warning`() {
        assertThat(count(Counting.BLUESKY, "$thumbsUpMedium$eAcute ok", spoiler = "ignored")).isEqualTo(5)
        assertThat(count(Counting.BLUESKY, "see $longUrl")).isEqualTo(4 + longUrl.length)
    }

    @Test
    fun `the conservative count is never below another Mastodon-API family`() {
        val random = Random(7)
        val atoms = listOf(
            "word", " ", "\n", thumbsUpMedium, eAcute, "https://a.co", longUrl, "@bob", "@alice@example.com",
            "#tag", "ё", "漢字", "👨‍👩‍👧", ".",
        )
        repeat(2000) {
            val text = List(random.nextInt(0, 12)) { atoms.random(random) }.joinToString("")
            val spoiler = List(random.nextInt(0, 3)) { atoms.random(random) }.joinToString("")
            val full = count(Counting.FULL, text, spoiler)
            for (family in listOf(Counting.MASTODON, Counting.PLEROMA, Counting.GOTOSOCIAL)) {
                assertThat(full).isAtLeast(count(family, text, spoiler))
            }
        }
    }
}
