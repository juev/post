package org.evsyukov.post.accounts

import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.media.NetworkMediaRules
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.accounts.AccountLimits

/**
 * The stored limits of a Mastodon account (N1), or the vanilla defaults when none were read yet or the
 * account is not a Mastodon one.
 */
fun AccountLimits?.mastodonCapabilities(): MastodonCapabilities =
    (this as? AccountLimits.Mastodon)?.capabilities ?: MastodonCapabilities()

/**
 * The media rules of a Mastodon-API server (media.md, networks.md N1). The mapping starts from
 * [NetworkMediaRules.mastodonDefaults] so that what the server does not report, such as the GIF matrix
 * limit, keeps its vanilla value. A type group the server lists nothing for keeps the vanilla types.
 */
fun MastodonCapabilities.toMediaRules(): NetworkMediaRules {
    val defaults = NetworkMediaRules.mastodonDefaults()
    fun group(prefix: String, fallback: Set<String>): Set<String> =
        supportedMediaMimeTypes.filter { it.startsWith(prefix) }.toSet().ifEmpty { fallback }
    return defaults.copy(
        maxAttachments = maxMediaAttachments,
        imageMimes = group("image/", defaults.imageMimes),
        maxImageBytes = imageSizeLimit,
        maxImagePixels = imageMatrixLimit,
        videoMimes = group("video/", defaults.videoMimes),
        maxVideoBytes = videoSizeLimit,
        maxVideoPixels = videoMatrixLimit,
        maxVideoFrameRate = videoFrameRateLimit,
        maxAltLength = descriptionLimit,
        audioMimes = group("audio/", defaults.audioMimes),
    )
}

/** The media rules of [this] account: its server's for Mastodon, the fixed ones for Bluesky. */
fun Account.mediaRules(): NetworkMediaRules = when (network) {
    Network.MASTODON -> limits.mastodonCapabilities().toMediaRules()
    Network.BLUESKY -> NetworkMediaRules.bluesky()
}
