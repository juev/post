package org.evsyukov.post.text

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.text.graphemeCount
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * The platform ICU on the device splits text as the ICU4J segmenter of the core tests does (threads.md T4,
 * networks.md N3, N16): an emoji sequence is one character, and an abbreviation does not end a sentence.
 */
@RunWith(AndroidJUnit4::class)
class AndroidTextSegmenterTest {
    private val segmenter = AndroidTextSegmenter()
    private val ru = Locale.forLanguageTag("ru")

    @Test
    fun anEmojiZwjSequenceIsOneGrapheme() {
        val family = "👨‍👩‍👧‍👦" // 👨‍👩‍👧‍👦
        assertThat(segmenter.graphemeBoundaries(family).toList()).containsExactly(0, family.length).inOrder()
        assertThat(segmenter.graphemeCount("a$family b")).isEqualTo(4)
    }

    @Test
    fun eachFlagIsOneGrapheme() {
        val flags = "🇷🇺🇺🇦" // 🇷🇺🇺🇦
        assertThat(segmenter.graphemeBoundaries(flags).toList()).containsExactly(0, 4, 8).inOrder()
    }

    @Test
    fun aSkinToneStaysWithItsEmoji() {
        val thumb = "👍🏽!" // 👍🏽!
        assertThat(segmenter.graphemeBoundaries(thumb).toList()).containsExactly(0, 4, 5).inOrder()
    }

    @Test
    fun anEmptyTextHasOneBoundary() {
        assertThat(segmenter.graphemeBoundaries("").toList()).containsExactly(0)
        assertThat(segmenter.sentenceBoundaries("", ru).toList()).containsExactly(0)
    }

    @Test
    fun aRussianAbbreviationDoesNotEndTheSentence() {
        val text = "Это пример, т. е. образец. Второе предложение."
        val second = text.indexOf("Второе")
        assertThat(segmenter.sentenceBoundaries(text, ru).toList()).containsExactly(0, second, text.length).inOrder()
    }

    @Test
    fun anEnglishAbbreviationDoesNotEndTheSentence() {
        val text = "Use e.g. apples. Then go."
        assertThat(segmenter.sentenceBoundaries(text, Locale.ENGLISH).toList()).containsExactly(0, 17, 25).inOrder()
    }

    @Test
    fun russianWordsAreSplitAtSpaces() {
        assertThat(segmenter.wordBoundaries("Привет мир", ru).toList()).containsExactly(0, 6, 7, 10).inOrder()
    }
}
