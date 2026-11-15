package org.evsyukov.post.core.bluesky

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** networks.md N17: links, mentions and tags with UTF-8 byte offsets (start inclusive, end exclusive). */
class FacetsTest {
    private val did = "did:plc:ewvi7nxzyoun6zhxrhs64oiz"

    private fun bytes(text: String, from: Int, to: Int) = text.substring(from, to).toByteArray(Charsets.UTF_8)

    @Test
    fun asciiOffsetsEqualCharOffsets() {
        val text = "see https://example.com now"
        val facets = buildFacets(text, FacetInput(links = listOf(LinkSpan(4, 23, "https://example.com"))), ::icuGraphemes)
        assertThat(facets).containsExactly(Facet(ByteSlice(4, 23), listOf(FacetFeature.Link("https://example.com"))))
    }

    @Test
    fun cyrillicCountsTwoBytesPerLetter() {
        val text = "Привет @alice.test и #тег"
        val mention = text.indexOf("@alice.test")
        val tag = text.indexOf("#тег")
        val facets = buildFacets(
            text,
            FacetInput(mentions = listOf(MentionSpan(mention, mention + 11, did)), tags = listOf(TagSpan(tag, text.length, "тег"))),
            ::icuGraphemes,
        )
        // "Привет " is 6 letters × 2 bytes + 1 space = 13 bytes.
        assertThat(facets[0].index).isEqualTo(ByteSlice(13, 24))
        assertThat(facets[0].features).containsExactly(FacetFeature.Mention(did))
        // "#тег" starts after "Привет @alice.test и " = 13 + 11 + 1 + 2 + 1 = 28 bytes; "#тег" is 1 + 6 bytes.
        assertThat(facets[1].index).isEqualTo(ByteSlice(28, 35))
        assertThat(facets[1].features).containsExactly(FacetFeature.Tag("тег"))
        val all = text.toByteArray(Charsets.UTF_8)
        assertThat(all.copyOfRange(13, 24).toString(Charsets.UTF_8)).isEqualTo("@alice.test")
        assertThat(all.copyOfRange(28, 35).toString(Charsets.UTF_8)).isEqualTo("#тег")
    }

    @Test
    fun emojiOutsideTheBmpCountsFourBytes() {
        val text = "🌊🏄‍♀️ go https://surf.example"
        val start = text.indexOf("https")
        val facets = buildFacets(text, FacetInput(links = listOf(LinkSpan(start, text.length, "https://surf.example"))), ::icuGraphemes)
        val expectedStart = bytes(text, 0, start).size
        // 🌊 (4) + 🏄 (4) + ZWJ (3) + ♀ (3) + VS16 (3) + " go " (4) = 21
        assertThat(expectedStart).isEqualTo(21)
        assertThat(facets.single().index).isEqualTo(ByteSlice(21, 21 + "https://surf.example".length))
    }

    @Test
    fun combiningMarksCountTheirOwnBytes() {
        // "é" as e + U+0301 (2 bytes), then a link.
        val text = "café #café"
        val start = text.indexOf('#')
        val facets = buildFacets(text, FacetInput(tags = listOf(TagSpan(start, text.length, "café"))), ::icuGraphemes)
        assertThat(facets.single().index).isEqualTo(ByteSlice(7, 14))
        assertThat(text.toByteArray(Charsets.UTF_8).copyOfRange(7, 14).toString(Charsets.UTF_8)).isEqualTo("#café")
    }

    @Test
    fun facetsComeOutInTextOrder() {
        val text = "#one https://x.example @bob.test"
        val facets = buildFacets(
            text,
            FacetInput(
                links = listOf(LinkSpan(5, 22, "https://x.example")),
                mentions = listOf(MentionSpan(23, 32, did)),
                tags = listOf(TagSpan(0, 4, "one")),
            ),
            ::icuGraphemes,
        )
        assertThat(facets.map { it.index.byteStart }).containsExactly(0, 5, 23).inOrder()
    }

    @Test
    fun tagOfSixtyFourGraphemesStaysAndSixtyFiveIsDropped() {
        // Each "е́" is two chars and one grapheme, so chars and graphemes differ.
        val g64 = "е́".repeat(64)
        val g65 = "е́".repeat(65)
        val text = "#$g64 #$g65"
        val second = text.lastIndexOf('#')
        val facets = buildFacets(
            text,
            FacetInput(tags = listOf(TagSpan(0, 1 + g64.length, g64), TagSpan(second, text.length, g65))),
            ::icuGraphemes,
        )
        assertThat(facets.map { (it.features.single() as FacetFeature.Tag).tag }).containsExactly(g64)
    }

    @Test
    fun graphemeCountComesFromTheGivenSegmenter() {
        val text = "#abc"
        val dropAll = buildFacets(text, FacetInput(tags = listOf(TagSpan(0, 4, "abc"))), graphemeCount = { 65 })
        assertThat(dropAll).isEmpty()
    }

    @Test
    fun tagOverTheByteLimitIsDroppedEvenWithFewGraphemes() {
        // One grapheme of 1 + 400 combining marks = 801 bytes > 640.
        val heavy = "a" + "́".repeat(400)
        val text = "#$heavy"
        val facets = buildFacets(text, FacetInput(tags = listOf(TagSpan(0, text.length, heavy))), ::icuGraphemes)
        assertThat(facets).isEmpty()
    }

    @Test
    fun emptyTagIsDropped() {
        assertThat(buildFacets("#", FacetInput(tags = listOf(TagSpan(0, 1, ""))), ::icuGraphemes)).isEmpty()
    }

    @Test
    fun invalidRangesAreRefused() {
        val text = "a🌊b"
        fun fails(input: FacetInput) = runCatching { buildFacets(text, input, ::icuGraphemes) }.exceptionOrNull()
        assertThat(fails(FacetInput(links = listOf(LinkSpan(0, 2, "https://x"))))).isInstanceOf(IllegalArgumentException::class.java) // splits 🌊
        assertThat(fails(FacetInput(links = listOf(LinkSpan(2, 2, "https://x"))))).isInstanceOf(IllegalArgumentException::class.java) // empty
        assertThat(fails(FacetInput(links = listOf(LinkSpan(0, 9, "https://x"))))).isInstanceOf(IllegalArgumentException::class.java) // outside
        assertThat(fails(FacetInput(links = listOf(LinkSpan(0, 1, " "))))).isInstanceOf(IllegalArgumentException::class.java) // blank uri
        assertThat(fails(FacetInput(mentions = listOf(MentionSpan(0, 1, "alice.test"))))).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(
            fails(FacetInput(links = listOf(LinkSpan(0, 3, "https://x")), tags = listOf(TagSpan(1, 4, "x")))),
        ).isInstanceOf(IllegalArgumentException::class.java) // overlap
    }

    @Test
    fun adjacentRangesDoNotOverlap() {
        val text = "#a#b"
        val facets = buildFacets(text, FacetInput(tags = listOf(TagSpan(0, 2, "a"), TagSpan(2, 4, "b"))), ::icuGraphemes)
        assertThat(facets.map { it.index }).containsExactly(ByteSlice(0, 2), ByteSlice(2, 4)).inOrder()
    }

    @Test
    fun loneSurrogateCountsOneByteLikeTheEncoder() {
        val text = "\uD83C x https://y"
        val start = text.indexOf("https")
        val facets = buildFacets(text, FacetInput(links = listOf(LinkSpan(start, text.length, "https://y"))), ::icuGraphemes)
        assertThat(facets.single().index.byteStart).isEqualTo(text.substring(0, start).toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun featuresSerializeWithTheirLexiconTypes() {
        val facets = buildFacets(
            "#t @m.test https://l",
            FacetInput(links = listOf(LinkSpan(11, 20, "https://l")), mentions = listOf(MentionSpan(3, 10, did)), tags = listOf(TagSpan(0, 2, "t"))),
            ::icuGraphemes,
        )
        val record = PostRecord(text = "#t @m.test https://l", createdAt = "2026-11-15T00:00:00.000Z", facets = facets).toJson()
        val types = record["facets"]!!.jsonArray.map { it.jsonObject["features"]!!.jsonArray.single().jsonObject.str("\$type") }
        assertThat(types).containsExactly(
            "app.bsky.richtext.facet#tag",
            "app.bsky.richtext.facet#mention",
            "app.bsky.richtext.facet#link",
        ).inOrder()
        assertThat(record["facets"]!!.jsonArray.first().jsonObject.keys).containsExactly("index", "features")
        assertThat(Lexicons.validateRecord(Collections.POST, record)).isEmpty()
        assertThat(Json.encodeToString(Facet.serializer(), facets.first())).isEqualTo(
            """{"index":{"byteStart":0,"byteEnd":2},"features":[{"${'$'}type":"app.bsky.richtext.facet#tag","tag":"t"}]}""",
        )
    }
}
