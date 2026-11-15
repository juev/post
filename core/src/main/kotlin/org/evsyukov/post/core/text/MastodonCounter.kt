package org.evsyukov.post.core.text

/**
 * Counts the characters of a Mastodon status the way the server checks its limit (networks.md, N3).
 *
 * It follows `StatusLengthValidator` of `mastodon/mastodon`
 * (`app/validators/status_length_validator.rb` at commit `296ca2874f3b7c02e79d2041b54f352a392f17ba`,
 * `main` on 2026-11-15; the file last changed in `d90d68bddf50de32338bb9613d4654f626b2616f`):
 *
 * ```ruby
 * def countable_length(str)
 *   str.each_grapheme_cluster.size
 * end
 *
 * def combined_text(status)
 *   [status.spoiler_text, countable_text(status.text)].join
 * end
 * ```
 *
 * - The count is the number of grapheme clusters of the content warning joined with the text, with
 *   nothing between them.
 * - A text that is empty or only whitespace counts as empty (`return '' if str.blank?`).
 * - Every URL counts as [charactersReservedPerUrl] characters. Only URLs with `http://` or `https://`
 *   count so (`extract_url_without_protocol: false`); see [TextEntities.urls]. The server's extractor
 *   skips URLs longer than 4096 code points (`Extractor::UTS58.max_length`), so those count in full,
 *   except a URL that starts inside one, such as in its query.
 * - A mention counts without its domain: `@alice@example.com` counts as `@alice`
 *   (`"@#{entity[:screen_name].split('@').first}"`); see [TextEntities.mastodonMentions].
 * - Where a URL and a mention overlap, the one that starts first wins, and on a tie the URL
 *   (`Extractor.remove_overlapping_entities`).
 *
 * The content warning is counted as typed, without URL or mention rewriting, as on the server.
 *
 * @param charactersReservedPerUrl `configuration.statuses.characters_reserved_per_url` of the instance
 *   (N1); vanilla Mastodon reserves 23.
 */
class MastodonCounter(
    private val segmenter: TextSegmenter,
    private val charactersReservedPerUrl: Int = 23,
) {
    init {
        require(charactersReservedPerUrl >= 0) { "charactersReservedPerUrl must not be negative" }
    }

    /** The number of characters the server counts for a status with [text] and the content warning [spoilerText]. */
    fun count(text: String, spoilerText: String = ""): Int =
        segmenter.graphemeCount(spoilerText + countableText(text))

    /** [text] with every URL replaced by its placeholder and every mention cut to `@user`. */
    internal fun countableText(text: String): String {
        if (text.isBlank()) return ""
        val urls = TextEntities.urls(text, MAX_URL_CODE_POINTS)
        val entities = removeOverlapping(urls + TextEntities.mastodonMentions(text))
        if (entities.isEmpty()) return text
        val placeholder = "x".repeat(charactersReservedPerUrl)
        val result = StringBuilder(text.length)
        var last = 0
        for (entity in entities) {
            result.append(text, last, entity.start)
            when (entity.kind) {
                TextEntity.Kind.URL -> result.append(placeholder)
                else -> result.append('@').append(entity.value.substringBefore('@'))
            }
            last = entity.end
        }
        result.append(text, last, text.length)
        return result.toString()
    }

    private fun removeOverlapping(entities: List<TextEntity>): List<TextEntity> {
        // A stable sort keeps URLs before mentions that start at the same offset.
        val sorted = entities.sortedBy { it.start }
        val kept = ArrayList<TextEntity>(sorted.size)
        var end = 0
        for (entity in sorted) {
            if (entity.start >= end) {
                kept += entity
                end = entity.end
            }
        }
        return kept
    }

    private companion object {
        const val MAX_URL_CODE_POINTS = 4096
    }
}
