package org.evsyukov.post.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.evsyukov.post.core.media.MediaKind
import org.evsyukov.post.core.media.Network

// Room stores every enum below by its name, as TEXT. Lists and maps live in JSON columns written with
// kotlinx.serialization (DbJson); times are epoch milliseconds.

/** Whether the account can publish, or its server rejected the credentials (product.md R5). */
enum class AccountState { OK, NEEDS_SIGN_IN }

/** A new post, or an edit or a deletion of a published one (R37–R41). */
enum class PostKind { NEW, EDIT, DELETE }

/**
 * - DRAFT: edited in the composer, not queued.
 * - QUEUED: waits for its send time; no delivery has started.
 * - SENDING: a delivery has started; the post can no longer move (scheduling.md S9).
 * - HELD: missed its time and waits for the user (Q5).
 * - SENT: every delivery succeeded (R28). The row, with the ids in its deliveries, stays only while a
 *   history record points to it: with history off, or once the record goes, so does the post (R31, R37).
 * - FAILED: nothing is pending and at least one delivery failed.
 */
enum class PostState { DRAFT, QUEUED, SENDING, HELD, SENT, FAILED }

/** NOW: send at once; SLOT: a slot of the weekly schedule (S2); EXPLICIT: an instant the user chose (S5). */
enum class ScheduleKind { NOW, SLOT, EXPLICIT }

/** The state of sending one post to one account (scheduling.md Q1). */
enum class DeliveryState { WAITING, SENDING, SENT, FAILED }

/**
 * One signed-in profile (R1, R2). [secret] holds the sealed JSON of its tokens (R4), never the tokens
 * themselves. A Mastodon account and a Bluesky account are told apart by [network]; [remoteId] is the
 * Mastodon account id or the DID, so the same person signing in again finds the same row.
 */
@Entity(tableName = "accounts", indices = [Index(value = ["network", "server", "remoteId"], unique = true)])
class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val network: Network,
    /** The origin of the Mastodon instance or of the Bluesky PDS. */
    val server: String,
    val handle: String,
    val remoteId: String,
    val displayName: String,
    val avatarUrl: String?,
    val label: String,
    /** The order within the network. */
    val position: Int,
    val state: AccountState,
    val limitsJson: String?,
    val limitsFetchedAt: Long?,
    /** Default options of the account (R17). */
    val defaultsJson: String,
    val secret: ByteArray,
    val createdAt: Long,
) {
    // Not a data class: a generated toString would print the secret's bytes and equals would compare the
    // array by reference.
    override fun toString(): String = "AccountEntity(id=$id, network=$network, server=$server, handle=$handle)"
}

/** The registration of Post on one Mastodon instance (networks.md N4); the client secret is sealed. */
@Entity(tableName = "mastodon_apps")
class MastodonAppEntity(
    @PrimaryKey val origin: String,
    val clientId: String,
    val clientSecret: ByteArray,
    val redirectUri: String,
    val scopes: String,
    val createdAt: Long,
) {
    override fun toString(): String = "MastodonAppEntity(origin=$origin, clientId=$clientId)"
}

/**
 * A post saved for sending, or a draft (R15, R27).
 *
 * @property text the Markdown source with manual-break lines (threads.md T13).
 * @property targetsJson the ordered account ids chosen for the post; a queued post also has a delivery per id.
 * @property slotLocal for [ScheduleKind.SLOT], the ISO local date-time of the slot (S2).
 * @property sendAt the instant to send: the chosen one for NOW and EXPLICIT; for SLOT, [slotLocal]
 *   resolved in the device zone when the post was queued or the zone changed.
 * @property queueOrder the order among posts due at the same time (Q8); a newly queued post goes last.
 * @property historyId the history record an EDIT or DELETE post acts on.
 */
@Entity(tableName = "posts", indices = [Index("state")])
data class PostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: PostKind,
    val state: PostState,
    val text: String,
    val optionsJson: String,
    val targetsJson: String,
    val scheduleKind: ScheduleKind?,
    val slotLocal: String?,
    val sendAt: Long?,
    val queueOrder: Long,
    val historyId: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * An original file copied into app storage (media.md A1). [path] is relative to the app's files
 * directory. [pieceIndex] is the piece of the text the file belongs to (threads.md T14).
 */
@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(entity = PostEntity::class, parentColumns = ["id"], childColumns = ["postId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("postId")],
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val postId: Long,
    val position: Int,
    val pieceIndex: Int,
    val kind: MediaKind,
    val path: String,
    val mime: String,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val frameRate: Double?,
    val animated: Boolean,
    val hasTransparency: Boolean,
    val videoCodec: String?,
    val alt: String,
    val focusX: Float?,
    val focusY: Float?,
    val createdAt: Long,
)

/** A converted copy of an attachment for one conversion profile (media.md A2, A4). */
@Entity(
    tableName = "variants",
    foreignKeys = [
        ForeignKey(
            entity = AttachmentEntity::class,
            parentColumns = ["id"],
            childColumns = ["attachmentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["attachmentId", "profileKey"], unique = true)],
)
data class VariantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val attachmentId: Long,
    val profileKey: String,
    val path: String,
    val mime: String,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    val createdAt: Long,
)

/**
 * Sending one post to one account (R28). [accountId] is not a foreign key: a removed account leaves
 * its deliveries behind, failed with "account removed" (R1).
 *
 * @property planJson the parts and media frozen at the first attempt (R30).
 * @property progressJson what is published so far, with idempotency keys and uploaded media (R30, N11, N24).
 */
@Entity(
    tableName = "deliveries",
    foreignKeys = [
        ForeignKey(entity = PostEntity::class, parentColumns = ["id"], childColumns = ["postId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["postId", "accountId"], unique = true), Index("accountId")],
)
data class DeliveryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val postId: Long,
    val accountId: Long,
    val state: DeliveryState,
    val planJson: String?,
    val progressJson: String?,
    val error: String?,
    val attempts: Int,
    val lastAttemptAt: Long?,
    val nextAttemptAt: Long?,
)

/**
 * A published post kept for the Sent tab (R31) and for editing and deleting it (R37–R39). The post row
 * may go first; then [postId] becomes null.
 */
@Entity(
    tableName = "history",
    foreignKeys = [
        ForeignKey(entity = PostEntity::class, parentColumns = ["id"], childColumns = ["postId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("postId"), Index("sentAt"), Index("expiresAt")],
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val postId: Long?,
    val sentAt: Long,
    val text: String,
    val optionsJson: String,
    val itemsJson: String,
    val expiresAt: Long,
)

/** One slot of the weekly schedule (S1): ISO day of week (1 is Monday) and minutes after midnight. */
@Entity(tableName = "schedule_slots", primaryKeys = ["dayOfWeek", "minuteOfDay"])
data class ScheduleSlotEntity(
    val dayOfWeek: Int,
    val minuteOfDay: Int,
)
