package org.evsyukov.post.data.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.db.DbJson
import org.evsyukov.post.data.db.HistoryEntity
import org.evsyukov.post.data.db.PostDatabase
import org.evsyukov.post.data.posts.PostOptions
import org.evsyukov.post.data.posts.PublishedPart
import org.evsyukov.post.data.settings.HistoryRetention
import java.time.Instant

/**
 * The published copy of a post on one account (R38): its parts in thread order. The account's network,
 * server and handle are kept so the Sent tab can show the copy after the account is removed.
 */
@Serializable
data class HistoryItem(
    val accountId: Long,
    val network: Network,
    val server: String,
    val handle: String,
    val parts: List<PublishedPart>,
)

/** A record of the Sent tab (R31, R38). Media files are not kept, only their server ids and alt text. */
data class HistoryRecord(
    val id: Long,
    val postId: Long?,
    val sentAt: Instant,
    val text: String,
    val options: PostOptions,
    val items: List<HistoryItem>,
    val expiresAt: Instant,
)

/** The history of sent posts (R31, R37–R39). */
interface HistoryRepository {
    /** The records, newest first. */
    fun observe(): Flow<List<HistoryRecord>>

    suspend fun get(id: Long): HistoryRecord?

    /**
     * Records a post sent at [sentAt]; it expires after the retention period of the settings. With
     * history off nothing is written and the result is null.
     */
    suspend fun insert(postId: Long?, sentAt: Instant, text: String, options: PostOptions, items: List<HistoryItem>): Long?

    suspend fun delete(id: Long): Boolean

    /** Deletes the records whose expiry is not after [now]; returns how many went. */
    suspend fun purgeExpired(now: Instant): Int
}

class RoomHistoryRepository(
    db: PostDatabase,
    private val retention: suspend () -> HistoryRetention,
) : HistoryRepository {
    private val dao = db.historyDao()

    override fun observe(): Flow<List<HistoryRecord>> = dao.observeAll().map { list -> list.map { it.toRecord() } }

    override suspend fun get(id: Long): HistoryRecord? = dao.get(id)?.toRecord()

    override suspend fun insert(
        postId: Long?,
        sentAt: Instant,
        text: String,
        options: PostOptions,
        items: List<HistoryItem>,
    ): Long? {
        val expiresAt = retention().expiresAt(sentAt) ?: return null
        return dao.insert(historyEntity(postId, sentAt, text, encodeOptions(options), items, expiresAt))
    }

    override suspend fun delete(id: Long): Boolean = dao.delete(id) > 0

    override suspend fun purgeExpired(now: Instant): Int = dao.deleteExpired(now.toEpochMilli())
}

private val itemsSerializer = ListSerializer(HistoryItem.serializer())

internal fun encodeOptions(options: PostOptions): String = DbJson.encodeToString(PostOptions.serializer(), options)

internal fun encodeItems(items: List<HistoryItem>): String = DbJson.encodeToString(itemsSerializer, items)

internal fun historyEntity(
    postId: Long?,
    sentAt: Instant,
    text: String,
    optionsJson: String,
    items: List<HistoryItem>,
    expiresAt: Instant,
) = HistoryEntity(
    postId = postId,
    sentAt = sentAt.toEpochMilli(),
    text = text,
    optionsJson = optionsJson,
    itemsJson = encodeItems(items),
    expiresAt = expiresAt.toEpochMilli(),
)

internal fun HistoryEntity.toRecord() = HistoryRecord(
    id = id,
    postId = postId,
    sentAt = Instant.ofEpochMilli(sentAt),
    text = text,
    options = DbJson.decodeFromString(PostOptions.serializer(), optionsJson),
    items = DbJson.decodeFromString(itemsSerializer, itemsJson),
    expiresAt = Instant.ofEpochMilli(expiresAt),
)
