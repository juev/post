package org.evsyukov.post.core.text

/**
 * Checks the text of a Bluesky post against the limits of the `app.bsky.feed.post` lexicon: at most
 * [maxGraphemes] grapheme clusters and at most [maxBytes] UTF-8 bytes (networks.md, N16).
 *
 * Bluesky counts the text exactly as sent: a link counts in full unless the text shows it shortened, and
 * facets do not change the count.
 */
class BlueskyCounter(
    private val segmenter: TextSegmenter,
    private val maxGraphemes: Int = 300,
    private val maxBytes: Int = 3000,
) {
    /** The number of grapheme clusters in [text]. */
    fun graphemes(text: String): Int = segmenter.graphemeCount(text)

    /** The number of UTF-8 bytes of [text]. */
    fun bytes(text: String): Int = utf8Length(text)

    /** Whether [text] fits both limits. */
    fun fits(text: String): Boolean = bytes(text) <= maxBytes && graphemes(text) <= maxGraphemes
}
