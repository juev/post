package org.evsyukov.post.accounts

import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import org.evsyukov.post.core.mastodon.MastodonClient
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.AccountsRepository
import org.evsyukov.post.data.accounts.MastodonAppsRepository
import org.evsyukov.post.data.secrets.MastodonSecret

/**
 * Removes an account (product.md R1). A Mastodon token is revoked first with `POST /oauth/revoke`
 * (networks.md N5), best effort: a server that is down or refuses does not keep the account. Bluesky
 * app-password sessions are only forgotten.
 */
class AccountRemover(
    private val accounts: AccountsRepository,
    private val apps: MastodonAppsRepository,
    private val http: OkHttpClient,
) {
    /** Returns false when there was no such account. */
    suspend fun remove(accountId: Long): Boolean {
        val account = accounts.get(accountId) ?: return false
        if (account.network == Network.MASTODON) {
            try {
                val token = (accounts.readSecret(accountId) as MastodonSecret).accessToken
                val app = apps.get(account.server)
                if (app != null) MastodonClient(rootOf(account.server), http).revokeToken(app.clientId, app.clientSecret, token)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best effort (N5): the account goes anyway.
            }
        }
        return accounts.remove(accountId)
    }
}
