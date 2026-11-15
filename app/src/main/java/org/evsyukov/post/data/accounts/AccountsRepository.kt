package org.evsyukov.post.data.accounts

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.db.AccountEntity
import org.evsyukov.post.data.db.AccountState
import org.evsyukov.post.data.db.DbJson
import org.evsyukov.post.data.db.PostDatabase
import org.evsyukov.post.data.db.refreshPostState
import org.evsyukov.post.data.secrets.AccountSecret
import org.evsyukov.post.data.secrets.BlueskySecret
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.data.secrets.SecretStore
import org.evsyukov.post.data.secrets.SecretUnreadable
import java.time.Clock
import java.time.Instant

/** The signed-in accounts (product.md R1–R5). */
interface AccountsRepository {
    /** Every account, grouped by network and in the user's order within it. */
    fun observe(): Flow<List<Account>>

    suspend fun get(id: Long): Account?

    /**
     * Stores an account that has just signed in, with its tokens sealed, and returns its id. The same
     * profile (network, server and remote id) signing in again keeps its id, label, place and
     * defaults, gets the new profile and tokens and is OK again (R5).
     */
    suspend fun add(account: NewAccount, secret: AccountSecret): Long

    suspend fun updateProfile(id: Long, handle: String, displayName: String, avatarUrl: String?)

    suspend fun updateLimits(id: Long, limits: AccountLimits, fetchedAt: Instant)

    suspend fun updateDefaults(id: Long, defaults: AccountOptions)

    suspend fun setLabel(id: Long, label: String)

    /** Puts the accounts of [orderedIds] in this order; the others keep their places. */
    suspend fun reorder(orderedIds: List<Long>)

    suspend fun setState(id: Long, state: AccountState)

    /**
     * @throws AccountNotFound when there is no such account.
     * @throws SecretUnreadable when the stored tokens do not open (R5: the account needs sign-in).
     */
    suspend fun readSecret(id: Long): AccountSecret

    /** Replaces the tokens, as after a Bluesky refresh (N15). */
    suspend fun updateSecret(id: Long, secret: AccountSecret)

    /**
     * Removes the account and its sealed tokens. Its waiting and sending deliveries fail with the
     * reason [ACCOUNT_REMOVED] in the same transaction (R1). Returns false when there was no account.
     */
    suspend fun remove(id: Long): Boolean

    companion object {
        /** The error of a delivery whose account was removed (R1). */
        const val ACCOUNT_REMOVED = "account removed"
    }
}

class RoomAccountsRepository(
    private val db: PostDatabase,
    private val secrets: SecretStore,
    private val clock: Clock = Clock.systemUTC(),
) : AccountsRepository {
    private val dao = db.accountDao()

    override fun observe(): Flow<List<Account>> = dao.observeAll().map { list -> list.map { it.toAccount() } }

    override suspend fun get(id: Long): Account? = dao.get(id)?.toAccount()

    override suspend fun add(account: NewAccount, secret: AccountSecret): Long {
        requireMatches(account.network, secret)
        val sealed = secrets.seal(secret)
        return db.withTransaction {
            val existing = dao.find(account.network, account.server, account.remoteId)
            if (existing != null) {
                dao.signedInAgain(existing.id, account.handle, account.displayName, account.avatarUrl, sealed)
                existing.id
            } else {
                dao.insert(
                    AccountEntity(
                        network = account.network,
                        server = account.server,
                        handle = account.handle,
                        remoteId = account.remoteId,
                        displayName = account.displayName,
                        avatarUrl = account.avatarUrl,
                        label = account.label,
                        position = dao.nextPosition(account.network),
                        state = AccountState.OK,
                        limitsJson = null,
                        limitsFetchedAt = null,
                        defaultsJson = DbJson.encodeToString(AccountOptions.serializer(), AccountOptions()),
                        secret = sealed,
                        createdAt = clock.millis(),
                    ),
                )
            }
        }
    }

    override suspend fun updateProfile(id: Long, handle: String, displayName: String, avatarUrl: String?) {
        found(id, dao.updateProfile(id, handle, displayName, avatarUrl))
    }

    override suspend fun updateLimits(id: Long, limits: AccountLimits, fetchedAt: Instant) {
        found(id, dao.updateLimits(id, DbJson.encodeToString(AccountLimits.serializer(), limits), fetchedAt.toEpochMilli()))
    }

    override suspend fun updateDefaults(id: Long, defaults: AccountOptions) {
        found(id, dao.updateDefaults(id, DbJson.encodeToString(AccountOptions.serializer(), defaults)))
    }

    override suspend fun setLabel(id: Long, label: String) {
        found(id, dao.updateLabel(id, label))
    }

    override suspend fun reorder(orderedIds: List<Long>) {
        require(orderedIds.toSet().size == orderedIds.size) { "account ids repeat" }
        db.withTransaction {
            orderedIds.forEachIndexed { index, id -> found(id, dao.updatePosition(id, index)) }
        }
    }

    override suspend fun setState(id: Long, state: AccountState) {
        found(id, dao.updateState(id, state))
    }

    override suspend fun readSecret(id: Long): AccountSecret {
        val sealed = dao.secret(id) ?: throw AccountNotFound(id)
        return secrets.unsealAccountSecret(sealed)
    }

    override suspend fun updateSecret(id: Long, secret: AccountSecret) {
        val account = dao.get(id) ?: throw AccountNotFound(id)
        requireMatches(account.network, secret)
        found(id, dao.updateSecret(id, secrets.seal(secret)))
    }

    override suspend fun remove(id: Long): Boolean = db.withTransaction {
        val deliveries = db.deliveryDao()
        val affected = deliveries.postsPendingFor(id)
        deliveries.failPendingOf(id, AccountsRepository.ACCOUNT_REMOVED)
        val now = clock.millis()
        affected.forEach { db.refreshPostState(it, now) }
        dao.delete(id) > 0
    }

    private fun found(id: Long, rows: Int) {
        if (rows == 0) throw AccountNotFound(id)
    }

    private fun requireMatches(network: Network, secret: AccountSecret) {
        val matches = when (network) {
            Network.MASTODON -> secret is MastodonSecret
            Network.BLUESKY -> secret is BlueskySecret
        }
        require(matches) { "a ${secret::class.simpleName} is not a secret of a $network account" }
    }

    private fun AccountEntity.toAccount() = Account(
        id = id,
        network = network,
        server = server,
        handle = handle,
        remoteId = remoteId,
        displayName = displayName,
        avatarUrl = avatarUrl,
        label = label,
        position = position,
        state = state,
        limits = limitsJson?.let { DbJson.decodeFromString(AccountLimits.serializer(), it) },
        limitsFetchedAt = limitsFetchedAt?.let(Instant::ofEpochMilli),
        defaults = DbJson.decodeFromString(AccountOptions.serializer(), defaultsJson),
        createdAt = Instant.ofEpochMilli(createdAt),
    )
}
