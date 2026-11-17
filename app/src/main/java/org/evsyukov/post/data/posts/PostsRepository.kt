package org.evsyukov.post.data.posts

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.evsyukov.post.core.schedule.resolveLocal
import org.evsyukov.post.data.db.AttachmentEntity
import org.evsyukov.post.data.db.AttachmentWithVariants
import org.evsyukov.post.data.db.DbJson
import org.evsyukov.post.data.db.DeliveryEntity
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.db.PostDatabase
import org.evsyukov.post.data.db.PostEntity
import org.evsyukov.post.data.db.PostKind
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.db.PostWithDetails
import org.evsyukov.post.data.db.ScheduleKind
import org.evsyukov.post.data.db.VariantEntity
import org.evsyukov.post.data.db.refreshPostState
import org.evsyukov.post.data.history.HistoryItem
import org.evsyukov.post.data.history.after
import org.evsyukov.post.data.history.encodeItems
import org.evsyukov.post.data.history.encodeOptions
import org.evsyukov.post.data.history.historyEntity
import org.evsyukov.post.data.history.toRecord
import org.evsyukov.post.data.history.withItem
import org.evsyukov.post.data.settings.HistoryRetention
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Drafts, the queue and the deliveries (R15, R24–R30). Every write that touches several tables runs in
 * one transaction, so a crash leaves either the old state or the new one (R27).
 */
interface PostsRepository {
    /**
     * Inserts a draft ([DraftInput.id] null) or updates a post, and returns its id. Attachments are
     * saved in the order given; those no longer listed are deleted with their files. A queued post can
     * be updated until its first delivery starts (Q2); its deliveries then follow its targets.
     *
     * @throws PostNotFound for an unknown id.
     * @throws PostNotEditable when the post can no longer be edited.
     */
    suspend fun saveDraft(draft: DraftInput): Long

    suspend fun get(id: Long): Post?

    fun observe(id: Long): Flow<Post?>

    /** Drafts, the last saved first (screens.md, Drafts). */
    fun observeDrafts(): Flow<List<Post>>

    /** Deletes a draft and its files; returns false when [id] is not a draft. */
    suspend fun deleteDraft(id: Long): Boolean

    /** Deletes any post with its attachments, variants and their files; returns false when there was none. */
    suspend fun delete(id: Long): Boolean

    /**
     * Delete from the queue (S7): like [delete], but a post with a delivery sending now stays and false is
     * returned. The check and the deletion are one transaction, so the sender cannot claim the post in between.
     */
    suspend fun deleteUnlessSending(id: Long): Boolean

    /**
     * Puts a draft into the queue (R21, R24): one waiting delivery per target and the state QUEUED, in
     * one transaction (R27). A new post goes last among posts due at the same time (Q8). A queued post
     * that has not started can be queued again with a new time and targets.
     *
     * @throws IllegalArgumentException when [targets] is empty, repeats an id, or names an unknown account.
     * @throws PostNotEditable when the post is not a draft or a queued post that has not started.
     */
    suspend fun queue(postId: Long, schedule: Schedule, targets: List<Long>)

    /**
     * Saves [draft] as [saveDraft] does and queues it at [schedule] for its targets as [queue] does, in one
     * transaction (R27): the composer's Send now and Send later. Nothing is written when either step
     * fails. Returns the id of the post.
     *
     * @throws IllegalArgumentException when the targets are empty, repeat an id, or name an unknown account.
     * @throws PostNotFound when the draft has an id and no such post exists.
     * @throws PostNotEditable when the post is not a draft or a queued post that has not started.
     */
    suspend fun saveAndQueue(draft: DraftInput, schedule: Schedule): Long

    /** Posts queued, sending, held or failed, with their deliveries, by send time and queue order (Q8). */
    fun observeQueue(): Flow<List<Post>>

    /**
     * Sets new times for many posts at once, in one transaction: the result of a reorder (S8),
     * a redistribution (S6), a reschedule (Q6) or Send now (Q3). A held post given a time is queued
     * again. Every post must be queued or held and not started; otherwise nothing changes.
     *
     * @throws PostNotFound or [PostNotEditable] when a post is gone or has started.
     */
    suspend fun updateSchedules(changes: Map<Long, Schedule>)

    /** Resolves the slots of queued posts again in [zone], after the device zone changed (S2). */
    suspend fun refreshSlotTimes(zone: ZoneId)

    /** Puts queued posts that have not started on hold (Q5) and returns the ids that went on hold. */
    suspend fun hold(ids: Collection<Long>): List<Long>

    /** Send now for held posts (Q6): they are queued again, due at [at]. */
    suspend fun release(ids: Collection<Long>, at: Instant)

    /** Stores a converted variant, replacing the one of the same profile and deleting its file (A4). */
    suspend fun putVariant(variant: Variant): Long

    /**
     * Claims a waiting delivery before any request: it becomes SENDING, its attempts grow by one and
     * [plan] is frozen if the delivery has none yet (R30). Returns the delivery as stored, or null when
     * it was not waiting.
     */
    suspend fun startDelivery(id: Long, plan: DeliveryPlan): Delivery?

    /**
     * Stores the plan of a waiting delivery frozen when the post was queued (R30, media.md A4). Returns false,
     * and changes nothing, when the delivery started meanwhile or already has a plan, or when the post
     * changed after [postUpdatedAt].
     */
    suspend fun freezePlan(id: Long, postUpdatedAt: Instant, plan: DeliveryPlan): Boolean

    /** Stores what has been published so far (R30). */
    suspend fun saveProgress(id: Long, progress: DeliveryProgress)

    /** The delivery succeeded; returns false when it was not sending. */
    suspend fun deliverySucceeded(id: Long, progress: DeliveryProgress): Boolean

    /**
     * The delivery failed with [error]. With [retryAt] it waits for another attempt then (R29);
     * without it the failure is final. Returns false when it was not sending.
     */
    suspend fun deliveryFailed(id: Long, error: String, retryAt: Instant?): Boolean

    /**
     * Retry from Post details: the failed deliveries of [postId] wait again; returns how many. A delivery
     * of a removed account stays failed with its reason (R1).
     */
    suspend fun retryFailed(postId: Long): Int

    /** Deliveries left SENDING by a process that died wait again; returns how many. */
    suspend fun recoverInterrupted(): Int

    /**
     * A waiting delivery that cannot start, such as one whose plan has a blocking conflict, fails with
     * [error] without a plan (R29). Returns false when it was not waiting.
     */
    suspend fun failUnstarted(id: Long, error: String): Boolean

    /**
     * Retry of one failed delivery (Post details, Queue): it waits again. Returns false when it was not
     * failed, or its account was removed (R1).
     */
    suspend fun retryDelivery(id: Long): Boolean

    /**
     * R5: the account [accountId] signed in again, so its deliveries that failed with [NEEDS_SIGN_IN] wait
     * again. Returns the ids of their posts.
     */
    suspend fun resumeAfterSignIn(accountId: Long): List<Long>

    /** The earliest time a waiting delivery of a queued or sending post is to be tried again, or null. */
    suspend fun earliestRetry(): Instant?

    /**
     * The user cancelled the preparation of the post's media (media.md A7): the post goes back to the
     * drafts with its attachments, and its deliveries go. Returns false, and changes nothing, when a
     * delivery has already published something: the post can no longer be a draft.
     */
    suspend fun returnToDraft(postId: Long): Boolean

    /**
     * The preparation of the post's media was cancelled before its send (media.md A4, A7): the post goes back
     * to the drafts with its attachments and its deliveries go. Returns false, and changes nothing, when a
     * delivery has made an attempt meanwhile: a send that claimed the post goes on, and its attempt prepares
     * the files itself.
     */
    suspend fun returnUnstartedToDraft(postId: Long): Boolean

    /**
     * Records that every delivery of [postId] succeeded (R28, R31): the post becomes SENT, its history
     * changes, and its attachments and variants go with their files. A NEW post gets a history record
     * with [items] unless history is off; an EDIT post replaces text, options and items of its record;
     * a DELETE post keeps [items] as what remains published, and with none the record goes (R39).
     * The SENT post stays only while a record points to it, that is a NEW post with history on; any
     * other post leaves with its deliveries at once (R31, R37). Returns the id of the record written,
     * or null.
     *
     * @throws IllegalStateException when a delivery has not succeeded.
     */
    suspend fun markSent(postId: Long, items: List<HistoryItem>): Long?

    companion object {
        /** The error of a delivery whose account's server rejected the credentials (R5). */
        const val NEEDS_SIGN_IN = "needs sign-in"
    }
}

class RoomPostsRepository(
    private val db: PostDatabase,
    private val files: AttachmentFiles,
    private val historyRetention: suspend () -> HistoryRetention,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : PostsRepository {
    private val posts = db.postDao()
    private val attachments = db.attachmentDao()
    private val deliveries = db.deliveryDao()

    override suspend fun saveDraft(draft: DraftInput): Long {
        requireDistinct(draft.targets)
        val now = clock.millis()
        val removedFiles = mutableListOf<String>()
        val id = db.withTransaction { writeDraft(draft, now, removedFiles) }
        deleteFiles(removedFiles)
        return id
    }

    override suspend fun saveAndQueue(draft: DraftInput, schedule: Schedule): Long {
        require(draft.targets.isNotEmpty()) { "a post needs at least one target" }
        requireDistinct(draft.targets)
        val now = clock.millis()
        val removedFiles = mutableListOf<String>()
        val id = db.withTransaction {
            val postId = writeDraft(draft, now, removedFiles)
            queueInTransaction(postId, schedule, draft.targets, now)
            postId
        }
        deleteFiles(removedFiles)
        return id
    }

    /** The body of [saveDraft], run inside a transaction; the files of removed attachments go to [removedFiles]. */
    private suspend fun writeDraft(draft: DraftInput, now: Long, removedFiles: MutableList<String>): Long {
        val postId = if (draft.id == null) {
            posts.insert(
                PostEntity(
                    kind = draft.kind,
                    state = PostState.DRAFT,
                    text = draft.text,
                    optionsJson = encodeOptions(draft.options),
                    targetsJson = encodeTargets(draft.targets),
                    scheduleKind = null,
                    slotLocal = null,
                    sendAt = null,
                    queueOrder = 0,
                    historyId = draft.historyId,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        } else {
            val post = posts.get(draft.id) ?: throw PostNotFound(draft.id)
            checkEditable(post)
            posts.updateContent(
                id = post.id,
                kind = draft.kind,
                text = draft.text,
                optionsJson = encodeOptions(draft.options),
                targetsJson = encodeTargets(draft.targets),
                historyId = draft.historyId,
                updatedAt = now,
            )
            if (post.state != PostState.DRAFT) {
                // A4: the variants and plans of a queued post were made for its old text; the edit drops them and
                // the preparation builds them again.
                clearPreparedMedia(post.id, removedFiles)
                syncDeliveries(post.id, draft.targets, now)
            }
            post.id
        }
        removedFiles += saveAttachments(postId, draft.attachments, now)
        return postId
    }

    override suspend fun get(id: Long): Post? = posts.details(id)?.toPost()

    override fun observe(id: Long): Flow<Post?> = posts.observeDetails(id).map { it?.toPost() }

    override fun observeDrafts(): Flow<List<Post>> = posts.observeDrafts().map { list -> list.map { it.toPost() } }

    override suspend fun deleteDraft(id: Long): Boolean = deletePost(id, onlyDraft = true)

    override suspend fun delete(id: Long): Boolean = deletePost(id, onlyDraft = false)

    override suspend fun deleteUnlessSending(id: Long): Boolean = deletePost(id, onlyDraft = false, unlessSending = true)

    private suspend fun deletePost(id: Long, onlyDraft: Boolean, unlessSending: Boolean = false): Boolean {
        val paths = db.withTransaction {
            val post = posts.get(id) ?: return@withTransaction null
            if (onlyDraft && post.state != PostState.DRAFT) return@withTransaction null
            if (unlessSending && deliveries.forPost(id).any { it.state == DeliveryState.SENDING }) return@withTransaction null
            // #4: an edit or a deletion that was applied in part leaves its effects in the record before it goes.
            val unfinished = deliveries.forPost(id).filter { it.state != DeliveryState.SENT }
            writeOutcomes(post, unfinished, succeeded = false, dropEmpty = true)
            val paths = attachments.pathsOfPost(id) + attachments.variantPathsOfPost(id)
            posts.delete(id)
            paths
        } ?: return false
        deleteFiles(paths)
        return true
    }

    override suspend fun queue(postId: Long, schedule: Schedule, targets: List<Long>) {
        require(targets.isNotEmpty()) { "a post needs at least one target" }
        requireDistinct(targets)
        val now = clock.millis()
        db.withTransaction { queueInTransaction(postId, schedule, targets, now) }
    }

    /** The body of [queue], run inside a transaction. */
    private suspend fun queueInTransaction(postId: Long, schedule: Schedule, targets: List<Long>, now: Long) {
        val post = posts.get(postId) ?: throw PostNotFound(postId)
        if (post.state != PostState.DRAFT && !isWaiting(post)) throw PostNotEditable(postId, "post $postId is ${post.state} and cannot be queued")
        val order = if (post.state == PostState.DRAFT) posts.nextQueueOrder() else post.queueOrder
        val time = ScheduleColumns.of(schedule, zone())
        posts.queue(postId, PostState.QUEUED, time.kind, time.slotLocal, time.sendAt, encodeTargets(targets), order, now)
        syncDeliveries(postId, targets, now)
    }

    override fun observeQueue(): Flow<List<Post>> = posts.observeQueue().map { list -> list.map { it.toPost() } }

    override suspend fun updateSchedules(changes: Map<Long, Schedule>) {
        if (changes.isEmpty()) return
        val now = clock.millis()
        val zone = zone()
        db.withTransaction {
            for ((id, schedule) in changes) {
                val post = posts.get(id) ?: throw PostNotFound(id)
                if (!isWaiting(post)) throw PostNotEditable(id, "post $id is ${post.state} or has started and keeps its time")
                val time = ScheduleColumns.of(schedule, zone)
                posts.updateSchedule(id, PostState.QUEUED, time.kind, time.slotLocal, time.sendAt, now)
            }
        }
    }

    override suspend fun refreshSlotTimes(zone: ZoneId) {
        db.withTransaction {
            for (post in posts.queuedSlotPosts()) {
                val local = LocalDateTime.parse(post.slotLocal ?: continue)
                posts.updateSendAt(post.id, resolveLocal(local, zone).toEpochMilli())
            }
        }
    }

    override suspend fun hold(ids: Collection<Long>): List<Long> {
        val now = clock.millis()
        return db.withTransaction {
            ids.distinct().filter { id ->
                val post = posts.get(id)
                val holds = post != null && post.state == PostState.QUEUED && !started(id)
                if (holds) posts.updateState(id, PostState.HELD, now)
                holds
            }
        }
    }

    override suspend fun release(ids: Collection<Long>, at: Instant) {
        updateSchedules(ids.associateWith { Schedule.Now(at) })
    }

    override suspend fun putVariant(variant: Variant): Long {
        var replacedPath: String? = null
        val id = db.withTransaction {
            attachments.get(variant.attachmentId) ?: throw IllegalArgumentException("no attachment ${variant.attachmentId}")
            replacedPath = attachments.variant(variant.attachmentId, variant.profileKey)?.path?.takeIf { it != variant.path }
            attachments.putVariant(
                VariantEntity(
                    attachmentId = variant.attachmentId,
                    profileKey = variant.profileKey,
                    path = variant.path,
                    mime = variant.mime,
                    sizeBytes = variant.sizeBytes,
                    width = variant.width,
                    height = variant.height,
                    createdAt = clock.millis(),
                ),
            )
        }
        replacedPath?.let { deleteFiles(listOf(it)) }
        return id
    }

    override suspend fun startDelivery(id: Long, plan: DeliveryPlan): Delivery? {
        val now = clock.millis()
        return db.withTransaction {
            if (deliveries.start(id, now, DbJson.encodeToString(DeliveryPlan.serializer(), plan)) == 0) {
                return@withTransaction null
            }
            val delivery = deliveries.get(id) ?: return@withTransaction null
            db.refreshPostState(delivery.postId, now)
            delivery.toDelivery()
        }
    }

    override suspend fun freezePlan(id: Long, postUpdatedAt: Instant, plan: DeliveryPlan): Boolean =
        deliveries.freezePlan(id, postUpdatedAt.toEpochMilli(), DbJson.encodeToString(DeliveryPlan.serializer(), plan)) > 0

    override suspend fun saveProgress(id: Long, progress: DeliveryProgress) {
        if (deliveries.updateProgress(id, encodeProgress(progress)) == 0) throw DeliveryNotFound(id)
    }

    override suspend fun deliverySucceeded(id: Long, progress: DeliveryProgress): Boolean =
        changeDelivery(id) {
            val changed = deliveries.succeed(id, encodeProgress(progress))
            if (changed > 0) {
                val delivery = deliveries.get(id) ?: throw DeliveryNotFound(id)
                posts.get(delivery.postId)?.let { writeOutcomes(it, listOf(delivery), succeeded = true) }
            }
            changed
        }

    /**
     * #4, R38: writes what [delivered] did on the server into the history record the edit or deletion [post]
     * acts on, in the caller's transaction. A succeeded delivery writes at once, so the record follows each
     * account even when another one fails; a delivery cut short is written only when its post leaves the queue,
     * because a retry compares its plan with the record as it was. With [dropEmpty] a record left without
     * copies goes; otherwise the sender's [markSent] removes it.
     */
    private suspend fun writeOutcomes(post: PostEntity, delivered: List<DeliveryEntity>, succeeded: Boolean, dropEmpty: Boolean = false) {
        if (post.kind == PostKind.NEW) return
        val historyId = post.historyId ?: return
        val history = db.historyDao()
        val before = history.get(historyId)?.toRecord()?.items ?: return
        var items = before
        for (entity in delivered) {
            val delivery = entity.toDelivery()
            val item = items.firstOrNull { it.accountId == delivery.accountId } ?: continue
            items = items.withItem(delivery.accountId, item.after(post.kind, delivery.plan, delivery.progress, succeeded))
        }
        when {
            items.isEmpty() && dropEmpty -> history.delete(historyId)
            items != before -> history.updateItems(historyId, encodeItems(items))
        }
    }

    override suspend fun deliveryFailed(id: Long, error: String, retryAt: Instant?): Boolean =
        changeDelivery(id) {
            val state = if (retryAt != null) DeliveryState.WAITING else DeliveryState.FAILED
            deliveries.fail(id, state, error, retryAt?.toEpochMilli())
        }

    override suspend fun retryFailed(postId: Long): Int {
        val now = clock.millis()
        return db.withTransaction {
            val count = deliveries.retryFailed(postId)
            if (count > 0) db.refreshPostState(postId, now)
            count
        }
    }

    override suspend fun recoverInterrupted(): Int {
        val now = clock.millis()
        return db.withTransaction {
            val affected = deliveries.postsWithSending()
            val count = deliveries.recoverInterrupted()
            affected.forEach { db.refreshPostState(it, now) }
            count
        }
    }

    override suspend fun failUnstarted(id: Long, error: String): Boolean =
        changeDelivery(id) { deliveries.failUnstarted(id, clock.millis(), error) }

    override suspend fun retryDelivery(id: Long): Boolean = changeDelivery(id) { deliveries.retryOne(id) }

    override suspend fun resumeAfterSignIn(accountId: Long): List<Long> {
        val now = clock.millis()
        return db.withTransaction {
            val affected = deliveries.postsFailedWith(accountId, PostsRepository.NEEDS_SIGN_IN)
            deliveries.resumeFailedWith(accountId, PostsRepository.NEEDS_SIGN_IN)
            affected.forEach { db.refreshPostState(it, now) }
            affected
        }
    }

    override suspend fun earliestRetry(): Instant? = deliveries.earliestRetry()?.let(Instant::ofEpochMilli)

    override suspend fun returnToDraft(postId: Long): Boolean {
        val now = clock.millis()
        return db.withTransaction {
            val post = posts.get(postId) ?: return@withTransaction false
            if (post.state == PostState.DRAFT) return@withTransaction true
            val all = deliveries.forPost(postId).map { it.toDelivery() }
            val published = all.any { delivery ->
                delivery.state == DeliveryState.SENT || delivery.progress.parts.any { it.published != null || it.unknownSince != null }
            }
            if (published) return@withTransaction false
            deliveries.deleteForPost(postId)
            posts.updateState(postId, PostState.DRAFT, now)
            true
        }
    }

    override suspend fun returnUnstartedToDraft(postId: Long): Boolean {
        val now = clock.millis()
        return db.withTransaction {
            val post = posts.get(postId) ?: return@withTransaction false
            if (post.state == PostState.DRAFT) return@withTransaction true
            // A4: a run claimed the post between the preparation's read and the cancel; the send goes on and
            // prepares the files itself, so the delivery must not be deleted under it.
            if (deliveries.forPost(postId).any { it.attempts > 0 }) return@withTransaction false
            deliveries.deleteForPost(postId)
            posts.updateState(postId, PostState.DRAFT, now)
            true
        }
    }

    override suspend fun markSent(postId: Long, items: List<HistoryItem>): Long? {
        val retention = historyRetention()
        val sentAt = clock.instant()
        val (historyId, paths) = db.withTransaction {
            val post = posts.get(postId) ?: throw PostNotFound(postId)
            val all = deliveries.forPost(postId)
            check(all.isNotEmpty() && all.all { it.state == DeliveryState.SENT }) { "post $postId has deliveries that did not succeed" }
            val historyId = writeHistory(post, items, retention, sentAt)
            posts.updateState(postId, PostState.SENT, sentAt.toEpochMilli())
            val paths = attachments.pathsOfPost(postId) + attachments.variantPathsOfPost(postId)
            attachments.deleteForPost(postId)
            // With no record pointing to it (history off, an edit or a deletion), the post goes now.
            posts.deleteUnrecordedSent()
            historyId to paths
        }
        deleteFiles(paths)
        return historyId
    }

    private suspend fun writeHistory(post: PostEntity, items: List<HistoryItem>, retention: HistoryRetention, sentAt: Instant): Long? {
        val history = db.historyDao()
        return when (post.kind) {
            PostKind.NEW -> retention.expiresAt(sentAt)?.let { expiresAt ->
                history.insert(historyEntity(post.id, sentAt, post.text, post.optionsJson, items, expiresAt))
            }
            PostKind.EDIT -> post.historyId?.takeIf { history.update(it, post.text, post.optionsJson, encodeItems(items)) > 0 }
            PostKind.DELETE -> post.historyId?.let { id ->
                if (items.isEmpty()) {
                    history.delete(id)
                    null
                } else {
                    id.takeIf { history.updateItems(it, encodeItems(items)) > 0 }
                }
            }
        }
    }

    private suspend fun changeDelivery(id: Long, change: suspend () -> Int): Boolean {
        val now = clock.millis()
        return db.withTransaction {
            val delivery = deliveries.get(id) ?: throw DeliveryNotFound(id)
            val changed = change() > 0
            if (changed) db.refreshPostState(delivery.postId, now)
            changed
        }
    }

    /** The deliveries of a queued post follow its targets: removed accounts go, new ones wait. */
    private suspend fun syncDeliveries(postId: Long, targets: List<Long>, now: Long) {
        require(targets.isNotEmpty()) { "a queued post needs at least one target" }
        deliveries.deleteExcept(postId, targets)
        val accounts = db.accountDao()
        for (accountId in targets) {
            require(accounts.exists(accountId)) { "no account $accountId" }
            deliveries.insertIgnore(
                DeliveryEntity(
                    postId = postId,
                    accountId = accountId,
                    state = DeliveryState.WAITING,
                    planJson = null,
                    progressJson = null,
                    error = null,
                    attempts = 0,
                    lastAttemptAt = null,
                    nextAttemptAt = null,
                ),
            )
        }
        db.refreshPostState(postId, now)
    }

    /**
     * Drops the variants of a queued post and forgets the plans of its deliveries (media.md A4); the files of
     * the variants go to [removedFiles] and are deleted once the transaction commits.
     */
    private suspend fun clearPreparedMedia(postId: Long, removedFiles: MutableList<String>) {
        removedFiles += attachments.variantPathsOfPost(postId)
        attachments.deleteVariantsOfPost(postId)
        deliveries.clearPlans(postId)
    }

    /** Saves [input] in its order and returns the files of the attachments that were left out. */
    private suspend fun saveAttachments(postId: Long, input: List<Attachment>, now: Long): List<String> {
        val existing = attachments.forPost(postId).map { it.id }.toSet()
        val kept = input.map { it.id }.filter { it != 0L }
        require(kept.toSet().size == kept.size) { "attachment ids repeat" }
        kept.forEach { require(it in existing) { "attachment $it is not of post $postId" } }
        val removed = (existing - kept.toSet()).toList()
        val removedFiles = if (removed.isEmpty()) {
            emptyList()
        } else {
            removed.mapNotNull { attachments.get(it)?.path } + attachments.variantPaths(removed)
        }
        if (removed.isNotEmpty()) attachments.delete(removed)
        input.forEachIndexed { position, attachment ->
            if (attachment.id == 0L) {
                attachments.insert(attachment.toEntity(postId, position, now))
            } else {
                attachments.updateEditable(
                    attachment.id,
                    position,
                    attachment.pieceIndex,
                    attachment.alt,
                    attachment.focusX,
                    attachment.focusY,
                )
            }
        }
        return removedFiles
    }

    private suspend fun checkEditable(post: PostEntity) {
        if (post.state != PostState.DRAFT && !isWaiting(post)) {
            throw PostNotEditable(post.id, "post ${post.id} is ${post.state} and can no longer be edited")
        }
    }

    /** Queued or held, and no delivery has made an attempt yet. */
    private suspend fun isWaiting(post: PostEntity): Boolean =
        (post.state == PostState.QUEUED || post.state == PostState.HELD) && !started(post.id)

    private suspend fun started(postId: Long): Boolean = deliveries.forPost(postId).any { it.attempts > 0 }

    private suspend fun deleteFiles(paths: List<String>) {
        if (paths.isEmpty()) return
        withContext(Dispatchers.IO) { files.delete(paths) }
    }

    private fun requireDistinct(targets: List<Long>) {
        require(targets.toSet().size == targets.size) { "target accounts repeat" }
    }

    /** The schedule columns of a post: kind, slot and the instant it is due. */
    private class ScheduleColumns(val kind: ScheduleKind, val slotLocal: String?, val sendAt: Long) {
        companion object {
            fun of(schedule: Schedule, zone: ZoneId): ScheduleColumns = when (schedule) {
                is Schedule.Now -> ScheduleColumns(ScheduleKind.NOW, null, schedule.at.toEpochMilli())
                is Schedule.Slot -> ScheduleColumns(
                    ScheduleKind.SLOT,
                    schedule.local.toString(),
                    resolveLocal(schedule.local, zone).toEpochMilli(),
                )
                is Schedule.Explicit -> ScheduleColumns(ScheduleKind.EXPLICIT, null, schedule.at.toEpochMilli())
            }
        }
    }

    private companion object {
        val targetsSerializer = ListSerializer(Long.serializer())

        fun encodeTargets(targets: List<Long>): String = DbJson.encodeToString(targetsSerializer, targets)

        fun encodeProgress(progress: DeliveryProgress): String =
            DbJson.encodeToString(DeliveryProgress.serializer(), progress)

        fun Attachment.toEntity(postId: Long, position: Int, now: Long) = AttachmentEntity(
            postId = postId,
            position = position,
            pieceIndex = pieceIndex,
            kind = kind,
            path = path,
            mime = mime,
            sizeBytes = sizeBytes,
            width = width,
            height = height,
            durationMs = durationMs,
            frameRate = frameRate,
            animated = animated,
            hasTransparency = hasTransparency,
            videoCodec = videoCodec,
            alt = alt,
            focusX = focusX,
            focusY = focusY,
            createdAt = now,
        )

        fun AttachmentWithVariants.toAttachment() = Attachment(
            id = attachment.id,
            kind = attachment.kind,
            path = attachment.path,
            mime = attachment.mime,
            sizeBytes = attachment.sizeBytes,
            width = attachment.width,
            height = attachment.height,
            durationMs = attachment.durationMs,
            frameRate = attachment.frameRate,
            animated = attachment.animated,
            hasTransparency = attachment.hasTransparency,
            videoCodec = attachment.videoCodec,
            pieceIndex = attachment.pieceIndex,
            alt = attachment.alt,
            focusX = attachment.focusX,
            focusY = attachment.focusY,
            variants = variants.sortedBy { it.id }.map {
                Variant(it.id, it.attachmentId, it.profileKey, it.path, it.mime, it.sizeBytes, it.width, it.height)
            },
        )

        fun DeliveryEntity.toDelivery() = Delivery(
            id = id,
            postId = postId,
            accountId = accountId,
            state = state,
            plan = planJson?.let { DbJson.decodeFromString(DeliveryPlan.serializer(), it) },
            progress = progressJson?.let { DbJson.decodeFromString(DeliveryProgress.serializer(), it) } ?: DeliveryProgress(),
            error = error,
            attempts = attempts,
            lastAttemptAt = lastAttemptAt?.let(Instant::ofEpochMilli),
            nextAttemptAt = nextAttemptAt?.let(Instant::ofEpochMilli),
        )

        fun PostWithDetails.toPost(): Post {
            val schedule = when (post.scheduleKind) {
                null -> null
                ScheduleKind.NOW -> post.sendAt?.let { Schedule.Now(Instant.ofEpochMilli(it)) }
                ScheduleKind.SLOT -> post.slotLocal?.let { Schedule.Slot(LocalDateTime.parse(it)) }
                ScheduleKind.EXPLICIT -> post.sendAt?.let { Schedule.Explicit(Instant.ofEpochMilli(it)) }
            }
            val order = post.targetsJson.let { DbJson.decodeFromString(targetsSerializer, it) }
            return Post(
                id = post.id,
                kind = post.kind,
                state = post.state,
                text = post.text,
                options = DbJson.decodeFromString(PostOptions.serializer(), post.optionsJson),
                targets = order,
                schedule = schedule,
                sendAt = post.sendAt?.let(Instant::ofEpochMilli),
                queueOrder = post.queueOrder,
                historyId = post.historyId,
                createdAt = Instant.ofEpochMilli(post.createdAt),
                updatedAt = Instant.ofEpochMilli(post.updatedAt),
                attachments = attachments.sortedWith(compareBy({ it.attachment.position }, { it.attachment.id }))
                    .map { it.toAttachment() },
                // Deliveries in the order of the targets, then any left over from removed targets.
                deliveries = deliveries.sortedWith(compareBy({ order.indexOf(it.accountId).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.id }))
                    .map { it.toDelivery() },
            )
        }
    }
}
