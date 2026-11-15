package org.evsyukov.post.send

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import org.evsyukov.post.accounts.AccountSessions
import org.evsyukov.post.accounts.NeedsSignInException
import org.evsyukov.post.core.mastodon.MastodonClient
import org.evsyukov.post.core.mastodon.MastodonException
import org.evsyukov.post.core.mastodon.MediaState
import org.evsyukov.post.core.mastodon.PollParams
import org.evsyukov.post.core.mastodon.Status
import org.evsyukov.post.core.mastodon.StatusParams
import org.evsyukov.post.core.mastodon.Visibility
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.AccountNotFound
import org.evsyukov.post.data.accounts.AccountsRepository
import org.evsyukov.post.data.db.AccountState
import org.evsyukov.post.data.posts.DeliveryProgress
import org.evsyukov.post.data.posts.PartProgress
import org.evsyukov.post.data.posts.PlannedPart
import org.evsyukov.post.data.posts.PublishedMedia
import org.evsyukov.post.data.posts.PublishedPart
import org.evsyukov.post.media.convert.ConversionException
import java.io.FileNotFoundException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Publishes a frozen plan to a Mastodon-API server (networks.md N7–N12, threads.md T15).
 *
 * For each part from the first unpublished one: its media are uploaded (or reused when uploaded less than
 * a day ago, N8) and waited for, then the status goes out with the part's stored `Idempotency-Key` (N11)
 * as a reply to the previous part with the same visibility (N10). Progress is stored after every upload
 * and every status, so a crash resumes without duplicates (R30).
 *
 * A request whose answer was lost leaves the part "unknown since" its first such attempt. Within the hour
 * the server's idempotency cache answers a retry with the status already made; after it, the account's
 * latest statuses are searched by text, reply target and time before the status is sent again (N12).
 *
 * Errors map to outcomes (R5, R29): rejected credentials → [DeliveryOutcome.NeedsSignIn] with the account
 * marked; 429 → retry after `Retry-After`; 5xx, 408, network failures and malformed answers → retry with
 * backoff from 30 s; 422, other 4xx, a file the client refuses and failed media processing → failed with
 * the server's message.
 */
class MastodonExecutor(
    private val http: OkHttpClient,
    private val sessions: AccountSessions,
    private val accounts: AccountsRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : DeliveryExecutor {
    override val network: Network = Network.MASTODON

    override suspend fun publish(job: DeliveryJob): DeliveryOutcome {
        val auth = try {
            sessions.mastodon(job.account.id)
        } catch (_: NeedsSignInException) {
            return DeliveryOutcome.NeedsSignIn
        } catch (_: AccountNotFound) {
            return DeliveryOutcome.Failed(AccountsRepository.ACCOUNT_REMOVED)
        }
        val client = MastodonClient(auth.origin, http, clock = clock)
        val run = Run(job, client, auth.token)
        return try {
            run.publishAll()
        } catch (e: CancellationException) {
            throw e
        } catch (e: MastodonException) {
            outcomeOf(e, job)
        } catch (e: IllegalArgumentException) {
            // uploadMedia refuses a file it cannot read or a focus out of range: retrying cannot fix it.
            DeliveryOutcome.Failed(e.message ?: "the file cannot be uploaded")
        } catch (e: FileNotFoundException) {
            DeliveryOutcome.Failed("an attachment file is missing")
        } catch (e: ConversionException) {
            DeliveryOutcome.Failed(conversionMessage(e))
        } catch (e: MediaFailed) {
            DeliveryOutcome.Failed(e.message ?: "the server could not process the media")
        }
    }

    private suspend fun outcomeOf(e: MastodonException, job: DeliveryJob): DeliveryOutcome {
        val now = clock.instant()
        val attempt = job.attempt
        return when {
            e.isUnauthorized -> {
                markNeedsSignIn(job.account.id)
                DeliveryOutcome.NeedsSignIn
            }
            e.isRateLimited -> {
                val seconds = (e as MastodonException.Http).retryAfterSeconds
                val at = seconds?.let { now.plusSeconds(it.coerceAtLeast(1)) } ?: now.plus(backoff(attempt))
                DeliveryOutcome.Retry(at, messageOf(e))
            }
            e.isTransient || e is MastodonException.Malformed -> DeliveryOutcome.Retry(now.plus(backoff(attempt)), messageOf(e))
            else -> DeliveryOutcome.Failed(messageOf(e))
        }
    }

    private suspend fun markNeedsSignIn(id: Long) {
        try {
            accounts.setState(id, AccountState.NEEDS_SIGN_IN)
        } catch (_: AccountNotFound) {
            // Removed meanwhile; its deliveries already failed with that reason.
        }
    }

    /** One attempt of one delivery. */
    private inner class Run(private val job: DeliveryJob, private val client: MastodonClient, private val token: String) {
        private var progress = job.progress
        private var files: Map<Long, UploadFile>? = null

        suspend fun publishAll(): DeliveryOutcome {
            val parts = job.plan.parts
            val start = progress.firstUnpublished(parts.size) ?: return DeliveryOutcome.Sent(progress)
            var previousId = if (start > 0) partProgress(start - 1).published?.id else null
            for (index in start until parts.size) {
                val part = parts[index]
                val status = publishPart(index, part, previousId)
                previousId = status.id
            }
            return DeliveryOutcome.Sent(progress)
        }

        private suspend fun publishPart(index: Int, part: PlannedPart, inReplyToId: String?): PublishedPart {
            var state = partProgress(index).let { it.copy(key = it.key ?: part.key ?: "${job.delivery.id}-$index") }
            val now = clock.millis()
            val unknownSince = state.unknownSince
            if (unknownSince != null && now - unknownSince >= UNKNOWN_WINDOW.toMillis()) {
                // N12: the idempotency cache of the server may have forgotten the key; look before posting again.
                val found = findPublished(part, inReplyToId, unknownSince)
                if (found != null) {
                    val published = PublishedPart(id = found.id, uri = found.uri, url = found.url, media = mediaOf(part, state))
                    save(state.copy(published = published, unknownSince = null))
                    return published
                }
            }
            val mediaIds = part.attachmentIds.map { id ->
                val uploaded = state.uploaded[id]
                val at = state.uploadedAt[id]
                if (uploaded != null && at != null && now - at < MEDIA_LIFETIME.toMillis()) {
                    uploaded
                } else {
                    val mediaId = upload(id)
                    state = state.copy(uploaded = state.uploaded + (id to mediaId), uploadedAt = state.uploadedAt + (id to clock.millis()))
                    save(state)
                    mediaId
                }
            }
            val options = job.plan.mastodon
            val params = StatusParams(
                status = part.text,
                mediaIds = mediaIds,
                spoilerText = part.spoilerText,
                sensitive = options?.sensitive ?: false,
                visibility = options?.visibility ?: Visibility.PUBLIC,
                language = options?.language,
                poll = options?.poll?.takeIf { part.carriesPoll }?.let { PollParams(it.options, it.expiresInSeconds, it.multiple) },
                inReplyToId = inReplyToId,
                quoteApprovalPolicy = options?.quoteApprovalPolicy,
                contentType = part.contentType,
            )
            // Until an answer comes, the part may be on the server: a crash or a lost answer leaves it unknown.
            state = state.copy(unknownSince = state.unknownSince ?: clock.millis())
            save(state)
            val status = try {
                client.createStatus(token, params, state.key!!)
            } catch (e: MastodonException) {
                if (e is MastodonException.Http) save(state.copy(unknownSince = null))
                throw e
            }
            val published = PublishedPart(id = status.id, uri = status.uri, url = status.url, media = mediaOf(part, state))
            save(state.copy(published = published, unknownSince = null))
            return published
        }

        private fun mediaOf(part: PlannedPart, state: PartProgress): List<PublishedMedia> =
            part.attachmentIds.mapNotNull { id ->
                state.uploaded[id]?.let { PublishedMedia(it, job.post.attachments.firstOrNull { a -> a.id == id }?.alt.orEmpty()) }
            }

        private suspend fun upload(attachmentId: Long): String {
            val all = files ?: job.files().also { files = it }
            val file = all[attachmentId] ?: throw FileNotFoundException("no file for attachment $attachmentId")
            val attachment = job.post.attachments.firstOrNull { it.id == attachmentId }
            val focus = attachment?.focusX?.let { x -> attachment.focusY?.let { y -> x to y } }
            val upload = client.uploadMedia(token, file.file, file.mime, attachment?.alt?.takeIf { it.isNotBlank() }, focus)
            if (!upload.processed) waitForProcessing(upload.id)
            return upload.id
        }

        /** N7: a 202 upload is polled until ready; a status with unprocessed media would be rejected. */
        private suspend fun waitForProcessing(id: String) {
            var wait = FIRST_POLL.toMillis()
            val deadline = clock.millis() + PROCESSING_LIMIT.toMillis()
            while (true) {
                when (val state = client.getMedia(token, id)) {
                    is MediaState.Ready -> return
                    is MediaState.Failed -> throw MediaFailed(state.message ?: "the server could not process the media")
                    MediaState.Processing -> Unit
                }
                if (clock.millis() >= deadline) {
                    throw MastodonException.Network(java.io.IOException("media processing timed out"), "media $id is still processing")
                }
                sleep(wait)
                wait = (wait * 2).coerceAtMost(MAX_POLL.toMillis())
            }
        }

        private suspend fun findPublished(part: PlannedPart, inReplyToId: String?, since: Long): Status? {
            val statuses = client.accountStatuses(token, job.account.remoteId)
            return statuses.firstOrNull { StatusMatch.matches(it, part.text, inReplyToId, Instant.ofEpochMilli(since)) }
        }

        private fun partProgress(index: Int): PartProgress = progress.parts.firstOrNull { it.index == index } ?: PartProgress(index)

        private suspend fun save(state: PartProgress) {
            progress = DeliveryProgress((progress.parts.filter { it.index != state.index } + state).sortedBy { it.index })
            job.saveProgress(progress)
        }
    }

    /** The server could not process an uploaded file (N7: 422 on `GET /api/v1/media/:id`). */
    private class MediaFailed(message: String) : Exception(message)

    companion object {
        /** How long the server's idempotency cache keeps a key (N12). */
        val UNKNOWN_WINDOW: Duration = Duration.ofHours(1)

        /** Unattached media are deleted by the server after a day (N8). */
        val MEDIA_LIFETIME: Duration = Duration.ofHours(24)

        private val FIRST_POLL: Duration = Duration.ofSeconds(1)
        private val MAX_POLL: Duration = Duration.ofSeconds(10)
        private val PROCESSING_LIMIT: Duration = Duration.ofMinutes(10)

        /** R29, M6: 30 s, doubling with every attempt, at most an hour. */
        fun backoff(attempt: Int): Duration {
            val shift = (attempt - 1).coerceIn(0, 7)
            return Duration.ofSeconds(30L shl shift).coerceAtMost(Duration.ofHours(1))
        }

        /** The server's message: the `error` of its answer, or the client's description. */
        fun messageOf(e: MastodonException): String = when (e) {
            is MastodonException.Http -> listOfNotNull(e.error, e.description).joinToString(": ").ifBlank { e.message ?: "HTTP ${e.status}" }
            else -> e.message ?: e.javaClass.simpleName
        }

        fun conversionMessage(e: ConversionException): String = when (e) {
            is ConversionException.CannotFit -> "a file cannot be made to fit this server's limits"
            is ConversionException.TranscodeFailed -> "the video could not be converted"
            is ConversionException.ImageFailed -> "the image could not be converted"
        }
    }
}

/** N12: how a status found among the account's latest ones is recognized as the part sent before. */
object StatusMatch {
    /** A status made a little before the first attempt still counts: device and server clocks differ. */
    private val CLOCK_SKEW: Duration = Duration.ofMinutes(5)

    fun matches(status: Status, text: String, inReplyToId: String?, since: Instant): Boolean {
        if (status.inReplyToId != inReplyToId) return false
        val created = status.createdAt?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: return false
        if (created.isBefore(since.minus(CLOCK_SKEW))) return false
        val wanted = normalize(text)
        return normalize(status.text ?: "") == wanted || normalize(plainOf(status.content)) == wanted
    }

    /** Letters and digits only, lowercased: the HTML a server renders and the source text then compare. */
    internal fun normalize(text: String): String = buildString {
        text.codePoints().forEach { if (Character.isLetterOrDigit(it)) appendCodePoint(Character.toLowerCase(it)) }
    }

    private fun plainOf(html: String): String = html
        .replace(Regex("<[^>]*>"), " ")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
}
