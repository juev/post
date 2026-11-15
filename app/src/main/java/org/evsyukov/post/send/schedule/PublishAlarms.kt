package org.evsyukov.post.send.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.evsyukov.post.data.posts.PostsRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The one alarm of the sender (scheduling.md M1): set for the earliest pending time of the queue with
 * `setExactAndAllowWhileIdle`, which fires in Doze, under `USE_EXACT_ALARM`. Should the system still refuse
 * exact alarms, it falls back to `setAndAllowWhileIdle` together with a delayed WorkManager run, and remembers
 * which way it went. [refresh] runs on every change of the queue and after every run of the sender.
 */
class PublishAlarms(
    context: Context,
    private val posts: PostsRepository,
    private val workManager: WorkManager,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    /** How the alarm was set. */
    enum class Path { EXACT, INEXACT, NONE }

    /** What [refresh] did last: the path and the time, null when nothing waits. */
    data class State(val path: Path, val at: Instant?)

    private val context = context.applicationContext
    private val alarmManager = this.context.getSystemService(AlarmManager::class.java)
    private val prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    /** The last state set, kept on disk for diagnostics. */
    val state: State?
        get() {
            val path = prefs.getString(KEY_PATH, null)?.let { runCatching { Path.valueOf(it) }.getOrNull() } ?: return null
            val at = prefs.getLong(KEY_AT, -1).takeIf { it >= 0 }?.let(Instant::ofEpochMilli)
            return State(path, at)
        }

    /** Sets the alarm for the earliest pending time of the queue, or cancels it when nothing waits. */
    suspend fun refresh(): State = mutex.withLock {
        val now = clock.instant()
        val at = nextWake(posts.observeQueue().first(), zone(), now)
        val state = if (at == null) {
            alarmManager.cancel(alarmIntent())
            workManager.cancelUniqueWork(FALLBACK)
            State(Path.NONE, null)
        } else {
            State(set(at, now), at)
        }
        prefs.edit().putString(KEY_PATH, state.path.name).putLong(KEY_AT, state.at?.toEpochMilli() ?: -1).apply()
        state
    }

    private fun set(at: Instant, now: Instant): Path {
        val millis = at.toEpochMilli()
        if (alarmManager.canScheduleExactAlarms()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, alarmIntent())
                workManager.cancelUniqueWork(FALLBACK)
                return Path.EXACT
            } catch (e: SecurityException) {
                // The permission went away between the check and the call; the fallback below covers it.
                Log.w(TAG, "exact alarm refused: ${e.javaClass.simpleName}")
            }
        }
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, alarmIntent())
        val request = OneTimeWorkRequestBuilder<AlarmFallbackWorker>()
            .setInitialDelay(Duration.between(now, at).coerceAtLeast(Duration.ZERO))
            .build()
        workManager.enqueueUniqueWork(FALLBACK, ExistingWorkPolicy.REPLACE, request)
        return Path.INEXACT
    }

    private fun alarmIntent(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, PublishAlarmReceiver::class.java).setAction(ACTION_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_ALARM = "org.evsyukov.post.action.PUBLISH_ALARM"

        /** The delayed run that stands in for an exact alarm the system refused. */
        const val FALLBACK = "publish-alarm-fallback"
        private const val PREFS = "publish-alarm"
        private const val KEY_PATH = "path"
        private const val KEY_AT = "at"
        private const val REQUEST_CODE = 1
        private const val TAG = "PostAlarm"
    }
}

/** The alarm went off (M2): it starts the expedited publish job, which sends what is due and sets the next alarm. */
class PublishAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val host = context.applicationContext as? SchedulingHost ?: return
        runAsync(host) { host.publishScheduler.enqueue() }
    }
}

/** The WorkManager half of the fallback path: like the alarm, it only starts the publish job. */
class AlarmFallbackWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? SchedulingHost ?: return Result.failure()
        host.publishScheduler.enqueue()
        return Result.success()
    }
}

/** Runs [block] in the app's scope, holding the broadcast open with `goAsync` while it works. */
internal fun BroadcastReceiver.runAsync(host: SchedulingHost, block: suspend () -> Unit) {
    // goAsync gives null when the receiver is called directly rather than by the system.
    val pending: BroadcastReceiver.PendingResult? = goAsync()
    host.launchInBackground {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("PostAlarm", "background work of a receiver failed", e)
        } finally {
            pending?.finish()
        }
    }
}
