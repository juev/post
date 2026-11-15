package org.evsyukov.post.oauth

import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import java.io.File
import java.io.IOException

/**
 * A Mastodon sign-in waiting for the browser to come back (networks.md N4–N5). It is written before
 * the browser opens, so the redirect completes the sign-in even after the process was killed.
 *
 * @property origin the instance, such as `https://mastodon.social`.
 * @property state the OAuth `state` the redirect must bring back.
 * @property codeVerifier the PKCE verifier sent with the code.
 * @property clientId the registration the authorization was asked for.
 * @property accountId the account signing in again, or null for a new account.
 * @property capabilities the limits and formats read when the sign-in started (N1, F1).
 * @property registeredAgain true after `invalid_client` made Post register once more; a second
 *   `invalid_client` fails the sign-in instead of looping.
 */
@Serializable
data class PendingSignIn(
    val origin: String,
    val state: String,
    val codeVerifier: String,
    val scopes: String,
    val clientId: String,
    val accountId: Long?,
    val capabilities: MastodonCapabilities,
    val registeredAgain: Boolean,
    val createdAt: Long,
) {
    override fun toString(): String = "PendingSignIn(origin=$origin, accountId=$accountId, registeredAgain=$registeredAgain)"
}

/** Keeps at most one [PendingSignIn]; a new sign-in replaces the one before. */
interface PendingSignInStore {
    suspend fun get(): PendingSignIn?

    suspend fun put(pending: PendingSignIn)

    suspend fun clear()
}

/**
 * The pending sign-in as JSON in a file of the no-backup directory, written atomically. A file that
 * does not read counts as no pending sign-in.
 */
class FilePendingSignInStore(file: File) : PendingSignInStore {
    private val atomic = AtomicFile(file)
    private val lock = Mutex()

    override suspend fun get(): PendingSignIn? = lock.withLock {
        withContext(Dispatchers.IO) {
            try {
                Json.decodeFromString(PendingSignIn.serializer(), atomic.readFully().decodeToString())
            } catch (_: IOException) {
                null
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    override suspend fun put(pending: PendingSignIn) = lock.withLock {
        withContext(Dispatchers.IO) {
            val stream = atomic.startWrite()
            try {
                stream.write(Json.encodeToString(PendingSignIn.serializer(), pending).encodeToByteArray())
                atomic.finishWrite(stream)
            } catch (e: IOException) {
                atomic.failWrite(stream)
                throw e
            }
        }
    }

    override suspend fun clear() = lock.withLock {
        withContext(Dispatchers.IO) { atomic.delete() }
    }
}
