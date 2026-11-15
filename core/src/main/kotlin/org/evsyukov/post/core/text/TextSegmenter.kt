package org.evsyukov.post.core.text

import java.util.Locale

/**
 * Splits text into grapheme clusters, words and sentences by the rules of Unicode Standard Annex #29.
 *
 * The core module has no implementation of its own: it must not depend on ICU4J or `java.text`. The app
 * supplies one backed by `android.icu.text.BreakIterator`, and the core tests use ICU4J with the same
 * rules (`org.evsyukov.post.core.testing.IcuTextSegmenter`), so counters and splits match the device.
 *
 * Every function returns boundaries as UTF-16 char offsets into [text][graphemeBoundaries], sorted
 * ascending, starting with `0` and ending with `text.length`. An empty text has the single boundary `0`.
 */
interface TextSegmenter {
    /** Boundaries of extended grapheme clusters: an emoji ZWJ sequence or a flag is one cluster. */
    fun graphemeBoundaries(text: String): IntArray

    /** Word boundaries for [locale]; spaces and punctuation between words form segments of their own. */
    fun wordBoundaries(text: String, locale: Locale): IntArray

    /** Sentence boundaries for [locale]; the rules know common abbreviations such as "e.g.". */
    fun sentenceBoundaries(text: String, locale: Locale): IntArray
}

/** The number of grapheme clusters in [text], which is what Mastodon and Bluesky call characters. */
fun TextSegmenter.graphemeCount(text: String): Int = if (text.isEmpty()) 0 else graphemeBoundaries(text).size - 1
