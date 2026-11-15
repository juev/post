@file:OptIn(ExperimentalSerializationApi::class)

package org.evsyukov.post.core.bluesky

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/*
 * Rich-text facets (networks.md N17), from `app.bsky.richtext.facet` at `bluesky-social/bsky`
 * `416b0ad1d9`: a byte slice of the UTF-8 text, start inclusive, end exclusive, with features that
 * are union members (`#link`, `#mention`, `#tag`).
 */

/** `app.bsky.richtext.facet#byteSlice`. */
@Serializable
data class ByteSlice(val byteStart: Int, val byteEnd: Int)

/** `app.bsky.richtext.facet`. */
@Serializable
data class Facet(val index: ByteSlice, val features: List<FacetFeature>)

/** Union members of `features`. */
@Serializable
@JsonClassDiscriminator("\$type")
sealed interface FacetFeature {
    @Serializable
    @SerialName("app.bsky.richtext.facet#link")
    data class Link(val uri: String) : FacetFeature

    @Serializable
    @SerialName("app.bsky.richtext.facet#mention")
    data class Mention(val did: String) : FacetFeature

    @Serializable
    @SerialName("app.bsky.richtext.facet#tag")
    data class Tag(val tag: String) : FacetFeature
}

/** A link over `text[start, end)`, in `String` (UTF-16) indices. */
data class LinkSpan(val start: Int, val end: Int, val uri: String)

/** A mention over `text[start, end)`, usually `@handle`, pointing to a resolved DID. */
data class MentionSpan(val start: Int, val end: Int, val did: String)

/** A hashtag over `text[start, end)`; [tag] is the value without the leading `#`. */
data class TagSpan(val start: Int, val end: Int, val tag: String)

/** Ranges found in the text by the renderer, in `String` (UTF-16) indices. */
data class FacetInput(
    val links: List<LinkSpan> = emptyList(),
    val mentions: List<MentionSpan> = emptyList(),
    val tags: List<TagSpan> = emptyList(),
)

/** Lexicon limits of a tag value: 64 graphemes and 640 UTF-8 bytes. */
const val MAX_TAG_GRAPHEMES = 64
const val MAX_TAG_BYTES = 640

/**
 * Facets for [text] with UTF-8 byte offsets, ordered by position.
 *
 * Tags over [MAX_TAG_GRAPHEMES] graphemes (counted by [graphemeCount], so the app's ICU segmenter
 * decides) or over [MAX_TAG_BYTES] bytes are dropped, as are empty tags: the PDS validates posts
 * against the lexicon and would reject the whole post.
 *
 * @throws IllegalArgumentException for a range outside the text, an empty range, a range that
 *   splits a surrogate pair, overlapping ranges, a blank link or a mention whose DID is malformed
 */
fun buildFacets(text: String, input: FacetInput, graphemeCount: (String) -> Int): List<Facet> {
    val offsets = utf8Offsets(text)
    val facets = mutableListOf<Pair<IntRange, FacetFeature>>()
    for (link in input.links) {
        require(link.uri.isNotBlank()) { "blank link" }
        facets += range(text, link.start, link.end) to FacetFeature.Link(link.uri)
    }
    for (mention in input.mentions) {
        require(IdentityResolver.isValidDid(mention.did)) { "mention without a valid DID" }
        facets += range(text, mention.start, mention.end) to FacetFeature.Mention(mention.did)
    }
    for (tag in input.tags) {
        val range = range(text, tag.start, tag.end)
        val value = tag.tag
        if (value.isEmpty() || graphemeCount(value) > MAX_TAG_GRAPHEMES || value.toByteArray(Charsets.UTF_8).size > MAX_TAG_BYTES) continue
        facets += range to FacetFeature.Tag(value)
    }
    facets.sortBy { it.first.first }
    for (i in 1 until facets.size) {
        require(facets[i].first.first >= facets[i - 1].first.last + 1) { "overlapping facet ranges" }
    }
    return facets.map { (chars, feature) ->
        Facet(ByteSlice(offsets[chars.first], offsets[chars.last + 1]), listOf(feature))
    }
}

/** Checks `[start, end)` and returns it as a closed char range. */
private fun range(text: String, start: Int, end: Int): IntRange {
    require(start in 0 until end && end <= text.length) { "facet range outside the text or empty" }
    require(!splitsPair(text, start) && !splitsPair(text, end)) { "facet range splits a surrogate pair" }
    return start until end
}

private fun splitsPair(text: String, index: Int): Boolean =
    index in 1 until text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()

/**
 * UTF-8 byte offset of every char index of [text], plus its total length at index `length`. A lone
 * surrogate counts one byte: Okio and `String.toByteArray` both write it as `?`.
 */
private fun utf8Offsets(text: String): IntArray {
    val offsets = IntArray(text.length + 1)
    var bytes = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        offsets[i] = bytes
        if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            offsets[i + 1] = bytes
            bytes += 4
            i += 2
            continue
        }
        bytes += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            c.isSurrogate() -> 1
            else -> 3
        }
        i++
    }
    offsets[text.length] = bytes
    return offsets
}
