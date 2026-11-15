package org.evsyukov.post.core.render

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.markdown.BlueskyLinkMode
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.media.Network
import org.junit.Assert.assertThrows
import org.junit.Test

/** F1, F8–F10, N1, N16 and T3: the profile of an account. */
class TextProfilesTest {

    @Test
    fun `the counting family follows the software name`() {
        fun family(name: String?) = TextProfiles.mastodon(MastodonCapabilities(softwareName = name), false).counting
        assertThat(family("mastodon")).isEqualTo(Counting.MASTODON)
        assertThat(family("hometown")).isEqualTo(Counting.MASTODON)
        assertThat(family("Pleroma")).isEqualTo(Counting.PLEROMA)
        assertThat(family("akkoma")).isEqualTo(Counting.PLEROMA)
        assertThat(family("gotosocial")).isEqualTo(Counting.GOTOSOCIAL)
        assertThat(family("sharkey")).isEqualTo(Counting.FULL)
        assertThat(family(null)).isEqualTo(Counting.FULL)
    }

    @Test
    fun `the format is the richest the server lists, and content type only when it listed formats`() {
        val html = MastodonCapabilities(postFormats = setOf("text/plain", "text/html"), postFormatsDeclared = true)
        val markdown = MastodonCapabilities(postFormats = setOf("text/plain", "text/html", "text/markdown"), postFormatsDeclared = true)
        assertThat(TextProfiles.mastodon(html, false).format).isEqualTo(TextFormat.HTML)
        assertThat(TextProfiles.mastodon(html, false).contentType).isEqualTo("text/html")
        assertThat(TextProfiles.mastodon(markdown, false).format).isEqualTo(TextFormat.MARKDOWN)
        assertThat(TextProfiles.mastodon(MastodonCapabilities(), false).format).isEqualTo(TextFormat.PLAIN)
        assertThat(TextProfiles.mastodon(MastodonCapabilities(), false).contentType).isNull()
    }

    @Test
    fun `limits come from the server and the URL weight only matters to Mastodon counting`() {
        val pleroma = MastodonCapabilities(maxCharacters = 5000, charactersReservedPerUrl = 0, softwareName = "pleroma")
        val mastodon = MastodonCapabilities(maxCharacters = 1000, charactersReservedPerUrl = 30, softwareName = "mastodon")
        assertThat(TextProfiles.mastodon(pleroma, false).maxCharacters).isEqualTo(5000)
        assertThat(TextProfiles.mastodon(mastodon, false).charactersReservedPerUrl).isEqualTo(30)
        assertThat(TextProfiles.mastodon(pleroma.copy(charactersReservedPerUrl = 23), false))
            .isEqualTo(TextProfiles.mastodon(pleroma, false))
        assertThat(TextProfiles.mastodon(MastodonCapabilities(maxCharacters = 0), false).maxCharacters).isEqualTo(500)
        // An unknown server keeps a URL weight it declares over 23 as the least weight of a URL.
        val unknown = MastodonCapabilities(charactersReservedPerUrl = 30, softwareName = "Iceshrimp.NET")
        assertThat(TextProfiles.mastodon(unknown, false).counting).isEqualTo(Counting.FULL)
        assertThat(TextProfiles.mastodon(unknown, false).charactersReservedPerUrl).isEqualTo(30)
        assertThat(TextProfiles.mastodon(unknown.copy(charactersReservedPerUrl = 0), false).charactersReservedPerUrl).isEqualTo(23)
    }

    @Test
    fun `accounts with the same limit and formats share a key, others do not`() {
        val a = TextProfiles.mastodon(MastodonCapabilities(softwareName = "mastodon"), false)
        val b = TextProfiles.mastodon(MastodonCapabilities(softwareName = "mastodon", version = "4.5.0"), false)
        assertThat(a).isEqualTo(b)
        assertThat(a.key).isEqualTo(b.key)
        val longer = TextProfiles.mastodon(MastodonCapabilities(maxCharacters = 5000, softwareName = "mastodon"), false)
        assertThat(longer.key).isNotEqualTo(a.key)
        assertThat(TextProfiles.mastodon(MastodonCapabilities(softwareName = "mastodon"), true).key).isNotEqualTo(a.key)
        val facet = TextProfiles.bluesky(BlueskyLinkMode.FACET_ON_TEXT, false)
        val short = TextProfiles.bluesky(BlueskyLinkMode.TEXT_AND_SHORT_URL, false)
        assertThat(facet.key).isNotEqualTo(short.key)
        assertThat(facet.key).isNotEqualTo(a.key)
    }

    @Test
    fun `Bluesky has 300 graphemes and 3000 bytes`() {
        val profile = TextProfiles.bluesky(BlueskyLinkMode.FACET_ON_TEXT, numberParts = true)
        assertThat(profile.network).isEqualTo(Network.BLUESKY)
        assertThat(profile.maxCharacters).isEqualTo(300)
        assertThat(profile.maxBytes).isEqualTo(3000)
        assertThat(profile.counting).isEqualTo(Counting.BLUESKY)
        assertThat(profile.numberParts).isTrue()
    }

    @Test
    fun `a profile that mixes networks is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            TextProfile(Network.BLUESKY, TextFormat.PLAIN, null, Counting.BLUESKY, 300, 3000, 0, BlueskyLinkMode.FACET_ON_TEXT, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextProfile(Network.MASTODON, TextFormat.PLAIN, null, Counting.MASTODON, 0, null, 23, BlueskyLinkMode.FACET_ON_TEXT, false)
        }
    }
}
