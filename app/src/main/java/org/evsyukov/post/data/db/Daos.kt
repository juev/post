package org.evsyukov.post.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import org.evsyukov.post.core.media.Network

/** An attachment with its converted variants. */
data class AttachmentWithVariants(
    @Embedded val attachment: AttachmentEntity,
    @Relation(parentColumn = "id", entityColumn = "attachmentId")
    val variants: List<VariantEntity>,
)

/** A post with its attachments and deliveries; Room does not order relations, the mapping does. */
data class PostWithDetails(
    @Embedded val post: PostEntity,
    @Relation(entity = AttachmentEntity::class, parentColumn = "id", entityColumn = "postId")
    val attachments: List<AttachmentWithVariants>,
    @Relation(parentColumn = "id", entityColumn = "postId")
    val deliveries: List<DeliveryEntity>,
)

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY network, position, id")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY network, position, id")
    suspend fun all(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun get(id: Long): AccountEntity?

    @Query("SELECT * FROM accounts WHERE network = :network AND server = :server AND remoteId = :remoteId")
    suspend fun find(network: Network, server: String, remoteId: String): AccountEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM accounts WHERE id = :id)")
    suspend fun exists(id: Long): Boolean

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM accounts WHERE network = :network")
    suspend fun nextPosition(network: Network): Int

    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Query("UPDATE accounts SET handle = :handle, displayName = :displayName, avatarUrl = :avatarUrl WHERE id = :id")
    suspend fun updateProfile(id: Long, handle: String, displayName: String, avatarUrl: String?): Int

    @Query("UPDATE accounts SET limitsJson = :limitsJson, limitsFetchedAt = :fetchedAt WHERE id = :id")
    suspend fun updateLimits(id: Long, limitsJson: String, fetchedAt: Long): Int

    @Query("UPDATE accounts SET defaultsJson = :defaultsJson WHERE id = :id")
    suspend fun updateDefaults(id: Long, defaultsJson: String): Int

    @Query("UPDATE accounts SET label = :label WHERE id = :id")
    suspend fun updateLabel(id: Long, label: String): Int

    @Query("UPDATE accounts SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Int): Int

    @Query("UPDATE accounts SET state = :state WHERE id = :id")
    suspend fun updateState(id: Long, state: AccountState): Int

    @Query("SELECT secret FROM accounts WHERE id = :id")
    suspend fun secret(id: Long): ByteArray?

    @Query("UPDATE accounts SET secret = :secret WHERE id = :id")
    suspend fun updateSecret(id: Long, secret: ByteArray): Int

    /** Signing in again (R5): new profile and tokens; label, position and defaults stay. */
    @Query(
        """
        UPDATE accounts SET handle = :handle, displayName = :displayName, avatarUrl = :avatarUrl,
            secret = :secret, state = 'OK'
        WHERE id = :id
        """,
    )
    suspend fun signedInAgain(id: Long, handle: String, displayName: String, avatarUrl: String?, secret: ByteArray): Int

    @Query("DELETE FROM accounts WHERE id = :id")
    suspend fun delete(id: Long): Int
}

@Dao
interface MastodonAppDao {
    @Query("SELECT * FROM mastodon_apps WHERE origin = :origin")
    suspend fun get(origin: String): MastodonAppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(app: MastodonAppEntity)

    @Query("DELETE FROM mastodon_apps WHERE origin = :origin")
    suspend fun delete(origin: String): Int
}

@Dao
interface PostDao {
    @Insert
    suspend fun insert(post: PostEntity): Long

    @Query("SELECT * FROM posts WHERE id = :id")
    suspend fun get(id: Long): PostEntity?

    @Transaction
    @Query("SELECT * FROM posts WHERE id = :id")
    suspend fun details(id: Long): PostWithDetails?

    @Transaction
    @Query("SELECT * FROM posts WHERE id = :id")
    fun observeDetails(id: Long): Flow<PostWithDetails?>

    /** Drafts, the last saved first (screens.md, Drafts). */
    @Transaction
    @Query("SELECT * FROM posts WHERE state = 'DRAFT' ORDER BY updatedAt DESC, id DESC")
    fun observeDrafts(): Flow<List<PostWithDetails>>

    /** The Queue tab: posts waiting, sending, held or failed, by send time, then queue order (Q8). */
    @Transaction
    @Query(
        """
        SELECT * FROM posts WHERE state IN ('QUEUED', 'SENDING', 'HELD', 'FAILED')
        ORDER BY sendAt, queueOrder, id
        """,
    )
    fun observeQueue(): Flow<List<PostWithDetails>>

    @Query("SELECT * FROM posts WHERE state = 'QUEUED' AND scheduleKind = 'SLOT'")
    suspend fun queuedSlotPosts(): List<PostEntity>

    @Query("SELECT COALESCE(MAX(queueOrder), 0) + 1 FROM posts")
    suspend fun nextQueueOrder(): Long

    @Query(
        """
        UPDATE posts SET kind = :kind, text = :text, optionsJson = :optionsJson, targetsJson = :targetsJson,
            historyId = :historyId, updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateContent(
        id: Long,
        kind: PostKind,
        text: String,
        optionsJson: String,
        targetsJson: String,
        historyId: Long?,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE posts SET state = :state, scheduleKind = :scheduleKind, slotLocal = :slotLocal, sendAt = :sendAt,
            targetsJson = :targetsJson, queueOrder = :queueOrder, updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun queue(
        id: Long,
        state: PostState,
        scheduleKind: ScheduleKind,
        slotLocal: String?,
        sendAt: Long,
        targetsJson: String,
        queueOrder: Long,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE posts SET state = :state, scheduleKind = :scheduleKind, slotLocal = :slotLocal, sendAt = :sendAt,
            updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateSchedule(
        id: Long,
        state: PostState,
        scheduleKind: ScheduleKind,
        slotLocal: String?,
        sendAt: Long,
        updatedAt: Long,
    ): Int

    @Query("UPDATE posts SET sendAt = :sendAt WHERE id = :id")
    suspend fun updateSendAt(id: Long, sendAt: Long): Int

    @Query("UPDATE posts SET state = :state, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateState(id: Long, state: PostState, updatedAt: Long): Int

    @Query("DELETE FROM posts WHERE id = :id")
    suspend fun delete(id: Long): Int

    /**
     * Sent posts that no history record points to go, with their deliveries and the server ids in them
     * (R31, R37). Their attachments went when they were sent.
     */
    @Query("DELETE FROM posts WHERE state = 'SENT' AND id NOT IN (SELECT postId FROM history WHERE postId IS NOT NULL)")
    suspend fun deleteUnrecordedSent(): Int
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE postId = :postId ORDER BY position, id")
    suspend fun forPost(postId: Long): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun get(id: Long): AttachmentEntity?

    @Insert
    suspend fun insert(attachment: AttachmentEntity): Long

    /** Only what the composer changes; the file and its facts stay as they were copied (A1). */
    @Query(
        """
        UPDATE attachments SET position = :position, pieceIndex = :pieceIndex, alt = :alt,
            focusX = :focusX, focusY = :focusY
        WHERE id = :id
        """,
    )
    suspend fun updateEditable(id: Long, position: Int, pieceIndex: Int, alt: String, focusX: Float?, focusY: Float?): Int

    @Query("DELETE FROM attachments WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>): Int

    @Query("DELETE FROM attachments WHERE postId = :postId")
    suspend fun deleteForPost(postId: Long): Int

    @Query("SELECT path FROM attachments WHERE postId = :postId")
    suspend fun pathsOfPost(postId: Long): List<String>

    @Query("SELECT v.path FROM variants v JOIN attachments a ON a.id = v.attachmentId WHERE a.postId = :postId")
    suspend fun variantPathsOfPost(postId: Long): List<String>

    @Query("SELECT path FROM variants WHERE attachmentId IN (:attachmentIds)")
    suspend fun variantPaths(attachmentIds: List<Long>): List<String>

    @Query("SELECT * FROM variants WHERE attachmentId = :attachmentId AND profileKey = :profileKey")
    suspend fun variant(attachmentId: Long, profileKey: String): VariantEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putVariant(variant: VariantEntity): Long
}

@Dao
interface DeliveryDao {
    @Query("SELECT * FROM deliveries WHERE postId = :postId ORDER BY id")
    suspend fun forPost(postId: Long): List<DeliveryEntity>

    @Query("SELECT * FROM deliveries WHERE id = :id")
    suspend fun get(id: Long): DeliveryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(delivery: DeliveryEntity): Long

    /** A queued post that has not started loses the deliveries of the accounts taken out of its targets. */
    @Query("DELETE FROM deliveries WHERE postId = :postId AND accountId NOT IN (:keep)")
    suspend fun deleteExcept(postId: Long, keep: List<Long>): Int

    /** Claims a waiting delivery before the network is touched; 0 rows means someone else has it. */
    @Query(
        """
        UPDATE deliveries SET state = 'SENDING', attempts = attempts + 1, lastAttemptAt = :at,
            nextAttemptAt = NULL, planJson = COALESCE(planJson, :planJson)
        WHERE id = :id AND state = 'WAITING'
        """,
    )
    suspend fun start(id: Long, at: Long, planJson: String): Int

    @Query("UPDATE deliveries SET progressJson = :progressJson WHERE id = :id")
    suspend fun updateProgress(id: Long, progressJson: String): Int

    @Query(
        """
        UPDATE deliveries SET state = 'SENT', progressJson = :progressJson, error = NULL, nextAttemptAt = NULL
        WHERE id = :id AND state = 'SENDING'
        """,
    )
    suspend fun succeed(id: Long, progressJson: String): Int

    @Query(
        """
        UPDATE deliveries SET state = :state, error = :error, nextAttemptAt = :nextAttemptAt
        WHERE id = :id AND state = 'SENDING'
        """,
    )
    suspend fun fail(id: Long, state: DeliveryState, error: String, nextAttemptAt: Long?): Int

    /** Failed deliveries wait again, except those of removed accounts: they have nothing to send with (R1). */
    @Query(
        """
        UPDATE deliveries SET state = 'WAITING', nextAttemptAt = NULL
        WHERE postId = :postId AND state = 'FAILED' AND accountId IN (SELECT id FROM accounts)
        """,
    )
    suspend fun retryFailed(postId: Long): Int

    /** A waiting delivery that cannot start, such as one whose plan cannot be made, fails at once (R29). */
    @Query(
        """
        UPDATE deliveries SET state = 'FAILED', error = :error, attempts = attempts + 1, lastAttemptAt = :at,
            nextAttemptAt = NULL
        WHERE id = :id AND state = 'WAITING'
        """,
    )
    suspend fun failUnstarted(id: Long, at: Long, error: String): Int

    /** One failed delivery waits again, unless its account was removed (R1). */
    @Query(
        """
        UPDATE deliveries SET state = 'WAITING', nextAttemptAt = NULL
        WHERE id = :id AND state = 'FAILED' AND accountId IN (SELECT id FROM accounts)
        """,
    )
    suspend fun retryOne(id: Long): Int

    @Query("SELECT DISTINCT postId FROM deliveries WHERE accountId = :accountId AND state = 'FAILED' AND error = :reason")
    suspend fun postsFailedWith(accountId: Long, reason: String): List<Long>

    /** R5: the deliveries of an account that failed because it needed sign-in wait again. */
    @Query(
        """
        UPDATE deliveries SET state = 'WAITING', error = NULL, nextAttemptAt = NULL
        WHERE accountId = :accountId AND state = 'FAILED' AND error = :reason
        """,
    )
    suspend fun resumeFailedWith(accountId: Long, reason: String): Int

    /** The earliest time a waiting delivery of a queued or sending post asked to be tried again. */
    @Query(
        """
        SELECT MIN(d.nextAttemptAt) FROM deliveries d JOIN posts p ON p.id = d.postId
        WHERE d.state = 'WAITING' AND d.nextAttemptAt IS NOT NULL AND p.state IN ('QUEUED', 'SENDING')
        """,
    )
    suspend fun earliestRetry(): Long?

    @Query("DELETE FROM deliveries WHERE postId = :postId")
    suspend fun deleteForPost(postId: Long): Int

    /** Deliveries the process left in SENDING when it died go back to waiting (R27, R30). */
    @Query("SELECT DISTINCT postId FROM deliveries WHERE state = 'SENDING'")
    suspend fun postsWithSending(): List<Long>

    @Query("UPDATE deliveries SET state = 'WAITING' WHERE state = 'SENDING'")
    suspend fun recoverInterrupted(): Int

    @Query("SELECT DISTINCT postId FROM deliveries WHERE accountId = :accountId AND state IN ('WAITING', 'SENDING')")
    suspend fun postsPendingFor(accountId: Long): List<Long>

    /** R1: the pending deliveries of a removed account fail with [reason]. */
    @Query(
        """
        UPDATE deliveries SET state = 'FAILED', error = :reason, nextAttemptAt = NULL
        WHERE accountId = :accountId AND state IN ('WAITING', 'SENDING')
        """,
    )
    suspend fun failPendingOf(accountId: Long, reason: String): Int
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(record: HistoryEntity): Long

    @Query("SELECT * FROM history WHERE id = :id")
    suspend fun get(id: Long): HistoryEntity?

    /** The Sent tab, newest first (R31). */
    @Query("SELECT * FROM history ORDER BY sentAt DESC, id DESC")
    fun observeAll(): Flow<List<HistoryEntity>>

    @Query("UPDATE history SET text = :text, optionsJson = :optionsJson, itemsJson = :itemsJson WHERE id = :id")
    suspend fun update(id: Long, text: String, optionsJson: String, itemsJson: String): Int

    @Query("UPDATE history SET itemsJson = :itemsJson WHERE id = :id")
    suspend fun updateItems(id: Long, itemsJson: String): Int

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("DELETE FROM history WHERE expiresAt <= :now")
    suspend fun deleteExpired(now: Long): Int

    /** Every record expires [periodMillis] after it was sent, as the retention setting now says. */
    @Query("UPDATE history SET expiresAt = sentAt + :periodMillis")
    suspend fun alignExpiry(periodMillis: Long): Int

    @Query("DELETE FROM history")
    suspend fun deleteAll(): Int
}

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedule_slots ORDER BY dayOfWeek, minuteOfDay")
    fun observeAll(): Flow<List<ScheduleSlotEntity>>

    @Query("SELECT * FROM schedule_slots ORDER BY dayOfWeek, minuteOfDay")
    suspend fun all(): List<ScheduleSlotEntity>

    @Query("DELETE FROM schedule_slots")
    suspend fun deleteAll()

    @Insert
    suspend fun insertAll(slots: List<ScheduleSlotEntity>)

    @Transaction
    suspend fun replaceAll(slots: List<ScheduleSlotEntity>) {
        deleteAll()
        insertAll(slots)
    }
}
