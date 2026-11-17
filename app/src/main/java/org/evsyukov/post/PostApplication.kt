package org.evsyukov.post

import android.app.Application
import androidx.work.WorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.evsyukov.post.accounts.LimitsRefreshWorker
import org.evsyukov.post.accounts.LimitsRefresher
import org.evsyukov.post.accounts.LimitsRefresherHost
import org.evsyukov.post.data.posts.AttachmentSweepWorker
import org.evsyukov.post.data.posts.AttachmentSweeper
import org.evsyukov.post.data.posts.AttachmentSweeperHost
import org.evsyukov.post.send.PublishScheduler
import org.evsyukov.post.send.Publisher
import org.evsyukov.post.send.PublisherHost
import org.evsyukov.post.send.schedule.QueueActions
import org.evsyukov.post.send.schedule.ScheduleCheckWorker
import org.evsyukov.post.send.schedule.ScheduleRestorer
import org.evsyukov.post.send.schedule.SchedulingHost
import org.evsyukov.post.share.DirectShare
import java.time.Instant

class PostApplication : Application(), AppContainerHost, LimitsRefresherHost, PublisherHost, SchedulingHost, AttachmentSweeperHost {
    override lateinit var container: AppContainer
        private set

    override val attachmentSweeper: AttachmentSweeper get() = container.attachmentSweeper

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
        // A4: the media of posts queued before the process died is prepared before its time comes.
        container.prepareTrigger.prepareNow()
        // M1, M4: the alarm follows the queue, and a periodic check restores it.
        container.startQueueHooks()
        ScheduleCheckWorker.schedule(WorkManager.getInstance(this))
        // R23: the Direct Share shortcut, published again whenever the default targets change.
        DirectShare.follow(container)
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
        // media.md A1: copies and variants that nothing refers to go at start and once a day.
        container.scope.launch {
            try {
                container.attachmentSweeper.sweep()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The daily job or the next start tries again.
            }
        }
        AttachmentSweepWorker.schedule(WorkManager.getInstance(this))
    }
}
