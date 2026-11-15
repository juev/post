package org.evsyukov.post.data.accounts

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.evsyukov.post.data.db.MastodonAppEntity
import org.evsyukov.post.data.db.PostDatabase
import org.evsyukov.post.data.secrets.MastodonClientSecret
import org.evsyukov.post.data.secrets.SecretStore
import org.evsyukov.post.data.secrets.SecretUnreadable
import java.time.Instant

/**
 * Post's registration on one Mastodon instance (networks.md N4). [toString] leaves out the client secret.
 *
 * @property origin the instance origin, such as `https://mastodon.social`.
 * @property scopes the scopes the app was registered with, separated by spaces.
 */
data class MastodonApp(
    val origin: String,
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
    val scopes: String,
    val createdAt: Instant,
) {
    override fun toString(): String =
        "MastodonApp(origin=$origin, clientId=$clientId, clientSecret=***, redirectUri=$redirectUri, scopes=$scopes)"
}

/** One app registration per instance; the client secret is sealed like a token (plan.md, Mastodon OAuth). */
interface MastodonAppsRepository {
    /**
     * The registration for [origin], or null when there is none.
     * @throws SecretUnreadable when its client secret does not open; register the app again then.
     */
    suspend fun get(origin: String): MastodonApp?

    /** Stores [app], replacing an earlier registration for its origin. */
    suspend fun put(app: MastodonApp)

    /** Forgets the registration, as after `invalid_client`; returns false when there was none. */
    suspend fun delete(origin: String): Boolean
}

class RoomMastodonAppsRepository(
    db: PostDatabase,
    private val secrets: SecretStore,
    // The Keystore answers over binder and makes its key on first use: never on the caller's thread.
    private val cipherDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MastodonAppsRepository {
    private val dao = db.mastodonAppDao()

    override suspend fun get(origin: String): MastodonApp? {
        val entity = dao.get(origin) ?: return null
        val clientSecret = withContext(cipherDispatcher) { secrets.unsealClientSecret(entity.clientSecret) }
        return MastodonApp(
            origin = entity.origin,
            clientId = entity.clientId,
            clientSecret = clientSecret.value,
            redirectUri = entity.redirectUri,
            scopes = entity.scopes,
            createdAt = Instant.ofEpochMilli(entity.createdAt),
        )
    }

    override suspend fun put(app: MastodonApp) {
        val sealed = withContext(cipherDispatcher) { secrets.seal(MastodonClientSecret(app.clientSecret)) }
        dao.put(
            MastodonAppEntity(
                origin = app.origin,
                clientId = app.clientId,
                clientSecret = sealed,
                redirectUri = app.redirectUri,
                scopes = app.scopes,
                createdAt = app.createdAt.toEpochMilli(),
            ),
        )
    }

    override suspend fun delete(origin: String): Boolean = dao.delete(origin) > 0
}
