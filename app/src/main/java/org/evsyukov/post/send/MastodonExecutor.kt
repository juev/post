package org.evsyukov.post.send

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import org.evsyukov.post.accounts.AccountSessions
import org.evsyukov.post.accounts.NeedsSignInException
import org.evsyukov.post.core.mastodon.MastodonClient
import org.evsyukov.post.core.mastodon.MastodonException
import org.evsyukov.post.core.mastodon.MediaAttribute
import org.evsyukov.post.core.mastodon.MediaState
import org.evsyukov.post.core.mastodon.PollParams
import org.evsyukov.post.core.mastodon.Status
import org.evsyukov.post.core.mastodon.StatusEditParams
import org.evsyukov.post.core.mastodon.StatusParams
import org.evsyukov.post.core.mastodon.Visibility
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.AccountLimits
import org.evsyukov.post.data.accounts.AccountNotFound
import org.evsyukov.post.data.accounts.AccountsRepository
import org.evsyukov.post.data.db.AccountState
import org.evsyukov.post.data.db.PostKind
import org.evsyukov.post.data.history.HistoryItem
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
            when (job.post.kind) {
                PostKind.NEW -> run.publishAll()
                // N31: a copy the user chose to delete and post again on a server that cannot edit. The choice
                // is kept with the post, so a retry after the server answered 405 does not turn the edit into it.
                PostKind.EDIT -> {
                    val previous = job.previous ?: return DeliveryOutcome.Failed(Publisher.NO_RECORD)
                    if (job.account.id in job.post.options.repostAccounts) run.repost(previous) else run.editAll(previous)
                }
                PostKind.DELETE -> run.deleteAll(job.previous ?: return DeliveryOutcome.Failed(Publisher.NO_RECORD))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: EditUnsupported) {
            DeliveryOutcome.Failed(EDIT_UNSUPPORTED)
        } catch (_: StatusGone) {
            DeliveryOutcome.Failed(STATUS_GONE)
        } catch (e: MastodonException) {
            outcomeOf(e, job)
        } catch (e: IllegalArgumentException) {
            // uploadMedia refuses a file it cannot read or a focus out of range: retrying cannot fix it.
            DeliveryOutcome.Failed(e.message ?: "the file cannot be uploaded")
        } catch (e: FileNotFoundException) {
            DeliveryOutcome.Failed(Publisher.MISSING_FILE)
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

    /**
     * N31: the server answered that it cannot edit. The account keeps the answer, so the composer offers
     * "Delete and post again" for it next time instead of another edit that cannot succeed.
     */
    private suspend fun markCannotEdit(job: DeliveryJob) {
        // Sign-in reads the limits before the account can send, so the capabilities are there; without them
        // there is nothing to mark, and storing defaults would lose what the server reported.
        val limits = job.account.limits as? AccountLimits.Mastodon ?: return
        try {
            accounts.updateLimits(job.account.id, AccountLimits.Mastodon(limits.capabilities.copy(supportsEdit = false)), job.account.limitsFetchedAt ?: clock.instant())
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

        /**
         * N28–N30: the new parts against the published ones, by position. A part whose text, content warning,
         * media and options did not change is left alone; a changed part is edited in place with `PUT`; extra
         * parts are posted as replies to the last remaining part; surplus parts are deleted, last first.
         */
        suspend fun editAll(previous: HistoryItem): DeliveryOutcome {
            val parts = job.plan.parts
            val old = previous.parts
            val optionsChanged = optionsChanged(previous)
            var previousId: String? = null
            for (index in parts.indices) {
                val done = partProgress(index).published
                if (done != null) {
                    previousId = done.id
                    continue
                }
                val part = parts[index]
                val published = old.getOrNull(index)
                val result = when {
                    published?.id == null -> publishPart(index, part, previousId)
                    !changed(index, part, published, optionsChanged) -> published.also { save(partProgress(index).copy(published = it)) }
                    else -> editPart(index, part, published)
                }
                previousId = result.id
            }
            for (index in old.indices.reversed()) {
                if (index < parts.size) break
                old[index].id?.let { delete(it) }
            }
            return DeliveryOutcome.Sent(progress)
        }

        /**
         * N31: delete and post again on a server that cannot edit. The old thread goes first, last part first:
         * a status takes a media attachment only while it belongs to no status (Mastodon
         * `PostStatusService#validate_media!`), so the delete releases the files the edit keeps, as Mastodon's
         * own delete and redraft does. A part already deleted is not deleted again, so a retry goes on.
         */
        suspend fun repost(previous: HistoryItem): DeliveryOutcome {
            for (part in previous.parts.reversed()) part.id?.let { delete(it) }
            return publishAll()
        }

        /** R39, N31: every part, last first; a part already gone counts as deleted, 429 waits (via [outcomeOf]). */
        suspend fun deleteAll(previous: HistoryItem): DeliveryOutcome {
            for (part in previous.parts.reversed()) part.id?.let { delete(it) }
            return DeliveryOutcome.Sent(progress)
        }

        private suspend fun delete(id: String) {
            if (id in progress.deleted) return
            client.deleteStatus(token, id)
            progress = progress.copy(deleted = progress.deleted + id)
            job.saveProgress(progress)
        }

        /** The options every status carries: a change in any of them is sent with every part. */
        private fun optionsChanged(previous: HistoryItem): Boolean {
            val before = previous.mastodon ?: return true
            val now = job.plan.mastodon ?: return true
            return before.sensitive != now.sensitive || before.language != now.language ||
                before.quoteApprovalPolicy != now.quoteApprovalPolicy || before.poll != now.poll
        }

        private fun keptOf(index: Int): List<PublishedMedia> = keptMediaOf(job.post.options.keptMedia, job.previous, index)

        private fun changed(index: Int, part: PlannedPart, published: PublishedPart, optionsChanged: Boolean): Boolean =
            optionsChanged || published.text != part.text || published.spoilerText.orEmpty() != part.spoilerText.orEmpty() ||
                part.attachmentIds.isNotEmpty() || keptOf(index) != published.media

        /** N31: the files the edit keeps in part [index] when the copy is deleted and posted again by choice. */
        private fun keptReposted(index: Int): List<PublishedMedia> =
            if (job.post.kind == PostKind.EDIT && job.account.id in job.post.options.repostAccounts) keptOf(index) else emptyList()

        /** N31: the kept files of part [index] whose alt text was changed in the composer. */
        private fun keptRelabelled(index: Int): List<PublishedMedia> {
            val published = job.previous?.parts?.getOrNull(index)?.media.orEmpty()
            return keptReposted(index).filter { media -> published.firstOrNull { it.id == media.id }?.alt != media.alt }
        }

        private suspend fun editPart(index: Int, part: PlannedPart, published: PublishedPart): PublishedPart {
            val kept = keptOf(index)
            val uploaded = uploadAll(index, part)
            val options = job.plan.mastodon
            val params = StatusEditParams(
                status = part.text,
                mediaIds = kept.map { it.id } + uploaded,
                mediaAttributes = kept.map { MediaAttribute(it.id, it.alt, null) },
                spoilerText = part.spoilerText,
                sensitive = options?.sensitive ?: false,
                language = options?.language,
                poll = options?.poll?.takeIf { part.carriesPoll }?.let { PollParams(it.options, it.expiresInSeconds, it.multiple) },
                quoteApprovalPolicy = options?.quoteApprovalPolicy,
                contentType = part.contentType,
            )
            val status = try {
                client.editStatus(token, published.id!!, params)
            } catch (e: MastodonException.Http) {
                // N31: 405 says the server cannot edit. A 404 may also be a status deleted elsewhere, so ask
                // for it: a 200 means the server cannot edit, a 404 means the status is gone.
                if (e.status == 405) {
                    markCannotEdit(job)
                    throw EditUnsupported()
                }
                if (e.status == 404) {
                    if (client.getStatus(token, published.id!!) == null) throw StatusGone()
                    markCannotEdit(job)
                    throw EditUnsupported()
                }
                throw e
            }
            val state = partProgress(index)
            val media = kept + part.attachmentIds.mapNotNull { id ->
                state.uploaded[id]?.let {
                    PublishedMedia(it, job.post.attachments.firstOrNull { a -> a.id == id }?.alt.orEmpty(), attachmentId = id)
                }
            }
            val result = PublishedPart(id = status.id, uri = status.uri ?: published.uri, url = status.url ?: published.url, media = media)
            save(state.copy(published = result))
            return result
        }

        /** Uploads the part's new files, reusing those uploaded less than a day ago (N8); returns their ids. */
        private suspend fun uploadAll(index: Int, part: PlannedPart): List<String> {
            var state = partProgress(index)
            val now = clock.millis()
            return part.attachmentIds.map { id ->
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
        }

        private suspend fun publishPart(index: Int, part: PlannedPart, inReplyToId: String?): PublishedPart {
            var state = partProgress(index).let { it.copy(key = it.key ?: part.key ?: "${job.delivery.id}-$index") }
            val now = clock.millis()
            val kept = keptReposted(index)
            val unknownSince = state.unknownSince
            if (unknownSince != null) {
                // N12: look before posting again. After an hour the server's idempotency cache may have forgotten
                // the key. Within the hour the key is enough on Mastodon, but GoToSocial ignores Idempotency-Key, so a
                // lost answer would make the part twice (found in the step 15 end-to-end run). A failed lookup within
                // the hour falls back to the key.
                val withinKeyWindow = now - unknownSince < UNKNOWN_WINDOW.toMillis()
                val found = try {
                    findPublished(part, inReplyToId, unknownSince)
                } catch (e: MastodonException) {
                    if (!withinKeyWindow) throw e
                    null
                }
                if (found != null) {
                    val published = PublishedPart(id = found.id, uri = found.uri, url = found.url, media = kept + mediaOf(part, state))
                    save(state.copy(published = published, unknownSince = null))
                    return published
                }
            }
            // N31: the kept files of a repost are the published media, by their ids, ahead of the new ones.
            val mediaIds = kept.map { it.id } + part.attachmentIds.map { id ->
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
            // N31: an alt text changed in the composer takes effect once the file is free of the old status.
            for (media in keptRelabelled(index)) relabel(media)
            // Until an answer comes, the part may be on the server: a crash or a lost answer leaves it unknown.
            state = state.copy(unknownSince = state.unknownSince ?: clock.millis())
            save(state)
            val status = try {
                createStatusWaitingForMedia(params, state.key!!, kept.map { it.id })
            } catch (e: MastodonException) {
                // Only a definite refusal (4xx: 422, 429, …) says the status was not made. A 5xx or 408 may come
                // from a proxy while the backend made it, so the part stays unknown and the retry looks first (N12).
                if (e is MastodonException.Http && !e.isTransient) save(state.copy(unknownSince = null))
                throw e
            }
            val published = PublishedPart(id = status.id, uri = status.uri, url = status.url, media = kept + mediaOf(part, state))
            save(state.copy(published = published, unknownSince = null))
            return published
        }

        /**
         * N31: the delete that released the kept media runs in a background job on the server, so a create may
         * come too early and be refused with 422 for the media it names. Wait for the release a few times; a
         * refusal that names none of them, such as a text over the limit, comes back at once.
         */
        private suspend fun createStatusWaitingForMedia(params: StatusParams, key: String, kept: List<String>): Status {
            var waits = 0
            while (true) {
                try {
                    return client.createStatus(token, params, key)
                } catch (e: MastodonException.Http) {
                    if (e.status != 422 || kept.isEmpty() || waits >= MEDIA_RELEASE_WAITS || !e.namesAnyOf(kept)) throw e
                    waits++
                    sleep(MEDIA_RELEASE_WAIT.toMillis())
                }
            }
        }

        /** N31: an alt text of a kept file, set once the file no longer belongs to the deleted status. */
        private suspend fun relabel(media: PublishedMedia) {
            var waits = 0
            while (true) {
                try {
                    client.updateMedia(token, media.id, media.alt, null)
                    return
                } catch (e: MastodonException.Http) {
                    if (e.status != 404 || waits >= MEDIA_RELEASE_WAITS) throw e
                    waits++
                    sleep(MEDIA_RELEASE_WAIT.toMillis())
                }
            }
        }

        private fun mediaOf(part: PlannedPart, state: PartProgress): List<PublishedMedia> =
            part.attachmentIds.mapNotNull { id ->
                state.uploaded[id]?.let {
                    PublishedMedia(it, job.post.attachments.firstOrNull { a -> a.id == id }?.alt.orEmpty(), attachmentId = id)
                }
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
            progress = progress.copy(parts = (progress.parts.filter { it.index != state.index } + state).sortedBy { it.index })
            job.saveProgress(progress)
        }
    }

    /** The server answered that it cannot edit (N31): a 405, or a 404 for a status that is still there. */
    private class EditUnsupported : Exception()

    /** N31: the status the edit would change was deleted elsewhere; the 404 on `PUT` was not about editing. */
    private class StatusGone : Exception()

    /** The server could not process an uploaded file (N7: 422 on `GET /api/v1/media/:id`). */
    private class MediaFailed(message: String) : Exception(message)

    companion object {
        /** N31: the failure of an edit on a server that cannot edit; the user can delete and post again. */
        const val EDIT_UNSUPPORTED = "this server cannot edit posts: delete the post and post it again"

        /** N31: the edit names a status that was deleted elsewhere, not a server without edit support. */
        const val STATUS_GONE = "a part of the published post is no longer on the server"

        /** How long the server's idempotency cache keeps a key (N12). */
        val UNKNOWN_WINDOW: Duration = Duration.ofHours(1)

        /** Unattached media are deleted by the server after a day (N8). */
        val MEDIA_LIFETIME: Duration = Duration.ofHours(24)

        /** N31: how long a server may take to release the media of a deleted status, in waits of this length. */
        val MEDIA_RELEASE_WAIT: Duration = Duration.ofSeconds(1)
        const val MEDIA_RELEASE_WAITS = 5

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

/** N31: whether the server's refusal names one of [ids], as "Media attachments not found: 1, 2" does. */
private fun MastodonException.Http.namesAnyOf(ids: List<String>): Boolean {
    val texts = listOfNotNull(error, description)
    return texts.any { text -> ids.any { id -> Regex("""(?<!\d)${Regex.escape(id)}(?!\d)""").containsMatchIn(text) } }
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
        if (normalize(status.text ?: "") == wanted) return true
        // A Markdown link `[label](url)` renders as its label alone; the address is only in the href. Mastodon
        // renders a mention `@user@domain` as `@user`, with the domain only in the href.
        val plain = normalize(plainOf(status.content))
        val linksShown = MARKDOWN_LINK.replace(text) { it.groupValues[1] }
        val mentionsShown = MENTION.replace(linksShown) { "@" + it.groupValues[1] }
        return plain == wanted || plain == normalize(linksShown) || plain == normalize(mentionsShown)
    }

    private val MARKDOWN_LINK = Regex("""\[([^\]]*)]\([^)\s]*(?:\s+"[^"]*")?\)""")

    /** `@user@domain`, not inside an e-mail address. */
    private val MENTION = Regex("""(?<![\w@])@(\w+)@[\w.-]*\w""")

    /** Letters and digits only, lowercased: the HTML a server renders and the source text then compare. */
    internal fun normalize(text: String): String = buildString {
        text.codePoints().forEach { if (Character.isLetterOrDigit(it)) appendCodePoint(Character.toLowerCase(it)) }
    }

    private fun plainOf(html: String): String = html
        .replace(Regex("<[^>]*>"), " ")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
}
