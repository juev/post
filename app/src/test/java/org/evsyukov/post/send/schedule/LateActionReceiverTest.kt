package org.evsyukov.post.send.schedule

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.send.PublishScheduler
import org.evsyukov.post.send.blocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Duration

/** The Send now and Reschedule actions of the late notification reach the queue (Q6). */
@RunWith(AndroidJUnit4::class)
@Config(application = SchedulingTestApp::class)
class LateActionReceiverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context as SchedulingTestApp
    private lateinit var f: SchedulingFixture
    private var alice = 0L

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        f = SchedulingFixture()
        alice = f.send.mastodon()
        app.queueActions = f.actions
        app.publishScheduler = PublishScheduler(WorkManager.getInstance(context), CoroutineScope(SupervisorJob() + Dispatchers.IO), f.clock)
    }

    @After
    fun tearDown() = f.close()

    private fun holdTwo(): Pair<Long, Long> {
        val a = f.slot("A", "2026-11-16T09:00", listOf(alice))
        val b = f.slot("B", "2026-11-16T09:30", listOf(alice))
        f.clock.advance(Duration.ofHours(4))
        blocking { f.holder.holdLate(f.clock.now) }
        assertThat(f.post(a).state).isEqualTo(PostState.HELD)
        return a to b
    }

    @Test
    fun sendNowQueuesEveryHeldPostDueNow() {
        val (a, b) = holdTwo()

        LateActionReceiver().onReceive(context, Intent(LateActionReceiver.ACTION_SEND_NOW))

        for (id in listOf(a, b)) {
            assertThat(f.post(id).state).isEqualTo(PostState.QUEUED)
            assertThat(f.post(id).schedule).isInstanceOf(Schedule.Now::class.java)
        }
        assertThat(f.post(a).sendAt).isLessThan(f.post(b).sendAt)
        assertThat(f.triggers.get()).isEqualTo(1)
    }

    @Test
    fun rescheduleMovesThemToTheNextFreeSlots() {
        f.slots(DayOfWeek.MONDAY to listOf("09:00", "18:00", "19:00"))
        val (a, b) = holdTwo()

        LateActionReceiver().onReceive(context, Intent(LateActionReceiver.ACTION_RESCHEDULE))

        assertThat(f.post(a).schedule.toString()).contains("2026-11-16T18:00")
        assertThat(f.post(b).schedule.toString()).contains("2026-11-16T19:00")
    }
}
