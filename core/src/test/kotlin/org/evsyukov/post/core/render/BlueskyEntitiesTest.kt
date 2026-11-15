package org.evsyukov.post.core.render

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.bluesky.FacetFeature
import org.evsyukov.post.core.bluesky.LinkSpan
import org.evsyukov.post.core.bluesky.TagSpan
import org.evsyukov.post.core.bluesky.buildFacets
import org.evsyukov.post.core.markdown.BlueskyLinkMode
import org.evsyukov.post.core.render.RenderFixtures.bluesky
import org.evsyukov.post.core.render.RenderFixtures.graphemes
import org.evsyukov.post.core.render.RenderFixtures.mastodon
import org.evsyukov.post.core.render.RenderFixtures.render
import org.junit.Test

/** F11 and N17: links, mentions and hashtags of Bluesky parts, and their facets. */
class BlueskyEntitiesTest {
    private val did = "did:plc:abcdefghijklmnopqrstuvwx"

    private fun part(source: String, mode: BlueskyLinkMode = BlueskyLinkMode.FACET_ON_TEXT): RenderedPart {
        val text = render(source, bluesky(mode = mode))
        return PartAssembler.assemble(text, null, hasPoll = false, spoilerText = "").parts.single()
    }

    @Test
    fun `links, mentions and tags are found in Cyrillic text with emoji`() {
        val part = part("Привет 👋 @Alice.bsky.social! Смотри [сайт](https://example.com) и https://bsky.app #тег, #ok 🎉")
        assertThat(part.text).isEqualTo("Привет 👋 @Alice.bsky.social! Смотри сайт и https://bsky.app #тег, #ok 🎉")
        val text = part.text
        assertThat(part.links).containsExactly(
            LinkSpan(text.indexOf("сайт"), text.indexOf("сайт") + 4, "https://example.com"),
            LinkSpan(text.indexOf("https://bsky.app"), text.indexOf("https://bsky.app") + 16, "https://bsky.app"),
        ).inOrder()
        assertThat(part.mentions).containsExactly(HandleSpan(text.indexOf('@'), text.indexOf('!'), "alice.bsky.social"))
        assertThat(part.tags).containsExactly(
            TagSpan(text.indexOf("#тег"), text.indexOf("#тег") + 4, "тег"),
            TagSpan(text.indexOf("#ok"), text.indexOf("#ok") + 3, "ok"),
        ).inOrder()
    }

    @Test
    fun `facets get UTF-8 byte offsets and a mention needs its DID`() {
        val part = part("Привет 👋 @alice.bsky.social! Смотри [сайт](https://example.com) #тег 🎉")
        val bytes = part.text.toByteArray(Charsets.UTF_8)
        fun covered(start: Int, end: Int) = String(bytes, start, end - start, Charsets.UTF_8)

        val resolved = buildFacets(part.text, part.facetInput(mapOf("alice.bsky.social" to did)), ::graphemes)
        assertThat(resolved.map { covered(it.index.byteStart, it.index.byteEnd) })
            .containsExactly("@alice.bsky.social", "сайт", "#тег").inOrder()
        assertThat(resolved.map { it.features.single() }).containsExactly(
            FacetFeature.Mention(did),
            FacetFeature.Link("https://example.com"),
            FacetFeature.Tag("тег"),
        ).inOrder()
        // "Привет 👋 " is 12 + 1 + 4 + 1 bytes.
        assertThat(resolved.first().index.byteStart).isEqualTo(18)

        val unresolved = buildFacets(part.text, part.facetInput(emptyMap()), ::graphemes)
        assertThat(unresolved.map { it.features.single() }.none { it is FacetFeature.Mention }).isTrue()
        val malformed = buildFacets(part.text, part.facetInput(mapOf("alice.bsky.social" to "not a did")), ::graphemes)
        assertThat(malformed.map { it.features.single() }.none { it is FacetFeature.Mention }).isTrue()
    }

    @Test
    fun `a mention or tag inside a link is not a facet of its own`() {
        val part = part("[@bob.bsky.social #inside](https://example.com) and #outside")
        assertThat(part.links.single().uri).isEqualTo("https://example.com")
        assertThat(part.mentions).isEmpty()
        assertThat(part.tags.map { it.tag }).containsExactly("outside")
    }

    @Test
    fun `a tag over 64 graphemes is not a facet`() {
        val part = part("#" + "a".repeat(65) + " #" + "b".repeat(64))
        assertThat(part.tags.map { it.tag }).containsExactly("b".repeat(64))
    }

    @Test
    fun `the short-address mode links the address in parentheses`() {
        val part = part("Read [the docs](https://example.com/docs)", BlueskyLinkMode.TEXT_AND_SHORT_URL)
        assertThat(part.text).isEqualTo("Read the docs (example.com/docs)")
        assertThat(part.links).containsExactly(LinkSpan(15, 31, "https://example.com/docs"))
    }

    @Test
    fun `facet offsets stay right in later parts and after numbering`() {
        val words = List(70) { "слово" }.joinToString(" ")
        val source = "$words @carol.bsky.social $words https://example.com/x #финал"
        val parts = PartAssembler.assemble(render(source, bluesky(numberParts = true)), null, false, "").parts
        assertThat(parts.size).isAtLeast(2)
        val all = parts.flatMap { part ->
            buildFacets(part.text, part.facetInput(mapOf("carol.bsky.social" to did)), ::graphemes).map { facet ->
                val bytes = part.text.toByteArray(Charsets.UTF_8)
                String(bytes, facet.index.byteStart, facet.index.byteEnd - facet.index.byteStart, Charsets.UTF_8)
            }
        }
        assertThat(all).containsExactly("@carol.bsky.social", "https://example.com/x", "#финал")
    }

    @Test
    fun `Mastodon parts carry no facets`() {
        val part = PartAssembler.assemble(render("@a@b.social #tag https://example.com", mastodon()), null, false, "").parts.single()
        assertThat(part.links).isEmpty()
        assertThat(part.mentions).isEmpty()
        assertThat(part.tags).isEmpty()
    }
}
