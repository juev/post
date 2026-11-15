package org.evsyukov.post.send.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.evsyukov.post.data.posts.PostsRepository
import org.evsyukov.post.send.PublishScheduler
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** What the receivers and workers of scheduling need from the application. */
interface SchedulingHost {
    val publishScheduler: PublishScheduler
    val scheduleRestorer: ScheduleRestorer
    val queueActions: QueueActions

    /** Runs [block] in the application's scope; it outlives a receiver. */
    fun launchInBackground(block: suspend () -> Unit)
}

/**
 * Puts the schedule back after the device lost or changed it (scheduling.md M3, M4): slots are resolved
 * in the current zone (S2), the alarm is set again, and the publish job runs once, which holds late posts
 * and asks about them (Q4–Q6) before it sends anything due.
 */
class ScheduleRestorer(
    private val posts: PostsRepository,
    private val refreshAlarm: suspend () -> Unit,
    private val runJob: suspend () -> Unit,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val housekeeping: suspend () -> Unit = {},
) {
    suspend fun restore() {
        posts.refreshSlotTimes(zone())
        refreshAlarm()
        runJob()
        housekeeping()
    }
}

/**
 * Boot, time change, time zone change and app update (M3). Not exported: these broadcasts come from the
 * system, which reaches receivers that are not exported.
 */
class ScheduleRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val host = context.applicationContext as? SchedulingHost ?: return
        runAsync(host) { host.scheduleRestorer.restore() }
    }

    companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}

/** The check every 30 minutes (M4): restores a missing alarm and runs the publish job, as [ScheduleRestorer] does. */
class ScheduleCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? SchedulingHost ?: return Result.failure()
        host.scheduleRestorer.restore()
        return Result.success()
    }

    companion object {
        const val NAME = "schedule-check"
        const val INTERVAL_MINUTES = 30L

        /** Keeps one periodic check; scheduling it again changes nothing. */
        fun schedule(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<ScheduleCheckWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.NONE)
                .build()
            workManager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
