package org.evsyukov.post.core.render

import org.evsyukov.post.core.markdown.BlueskyLinkMode
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.testing.IcuTextSegmenter
import java.util.Locale

/** Shared profiles and helpers of the render tests. */
internal object RenderFixtures {
    val segmenter = IcuTextSegmenter()
    val RU: Locale = Locale.forLanguageTag("ru")
    val EN: Locale = Locale.ENGLISH

    val vanillaMastodon = MastodonCapabilities(softwareName = "mastodon")
    val goToSocial = MastodonCapabilities(
        postFormats = setOf("text/plain", "text/markdown"),
        postFormatsDeclared = true,
        softwareName = "gotosocial",
    )

    fun mastodon(numberParts: Boolean = false, capabilities: MastodonCapabilities = vanillaMastodon) =
        TextProfiles.mastodon(capabilities, numberParts)

    fun bluesky(numberParts: Boolean = false, mode: BlueskyLinkMode = BlueskyLinkMode.FACET_ON_TEXT) =
        TextProfiles.bluesky(mode, numberParts)

    /** A Mastodon-API profile with any format, counting and limit, for tests that need small limits. */
    fun custom(
        format: TextFormat,
        maxCharacters: Int,
        counting: Counting = Counting.MASTODON,
        numberParts: Boolean = false,
        contentType: String? = null,
    ) = TextProfile(
        network = Network.MASTODON,
        format = format,
        contentType = contentType,
        counting = counting,
        maxCharacters = maxCharacters,
        maxBytes = null,
        charactersReservedPerUrl = if (counting == Counting.MASTODON) 23 else 0,
        blueskyLinkMode = BlueskyLinkMode.FACET_ON_TEXT,
        numberParts = numberParts,
    )

    fun render(source: String, profile: TextProfile, locale: Locale = EN, spoiler: String = "", mediaOnlyParts: Map<Int, Int> = emptyMap()) =
        TextRenderer.render(DraftText(source, spoiler, locale), profile, segmenter, mediaOnlyParts)

    /** Graphemes of [text] by the ICU rules the app uses. */
    fun graphemes(text: String): Int = segmenter.graphemeBoundaries(text).size - 1

    /** Checks the invariants every render must keep, whatever the input. */
    fun assertValid(source: String, result: TextRender) {
        val profile = result.profile
        if (result.issue != null) {
            check(result.parts.isEmpty()) { "parts with an issue" }
            return
        }
        check(result.parts.isNotEmpty()) { "no parts" }
        for (part in result.parts) {
            check(part.count <= profile.maxCharacters) { "part over the limit: ${part.count} > ${profile.maxCharacters}: ${part.text}" }
            profile.maxBytes?.let { max -> check(part.bytes!! <= max) { "part over the byte limit: ${part.bytes}" } }
            check(part.text.isEmpty() || (!part.text.first().isWhitespace() && !part.text.last().isWhitespace())) {
                "part with whitespace at an end: <${part.text}>"
            }
            check(part.sourceStart in 0..part.sourceEnd && part.sourceEnd <= source.length) { "bad source range" }
        }
    }
}
