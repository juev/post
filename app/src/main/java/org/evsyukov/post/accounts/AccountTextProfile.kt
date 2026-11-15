package org.evsyukov.post.accounts

import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.render.TextProfile
import org.evsyukov.post.core.render.TextProfiles
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.settings.Settings

/**
 * The text profile of [this] account (threads.md T3, formatting.md F8–F12): a Mastodon account's from its
 * stored limits and formats (vanilla defaults until they are read), a Bluesky account's fixed limits with
 * the link mode of the settings (F11). "Number thread parts" of the settings applies to both (T11).
 */
fun Account.textProfile(settings: Settings): TextProfile = when (network) {
    Network.MASTODON -> TextProfiles.mastodon(limits.mastodonCapabilities(), settings.numberThreadParts)
    Network.BLUESKY -> TextProfiles.bluesky(settings.blueskyLinkMode, settings.numberThreadParts)
}
