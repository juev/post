package org.evsyukov.post.accounts

import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.evsyukov.post.core.mastodon.InstanceV1
import org.evsyukov.post.core.mastodon.InstanceV2
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.mastodon.MastodonClient
import org.evsyukov.post.core.mastodon.MastodonException
import org.evsyukov.post.core.mastodon.NodeInfo
import org.evsyukov.post.core.mastodon.Pkce
import org.evsyukov.post.core.mastodon.REDIRECT_URI
import org.evsyukov.post.core.mastodon.newState
import org.evsyukov.post.core.mastodon.scopesFor
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.AccountLimits
import org.evsyukov.post.data.accounts.AccountsRepository
import org.evsyukov.post.data.accounts.MastodonApp
import org.evsyukov.post.data.accounts.MastodonAppsRepository
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.data.secrets.SecretUnreadable
import org.evsyukov.post.oauth.PendingSignIn
import org.evsyukov.post.oauth.PendingSignInStore
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Clock

/**
 * Reads what a Mastodon-API server accepts (networks.md N1, N2; formatting.md F1): `/api/v2/instance`,
 * then `/api/v1/instance`, then NodeInfo.
 *
 * @throws SignInException [SignInProblem.NOT_MASTODON] when neither instance document reads, or
 *   [SignInProblem.NETWORK] when the server could not be reached.
 */
internal suspend fun readCapabilities(client: MastodonClient): MastodonCapabilities {
    var failure: Exception? = null
    val v2: InstanceV2? = try {
        client.instanceV2()
    } catch (e: MastodonException) {
        failure = e
        null
    }
    val v1: InstanceV1? = try {
        client.instanceV1()
    } catch (e: MastodonException) {
        if (failure == null || failure is MastodonException.Http) failure = e
        null
    }
    if (v2 == null && v1 == null) {
        val transient = (failure as? MastodonException)?.isTransient == true
        throw SignInException(if (transient) SignInProblem.NETWORK else SignInProblem.NOT_MASTODON, failure)
    }
    val nodeInfo: NodeInfo? = try {
        client.nodeInfo()
    } catch (_: MastodonException) {
        null
    }
    return MastodonCapabilities.from(v2, v1, nodeInfo)
}

/**
 * Adds a Mastodon account, or signs one in again, with the OAuth code flow and PKCE (networks.md
 * N4–N6, plan.md "Mastodon OAuth").
 *
 * [start] reads the server, registers Post on it once per instance, writes a [PendingSignIn] and returns
 * the address to open in the browser. [complete] takes the redirect back. Between the two the process
 * may die: everything [complete] needs is on disk.
 */
class MastodonSignIn(
    private val accounts: AccountsRepository,
    private val apps: MastodonAppsRepository,
    private val pending: PendingSignInStore,
    private val http: OkHttpClient,
    private val policy: ServerPolicy,
    private val clock: Clock = Clock.systemUTC(),
    /** Called with the account id after a successful sign-in; the sender resumes its deliveries (R5). */
    private val onSignedIn: suspend (Long) -> Unit = {},
) {
    /** What the redirect led to. */
    sealed interface Completion {
        /** The account [accountId] is signed in. */
        data class Done(val accountId: Long) : Completion

        /** The server forgot Post's registration; Post registered again and the user signs in at [url]. */
        data class Restart(val url: HttpUrl) : Completion
    }

    /**
     * Prepares the sign-in at the instance the user typed and returns the authorization address.
     *
     * @param accountId the account that signs in again, or null for a new one.
     * @throws SignInException
     */
    suspend fun start(address: String, accountId: Long? = null): HttpUrl {
        val root = InstanceAddress.parse(address, policy) ?: throw SignInException(SignInProblem.INVALID_ADDRESS)
        return try {
            val client = MastodonClient(root, http)
            val capabilities = readCapabilities(client)
            begin(client, originOf(root), capabilities, accountId, registeredAgain = false)
        } catch (e: Exception) {
            throw mastodonProblem(e)
        }
    }

    /**
     * Completes the sign-in from the [redirect] the browser returned. A redirect whose `state` is not the
     * pending one is rejected before any request, and the pending sign-in stays for the real redirect.
     * Otherwise the pending sign-in is cleared whatever the outcome, unless a [Completion.Restart]
     * replaced it.
     *
     * @throws SignInException
     */
    suspend fun complete(redirect: String): Completion {
        val params = redirectParams(redirect) ?: throw SignInException(SignInProblem.UNKNOWN_STATE)
        val current = pending.get()
        val state = params["state"]
        if (current == null || state == null || !sameSecret(state, current.state)) {
            throw SignInException(SignInProblem.UNKNOWN_STATE)
        }
        try {
            if (params["error"] != null) throw SignInException(SignInProblem.DENIED)
            val code = params["code"]?.takeIf { it.isNotEmpty() } ?: throw SignInException(SignInProblem.SERVER)
            val client = MastodonClient(rootOf(current.origin), http)
            val app = registration(current.origin)
            if (app == null || app.clientId != current.clientId) {
                // The registration changed since the browser opened: the code belongs to another client.
                return restart(client, current)
            }
            val token = try {
                client.exchangeCode(app.clientId, app.clientSecret, REDIRECT_URI, code, current.codeVerifier, current.scopes)
            } catch (e: MastodonException) {
                if (!e.isInvalidClient) throw e
                apps.delete(current.origin)
                return restart(client, current)
            }
            val profile = client.verifyCredentials(token.accessToken)
            val host = rootOf(current.origin).host
            val handle = if ('@' in profile.acct) profile.acct else "${profile.acct}@$host"
            val id = accounts.add(
                NewAccount(
                    network = Network.MASTODON,
                    server = current.origin,
                    handle = handle,
                    remoteId = profile.id,
                    displayName = profile.displayName.ifBlank { profile.username },
                    avatarUrl = profile.avatar?.takeIf { it.isNotBlank() },
                ),
                MastodonSecret(token.accessToken),
            )
            accounts.updateLimits(id, AccountLimits.Mastodon(current.capabilities), clock.instant())
            pending.clear()
            notifySignedIn(id, onSignedIn)
            return Completion.Done(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pending.clear()
            throw mastodonProblem(e)
        }
    }

    /** Forgets the pending sign-in, as when the user closes the sign-in page. */
    suspend fun cancel() = pending.clear()

    private suspend fun restart(client: MastodonClient, current: PendingSignIn): Completion {
        if (current.registeredAgain) throw SignInException(SignInProblem.CLIENT_REJECTED)
        val url = begin(client, current.origin, current.capabilities, current.accountId, registeredAgain = true)
        return Completion.Restart(url)
    }

    private suspend fun begin(
        client: MastodonClient,
        origin: String,
        capabilities: MastodonCapabilities,
        accountId: Long?,
        registeredAgain: Boolean,
    ): HttpUrl {
        val scopes = scopesFor(client.authorizationServerMetadata())
        val app = registration(origin)?.takeIf { it.scopes == scopes && it.redirectUri == REDIRECT_URI && stillKnown(client, it) }
            ?: register(client, origin, scopes)
        val verifier = Pkce.verifier()
        val state = newState()
        pending.put(
            PendingSignIn(
                origin = origin,
                state = state,
                codeVerifier = verifier,
                scopes = scopes,
                clientId = app.clientId,
                accountId = accountId,
                capabilities = capabilities,
                registeredAgain = registeredAgain,
                createdAt = clock.millis(),
            ),
        )
        return client.authorizeUrl(app.clientId, REDIRECT_URI, scopes, state, Pkce.challenge(verifier), forceLogin = true)
    }

    /**
     * N4: whether the server still knows the stored registration. A server may remove it (for example when its
     * last token was revoked), and then rejects it at the authorize page, which sends no redirect back. An app
     * token answered with `invalid_client` says it is gone; any other answer keeps it, and the token goes.
     */
    private suspend fun stillKnown(client: MastodonClient, app: MastodonApp): Boolean {
        val token = try {
            client.appToken(app.clientId, app.clientSecret, app.scopes)
        } catch (e: MastodonException) {
            return !e.isInvalidClient
        }
        try {
            client.revokeToken(app.clientId, app.clientSecret, token.accessToken)
        } catch (_: MastodonException) {
            // The app token expires or stays unused; the sign-in goes on.
        }
        return true
    }

    private suspend fun registration(origin: String): MastodonApp? = try {
        apps.get(origin)
    } catch (_: SecretUnreadable) {
        null
    }

    private suspend fun register(client: MastodonClient, origin: String, scopes: String): MastodonApp {
        val registration = client.registerApp(CLIENT_NAME, listOf(REDIRECT_URI), scopes, WEBSITE)
        val app = MastodonApp(origin, registration.clientId, registration.clientSecret, REDIRECT_URI, scopes, clock.instant())
        apps.put(app)
        return app
    }

    companion object {
        const val CLIENT_NAME = "Post"
        const val WEBSITE = "https://github.com/juev/post"

        /** The query of a redirect to [REDIRECT_URI], or null when [redirect] is another address. */
        internal fun redirectParams(redirect: String): Map<String, String>? {
            val base = redirect.substringBefore('?').substringBefore('#')
            if (!base.equals(REDIRECT_URI, ignoreCase = true)) return null
            val query = redirect.substringAfter('?', "").substringBefore('#')
            return query.split('&').filter { it.isNotEmpty() }.associate { pair ->
                val name = pair.substringBefore('=')
                val value = pair.substringAfter('=', "")
                decode(name) to decode(value)
            }
        }

        private fun decode(text: String): String = try {
            URLDecoder.decode(text, Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            ""
        }

        private fun sameSecret(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}
