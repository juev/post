package org.evsyukov.post.accounts

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.markdown.BlueskyLinkMode
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.render.Counting
import org.evsyukov.post.core.render.TextFormat
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.accounts.AccountLimits
import org.evsyukov.post.data.accounts.AccountOptions
import org.evsyukov.post.data.db.AccountState
import org.evsyukov.post.data.settings.Settings
import org.junit.Test
import java.time.Instant

/** An account's stored limits and the settings give the text profile its parts are made for (T3, T11, F8–F12). */
class AccountTextProfileTest {
    private fun account(network: Network, limits: AccountLimits? = null) = Account(
        id = 1, network = network, server = "https://s.example", handle = "a", remoteId = "1", displayName = "A", avatarUrl = null,
        label = "", position = 0, state = AccountState.OK, limits = limits, limitsFetchedAt = null, defaults = AccountOptions(), createdAt = Instant.EPOCH,
    )

    @Test
    fun aMastodonAccountUsesItsStoredLimitsFormatAndCounting() {
        val caps = MastodonCapabilities(
            maxCharacters = 5000,
            charactersReservedPerUrl = 30,
            postFormats = setOf(MastodonCapabilities.TEXT_PLAIN, MastodonCapabilities.TEXT_MARKDOWN),
            postFormatsDeclared = true,
            softwareName = "gotosocial",
        )

        val profile = account(Network.MASTODON, AccountLimits.Mastodon(caps)).textProfile(Settings(numberThreadParts = true))

        assertThat(profile.maxCharacters).isEqualTo(5000)
        assertThat(profile.format).isEqualTo(TextFormat.MARKDOWN)
        assertThat(profile.counting).isEqualTo(Counting.GOTOSOCIAL)
        assertThat(profile.numberParts).isTrue()
    }

    @Test
    fun aMastodonAccountWithoutLimitsGetsTheVanillaDefaults() {
        val profile = account(Network.MASTODON).textProfile(Settings())

        assertThat(profile.maxCharacters).isEqualTo(500)
        assertThat(profile.format).isEqualTo(TextFormat.PLAIN)
        assertThat(profile.numberParts).isFalse()
    }

    @Test
    fun aBlueskyAccountTakesTheLinkModeOfTheSettings() {
        val settings = Settings(blueskyLinkMode = BlueskyLinkMode.TEXT_AND_SHORT_URL, numberThreadParts = true)

        val profile = account(Network.BLUESKY).textProfile(settings)

        assertThat(profile.network).isEqualTo(Network.BLUESKY)
        assertThat(profile.maxCharacters).isEqualTo(300)
        assertThat(profile.maxBytes).isEqualTo(3000)
        assertThat(profile.blueskyLinkMode).isEqualTo(BlueskyLinkMode.TEXT_AND_SHORT_URL)
        assertThat(profile.numberParts).isTrue()
        // Two Bluesky accounts share one profile key, so they share one split (T3).
        assertThat(account(Network.BLUESKY).textProfile(settings).key).isEqualTo(profile.key)
    }
}
