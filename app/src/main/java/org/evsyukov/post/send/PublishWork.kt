package org.evsyukov.post.send

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.evsyukov.post.PrepareTrigger
import org.evsyukov.post.PublishTrigger
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Starts publishing through WorkManager (scheduling.md M2): the unique work [PUBLISH] with a `CONNECTED`
 * constraint, expedited, or ordinary work when the expedited quota is used up. A run already waiting will
 * see a post saved now, so nothing is added then; a run in progress may have read the queue before the post
 * was saved, so exactly one successor is appended to it, as ShareDing does.
 */
class PublishScheduler(
    private val workManager: WorkManager,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
) : PublishTrigger {
    private val scheduling = Mutex()

    override fun publishNow() {
        scope.launch { enqueue() }
    }

    /** Enqueues a run of the sender unless one is already waiting. */
    suspend fun enqueue() {
        try {
            scheduling.withLock {
                withContext(Dispatchers.IO) {
                    val work = workManager.getWorkInfosForUniqueWork(PUBLISH).get()
                    val waiting = work.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
                    if (!waiting) {
                        workManager.enqueueUniqueWork(PUBLISH, ExistingWorkPolicy.APPEND_OR_REPLACE, publishRequest()).result.get()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "cannot schedule publishing", e)
        }
    }

    /**
     * Wakes the sender at [at], the earliest retry of a waiting delivery (R29, M6). A later call replaces an
     * earlier one: each run computes the earliest retry again.
     */
    fun scheduleRetry(at: Instant) {
        val delay = Duration.between(clock.instant(), at).coerceAtLeast(Duration.ZERO)
        val request = OneTimeWorkRequestBuilder<PublishRetryWorker>()
            .setConstraints(connected)
            .setInitialDelay(delay)
            .build()
        workManager.enqueueUniqueWork(RETRY, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        const val PUBLISH = "publish"
        const val RETRY = "publish-retry"
        private const val TAG = "PostSender"

        private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun publishRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<PublishWorker>()
            .setConstraints(connected)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
    }
}

/**
 * Prepares the media of queued posts (media.md A4) as soon as a post is queued: the unique work [PREPARE],
 * expedited, or ordinary work when the expedited quota is used up. It needs no network: converting a file
 * is local. A run already waiting will see a post queued now, so nothing is added then; a run in progress
 * may have read the queue before, so exactly one successor is appended to it, as [PublishScheduler] does.
 */
class PrepareScheduler(
    private val workManager: WorkManager,
    private val scope: CoroutineScope,
) : PrepareTrigger {
    private val scheduling = Mutex()

    override fun prepareNow() {
        scope.launch { enqueue() }
    }

    /** Enqueues a run of the preparation unless one is already waiting. */
    suspend fun enqueue() {
        try {
            scheduling.withLock {
                withContext(Dispatchers.IO) {
                    val work = workManager.getWorkInfosForUniqueWork(PREPARE).get()
                    val waiting = work.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
                    if (!waiting) {
                        workManager.enqueueUniqueWork(PREPARE, ExistingWorkPolicy.APPEND_OR_REPLACE, prepareRequest()).result.get()
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "cannot schedule the preparation of media", e)
        }
    }

    companion object {
        const val PREPARE = "prepare-media"
        private const val TAG = "PostSender"

        fun prepareRequest(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<PrepareWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
    }
}

/** One run of the [Publisher]'s preparation. Converting video shows progress and can be cancelled (A7). */
class PrepareWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? PublisherHost ?: return Result.failure()
        val notifier = AndroidSendNotifier(applicationContext)
        host.publisher.prepareQueued { progress -> setForeground(foregroundInfo(notifier, progress)) }
        return Result.success()
    }

    /** Expedited work on older systems asks for this before it runs; the run itself sets its own. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(AndroidSendNotifier(applicationContext), SendProgress(preparing = true))
}

/** One run of the [Publisher]. A run that uploads video or large files goes to the foreground (R32, M2). */
class PublishWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? PublisherHost ?: return Result.failure()
        val notifier = AndroidSendNotifier(applicationContext)
        host.publisher.run { progress -> setForeground(foregroundInfo(notifier, progress)) }
        return Result.success()
    }

    /** Expedited work on older systems asks for this before it runs; the run itself sets its own. */
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(AndroidSendNotifier(applicationContext), SendProgress())
}

/** The foreground notification of a worker that converts or uploads media (R32, M2, A7). */
private fun foregroundInfo(notifier: AndroidSendNotifier, progress: SendProgress): ForegroundInfo {
    notifier.createChannels()
    val notification = notifier.progressNotification(progress)
    // plan.md: dataSync on API 33–34; mediaProcessing on 35+ while a video is converted.
    val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && progress.transcodingPostId != null) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
    } else {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    }
    return ForegroundInfo(AndroidSendNotifier.PROGRESS_ID, notification, type)
}

/** Wakes the sender at the earliest retry (R29): it only starts a run of [PublishWorker]. */
class PublishRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? PublisherHost ?: return Result.failure()
        host.publishScheduler.enqueue()
        return Result.success()
    }
}
