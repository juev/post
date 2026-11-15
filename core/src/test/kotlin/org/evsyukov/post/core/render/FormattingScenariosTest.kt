package org.evsyukov.post.core.render

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.bluesky.LinkSpan
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.render.RenderFixtures.bluesky
import org.evsyukov.post.core.render.RenderFixtures.goToSocial
import org.evsyukov.post.core.render.RenderFixtures.mastodon
import org.evsyukov.post.core.render.RenderFixtures.render
import org.junit.Test

/** The verification scenarios of formatting.md, through the public API. */
class FormattingScenariosTest {
    private val draft = "**Hello** _there_ [site](https://example.com)"

    @Test
    fun `vanilla Mastodon gets plain text and counts the URL as 23`() {
        val profile = mastodon()
        assertThat(profile.format).isEqualTo(TextFormat.PLAIN)
        assertThat(profile.contentType).isNull()
        val part = render(draft, profile).parts.single()
        assertThat(part.text).isEqualTo("Hello there site (https://example.com)")
        assertThat(part.count).isEqualTo("Hello there site (".length + 23 + ")".length)
    }

    @Test
    fun `GoToSocial gets Markdown with star italic`() {
        val profile = mastodon(capabilities = goToSocial)
        assertThat(profile.format).isEqualTo(TextFormat.MARKDOWN)
        assertThat(profile.contentType).isEqualTo("text/markdown")
        assertThat(render(draft, profile).parts.single().text).isEqualTo("**Hello** *there* [site](https://example.com)")
    }

    @Test
    fun `Bluesky gets the text with a link facet on the link text`() {
        val part = render(draft, bluesky()).parts.single()
        assertThat(part.text).isEqualTo("Hello there site")
        assertThat(part.links).containsExactly(LinkSpan(12, 16, "https://example.com"))
    }

    @Test
    fun `Pleroma with only text plain gets plain text and keeps no formatting`() {
        val pleroma = MastodonCapabilities(postFormats = setOf("text/plain"), postFormatsDeclared = true, softwareName = "pleroma")
        val profile = mastodon(capabilities = pleroma)
        assertThat(profile.format).isEqualTo(TextFormat.PLAIN)
        assertThat(profile.contentType).isEqualTo("text/plain")
        assertThat(profile.keepsFormatting).isFalse()
        assertThat(mastodon(capabilities = goToSocial).keepsFormatting).isTrue()
        assertThat(render(draft, profile).parts.single().text).isEqualTo("Hello there site (https://example.com)")
    }
}
