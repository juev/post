package org.evsyukov.post.data.posts

import kotlinx.serialization.Serializable
import org.evsyukov.post.core.media.ConflictKind
import org.evsyukov.post.core.media.FixKind
import org.evsyukov.post.core.media.MediaItem
import org.evsyukov.post.core.media.MediaKind
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.schedule.QueuedRef
import org.evsyukov.post.core.schedule.SendTime
import org.evsyukov.post.data.accounts.AccountOptions
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.db.PostKind
import org.evsyukov.post.data.db.PostState
import java.time.Instant
import java.time.LocalDateTime

/** A Mastodon poll (R13); Bluesky has none. */
@Serializable
data class PollDraft(
    val options: List<String>,
    val expiresInSeconds: Long,
    val multiple: Boolean = false,
    val hideTotals: Boolean = false,
)

/**
 * The options of a post (R13), stored in `optionsJson`.
 *
 * @property common the options every target gets, unless [accounts] has its own for it.
 * @property accounts per-account options set for this post, by account id (screens.md, Targets).
 * @property poll the Mastodon poll, if any.
 * @property linkCard false when the user removed the Bluesky link card (R14).
 * @property mediaFixes the fixes chosen for media conflicts, per network (media.md A10).
 */
@Serializable
data class PostOptions(
    val common: AccountOptions = AccountOptions(),
    val accounts: Map<Long, AccountOptions> = emptyMap(),
    val poll: PollDraft? = null,
    val linkCard: Boolean = true,
    val mediaFixes: Map<Network, Map<ConflictKind, FixKind>> = emptyMap(),
) {
    /** The options of account [accountId] for this post. */
    fun forAccount(accountId: Long): AccountOptions = accounts[accountId] ?: common
}

/** When a queued post is sent (scheduling.md S2, S5). */
sealed interface Schedule {
    /** Send now (R21, Q3); the post is due at [at]. */
    data class Now(val at: Instant) : Schedule

    /** A slot of the weekly schedule, kept as local date and time and resolved in the device zone (S2). */
    data class Slot(val local: LocalDateTime) : Schedule

    /** A time the user chose; it does not occupy a slot (S5). */
    data class Explicit(val at: Instant) : Schedule
}

/** A converted copy of an attachment (media.md A2). [path] is relative to the app's files directory. */
data class Variant(
    val id: Long = 0,
    val attachmentId: Long,
    val profileKey: String,
    val path: String,
    val mime: String,
    val sizeBytes: Long,
    val width: Int? = null,
    val height: Int? = null,
)

/**
 * An attachment of a post (media.md A1). In [DraftInput.attachments] an [id] of 0 marks a new file;
 * an existing one changes only its order, piece, alt text and focus point there. [path] is relative
 * to the app's files directory.
 */
data class Attachment(
    val id: Long = 0,
    val kind: MediaKind,
    val path: String,
    val mime: String,
    val sizeBytes: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val frameRate: Double? = null,
    val animated: Boolean = false,
    val hasTransparency: Boolean = false,
    val videoCodec: String? = null,
    val pieceIndex: Int = 0,
    val alt: String = "",
    val focusX: Float? = null,
    val focusY: Float? = null,
    val variants: List<Variant> = emptyList(),
) {
    /** The attachment as the media planner sees it. */
    fun toMediaItem(): MediaItem = MediaItem(
        id = id,
        kind = kind,
        mime = mime,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        durationMs = durationMs,
        pieceIndex = pieceIndex,
        hasTransparency = hasTransparency,
        frameRate = frameRate,
        alt = alt,
    )
}

/** The parts and media of a delivery, frozen at its first attempt (R30). Later steps add fields. */
@Serializable
data class DeliveryPlan(val parts: List<PlannedPart>)

/** One thread part: its text as the account gets it and the attachments it carries. */
@Serializable
data class PlannedPart(val text: String, val attachmentIds: List<Long> = emptyList())

/** A media file uploaded to the server: its id there and the alt text it was sent with (R38). */
@Serializable
data class PublishedMedia(val id: String, val alt: String = "")

/**
 * A published thread part (R38): the Mastodon status [id], or the Bluesky [uri] and [cid]; [url] opens it
 * in a browser.
 */
@Serializable
data class PublishedPart(
    val id: String? = null,
    val uri: String? = null,
    val cid: String? = null,
    val url: String? = null,
    val media: List<PublishedMedia> = emptyList(),
)

/**
 * How far one part got (R30).
 *
 * @property key the Mastodon `Idempotency-Key` or the Bluesky record key (TID), chosen and stored
 *   before the first request for the part (N11, N24).
 * @property uploaded the media uploaded for the part, by attachment id: the Mastodon media id, or the
 *   Bluesky blob reference as JSON.
 * @property published set once the part is on the server.
 */
@Serializable
data class PartProgress(
    val index: Int,
    val key: String? = null,
    val uploaded: Map<Long, String> = emptyMap(),
    val published: PublishedPart? = null,
)

/** The progress of a delivery, stored in `progressJson`. */
@Serializable
data class DeliveryProgress(val parts: List<PartProgress> = emptyList()) {
    /** The index of the first part that is not published, from which a retry resumes (R30). */
    fun firstUnpublished(partCount: Int): Int? =
        (0 until partCount).firstOrNull { index -> parts.none { it.index == index && it.published != null } }
}

/** Sending a post to one account (R28). */
data class Delivery(
    val id: Long,
    val postId: Long,
    val accountId: Long,
    val state: DeliveryState,
    val plan: DeliveryPlan?,
    val progress: DeliveryProgress,
    val error: String?,
    val attempts: Int,
    val lastAttemptAt: Instant?,
    val nextAttemptAt: Instant?,
)

/**
 * A post with its attachments in order and its deliveries.
 *
 * @property targets the ordered account ids chosen for the post.
 * @property schedule null for a draft that was never queued.
 * @property sendAt the instant the post is due; for a slot, the slot resolved in the device zone.
 */
data class Post(
    val id: Long,
    val kind: PostKind,
    val state: PostState,
    val text: String,
    val options: PostOptions,
    val targets: List<Long>,
    val schedule: Schedule?,
    val sendAt: Instant?,
    val queueOrder: Long,
    val historyId: Long?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val attachments: List<Attachment>,
    val deliveries: List<Delivery>,
) {
    /** A delivery has made an attempt: the post can no longer be edited or moved (Q2, S9). */
    val started: Boolean get() = deliveries.any { it.attempts > 0 }

    /** The post as the schedule rules of core see it, or null for a post without a schedule. */
    fun toQueuedRef(): QueuedRef? {
        val time = when (val schedule = schedule) {
            null -> return null
            is Schedule.Slot -> SendTime.Slot(schedule.local)
            is Schedule.Explicit -> SendTime.Explicit(schedule.at)
            is Schedule.Now -> SendTime.Explicit(schedule.at)
        }
        return QueuedRef(
            id = id,
            time = time,
            order = queueOrder,
            started = started || state == PostState.SENDING,
            held = state == PostState.HELD,
            failed = state == PostState.FAILED,
        )
    }
}

/**
 * What the composer saves (R15). [id] null creates a draft; otherwise the post with this id is
 * updated, which a queued post allows until its first delivery starts (Q2).
 */
data class DraftInput(
    val id: Long? = null,
    val text: String,
    val options: PostOptions = PostOptions(),
    val targets: List<Long> = emptyList(),
    val attachments: List<Attachment> = emptyList(),
    val kind: PostKind = PostKind.NEW,
    val historyId: Long? = null,
)

/** No post has this id; it may have been sent or deleted. */
class PostNotFound(val id: Long) : Exception("no post $id")

/** No delivery has this id. */
class DeliveryNotFound(val id: Long) : Exception("no delivery $id")
