package org.evsyukov.post.core.text

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.testing.IcuTextSegmenter
import org.junit.Test
import java.util.Locale

class TextSegmenterTest {
    private val segmenter = IcuTextSegmenter()

    @Test
    fun `emoji ZWJ sequence is one grapheme`() {
        val family = "👨‍👩‍👧‍👦"
        assertThat(family.length).isEqualTo(11)
        assertThat(segmenter.graphemeCount(family)).isEqualTo(1)
    }

    @Test
    fun `flags and skin tones are one grapheme each`() {
        assertThat(segmenter.graphemeCount("🇷🇺")).isEqualTo(1)
        assertThat(segmenter.graphemeCount("🇷🇺🇺🇦🇯🇵")).isEqualTo(3)
        assertThat(segmenter.graphemeCount("👍🏽")).isEqualTo(1)
        assertThat(segmenter.graphemeCount("👍🏽👍")).isEqualTo(2)
    }

    @Test
    fun `combining marks join their base letter`() {
        assertThat(segmenter.graphemeCount("é")).isEqualTo(1)
        assertThat(segmenter.graphemeCount("йог")).isEqualTo(3)
    }

    @Test
    fun `Cyrillic and CJK count one grapheme per letter`() {
        assertThat(segmenter.graphemeCount("Привет, мир")).isEqualTo(11)
        assertThat(segmenter.graphemeCount("你好世界")).isEqualTo(4)
        assertThat(segmenter.graphemeCount("𠜎𠜱")).isEqualTo(2)
    }

    @Test
    fun `empty text has no graphemes and a single boundary`() {
        assertThat(segmenter.graphemeCount("")).isEqualTo(0)
        assertThat(segmenter.graphemeBoundaries("").toList()).containsExactly(0)
    }

    @Test
    fun `grapheme boundaries are sorted char offsets from 0 to length`() {
        val text = "a👍🏽é\r\nб"
        assertThat(segmenter.graphemeBoundaries(text).toList()).containsExactly(0, 1, 5, 6, 8, 9).inOrder()
    }

    @Test
    fun `word boundaries separate words, spaces and punctuation`() {
        assertThat(segmenter.wordBoundaries("Hello, world", Locale.ENGLISH).toList())
            .containsExactly(0, 5, 6, 7, 12).inOrder()
        assertThat(segmenter.wordBoundaries("Привет мир", Locale.forLanguageTag("ru")).toList())
            .containsExactly(0, 6, 7, 10).inOrder()
    }

    @Test
    fun `sentence boundaries keep abbreviations inside the sentence`() {
        val text = "Use e.g. apples. Then go."
        assertThat(segmenter.sentenceBoundaries(text, Locale.ENGLISH).toList())
            .containsExactly(0, 17, 25).inOrder()
    }
}
