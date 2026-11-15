package org.evsyukov.post.text

import android.icu.text.BreakIterator
import android.icu.util.ULocale
import org.evsyukov.post.core.text.TextSegmenter
import java.util.Locale

/**
 * [TextSegmenter] on the platform ICU (`android.icu.text.BreakIterator`): the same rules as the ICU4J
 * segmenter of the core tests, so counters and thread splits on the device match them (threads.md T4,
 * networks.md N3, N16).
 *
 * A [BreakIterator] is not thread-safe and the renderer runs on background threads, so every call takes
 * its own instance.
 */
class AndroidTextSegmenter : TextSegmenter {
    override fun graphemeBoundaries(text: String): IntArray =
        boundaries(BreakIterator.getCharacterInstance(ULocale.ROOT), text)

    override fun wordBoundaries(text: String, locale: Locale): IntArray =
        boundaries(BreakIterator.getWordInstance(locale), text)

    override fun sentenceBoundaries(text: String, locale: Locale): IntArray =
        boundaries(BreakIterator.getSentenceInstance(locale), text)

    private fun boundaries(iterator: BreakIterator, text: String): IntArray {
        if (text.isEmpty()) return intArrayOf(0)
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
