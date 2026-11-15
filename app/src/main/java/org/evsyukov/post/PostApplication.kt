package org.evsyukov.post

import android.app.Application
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.evsyukov.post.accounts.LimitsRefreshWorker
import org.evsyukov.post.accounts.LimitsRefresher
import org.evsyukov.post.accounts.LimitsRefresherHost
import org.evsyukov.post.send.PublishScheduler
import org.evsyukov.post.send.Publisher
import org.evsyukov.post.send.PublisherHost
import org.evsyukov.post.send.schedule.QueueActions
import org.evsyukov.post.send.schedule.ScheduleCheckWorker
import org.evsyukov.post.send.schedule.ScheduleRestorer
import org.evsyukov.post.send.schedule.SchedulingHost
import java.time.Instant

class PostApplication : Application(), LimitsRefresherHost, PublisherHost, SchedulingHost {
    lateinit var container: AppContainer
        private set

    override val limitsRefresher: LimitsRefresher get() = container.limitsRefresher

    override val publisher: Publisher get() = container.publisher

    override val publishScheduler: PublishScheduler get() = container.publishScheduler

    override val scheduleRestorer: ScheduleRestorer get() = container.scheduleRestorer

    override val queueActions: QueueActions get() = container.queueActions

    override fun launchInBackground(block: suspend () -> Unit) {
        container.scope.launch { block() }
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // N1: account limits are read once a day, and on start when they are older than a day.
        LimitsRefreshWorker.schedule(WorkManager.getInstance(this))
        // Posts due while the process was gone, and deliveries it left sending, go out now (R27, R30).
        container.publishTrigger.publishNow()
        // M1, M4: the alarm follows the queue, and a periodic check restores it.
        container.startQueueHooks()
        ScheduleCheckWorker.schedule(WorkManager.getInstance(this))
        // R31: records older than the retention setting go at start.
        container.scope.launch {
            try {
                container.history.purgeExpired(Instant.now())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The next start tries again.
            }
        }
    }
}
