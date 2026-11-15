package org.evsyukov.post.accounts

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.evsyukov.post.core.bluesky.BlueskyClient
import org.evsyukov.post.core.bluesky.XrpcException
import org.evsyukov.post.data.accounts.AccountNotFound
import org.evsyukov.post.data.accounts.AccountsRepository
import org.evsyukov.post.data.db.AccountState
import org.evsyukov.post.data.secrets.BlueskySecret
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.data.secrets.SecretUnreadable
import java.util.concurrent.ConcurrentHashMap

/** What a Mastodon call needs: the instance root and the user token. [toString] hides the token. */
class MastodonAuth(val origin: HttpUrl, val token: String) {
    override fun toString(): String = "MastodonAuth(origin=$origin, token=***)"
}

/** What a Bluesky call needs: the DID (the repo), the PDS root and the access token. [toString] hides the token. */
class BlueskyAuth(val did: String, val pds: HttpUrl, val accessJwt: String) {
    override fun toString(): String = "BlueskyAuth(did=$did, pds=$pds, accessJwt=***)"
}

/**
 * The account [accountId] needs sign-in (product.md R5): its server rejected the credentials, or its
 * stored tokens no longer open. The account is already marked [AccountState.NEEDS_SIGN_IN] when this is
 * thrown; the caller stops the delivery with this reason.
 */
class NeedsSignInException(val accountId: Long, cause: Throwable? = null) : Exception("account $accountId needs sign-in", cause)

/**
 * Credentials of signed-in accounts for the code that publishes, edits and deletes (steps 8 and 11).
 *
 * - Mastodon tokens do not expire (N5). [mastodon] returns the token; when the server answers a call
 *   with [org.evsyukov.post.core.mastodon.MastodonException.isUnauthorized], the caller marks the account
 *   with `AccountsRepository.setState(id, NEEDS_SIGN_IN)` and stops.
 * - Bluesky access tokens live for minutes (N15). [bluesky] runs the call with the stored access token;
 *   when it fails with [XrpcException.isExpiredToken], the session is refreshed with
 *   `com.atproto.server.refreshSession`, the rotated tokens are stored, and the call runs once more.
 *   Refresh tokens rotate, so refreshes of one account never run at the same time: a caller that waited
 *   for another's refresh uses the tokens it stored. A rejected refresh marks the account and throws
 *   [NeedsSignInException]; a network failure of the refresh is thrown as it is, for a later retry.
 *
 * An account already marked as needing sign-in, or one whose stored tokens do not open, throws
 * [NeedsSignInException] before any request. Tokens go only to the account's own server (R4).
 *
 * @throws AccountNotFound from both calls when the account was removed.
 */
class AccountSessions(
    private val accounts: AccountsRepository,
    private val http: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshLocks = ConcurrentHashMap<Long, Mutex>()

    /** The instance and token of Mastodon account [accountId]. */
    suspend fun mastodon(accountId: Long): MastodonAuth {
        val account = signedIn(accountId)
        val secret = secret(accountId) as? MastodonSecret ?: throw needsSignIn(accountId)
        return MastodonAuth(rootOf(account.server), secret.accessToken)
    }

    /** Runs [block] with the session of Bluesky account [accountId], refreshing it once when it expired. */
    suspend fun <T> bluesky(accountId: Long, block: suspend (BlueskyAuth) -> T): T {
        val account = signedIn(accountId)
        val pds = rootOf(account.server)
        val secret = secret(accountId) as? BlueskySecret ?: throw needsSignIn(accountId)
        try {
            return block(BlueskyAuth(account.remoteId, pds, secret.accessJwt))
        } catch (e: XrpcException) {
            if (!e.isExpiredToken) throw e
        }
        val fresh = refresh(accountId, pds, used = secret.accessJwt)
        return block(BlueskyAuth(account.remoteId, pds, fresh.accessJwt))
    }

    private suspend fun refresh(accountId: Long, pds: HttpUrl, used: String): BlueskySecret {
        val lock = refreshLocks.getOrPut(accountId) { Mutex() }
        return lock.withLock {
            val stored = secret(accountId) as? BlueskySecret ?: throw needsSignIn(accountId)
            // Another caller refreshed while this one waited: its tokens are the current ones.
            if (stored.accessJwt != used) return@withLock stored
            val session = try {
                BlueskyClient(pds, http, json).refreshSession(stored.refreshJwt)
            } catch (e: XrpcException) {
                if (e.isExpiredToken || e.isInvalidToken || e.isAccountTakedown) throw needsSignIn(accountId, e)
                throw e
            }
            BlueskySecret(session.accessJwt, session.refreshJwt).also { accounts.updateSecret(accountId, it) }
        }
    }

    private suspend fun signedIn(accountId: Long) =
        (accounts.get(accountId) ?: throw AccountNotFound(accountId)).also {
            if (it.state == AccountState.NEEDS_SIGN_IN) throw NeedsSignInException(accountId)
        }

    private suspend fun secret(accountId: Long) = try {
        accounts.readSecret(accountId)
    } catch (e: SecretUnreadable) {
        throw needsSignIn(accountId, e)
    }

    private suspend fun needsSignIn(accountId: Long, cause: Throwable? = null): NeedsSignInException {
        try {
            accounts.setState(accountId, AccountState.NEEDS_SIGN_IN)
        } catch (_: AccountNotFound) {
            // Removed meanwhile; the exception still stops the caller.
        }
        return NeedsSignInException(accountId, cause)
    }
}
