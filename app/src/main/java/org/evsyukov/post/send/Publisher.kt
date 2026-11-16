package org.evsyukov.post.send

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.evsyukov.post.PublishTrigger
import org.evsyukov.post.core.media.Conversion
import org.evsyukov.post.core.media.MediaKind
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.schedule.dueOrder
import org.evsyukov.post.core.schedule.inQueueOrder
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.accounts.AccountsRepository
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.db.PostKind
import org.evsyukov.post.data.history.HistoryItem
import org.evsyukov.post.data.history.HistoryRecord
import org.evsyukov.post.data.posts.Delivery
import org.evsyukov.post.data.posts.DeliveryPlan
import org.evsyukov.post.data.posts.FilesDirAttachmentFiles
import org.evsyukov.post.data.posts.Post
import org.evsyukov.post.data.posts.PostsRepository
import org.evsyukov.post.data.settings.Settings
import org.evsyukov.post.media.prepare.MediaPreparer
import org.evsyukov.post.media.prepare.VariantRequest
import java.io.File
import java.io.FileNotFoundException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Sends the posts that are due (product.md R27–R32, scheduling.md Q8). One run at a time per process.
 *
 * A run takes the posts whose time has come and the posts already sending (a retry or a resumed thread), in
 * queue order. Each post's deliveries run one account after another, so its threads keep their order, and
 * independently of each other (R28). The first attempt of a delivery freezes its plan (R30); a delivery
 * whose network has no executor yet stays waiting, untouched. When every delivery of a post succeeded the
 * post is recorded as sent, with a history record unless history is off, and its files go (R31).
 */
class Publisher(
    private val posts: PostsRepository,
    private val accounts: AccountsRepository,
    private val settings: suspend () -> Settings,
    private val freezer: PlanFreezer,
    executors: List<DeliveryExecutor>,
    private val preparer: MediaPreparer,
    filesDir: File,
    private val notifier: SendNotifier,
    /** Wakes the sender again at the earliest retry; step 9 moves this to the alarm. */
    private val scheduleRetry: (Instant) -> Unit,
    private val trigger: PublishTrigger,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /**
     * Runs before the due posts are chosen. Step 9 puts late posts on hold here (scheduling.md Q4–Q5), so
     * a held post is not sent.
     */
    private val beforeSend: suspend (now: Instant) -> Unit = {},
    /** Runs after every run: the alarm is set for the next pending time (scheduling.md M1). */
    private val afterRun: suspend () -> Unit = {},
    /** The history record an EDIT or DELETE post acts on (R37–R41). */
    private val history: suspend (Long) -> HistoryRecord? = { null },
) {
    private val executors: Map<Network, DeliveryExecutor> = executors.associateBy { it.network }
    private val files = FilesDirAttachmentFiles(filesDir)
    private val mutex = Mutex()

    private val lock = Any()
    private var preparing: Pair<Long, kotlinx.coroutines.Job>? = null
    private var cancelledPost: Long? = null

    /** Sends every due post; returns when they are all tried. */
    suspend fun run(foreground: SendForeground = SendForeground {}) = mutex.withLock {
        try {
            posts.recoverInterrupted()
            val now = clock.instant()
            beforeSend(now)
            val zone = zone()
            val handled = HashSet<Long>()
            var first = true
            while (true) {
                // Q2, S8: the queue is read again before every post, so an edit, a new time or a reorder saved
                // while an earlier post was sending is what goes out, and a post moved into the due time goes too.
                val order = dueNow(now, zone)
                if (first) {
                    if (order.any { it.hasLargeMedia() }) showForeground(foreground, SendProgress())
                    first = false
                }
                val post = order.firstOrNull { it.id !in handled } ?: break
                handled += post.id
                try {
                    publishPost(post, foreground)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // One post that cannot be handled, such as one deleted under the run, does not hold up the
                    // posts after it; its deliveries keep their state and the next run sees them again.
                    Log.w(TAG, "post ${post.id} was not handled: ${e.javaClass.simpleName}")
                }
            }
        } finally {
            // M1, R29: whatever happened above, the next run is set up, even when this one was stopped.
            withContext(NonCancellable) {
                posts.earliestRetry()?.let(scheduleRetry)
                afterRun()
            }
        }
    }

    /** The posts due at [now] and the posts already sending, in queue order, as the database has them now. */
    private suspend fun dueNow(now: Instant, zone: ZoneId): List<Post> {
        val queue = posts.observeQueue().first()
        val refs = queue.mapNotNull { it.toQueuedRef() }
        val due = dueOrder(refs, zone, now).toSet()
        // dueOrder leaves out posts that have started: a retry or a thread cut short resumes here (R30).
        val resumed = refs.filter { it.started && !it.held && !it.failed }.map { it.id }.toSet()
        val byId = queue.associateBy { it.id }
        return inQueueOrder(refs.filter { it.id in due || it.id in resumed }, zone).mapNotNull { byId[it.id] }
    }

    /**
     * The Cancel action of the conversion notification (media.md A7): stops the conversion for [postId];
     * the post goes back to the drafts with its files.
     */
    fun cancelPreparation(postId: Long) {
        synchronized(lock) {
            val current = preparing ?: return
            if (current.first != postId) return
            cancelledPost = postId
            current.second.cancel()
        }
    }

    /** R5: [accountId] signed in again; its deliveries stopped for sign-in wait again and sending starts. */
    suspend fun onSignedIn(accountId: Long) {
        if (posts.resumeAfterSignIn(accountId).isNotEmpty()) trigger.publishNow()
    }

    /** Retry of one failed delivery (Queue, Post details): it waits again and sending starts. */
    suspend fun retryDelivery(deliveryId: Long): Boolean =
        posts.retryDelivery(deliveryId).also { if (it) trigger.publishNow() }

    /** Retry of every failed delivery of [postId]; returns how many wait again. */
    suspend fun retryPost(postId: Long): Int =
        posts.retryFailed(postId).also { if (it > 0) trigger.publishNow() }

    private suspend fun publishPost(post: Post, foreground: SendForeground) {
        val settings = settings()
        val now = clock.instant()
        val targets = post.targets.mapNotNull { accounts.get(it) }
        for (delivery in post.deliveries) {
            if (delivery.state != DeliveryState.WAITING) continue
            if (delivery.nextAttemptAt?.isAfter(now) == true) continue
            val account = accounts.get(delivery.accountId) ?: continue
            val executor = executors[account.network] ?: continue
            val previous: HistoryItem? = if (post.kind == PostKind.NEW) {
                null
            } else {
                val item = post.historyId?.let { history(it) }?.items?.firstOrNull { it.accountId == account.id }
                if (item == null) {
                    // R37: without the record the app has no ids to act on.
                    if (posts.failUnstarted(delivery.id, NO_RECORD)) notifier.deliveryFailed(account, post.id, NO_RECORD)
                    continue
                }
                item
            }
            val plan = delivery.plan ?: if (post.kind == PostKind.DELETE) {
                DeliveryPlan(parts = emptyList())
            } else {
                when (val frozen = freezer.freeze(post, account, targets.filter { it.network == account.network }, settings)) {
                    is FreezeResult.Ready -> frozen.plan
                    is FreezeResult.Blocked -> {
                        if (posts.failUnstarted(delivery.id, frozen.message)) notifier.deliveryFailed(account, post.id, frozen.message)
                        continue
                    }
                }
            }
            val started = posts.startDelivery(delivery.id, plan) ?: continue
            val outcome = try {
                attempt(post, account, started, started.plan ?: plan, executor, foreground, previous)
            } catch (_: PreparationCancelled) {
                if (!posts.returnToDraft(post.id)) posts.deliveryFailed(started.id, CANCELLED, null)
                return
            }
            apply(post, account, started, outcome)
        }
        val fresh = posts.get(post.id) ?: return
        if (fresh.deliveries.isNotEmpty() && fresh.deliveries.all { it.state == DeliveryState.SENT }) complete(fresh, settings)
    }

    private suspend fun attempt(
        post: Post,
        account: Account,
        delivery: Delivery,
        plan: DeliveryPlan,
        executor: DeliveryExecutor,
        foreground: SendForeground,
        previous: HistoryItem?,
    ): DeliveryOutcome {
        val job = DeliveryJob(
            post = post,
            account = account,
            delivery = delivery,
            plan = plan,
            progress = delivery.progress,
            attempt = delivery.attempts,
            files = { prepareFiles(post, account.network, plan, foreground) },
            saveProgress = { posts.saveProgress(delivery.id, it) },
            previous = previous,
        )
        return try {
            executor.publish(job)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PreparationCancelled) {
            throw e
        } catch (e: Exception) {
            // Anything the executor did not classify is tried again later rather than lost.
            Log.w(TAG, "delivery ${delivery.id} failed unexpectedly: ${e.javaClass.simpleName}")
            DeliveryOutcome.Retry(clock.instant().plus(MastodonExecutor.backoff(delivery.attempts)), e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun apply(post: Post, account: Account, delivery: Delivery, outcome: DeliveryOutcome) {
        when (outcome) {
            is DeliveryOutcome.Sent -> posts.deliverySucceeded(delivery.id, outcome.progress)
            is DeliveryOutcome.Retry -> posts.deliveryFailed(delivery.id, outcome.message, outcome.at)
            is DeliveryOutcome.Failed -> {
                posts.deliveryFailed(delivery.id, outcome.message, null)
                notifier.deliveryFailed(account, post.id, outcome.message)
            }
            DeliveryOutcome.NeedsSignIn -> {
                posts.deliveryFailed(delivery.id, PostsRepository.NEEDS_SIGN_IN, null)
                notifier.needsSignIn(account)
            }
        }
    }

    /**
     * R28, R31, R38: the post is sent; history keeps per account and part the ids, urls, media and text. An
     * edit replaces the copies of its targets and keeps the others (R40: a cleared account keeps its copy); a
     * deletion leaves only the copies of the accounts it did not touch (R39).
     */
    private suspend fun complete(post: Post, settings: Settings) {
        val written = post.deliveries.mapNotNull { delivery ->
            val account = accounts.get(delivery.accountId) ?: return@mapNotNull null
            val plan = delivery.plan
            HistoryItem(
                accountId = account.id,
                network = account.network,
                server = account.server,
                handle = account.handle,
                parts = delivery.progress.parts.sortedBy { it.index }.mapNotNull { progress ->
                    val planned = plan?.parts?.getOrNull(progress.index)
                    progress.published?.copy(text = planned?.text, spoilerText = planned?.spoilerText)
                },
                mastodon = plan?.mastodon,
            )
        }
        val record = post.historyId?.let { history(it) }
        val items = when (post.kind) {
            PostKind.NEW -> written
            PostKind.EDIT -> {
                val edited = written.associateBy { it.accountId }
                val kept = record?.items.orEmpty().map { edited[it.accountId] ?: it }
                kept + written.filter { item -> kept.none { it.accountId == item.accountId } }
            }
            PostKind.DELETE -> {
                val deleted = post.deliveries.map { it.accountId }.toSet()
                record?.items?.filter { it.accountId !in deleted }.orEmpty()
            }
        }
        posts.markSent(post.id, items)
        preparer.deleteVariants(post.id)
        if (settings.successNotifications && post.kind == PostKind.NEW) notifier.posted(post.id, post.deliveries.size)
    }

    /** Prepares the variants of [plan] (media.md A4); a video conversion shows its progress and can be cancelled. */
    private suspend fun prepareFiles(post: Post, network: Network, plan: DeliveryPlan, foreground: SendForeground): Map<Long, UploadFile> {
        val requests = plan.conversions.map { (id, conversion) -> VariantRequest(id, network.name, conversion) }
        if (requests.isEmpty()) return emptyMap()
        val transcodes = requests.any { it.conversion is Conversion.TranscodeVideo }
        val prepared = coroutineScope {
            val percent = MutableStateFlow(0)
            val shown = if (transcodes) {
                launch {
                    var last = -1
                    while (isActive) {
                        val value = percent.value
                        if (value != last) {
                            showForeground(foreground, SendProgress(transcodingPostId = post.id, percent = value))
                            last = value
                        }
                        delay(PROGRESS_INTERVAL_MS)
                    }
                }
            } else {
                null
            }
            val work = async { preparer.prepare(post.id, requests) { percent.value = it } }
            synchronized(lock) { preparing = post.id to work }
            try {
                work.await()
            } catch (e: CancellationException) {
                val byUser = synchronized(lock) { cancelledPost == post.id }
                if (byUser && currentCoroutineContext().isActive) {
                    shown?.cancel()
                    throw PreparationCancelled()
                }
                throw e
            } finally {
                shown?.cancel()
                synchronized(lock) {
                    preparing = null
                    cancelledPost = null
                }
            }
        }
        if (transcodes) showForeground(foreground, SendProgress())
        return prepared.associate { media ->
            val file = files.resolve(media.path) ?: throw FileNotFoundException("attachment ${media.attachmentId} is outside the files directory")
            media.attachmentId to UploadFile(file, media.mime)
        }
    }

    private suspend fun showForeground(foreground: SendForeground, progress: SendProgress) {
        try {
            foreground.show(progress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // M2: the foreground may be refused (background start limits); the run goes on without it.
            Log.w(TAG, "cannot show the sending progress: ${e.javaClass.simpleName}")
        }
    }

    private fun Post.hasLargeMedia(): Boolean =
        attachments.any { it.kind == MediaKind.VIDEO || it.kind == MediaKind.AUDIO || it.kind == MediaKind.GIF || it.sizeBytes >= LARGE_FILE_BYTES }

    /** The user cancelled the conversion of the post's media (A7). */
    private class PreparationCancelled : Exception()

    companion object {
        private const val TAG = "PostSender"

        /** The error of an edit or a deletion whose history record is gone (R37). */
        const val NO_RECORD = "the published post is no longer in history"

        /** The error of a delivery whose preparation was cancelled after part of the post was published. */
        const val CANCELLED = "cancelled"

        /** A file of this size or more makes the run go to the foreground (R32). */
        const val LARGE_FILE_BYTES = 8L * 1024 * 1024

        private const val PROGRESS_INTERVAL_MS = 1000L
    }
}
