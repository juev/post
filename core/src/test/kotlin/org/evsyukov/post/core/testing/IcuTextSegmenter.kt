package org.evsyukov.post.core.testing

import com.ibm.icu.text.BreakIterator
import com.ibm.icu.util.ULocale
import org.evsyukov.post.core.text.TextSegmenter
import java.util.Locale

/**
 * [TextSegmenter] on ICU4J for the core tests. The app uses `android.icu.text.BreakIterator`, the same
 * ICU rules, so a count or a split that passes here matches the device up to the Unicode version.
 *
 * A [BreakIterator] is not thread-safe, so every call takes its own.
 */
class IcuTextSegmenter : TextSegmenter {
    override fun graphemeBoundaries(text: String): IntArray =
        boundaries(BreakIterator.getCharacterInstance(ULocale.ROOT), text)

    override fun wordBoundaries(text: String, locale: Locale): IntArray =
        boundaries(BreakIterator.getWordInstance(locale), text)

    override fun sentenceBoundaries(text: String, locale: Locale): IntArray =
        boundaries(BreakIterator.getSentenceInstance(locale), text)

    private fun boundaries(iterator: BreakIterator, text: String): IntArray {
        iterator.setText(text)
        val result = ArrayList<Int>()
        var boundary = iterator.first()
        while (boundary != BreakIterator.DONE) {
            result += boundary
            boundary = iterator.next()
        }
        return result.toIntArray()
    }
}
