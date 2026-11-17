package org.evsyukov.post.send.bluesky

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.evsyukov.post.accounts.AccountSessions
import org.evsyukov.post.accounts.BlueskyAuth
import org.evsyukov.post.accounts.NeedsSignInException
import org.evsyukov.post.core.bluesky.AspectRatio
import org.evsyukov.post.core.bluesky.BlobRef
import org.evsyukov.post.core.bluesky.BlueskyClient
import org.evsyukov.post.core.bluesky.Collections
import org.evsyukov.post.core.bluesky.Embed
import org.evsyukov.post.core.bluesky.EmbedImage
import org.evsyukov.post.core.bluesky.External
import org.evsyukov.post.core.bluesky.ExternalEmbed
import org.evsyukov.post.core.bluesky.FacetInput
import org.evsyukov.post.core.bluesky.GalleryEmbed
import org.evsyukov.post.core.bluesky.GalleryImage
import org.evsyukov.post.core.bluesky.IdentityException
import org.evsyukov.post.core.bluesky.IdentityResolver
import org.evsyukov.post.core.bluesky.ImagesEmbed
import org.evsyukov.post.core.bluesky.LinkSpan
import org.evsyukov.post.core.bluesky.MentionSpan
import org.evsyukov.post.core.bluesky.PostRecord
import org.evsyukov.post.core.bluesky.PostgateRecord
import org.evsyukov.post.core.bluesky.PostgateRule
import org.evsyukov.post.core.bluesky.ReplyRef
import org.evsyukov.post.core.bluesky.SelfLabels
import org.evsyukov.post.core.bluesky.StrongRef
import org.evsyukov.post.core.bluesky.TagSpan
import org.evsyukov.post.core.bluesky.ThreadgateRecord
import org.evsyukov.post.core.bluesky.ThreadgateRule
import org.evsyukov.post.core.bluesky.Tid
import org.evsyukov.post.core.bluesky.VideoClient
import org.evsyukov.post.core.bluesky.VideoEmbed
import org.evsyukov.post.core.bluesky.VideoPresentation
import org.evsyukov.post.core.bluesky.WriteOp
import org.evsyukov.post.core.bluesky.XrpcException
import org.evsyukov.post.core.bluesky.atUri
import org.evsyukov.post.core.bluesky.atprotoDatetime
import org.evsyukov.post.core.bluesky.buildFacets
import org.evsyukov.post.core.media.MediaKind
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.text.TextSegmenter
import org.evsyukov.post.core.text.graphemeCount
import org.evsyukov.post.data.accounts.AccountNotFound
import org.evsyukov.post.data.accounts.AccountsRepository
import org.evsyukov.post.data.accounts.ReplyRule
import org.evsyukov.post.data.db.AccountState
import org.evsyukov.post.data.db.PostKind
import org.evsyukov.post.data.history.HistoryItem
import org.evsyukov.post.data.posts.Attachment
import org.evsyukov.post.data.posts.BlueskyPostOptions
import org.evsyukov.post.data.posts.DeliveryProgress
import org.evsyukov.post.data.posts.PartProgress
import org.evsyukov.post.data.posts.PlannedPart
import org.evsyukov.post.data.posts.PublishedMedia
import org.evsyukov.post.data.posts.PublishedPart
import org.evsyukov.post.media.convert.ConversionException
import org.evsyukov.post.send.DeliveryExecutor
import org.evsyukov.post.send.DeliveryJob
import org.evsyukov.post.send.DeliveryOutcome
import org.evsyukov.post.send.MastodonExecutor
import org.evsyukov.post.send.Publisher
import org.evsyukov.post.send.UploadFile
import org.evsyukov.post.send.keptMediaOf
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Publishes a frozen plan to a Bluesky PDS (networks.md N16–N27, product.md R14).
 *
 * Each part goes in its own `applyWrites` call (N24): the post at a record key (TID) stored with the
 * delivery before the request, the threadgate on the first part when replies are restricted, and a
 * postgate on every part when quoting is off, all at the post's key. The URI and CID from the results (or
 * from `getRecord`) become the `reply` of the next part. Before a part that already has a key is written
 * again, `getRecord` looks it up: if it is there, the part counts as published (N25).
 *
 * Text carries facets with UTF-8 byte offsets; a mention whose handle does not resolve stays plain text
 * (N17). Images go through `uploadBlob` (N19), video and GIF through the video service (N20, N21); blobs
 * older than [BLOB_LIFETIME] are uploaded again (N26). A part without media gets a link card for its first
 * link when the options keep it (R14); a card that cannot be built is left out.
 *
 * "Delete and post again" (N32) posts the new thread first, embedding the files the edit keeps by the blob
 * references history holds, and deletes the old thread once the new one is up. A new thread the server refuses
 * is deleted again, so the old one stays the only copy.
 *
 * Errors map to outcomes (R5, R29): an expired token is refreshed by [AccountSessions]; rejected
 * credentials → [DeliveryOutcome.NeedsSignIn]; 429 → retry at `RateLimit-Reset`; 5xx, 408 and network
 * failures → retry with backoff; validation and other 4xx → failed with the server's message.
 */
class BlueskyExecutor(
    private val http: OkHttpClient,
    private val sessions: AccountSessions,
    private val accounts: AccountsRepository,
    private val segmenter: TextSegmenter,
    private val linkCards: LinkCardSource,
    /** Where a card's thumbnail is written for its upload. */
    private val tempDir: File,
    private val identity: IdentityResolver = IdentityResolver(http, JSON),
    private val videoBase: HttpUrl = VideoClient.DEFAULT_BASE,
    private val clock: Clock = Clock.systemUTC(),
    private val videoPollMillis: Long = VideoClient.DEFAULT_POLL_MILLIS,
    private val videoTimeout: Duration = Duration.ofMinutes(15),
) : DeliveryExecutor {
    override val network: Network = Network.BLUESKY

    override suspend fun publish(job: DeliveryJob): DeliveryOutcome {
        val run = Run(job)
        val outcome = guarded(job) { auth ->
            when (job.post.kind) {
                PostKind.NEW -> run.publishAll(auth)
                // N32: Bluesky has no edit here; an edit target is "delete and post again". The new thread goes up
                // first, so its records embed the blobs the old one still holds, and the old one goes after it.
                PostKind.EDIT -> {
                    val previous = job.previous ?: return@guarded DeliveryOutcome.Failed(Publisher.NO_RECORD)
                    run.publishAll(auth)
                    run.removeOld(auth, previous)
                    DeliveryOutcome.Sent(run.current)
                }
                PostKind.DELETE -> {
                    run.removeOld(auth, job.previous ?: return@guarded DeliveryOutcome.Failed(Publisher.NO_RECORD))
                    DeliveryOutcome.Sent(run.current)
                }
            }
        }
        // N32: a new thread the server refuses does not stay half posted next to the old one, which stays as it was.
        if (job.post.kind == PostKind.EDIT && outcome is DeliveryOutcome.Failed && !run.current.oldRemoved && run.startedNew) {
            try {
                sessions.bluesky(job.account.id) { auth -> run.withdrawNew(auth) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The parts stay in the progress: a retry goes on with them, and the failure keeps its own message.
            }
        }
        return outcome
    }

    /** Runs [block] with the account's session and maps what it throws to an outcome (R5, R29). */
    private suspend fun guarded(job: DeliveryJob, block: suspend (BlueskyAuth) -> DeliveryOutcome): DeliveryOutcome {
        return try {
            sessions.bluesky(job.account.id) { auth -> block(auth) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: NeedsSignInException) {
            DeliveryOutcome.NeedsSignIn
        } catch (_: AccountNotFound) {
            DeliveryOutcome.Failed(AccountsRepository.ACCOUNT_REMOVED)
        } catch (e: XrpcException) {
            outcomeOf(e, job)
        } catch (_: FileNotFoundException) {
            DeliveryOutcome.Failed(Publisher.MISSING_FILE)
        } catch (e: IOException) {
            DeliveryOutcome.Retry(clock.instant().plus(MastodonExecutor.backoff(job.attempt)), e.message ?: "network error")
        } catch (e: VideoRejected) {
            DeliveryOutcome.Failed(e.message ?: QUOTA_MESSAGE)
        } catch (e: ConversionException) {
            DeliveryOutcome.Failed(MastodonExecutor.conversionMessage(e))
        } catch (e: IllegalArgumentException) {
            // A facet range or a record field the lexicon refuses: the same plan fails the same way again.
            DeliveryOutcome.Failed(e.message ?: "the post cannot be built")
        }
    }

    private suspend fun outcomeOf(e: XrpcException, job: DeliveryJob): DeliveryOutcome {
        val now = clock.instant()
        return when {
            e.isInvalidToken -> {
                try {
                    accounts.setState(job.account.id, AccountState.NEEDS_SIGN_IN)
                } catch (_: AccountNotFound) {
                    // Removed meanwhile.
                }
                DeliveryOutcome.NeedsSignIn
            }
            e.isRateLimited -> {
                val reset = e.rateLimitResetEpochSeconds?.let { Instant.ofEpochSecond(it) }
                val at = reset?.takeIf { it.isAfter(now) } ?: now.plus(MastodonExecutor.backoff(job.attempt))
                DeliveryOutcome.Retry(at, messageOf(e))
            }
            // A token that expired again right after its refresh, or an answer this client cannot read.
            e.isTransient || e.isExpiredToken || e.error == XrpcException.INVALID_RESPONSE ->
                DeliveryOutcome.Retry(now.plus(MastodonExecutor.backoff(job.attempt)), messageOf(e))
            else -> DeliveryOutcome.Failed(messageOf(e))
        }
    }

    /** One attempt of one delivery; it outlives a session refresh, so its progress is kept across it. */
    private inner class Run(private val job: DeliveryJob) {
        private var progress = job.progress
        private var files: Map<Long, UploadFile>? = null
        private val dids = HashMap<String, String?>()
        private var lastCreatedAt: Instant? = null
        private val options: BlueskyPostOptions = job.plan.bluesky ?: BlueskyPostOptions()

        val current: DeliveryProgress get() = progress

        /** Whether a part of the new thread got a record key, so it may be on the server. */
        val startedNew: Boolean get() = progress.parts.any { it.key != null }

        /**
         * N33, R39: deletes every part of the published thread with its threadgate and postgates, in one
         * `applyWrites` while they fit its limit. Records already gone are left out, so a retry, or a part
         * deleted elsewhere, does not fail the call (R39: a part already gone counts as deleted).
         */
        suspend fun removeOld(auth: BlueskyAuth, previous: HistoryItem) {
            if (progress.oldRemoved) return
            deleteThread(auth, previous.parts.mapNotNull { it.uri?.substringAfterLast('/') })
            progress = progress.copy(deleted = previous.parts.mapNotNull { it.uri }, oldRemoved = true)
            job.saveProgress(progress)
        }

        /**
         * N32: deletes the parts of the new thread that may be on the server, with their gates, and forgets
         * them, so the old thread is the only copy again and a retry starts the new one over.
         */
        suspend fun withdrawNew(auth: BlueskyAuth) {
            deleteThread(auth, progress.parts.sortedBy { it.index }.mapNotNull { it.key })
            progress = progress.copy(parts = emptyList())
            job.saveProgress(progress)
        }

        /** The posts at [keys] (thread order), the threadgate at the first and every postgate, those still there. */
        private suspend fun deleteThread(auth: BlueskyAuth, keys: List<String>) {
            val client = BlueskyClient(auth.pds, http, JSON, clock::millis)
            val did = auth.did
            val candidates = buildList {
                keys.reversed().forEach { add(WriteOp.Delete(Collections.POST, it)) }
                keys.firstOrNull()?.let { add(WriteOp.Delete(Collections.THREADGATE, it)) }
                keys.reversed().forEach { add(WriteOp.Delete(Collections.POSTGATE, it)) }
            }
            val writes = candidates.filter { client.getRecord(did, it.collection, it.rkey) != null }
            writes.chunked(BlueskyClient.MAX_WRITES).forEach { client.applyWrites(auth.accessJwt, did, it) }
        }

        suspend fun publishAll(auth: BlueskyAuth): DeliveryOutcome {
            val client = BlueskyClient(auth.pds, http, JSON, clock::millis)
            val parts = job.plan.parts
            val start = progress.firstUnpublished(parts.size) ?: return DeliveryOutcome.Sent(progress)
            var root = if (start > 0) refOf(partProgress(0).published) else null
            var parent = if (start > 0) refOf(partProgress(start - 1).published) else null
            for (index in start until parts.size) {
                val reply = if (root != null && parent != null) ReplyRef(root, parent) else null
                val ref = publishPart(client, auth, index, parts[index], reply)
                if (index == 0) root = ref
                parent = ref
            }
            return DeliveryOutcome.Sent(progress)
        }

        private suspend fun publishPart(client: BlueskyClient, auth: BlueskyAuth, index: Int, part: PlannedPart, reply: ReplyRef?): StrongRef {
            var state = partProgress(index)
            val did = auth.did
            val existingKey = state.key
            if (existingKey != null) {
                // N25: the earlier attempt may have written the part; the PDS would answer a second create with 500.
                client.getRecord(did, Collections.POST, existingKey)?.let { found ->
                    return published(state, part, did, existingKey, found.uri, found.cid)
                }
            }
            val rkey = existingKey ?: Tid.next().also {
                state = state.copy(key = it)
                save(state)
            }
            val hasMedia = part.attachmentIds.isNotEmpty() || kept(index).isNotEmpty()
            val embed = if (hasMedia) mediaEmbed(client, auth, index, part) else linkCard(client, auth, part)
            state = partProgress(index)
            val createdAt = nextCreatedAt()
            val uri = atUri(did, Collections.POST, rkey)
            val record = PostRecord(
                text = part.text,
                createdAt = createdAt,
                facets = facets(part).ifEmpty { null },
                reply = reply,
                embed = embed,
                langs = options.langs.take(MAX_LANGS).ifEmpty { null },
                labels = if (hasMedia) SelfLabels.of(options.labels) else null,
            )
            val writes = mutableListOf<WriteOp>(record.create(rkey))
            val rules = options.replyRules
            if (index == 0 && rules != null) writes += ThreadgateRecord(uri, createdAt, rules.map(::threadgateRule)).create(rkey)
            if (!options.allowQuotes) writes += PostgateRecord(uri, createdAt, listOf(PostgateRule.DisableEmbedding)).create(rkey)
            val result = client.applyWrites(auth.accessJwt, did, writes).firstOrNull()
            val resultUri = result?.uri
            val resultCid = result?.cid
            if (resultUri != null && resultCid != null) return published(state, part, did, rkey, resultUri, resultCid)
            val found = client.getRecord(did, Collections.POST, rkey) ?: throw IOException("the post is not found after it was written")
            return published(state, part, did, rkey, found.uri, found.cid)
        }

        private suspend fun published(state: PartProgress, part: PlannedPart, did: String, rkey: String, uri: String, cid: String?): StrongRef {
            if (cid == null) throw IOException("the server gave the post without its CID")
            // R38: the full blob reference, so "delete and post again" can embed the same blob (N32).
            val media = kept(state.index) + part.attachmentIds.mapNotNull { id ->
                val json = state.uploaded[id] ?: return@mapNotNull null
                val blobCid = blobOf(json).cid ?: return@mapNotNull null
                val attachment = attachment(id)
                val aspect = attachment?.let(::aspectOf)
                PublishedMedia(
                    id = blobCid,
                    alt = attachment?.alt.orEmpty(),
                    blob = json,
                    width = aspect?.width,
                    height = aspect?.height,
                    gif = attachment?.kind == MediaKind.GIF,
                    attachmentId = id,
                )
            }
            val published = PublishedPart(uri = uri, cid = cid, url = postUrl(did, rkey), media = media)
            save(state.copy(key = rkey, published = published, videoJobs = emptyMap()))
            return StrongRef(uri, cid)
        }

        /** N24: `createdAt` is the send time, one millisecond later than the part before at least. */
        private fun nextCreatedAt(): String {
            val now = Instant.ofEpochMilli(clock.millis())
            val last = lastCreatedAt
            val value = if (last != null && !now.isAfter(last)) last.plusMillis(1) else now
            lastCreatedAt = value
            return atprotoDatetime(value)
        }

        private suspend fun facets(part: PlannedPart): List<org.evsyukov.post.core.bluesky.Facet> {
            val mentions = part.mentions.mapNotNull { span ->
                resolve(span.value)?.let { MentionSpan(span.start, span.end, it) }
            }
            val input = FacetInput(
                links = part.links.map { LinkSpan(it.start, it.end, it.value) },
                mentions = mentions,
                tags = part.tags.map { TagSpan(it.start, it.end, it.value) },
            )
            return buildFacets(part.text, input) { segmenter.graphemeCount(it) }
        }

        /** N17: the DID of a mentioned handle, or null to leave it plain text. Cached for the run. */
        private suspend fun resolve(handle: String): String? {
            if (dids.containsKey(handle)) return dids[handle]
            val did = try {
                identity.resolveHandle(handle).takeIf { IdentityResolver.isValidDid(it) }
            } catch (_: IdentityException) {
                null
            }
            dids[handle] = did
            return did
        }

        private suspend fun linkCard(client: BlueskyClient, auth: BlueskyAuth, part: PlannedPart): Embed? {
            if (!options.linkCard) return null
            val link = part.links.minByOrNull { it.start } ?: return null
            val card = linkCards.fetch(link.value) ?: return null
            val thumb = card.thumb?.let { bytes ->
                val file = File.createTempFile("card", ".jpg", tempDir.apply { mkdirs() })
                try {
                    file.writeBytes(bytes)
                    client.uploadBlob(auth.accessJwt, file, "image/jpeg")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: XrpcException) {
                    // The refresh of an expired session repeats the part; anything else drops the card (R14).
                    if (e.isExpiredToken) throw e
                    return null
                } catch (_: IOException) {
                    return null
                } finally {
                    file.delete()
                }
            }
            return ExternalEmbed(External(card.uri, card.title, card.description, thumb))
        }

        /**
         * The files the part embeds: those an edit keeps from the old thread's part at [index] first (N32), by
         * their blob references, then the new attachments, uploaded. A video, kept or new, makes the embed.
         */
        private suspend fun mediaEmbed(client: BlueskyClient, auth: BlueskyAuth, index: Int, part: PlannedPart): Embed {
            val kept = kept(index).map { media ->
                Shown(blobOf(media.blob!!), media.alt, aspectOf(media.width, media.height), media.gif)
            }
            val items = part.attachmentIds.map { id -> id to (attachment(id) ?: throw FileNotFoundException("no attachment $id")) }
            kept.firstOrNull { it.blob.mimeType?.startsWith("video/") == true }?.let { return videoEmbed(it) }
            val video = items.firstOrNull { (_, a) -> a.kind == MediaKind.VIDEO || a.kind == MediaKind.GIF }
            if (video != null) {
                val (id, attachment) = video
                val blob = blob(index, id) { uploadVideo(client, auth, index, id) }
                return videoEmbed(Shown(blob, attachment.alt, aspectOf(attachment), attachment.kind == MediaKind.GIF))
            }
            val images = kept + items.map { (id, attachment) ->
                Shown(blob(index, id) { client.uploadBlob(auth.accessJwt, file(id).file, file(id).mime) }, attachment.alt, aspectOf(attachment), false)
            }
            return if (images.size <= ImagesEmbed.MAX_IMAGES) {
                ImagesEmbed(images.map { EmbedImage(it.blob, it.alt, it.aspect) })
            } else {
                GalleryEmbed(images.map { GalleryImage(it.blob, it.alt, it.aspect ?: AspectRatio(1, 1)) })
            }
        }

        private fun videoEmbed(video: Shown) = VideoEmbed(
            video = video.blob,
            alt = video.alt.takeIf { it.isNotBlank() },
            aspectRatio = video.aspect,
            presentation = VideoPresentation.GIF.takeIf { video.gif },
        )

        /**
         * N32: the files an edit keeps from part [index] of the old thread, with their edited alt text. A file
         * recorded without its blob reference, by a version before it was kept, cannot be embedded and is left out.
         */
        private fun kept(index: Int): List<PublishedMedia> =
            if (job.post.kind != PostKind.EDIT) emptyList() else keptMediaOf(job.post.options.keptMedia, job.previous, index).filter { it.blob != null }

        /** The stored blob of [attachmentId] while it is fresh (N26), or a new upload. */
        private suspend fun blob(index: Int, attachmentId: Long, upload: suspend () -> BlobRef): BlobRef {
            val state = partProgress(index)
            val stored = state.uploaded[attachmentId]
            val at = state.uploadedAt[attachmentId]
            if (stored != null && at != null && clock.millis() - at < BLOB_LIFETIME.toMillis()) return blobOf(stored)
            val blob = upload()
            val fresh = partProgress(index)
            save(
                fresh.copy(
                    uploaded = fresh.uploaded + (attachmentId to JSON.encodeToString(JsonObject.serializer(), blob.json)),
                    uploadedAt = fresh.uploadedAt + (attachmentId to clock.millis()),
                    videoJobs = fresh.videoJobs - attachmentId,
                ),
            )
            return blob
        }

        /** N20, N21: limits, service auth, the multipart upload (resumed by its stored job) and the job's blob. */
        private suspend fun uploadVideo(client: BlueskyClient, auth: BlueskyAuth, index: Int, attachmentId: Long): BlobRef {
            val upload = file(attachmentId)
            val video = VideoClient(http, JSON, videoBase, clock::millis)
            val stored = partProgress(index).videoJobs[attachmentId]
            if (stored == null) {
                val limitsToken = client.getServiceAuth(auth.accessJwt, VideoClient.LIMITS_AUDIENCE, VideoClient.LIMITS_LXM, null)
                val limits = video.getUploadLimits(limitsToken)
                if (!limits.canUpload) throw VideoRejected(listOfNotNull(QUOTA_MESSAGE, limits.message?.takeIf { it.isNotBlank() }).joinToString(": "))
            }
            val token: suspend (Boolean) -> String = {
                client.getServiceAuth(
                    auth.accessJwt,
                    VideoClient.uploadAudience(auth.pds),
                    VideoClient.UPLOAD_LXM,
                    VideoClient.uploadTokenExpiry(clock.millis()),
                )
            }
            val onStarted: suspend (org.evsyukov.post.core.bluesky.UploadSession) -> Unit = { session ->
                val fresh = partProgress(index)
                save(fresh.copy(videoJobs = fresh.videoJobs + (attachmentId to session.jobId)))
            }
            val finished = try {
                video.upload(upload.file, upload.mime, token, resumeJobId = stored, onStarted = onStarted)
            } catch (e: XrpcException) {
                if (e.error !in RESTART_ERRORS) throw e
                // The service forgot or closed the upload: it starts over.
                val fresh = partProgress(index)
                save(fresh.copy(videoJobs = fresh.videoJobs - attachmentId))
                video.upload(upload.file, upload.mime, token, resumeJobId = null, onStarted = onStarted)
            }
            val status = if (finished.jobStatus.blob != null) {
                finished.jobStatus
            } else {
                withTimeoutOrNull(videoTimeout.toMillis()) { video.awaitJob(finished.completedJobId, videoPollMillis) }
                    ?: throw IOException("the video is still being processed")
            }
            return status.blob ?: throw VideoRejected(
                listOfNotNull(VIDEO_FAILED_MESSAGE, (status.message ?: status.error)?.takeIf { it.isNotBlank() }).joinToString(": "),
            )
        }

        private suspend fun file(attachmentId: Long): UploadFile {
            val all = files ?: job.files().also { files = it }
            return all[attachmentId] ?: throw FileNotFoundException("no file for attachment $attachmentId")
        }

        private fun attachment(id: Long): Attachment? = job.post.attachments.firstOrNull { it.id == id }

        private fun partProgress(index: Int): PartProgress = progress.parts.firstOrNull { it.index == index } ?: PartProgress(index)

        private suspend fun save(state: PartProgress) {
            progress = progress.copy(parts = (progress.parts.filter { it.index != state.index } + state).sortedBy { it.index })
            job.saveProgress(progress)
        }
    }

    /** The video service refused the upload (daily quota) or could not process the video. */
    private class VideoRejected(message: String) : Exception(message)

    /** A file as a post embeds it. */
    private class Shown(val blob: BlobRef, val alt: String, val aspect: AspectRatio?, val gif: Boolean)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Servers keep an unreferenced blob for at least an hour (N26); an older one is uploaded again. */
        val BLOB_LIFETIME: Duration = Duration.ofHours(1)

        const val QUOTA_MESSAGE = "the daily Bluesky video quota is used up"
        const val VIDEO_FAILED_MESSAGE = "Bluesky could not process the video"
        private const val MAX_LANGS = 3

        /** Answers of the video service after which the upload starts over (N20). */
        private val RESTART_ERRORS = setOf("UploadExpired", "UploadAborted", "UploadNotFound")

        /** `https://bsky.app/profile/<did>/post/<rkey>`, the post in the official app (R38). */
        fun postUrl(did: String, rkey: String): String = "https://bsky.app/profile/$did/post/$rkey"

        fun messageOf(e: XrpcException): String =
            listOfNotNull(e.error, e.message?.takeIf { it.isNotBlank() }).joinToString(": ").ifBlank { "HTTP ${e.status}" }

        private fun blobOf(json: String): BlobRef = BlobRef(JSON.parseToJsonElement(json).jsonObject)

        private fun refOf(part: PublishedPart?): StrongRef? {
            val uri = part?.uri ?: return null
            val cid = part.cid ?: return null
            return StrongRef(uri, cid)
        }

        private fun threadgateRule(rule: ReplyRule): ThreadgateRule = when (rule) {
            ReplyRule.MENTIONED -> ThreadgateRule.Mentioned
            ReplyRule.FOLLOWERS -> ThreadgateRule.Followers
            ReplyRule.FOLLOWING -> ThreadgateRule.Following
        }

        private fun aspectOf(attachment: Attachment): AspectRatio? = aspectOf(attachment.width, attachment.height)

        private fun aspectOf(width: Int?, height: Int?): AspectRatio? {
            if (width == null || height == null) return null
            return if (width >= 1 && height >= 1) AspectRatio(width, height) else null
        }
    }
}
