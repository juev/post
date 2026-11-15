package org.evsyukov.post.core.render

import org.evsyukov.post.core.markdown.BlueskyLinkMode
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.text.MastodonCounter
import org.evsyukov.post.core.text.TextEntities
import org.evsyukov.post.core.text.TextSegmenter
import org.evsyukov.post.core.text.graphemeCount

/** The form of the text an account receives (formatting.md, F8–F11). */
enum class TextFormat {
    /** Plain text with the markup removed (F10). */
    PLAIN,

    /** Markdown in the server's dialect, sent as `text/markdown` (F8). */
    MARKDOWN,

    /** HTML with the tags of F3, sent as `text/html` (F9). */
    HTML,

    /** The plain text of F10 with Bluesky's link facets (F11). */
    BLUESKY,
}

/**
 * How a server counts the length of a post against its limit. Every family counts the text as sent, which
 * for Markdown and HTML servers is the source, not the rendered text (formatting.md, F12).
 */
enum class Counting {
    /**
     * Mastodon's `StatusLengthValidator` (networks.md, N3; [MastodonCounter]): grapheme clusters of the
     * content warning joined with the text, every URL as `characters_reserved_per_url`, a mention as
     * `@user` without its domain. glitch-soc and Hometown keep this validator.
     */
    MASTODON,

    /**
     * Pleroma and Akkoma: `String.length` of the trimmed text joined with the content warning, URLs and
     * mentions in full. Elixir's `String.length` counts grapheme clusters. Read in
     * `lib/pleroma/web/common_api/activity_draft.ex` (`full_payload/1`: `String.trim(status <> summary)`,
     * where `status` is the trimmed `status` parameter and `summary` the `spoiler_text`) and
     * `lib/pleroma/web/common_api/utils.ex` (`validate_character_limit/2`: `String.length(full_payload)`
     * against `[:instance, :limit]`), Pleroma `develop` at `cf6b709685ce6eb96693208eb741f547aafdd741` and
     * Akkoma `develop` at `3e9ad30e1e769f26e2898c93c0df608b8af3c686`, identical in both. The poll is
     * checked separately and does not count. Post does not trim: the parts it makes have no whitespace at
     * their ends, and counting a content warning's own trailing spaces never undercounts.
     */
    PLEROMA,

    /**
     * GoToSocial: `len([]rune(status)) + len([]rune(spoiler))`, that is Unicode code points of the text and
     * the content warning, URLs and mentions in full, checked against `statuses-max-chars`. Read in
     * `validateStatusContent` of `internal/processing/status/common.go`, GoToSocial `main` at
     * `257aad7405a0c72634c08ff3cb54f5f391ac50a7`; `Create` calls it with the raw `status` and
     * `spoiler_text` of the form before any Markdown formatting. A code point count is never below the
     * grapheme count, so an emoji with a skin tone counts 2 here and 1 on Mastodon.
     */
    GOTOSOCIAL,

    /**
     * The conservative count for servers whose software Post does not know or could not detect: code
     * points of the content warning and the text, mentions in full, and every URL in full but at least
     * [MastodonCapabilities.Defaults.CHARACTERS_RESERVED_PER_URL] (23), as Mastodon forks weigh a short
     * URL. It is never below the other Mastodon-API counts: a grapheme holds at least one code point, and
     * no family shortens a URL below 23 or a mention below its full length. Not covered: a server that
     * counts UTF-16 units or bytes (not verified for any Mastodon-API server).
     */
    FULL,

    /**
     * Bluesky (networks.md, N16): grapheme clusters of the text up to `maxCharacters` and UTF-8 bytes up to
     * `maxBytes`. Bluesky has no content warning text, so it is not counted.
     */
    BLUESKY,
    ;

    /**
     * The length of a post with [text] and the content warning [spoilerText] as this family's server counts
     * it. [charactersReservedPerUrl] matters only for [MASTODON].
     */
    fun count(text: String, spoilerText: String, segmenter: TextSegmenter, charactersReservedPerUrl: Int = 23): Int =
        when (this) {
            MASTODON -> MastodonCounter(segmenter, charactersReservedPerUrl).count(text, spoilerText)
            PLEROMA -> segmenter.graphemeCount(text + spoilerText)
            GOTOSOCIAL -> codePoints(text) + codePoints(spoilerText)
            FULL -> codePoints(text) + codePoints(spoilerText) + TextEntities.urls(text).sumOf {
                maxOf(0, MastodonCapabilities.Defaults.CHARACTERS_RESERVED_PER_URL - codePoints(it.value))
            }
            BLUESKY -> segmenter.graphemeCount(text)
        }

    internal fun usesGraphemes(): Boolean = this == MASTODON || this == PLEROMA || this == BLUESKY
}

private fun codePoints(text: String): Int = text.codePointCount(0, text.length)

/**
 * What decides the text one account receives: its network, format, counting rule and limits, and the
 * setting "Number thread parts" (threads.md, T11). Accounts with equal profiles get the same parts (T3);
 * [key] names the profile for grouping and storage.
 *
 * @property contentType the `content_type` sent with a Mastodon status, or null to send none (only a type
 *   the server listed is sent; formatting.md, notes to the table).
 * @property maxBytes the UTF-8 byte limit (Bluesky's 3000), or null when the network has none.
 * @property charactersReservedPerUrl the weight of a URL for [Counting.MASTODON]; 0 for other families,
 *   which do not use it, so it does not split their accounts into different profiles.
 * @property blueskyLinkMode how Markdown links reach Bluesky (F11); [BlueskyLinkMode.FACET_ON_TEXT] for
 *   other formats, where it has no effect.
 */
data class TextProfile(
    val network: Network,
    val format: TextFormat,
    val contentType: String?,
    val counting: Counting,
    val maxCharacters: Int,
    val maxBytes: Int?,
    val charactersReservedPerUrl: Int,
    val blueskyLinkMode: BlueskyLinkMode,
    val numberParts: Boolean,
) {
    init {
        require(maxCharacters > 0) { "maxCharacters must be positive" }
        require(maxBytes == null || maxBytes > 0) { "maxBytes must be positive" }
        require(charactersReservedPerUrl >= 0) { "charactersReservedPerUrl must not be negative" }
        require((network == Network.BLUESKY) == (format == TextFormat.BLUESKY)) { "Bluesky takes the Bluesky format only" }
        require((network == Network.BLUESKY) == (counting == Counting.BLUESKY)) { "Bluesky counts by its own rule only" }
    }

    /** Equal for accounts that must get the same split (T3); it names every field. */
    val key: String
        get() = listOf(
            network.name, format.name, contentType ?: "-", counting.name, maxCharacters, maxBytes ?: "-",
            charactersReservedPerUrl, blueskyLinkMode.name, if (numberParts) "numbered" else "-",
        ).joinToString("|")

    /** Whether the account keeps Markdown formatting in some form; when no target does, the composer warns (F6). */
    val keepsFormatting: Boolean get() = format == TextFormat.MARKDOWN || format == TextFormat.HTML

    /** The length of a part with [text] and the content warning [spoilerText] as the server counts it. */
    fun count(text: String, spoilerText: String, segmenter: TextSegmenter): Int =
        counting.count(text, spoilerText, segmenter, charactersReservedPerUrl)
}

/** The profiles of the two networks. */
object TextProfiles {

    /**
     * The profile of a Mastodon-API account from its [capabilities] (formatting.md F1, F8–F10; networks.md N1):
     * Markdown when the server lists `text/markdown`, else HTML when it lists `text/html`, else plain text;
     * `content_type` only when the server listed its formats. The counting family follows the software name
     * of NodeInfo: `mastodon` and `hometown` count as [Counting.MASTODON], `pleroma` and `akkoma` as
     * [Counting.PLEROMA], `gotosocial` as [Counting.GOTOSOCIAL], anything else and an unknown name as
     * [Counting.FULL].
     */
    fun mastodon(capabilities: MastodonCapabilities, numberParts: Boolean): TextProfile {
        val format = when (capabilities.preferredFormat()) {
            MastodonCapabilities.TEXT_MARKDOWN -> TextFormat.MARKDOWN
            MastodonCapabilities.TEXT_HTML -> TextFormat.HTML
            else -> TextFormat.PLAIN
        }
        val counting = when (capabilities.softwareName?.trim()?.lowercase()) {
            "mastodon", "hometown" -> Counting.MASTODON
            "pleroma", "akkoma" -> Counting.PLEROMA
            "gotosocial" -> Counting.GOTOSOCIAL
            else -> Counting.FULL
        }
        val maxCharacters = capabilities.maxCharacters.takeIf { it > 0 } ?: MastodonCapabilities.Defaults.MAX_CHARACTERS
        return TextProfile(
            network = Network.MASTODON,
            format = format,
            contentType = capabilities.statusContentType(),
            counting = counting,
            maxCharacters = maxCharacters,
            maxBytes = null,
            charactersReservedPerUrl = if (counting == Counting.MASTODON) capabilities.charactersReservedPerUrl.coerceAtLeast(0) else 0,
            blueskyLinkMode = BlueskyLinkMode.FACET_ON_TEXT,
            numberParts = numberParts,
        )
    }

    /** The profile of a Bluesky account: 300 graphemes and 3000 bytes (networks.md, N16), links by [linkMode] (F11). */
    fun bluesky(linkMode: BlueskyLinkMode, numberParts: Boolean): TextProfile = TextProfile(
        network = Network.BLUESKY,
        format = TextFormat.BLUESKY,
        contentType = null,
        counting = Counting.BLUESKY,
        maxCharacters = BLUESKY_MAX_GRAPHEMES,
        maxBytes = BLUESKY_MAX_BYTES,
        charactersReservedPerUrl = 0,
        blueskyLinkMode = linkMode,
        numberParts = numberParts,
    )

    const val BLUESKY_MAX_GRAPHEMES = 300
    const val BLUESKY_MAX_BYTES = 3000
}
