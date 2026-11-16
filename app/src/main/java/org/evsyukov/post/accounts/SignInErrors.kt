package org.evsyukov.post.accounts

import kotlinx.coroutines.CancellationException
import org.evsyukov.post.core.bluesky.IdentityException
import org.evsyukov.post.core.bluesky.XrpcException
import org.evsyukov.post.core.mastodon.MastodonException
import java.io.IOException

/** Why a sign-in failed; the screens show a sentence for each (strings_accounts.xml). */
enum class SignInProblem {
    /** The text names no server, or one that is not HTTPS. */
    INVALID_ADDRESS,

    /** The server answers, but not as a Mastodon-API server. */
    NOT_MASTODON,

    /** The server could not be reached, or failed for a while; trying again may help. */
    NETWORK,

    /** The server refused or answered something Post cannot read. */
    SERVER,

    /** The redirect does not belong to a sign-in Post started; nothing was sent anywhere. */
    UNKNOWN_STATE,

    /** The user declined the authorization, or closed the sign-in page. */
    DENIED,

    /** The server does not accept Post's registration, even after registering again. */
    CLIENT_REJECTED,

    /** No browser can open the sign-in page. */
    NO_BROWSER,

    /** Not a valid Bluesky handle or DID. */
    INVALID_HANDLE,

    /** The handle does not resolve to an account. */
    HANDLE_NOT_FOUND,

    /** The account's DID document does not claim the handle back (networks.md N13). */
    HANDLE_MISMATCH,

    /** The DID document names no PDS, or one that is not HTTPS. */
    NO_PDS,

    /** The PDS rejected the handle and app password. */
    WRONG_PASSWORD,

    /** The code from the e-mail is wrong or expired. */
    WRONG_CODE,

    /** The account is deactivated or suspended. */
    ACCOUNT_INACTIVE,

    /** The account is taken down. */
    ACCOUNT_TAKEN_DOWN,

    /** Too many sign-ins for now (Bluesky allows 30 in 5 minutes, N15). */
    RATE_LIMITED,

    /**
     * Signing an account in again brought back another account (product.md R5); the original one still
     * needs sign-in.
     */
    WRONG_ACCOUNT,
}

/**
 * The account [signedIn] came back from a sign-in meant for [expected]; both are handles such as
 * `alice@mastodon.social`.
 */
data class AccountMismatch(val signedIn: String, val expected: String)

/**
 * A sign-in failed for [problem]. The message never holds a token, a code or a password. [mismatch] names
 * both accounts for [SignInProblem.WRONG_ACCOUNT].
 */
class SignInException(
    val problem: SignInProblem,
    cause: Throwable? = null,
    val mismatch: AccountMismatch? = null,
) : Exception(problem.name, cause)

/** Maps a failure of a Mastodon call to what the user sees. */
internal fun mastodonProblem(e: Exception): SignInException {
    if (e is CancellationException) throw e
    if (e is SignInException) return e
    val problem = when {
        e is MastodonException && e.isTransient -> SignInProblem.NETWORK
        e is MastodonException && e.isRateLimited -> SignInProblem.RATE_LIMITED
        e is IOException -> SignInProblem.NETWORK
        else -> SignInProblem.SERVER
    }
    return SignInException(problem, e)
}

/** Maps a failure of identity resolution or a PDS call to what the user sees. */
internal fun blueskyProblem(e: Exception): SignInException {
    if (e is CancellationException) throw e
    if (e is SignInException) return e
    val problem = when {
        e is IdentityException -> SignInProblem.HANDLE_NOT_FOUND
        e is XrpcException && e.isRateLimited -> SignInProblem.RATE_LIMITED
        e is XrpcException && e.isAccountTakedown -> SignInProblem.ACCOUNT_TAKEN_DOWN
        e is XrpcException && e.isTransient -> SignInProblem.NETWORK
        e is IOException -> SignInProblem.NETWORK
        else -> SignInProblem.SERVER
    }
    return SignInException(problem, e)
}

/**
 * Tells the sender that [accountId] signed in (product.md R5: signing in again resumes its deliveries).
 * The sign-in has succeeded by then, so a failure of the hook does not undo it.
 */
internal suspend fun notifySignedIn(accountId: Long, hook: suspend (Long) -> Unit) {
    try {
        hook(accountId)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // The deliveries stay failed; the user can retry them from the queue.
    }
}
