package org.evsyukov.post.accounts

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.evsyukov.post.core.bluesky.AppViewClient
import org.evsyukov.post.core.bluesky.BlueskyClient
import org.evsyukov.post.core.bluesky.IdentityException
import org.evsyukov.post.core.bluesky.IdentityResolver
import org.evsyukov.post.core.bluesky.Profile
import org.evsyukov.post.core.bluesky.XrpcException
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.AccountsRepository
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.secrets.BlueskySecret

/**
 * Adds a Bluesky account with its handle and an app password, or signs one in again (networks.md
 * N13–N15, plan.md "Bluesky"). The password goes to the account's PDS only and is never stored: only the
 * session tokens are, sealed.
 */
class BlueskySignIn(
    private val accounts: AccountsRepository,
    private val http: OkHttpClient,
    private val policy: ServerPolicy,
    private val appView: HttpUrl = IdentityResolver.DEFAULT_APP_VIEW,
    private val plc: HttpUrl = IdentityResolver.DEFAULT_PLC,
) {
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Result {
        data class Done(val accountId: Long) : Result

        /** The PDS sent a code to the account's e-mail; call [signIn] again with it. */
        data object NeedsCode : Result
    }

    /**
     * Signs in [identifier], a handle (with or without `@`) or a DID, with [appPassword], and with
     * [code] when the PDS asked for one.
     *
     * @throws SignInException
     */
    suspend fun signIn(identifier: String, appPassword: String, code: String? = null): Result {
        try {
            val resolver = IdentityResolver(http, json, appView, plc)
            val input = IdentityResolver.normalizeHandle(identifier)
            val (did, handle) = resolve(resolver, input)
            val doc = resolver.didDocument(did)
            if (!doc.handleMatches(handle)) throw SignInException(SignInProblem.HANDLE_MISMATCH)
            val pds = doc.pdsEndpoint()?.takeIf { policy.accepts(it) } ?: throw SignInException(SignInProblem.NO_PDS)
            val session = try {
                BlueskyClient(pds, http, json).createSession(did, appPassword, code?.trim()?.takeIf { it.isNotEmpty() })
            } catch (e: XrpcException) {
                when {
                    e.isAuthFactorRequired -> return if (code.isNullOrBlank()) Result.NeedsCode else throw SignInException(SignInProblem.WRONG_CODE, e)
                    e.isAccountTakedown -> throw SignInException(SignInProblem.ACCOUNT_TAKEN_DOWN, e)
                    e.status == 401 || (e.status == 400 && e.error == "InvalidRequest") ->
                        throw SignInException(if (code.isNullOrBlank()) SignInProblem.WRONG_PASSWORD else SignInProblem.WRONG_CODE, e)
                    else -> throw e
                }
            }
            if (session.did != did) throw SignInException(SignInProblem.HANDLE_MISMATCH)
            if (session.active == false) {
                val takenDown = session.status == "takendown"
                throw SignInException(if (takenDown) SignInProblem.ACCOUNT_TAKEN_DOWN else SignInProblem.ACCOUNT_INACTIVE)
            }
            val profile = profileOrNull(did)
            val id = accounts.add(
                NewAccount(
                    network = Network.BLUESKY,
                    server = originOf(pds),
                    handle = handle,
                    remoteId = did,
                    displayName = profile?.displayName.orEmpty(),
                    avatarUrl = profile?.avatar,
                ),
                BlueskySecret(session.accessJwt, session.refreshJwt),
            )
            return Result.Done(id)
        } catch (e: Exception) {
            throw blueskyProblem(e)
        }
    }

    /** The DID and the handle of [input]: a handle resolves to its DID, a DID gets the handle its document claims. */
    private suspend fun resolve(resolver: IdentityResolver, input: String): Pair<String, String> {
        if (input.startsWith("did:")) {
            if (!IdentityResolver.isValidDid(input)) throw SignInException(SignInProblem.INVALID_HANDLE)
            val handle = resolver.didDocument(input).claimedHandle() ?: throw SignInException(SignInProblem.HANDLE_MISMATCH)
            // Both directions (N13): the claimed handle must resolve back to the DID.
            val back = try {
                resolver.resolveHandle(handle)
            } catch (_: IdentityException) {
                throw SignInException(SignInProblem.HANDLE_MISMATCH)
            }
            if (back != input) throw SignInException(SignInProblem.HANDLE_MISMATCH)
            return input to handle
        }
        if (!IdentityResolver.isValidHandle(input)) throw SignInException(SignInProblem.INVALID_HANDLE)
        return resolver.resolveHandle(input) to input
    }

    /** The profile from the public AppView; a failure leaves the name and avatar empty, it does not fail sign-in. */
    private suspend fun profileOrNull(did: String): Profile? = try {
        AppViewClient(http, json, appView).getProfile(did)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
