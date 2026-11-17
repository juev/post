package org.evsyukov.post.data.posts

import kotlinx.serialization.Serializable
import org.evsyukov.post.core.bluesky.ContentLabel
import org.evsyukov.post.core.mastodon.Visibility
import org.evsyukov.post.core.media.ConflictKind
import org.evsyukov.post.core.media.Conversion
import org.evsyukov.post.core.media.FixKind
import org.evsyukov.post.core.media.MediaItem
import org.evsyukov.post.core.media.MediaKind
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.schedule.QueuedRef
import org.evsyukov.post.core.schedule.SendTime
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.accounts.AccountOptions
import org.evsyukov.post.data.accounts.QuotePolicy
import org.evsyukov.post.data.accounts.ReplyRule
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.db.PostKind
import org.evsyukov.post.data.db.PostState
import java.time.Instant
import java.time.LocalDateTime

/**
 * A Mastodon poll (R13); Bluesky has none, and a poll does not go with media (step 7 shows the conflict).
 *
 * @property options the choices in order, 2 up to the server's `pollMaxOptions`, each at most
 *   `pollMaxCharactersPerOption` characters (networks.md N1). The composer keeps Send disabled while an option
 *   is empty or too long, so a queued poll is valid.
 * @property expiresInSeconds Mastodon `poll[expires_in]`, within the server's minimum and maximum.
 * @property multiple Mastodon `poll[multiple]`: more than one choice allowed.
 * @property hideTotals Mastodon `poll[hide_totals]`: counts hidden until the poll ends.
 */
@Serializable
data class PollDraft(
    val options: List<String>,
    val expiresInSeconds: Long,
    val multiple: Boolean = false,
    val hideTotals: Boolean = false,
)

/**
 * The options of one account for this post (R13, R17): what the composer's per-account sheet edits. A new
 * entry is made from the account's defaults ([from]) when the sheet opens, so it starts prefilled. Each
 * network reads its own fields and ignores the rest.
 *
 * @property visibility Mastodon `visibility`.
 * @property quotePolicy Mastodon `quote_approval_policy`; sent only to servers that support it
 *   (`MastodonCapabilities.supportsQuotePolicy`, API version 7 and later).
 * @property sensitive Mastodon `sensitive` for this account's media; it adds to [PostOptions.sensitive].
 * @property replyRules the Bluesky threadgate on the first part: null lets anyone reply, an empty set
 *   nobody, otherwise the people of these rules.
 * @property allowQuotes false adds a Bluesky postgate that disables quoting on every part (N24).
 * @property contentLabels Bluesky self-labels; they matter only for a post with media (N22).
 * @property linkCard whether a Bluesky account builds a link card for the first link when the post has
 *   no media (R14). On by default; the card itself is built at send time.
 */
@Serializable
data class AccountPostOptions(
    val visibility: Visibility = Visibility.PUBLIC,
    val quotePolicy: QuotePolicy = QuotePolicy.PUBLIC,
    val sensitive: Boolean = false,
    val replyRules: Set<ReplyRule>? = null,
    val allowQuotes: Boolean = true,
    val contentLabels: Set<ContentLabel> = emptySet(),
    val linkCard: Boolean = true,
) {
    companion object {
        /** The options an account gives a new post: its defaults (R17), with the link card on. */
        fun from(defaults: AccountOptions) = AccountPostOptions(
            visibility = defaults.visibility,
            quotePolicy = defaults.quotePolicy,
            sensitive = defaults.sensitive,
            replyRules = defaults.replyRules,
            allowQuotes = defaults.allowQuotes,
            contentLabels = defaults.contentLabels,
        )
    }
}

/**
 * The options of a post (R13), stored as JSON in `posts.optionsJson` and in history records: the contract
 * between the composer, which writes it, and the sender, which reads it. Every field has a default, and
 * the JSON reader ignores unknown keys, so a field added later keeps old rows readable.
 *
 * @property language the post's language as a BCP 47 code, entered once and given to both networks
 *   (R13): Mastodon `language`, Bluesky `langs` with this one code. The composer fills it from the first
 *   selected account's default language or the device locale; null means none was chosen, and the sender
 *   then uses the account's default languages.
 * @property spoilerText the content warning, Mastodon `spoiler_text`, sent with every part of a Mastodon
 *   thread and counted in it (N3). Bluesky has none and ignores it.
 * @property sensitive marks the media sensitive for every Mastodon target; an account's own
 *   [AccountPostOptions.sensitive] can only add to it.
 * @property accounts the per-account options of this post by account id. An account without an entry
 *   uses [AccountPostOptions.from] its defaults as they are at send time; see [forAccount].
 * @property poll the Mastodon poll, if any; Bluesky targets get the text without it.
 * @property chosenFixes the fixes the user chose for media conflicts, per network (media.md A10); the
 *   attachments step fills it, and a conflict without a chosen fix falls back to the settings' defaults.
 */
@Serializable
data class PostOptions(
    val language: String? = null,
    val spoilerText: String = "",
    val sensitive: Boolean = false,
    val accounts: Map<Long, AccountPostOptions> = emptyMap(),
    val poll: PollDraft? = null,
    val chosenFixes: Map<Network, Map<ConflictKind, FixKind>> = emptyMap(),
    val keptMedia: List<KeptMedia> = emptyList(),
) {
    /** The options [account] gets for this post: its own entry, or its defaults (R17). */
    fun forAccount(account: Account): AccountPostOptions = accounts[account.id] ?: AccountPostOptions.from(account.defaults)

    /** Whether a Mastodon [account]'s media is marked sensitive. */
    fun sensitiveFor(account: Account): Boolean = sensitive || forAccount(account).sensitive

    /** The languages [account] gets: [language], or its default languages when the post has none. */
    fun languagesFor(account: Account): List<String> = language?.let(::listOf) ?: account.defaults.languages
}

/**
 * An edit of a media file a published post keeps (R40, N28, N30, N32), by its place: the [index]-th file of
 * thread part [part]. A file without an entry is kept as published; [removed] leaves it out of the edit, and
 * [alt] is its alt text, sent as Mastodon `media_attributes` or in the new Bluesky record. Media files themselves
 * are not kept (R38), so a kept file can only stay or go, not change.
 */
@Serializable
data class KeptMedia(
    val part: Int,
    val index: Int,
    val alt: String,
    val removed: Boolean = false,
    /**
     * The file's place among all files of the post, in thread order. Every Mastodon copy has the files in that
     * order while its split into parts may differ (T3), so the edit finds the same file in each; null in edits
     * saved before, which go by [part] and [index].
     */
    val ordinal: Int? = null,
)

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

/**
 * The parts and media of a delivery, frozen at its first attempt (R30). Later steps add fields.
 *
 * @property conversions the file to upload for every attachment of the parts (media.md A2): the original
 *   or a variant, as the network's media plan chose at the freeze.
 * @property mastodon the Mastodon status options of the delivery, the same for every part (N10).
 */
@Serializable
data class DeliveryPlan(
    val parts: List<PlannedPart>,
    val conversions: Map<Long, Conversion> = emptyMap(),
    val mastodon: MastodonStatusOptions? = null,
    val bluesky: BlueskyPostOptions? = null,
)

/**
 * One thread part: its text as the account gets it and the attachments it carries.
 *
 * @property key the Mastodon `Idempotency-Key` of the part, chosen at the freeze and sent with every
 *   attempt (N11). Bluesky parts have none: their record key is chosen before the first write (N24).
 * @property contentType the Mastodon `content_type`, or null to send none.
 * @property spoilerText the Mastodon content warning of the part (N3).
 * @property carriesPoll the Mastodon poll goes with this part, the first one.
 * @property links Bluesky link facets over [text] (formatting.md F11), [mentions] by handle and [tags]
 *   (networks.md N17); empty for Mastodon.
 */
@Serializable
data class PlannedPart(
    val text: String,
    val attachmentIds: List<Long> = emptyList(),
    val key: String? = null,
    val contentType: String? = null,
    val spoilerText: String? = null,
    val carriesPoll: Boolean = false,
    val links: List<PlannedSpan> = emptyList(),
    val mentions: List<PlannedSpan> = emptyList(),
    val tags: List<PlannedSpan> = emptyList(),
)

/**
 * A range `text[start, end)` of a part in UTF-16 indices with its [value]: the link URI, the lowercased
 * handle of a mention, or the tag without `#`.
 */
@Serializable
data class PlannedSpan(val start: Int, val end: Int, val value: String)

/**
 * What every post of a Bluesky delivery carries (networks.md N18, N22, N24; product.md R14), frozen with
 * the plan.
 *
 * @property langs up to three languages (N18).
 * @property labels self-labels, put on the parts that carry media (N22).
 * @property replyRules the threadgate of the first part: null for none, empty for nobody.
 * @property allowQuotes false puts a postgate that disables quoting on every part.
 * @property linkCard a part without media gets a card for its first link (R14).
 */
@Serializable
data class BlueskyPostOptions(
    val langs: List<String> = emptyList(),
    val labels: Set<ContentLabel> = emptySet(),
    val replyRules: Set<ReplyRule>? = null,
    val allowQuotes: Boolean = true,
    val linkCard: Boolean = true,
)

/**
 * What every status of a Mastodon delivery carries (N9, N10), frozen with the plan.
 *
 * @property visibility the wire value of `visibility`, the same for every part (N10).
 * @property quoteApprovalPolicy sent only when the server supports it.
 */
@Serializable
data class MastodonStatusOptions(
    val visibility: Visibility = Visibility.PUBLIC,
    val sensitive: Boolean = false,
    val language: String? = null,
    val quoteApprovalPolicy: String? = null,
    val poll: PollDraft? = null,
)

/**
 * A media file uploaded to the server: its id there (Mastodon media id, Bluesky blob CID) and the alt text it
 * was sent with (R38). The file itself is not kept.
 *
 * A Bluesky file also keeps what a new record needs to embed the same blob when the post is deleted and posted
 * again (N32): the blob reference as the record carried it, its aspect ratio and whether the video shows as a
 * GIF. Records written before these fields have none; such a file cannot be posted again.
 *
 * @property blob the Bluesky blob reference as JSON: `$type`, `ref` (the CID), `mimeType` and `size`.
 */
@Serializable
data class PublishedMedia(
    val id: String,
    val alt: String = "",
    val blob: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val gif: Boolean = false,
)

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
    /** The text of the part as published; an edit leaves a part whose text and media did not change (N29). */
    val text: String? = null,
    /** The Mastodon content warning of the part as published. */
    val spoilerText: String? = null,
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
    /** When each media of [uploaded] was uploaded, epoch milliseconds; Mastodon drops unattached media after a day (N8). */
    val uploadedAt: Map<Long, Long> = emptyMap(),
    /**
     * When a request to publish the part was first sent without a definite answer, epoch milliseconds: the
     * part may be on the server (N12). Null once an answer said it is not.
     */
    val unknownSince: Long? = null,
    /**
     * Bluesky video service jobs by attachment id, stored when an upload starts so that a later attempt
     * resumes it (N20); dropped once the video's blob is in [uploaded].
     */
    val videoJobs: Map<Long, String> = emptyMap(),
)

/** The progress of a delivery, stored in `progressJson`. */
@Serializable
data class DeliveryProgress(
    val parts: List<PartProgress> = emptyList(),
    /** Published parts this delivery has deleted (R39, N29, N33): Mastodon status ids or Bluesky AT URIs. */
    val deleted: List<String> = emptyList(),
    /** For a Bluesky "delete and post again" (N32): the old thread is gone, the new one is being published. */
    val oldRemoved: Boolean = false,
) {
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

/**
 * The post can no longer be changed: it is not a draft, or its first delivery has started (scheduling.md Q2).
 * An [IllegalStateException], so callers that treat any state conflict alike keep working.
 */
class PostNotEditable(val id: Long, message: String) : IllegalStateException(message)

/** No delivery has this id. */
class DeliveryNotFound(val id: Long) : Exception("no delivery $id")
