package org.evsyukov.post.send.schedule

import android.app.AlarmManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.MainActivity
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.send.AndroidSendNotifier
import org.evsyukov.post.send.PublishScheduler
import org.evsyukov.post.send.blocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/** The application of these tests hands receivers and workers the scheduling objects, as PostApplication does. */
class SchedulingTestApp : Application(), SchedulingHost {
    override lateinit var publishScheduler: PublishScheduler
    override lateinit var scheduleRestorer: ScheduleRestorer
    override lateinit var queueActions: QueueActions

    // Receivers return only after the work is done, so a test can look at its effect right away.
    override fun launchInBackground(block: suspend () -> Unit) = runBlocking { block() }
}

/** The exact alarm and its fallback (scheduling.md M1, M2), the receivers (M3) and the periodic check (M4). */
@RunWith(AndroidJUnit4::class)
@Config(application = SchedulingTestApp::class)
class PublishAlarmsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context as SchedulingTestApp
    private lateinit var f: SchedulingFixture
    private lateinit var workManager: WorkManager
    private lateinit var alarms: PublishAlarms
    private var zone: ZoneId = ZoneOffset.UTC
    private var alice = 0L
    private val alarmManager get() = context.getSystemService(AlarmManager::class.java)
    private val shadowAlarms get() = shadowOf(alarmManager)

    @Before
    fun setUp() {
        val config = Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        f = SchedulingFixture()
        alice = f.send.mastodon()
        alarms = PublishAlarms(context, f.send.posts, workManager, f.clock) { zone }
        app.publishScheduler = PublishScheduler(workManager, CoroutineScope(SupervisorJob() + Dispatchers.IO), f.clock)
        app.scheduleRestorer = ScheduleRestorer(f.send.posts, { alarms.refresh() }, { app.publishScheduler.enqueue() }, { zone })
        app.queueActions = f.actions
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() {
        f.close()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    private fun publishWork(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(PublishScheduler.PUBLISH).get()

    @Test
    fun theExactAlarmIsAnAlarmClockForTheEarliestPendingTime() {
        f.queue("later", Schedule.Explicit(f.clock.now.plusSeconds(7200)), listOf(alice))
        f.queue("sooner", Schedule.Explicit(f.clock.now.plusSeconds(3600)), listOf(alice))

        val state = blocking { alarms.refresh() }

        assertThat(state).isEqualTo(PublishAlarms.State(PublishAlarms.Path.EXACT, f.clock.now.plusSeconds(3600)))
        val alarm = shadowAlarms.scheduledAlarms.single()
        val at = f.clock.now.plusSeconds(3600).toEpochMilli()
        assertThat(alarm.type).isEqualTo(AlarmManager.RTC_WAKEUP)
        assertThat(alarm.triggerAtMs).isEqualTo(at)
        assertThat(alarm.windowLengthMs).isEqualTo(ShadowAlarmManager.WINDOW_EXACT)
        assertThat(shadowOf(alarm.operation).savedIntent.component!!.className).isEqualTo(PublishAlarmReceiver::class.java.name)
        // M7: an alarm clock, which the firmware of some phones does not delay as it does other exact alarms.
        assertThat(alarm.alarmClockInfo!!.triggerTime).isEqualTo(at)
        assertThat(alarmManager.nextAlarmClock!!.triggerTime).isEqualTo(at)
        assertThat(alarms.state).isEqualTo(state)
    }

    @Test
    fun aTapOnTheTimeOfTheAlarmClockOpensTheQueue() {
        f.queue("post", Schedule.Explicit(f.clock.now.plusSeconds(3600)), listOf(alice))
        blocking { alarms.refresh() }

        val show = shadowOf(alarmManager.nextAlarmClock!!.showIntent)

        assertThat(show.isActivity).isTrue()
        assertThat(show.savedIntent.component!!.className).isEqualTo(MainActivity::class.java.name)
        assertThat(show.savedIntent.getStringExtra(AndroidSendNotifier.EXTRA_OPEN)).isEqualTo(AndroidSendNotifier.OPEN_QUEUE)
    }

    @Test
    fun aQueueChangeMovesTheAlarmAndAnEmptyQueueCancelsIt() {
        val later = f.queue("later", Schedule.Explicit(f.clock.now.plusSeconds(7200)), listOf(alice))
        blocking { alarms.refresh() }
        val sooner = f.queue("sooner", Schedule.Explicit(f.clock.now.plusSeconds(600)), listOf(alice))

        blocking { alarms.refresh() }
        assertThat(shadowAlarms.scheduledAlarms.single().triggerAtMs).isEqualTo(f.clock.now.plusSeconds(600).toEpochMilli())

        blocking { f.send.posts.delete(sooner) }
        blocking { f.send.posts.delete(later) }
        val state = blocking { alarms.refresh() }

        assertThat(state.path).isEqualTo(PublishAlarms.Path.NONE)
        assertThat(shadowAlarms.scheduledAlarms).isEmpty()
        assertThat(alarmManager.nextAlarmClock).isNull()
    }

    @Test
    fun aSlotPostWakesAtItsLocalTimeInTheCurrentZone() {
        f.slot("slot", "2026-11-16T15:00", listOf(alice))
        zone = ZoneId.of("Asia/Tokyo")
        // 15:00 in Tokyo is 06:00 UTC, before now: nothing to wake for.
        assertThat(blocking { alarms.refresh() }.path).isEqualTo(PublishAlarms.Path.NONE)

        zone = ZoneId.of("America/New_York")
        val state = blocking { alarms.refresh() }

        assertThat(state.at).isEqualTo(Instant.parse("2026-11-16T20:00:00Z"))
    }

    @Test
    fun withoutExactAlarmsItFallsBackToAnInexactAlarmAndADelayedRun() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        f.queue("post", Schedule.Explicit(f.clock.now.plusSeconds(1800)), listOf(alice))

        val state = blocking { alarms.refresh() }

        assertThat(state.path).isEqualTo(PublishAlarms.Path.INEXACT)
        assertThat(alarms.state!!.path).isEqualTo(PublishAlarms.Path.INEXACT)
        val alarm = shadowAlarms.scheduledAlarms.single()
        assertThat(alarm.allowWhileIdle).isTrue()
        assertThat(alarm.windowLengthMs).isNotEqualTo(ShadowAlarmManager.WINDOW_EXACT)
        assertThat(alarm.alarmClockInfo).isNull()
        val fallback = workManager.getWorkInfosForUniqueWork(PublishAlarms.FALLBACK).get().single()
        assertThat(fallback.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(fallback.initialDelayMillis).isEqualTo(Duration.ofMinutes(30).toMillis())

        // Exact alarms allowed again: the next refresh takes the exact path and drops the delayed run.
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertThat(blocking { alarms.refresh() }.path).isEqualTo(PublishAlarms.Path.EXACT)
        assertThat(workManager.getWorkInfosForUniqueWork(PublishAlarms.FALLBACK).get().single().state).isEqualTo(WorkInfo.State.CANCELLED)
        // The alarm clock replaces the inexact alarm: they share the operation.
        assertThat(shadowAlarms.scheduledAlarms.single().alarmClockInfo).isNotNull()
    }

    @Test
    fun theAlarmStartsTheExpeditedPublishJob() {
        f.queue("post", Schedule.Explicit(f.clock.now.plusSeconds(60)), listOf(alice))
        blocking { alarms.refresh() }

        shadowAlarms.fireAlarm(shadowAlarms.scheduledAlarms.single())
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val work = publishWork().single()
        assertThat(work.state).isEqualTo(WorkInfo.State.ENQUEUED)
    }

    @Test
    fun bootSetsTheAlarmAgainAndRunsTheJob() {
        f.queue("post", Schedule.Explicit(f.clock.now.plusSeconds(3600)), listOf(alice))
        assertThat(shadowAlarms.scheduledAlarms).isEmpty()

        ScheduleRestoreReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertThat(shadowAlarms.scheduledAlarms.single().triggerAtMs).isEqualTo(f.clock.now.plusSeconds(3600).toEpochMilli())
        assertThat(publishWork().single().state).isEqualTo(WorkInfo.State.ENQUEUED)
    }

    @Test
    fun aTimeZoneChangeResolvesSlotsAgainInTheNewZone() {
        val id = f.slot("slot", "2026-11-16T15:00", listOf(alice))
        assertThat(f.post(id).sendAt).isEqualTo(Instant.parse("2026-11-16T15:00:00Z"))
        zone = ZoneId.of("Europe/Moscow")

        ScheduleRestoreReceiver().onReceive(context, Intent(Intent.ACTION_TIMEZONE_CHANGED))

        // S2: the post follows the device; 15:00 in Moscow is 12:00 UTC.
        assertThat(f.post(id).sendAt).isEqualTo(Instant.parse("2026-11-16T12:00:00Z"))
        assertThat(shadowAlarms.scheduledAlarms.single().triggerAtMs).isEqualTo(Instant.parse("2026-11-16T12:00:00Z").toEpochMilli())
    }

    @Test
    fun timeChangeAndAppUpdateRestoreTooAndOtherBroadcastsDoNothing() {
        f.queue("post", Schedule.Explicit(f.clock.now.plusSeconds(3600)), listOf(alice))

        ScheduleRestoreReceiver().onReceive(context, Intent(Intent.ACTION_SCREEN_ON))
        assertThat(shadowAlarms.scheduledAlarms).isEmpty()

        for (action in listOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            ShadowAlarmManager.reset()
            ScheduleRestoreReceiver().onReceive(context, Intent(action))
            assertThat(shadowAlarms.scheduledAlarms).hasSize(1)
        }
    }

    @Test
    fun theManifestRoutesTheSystemBroadcastsToTheRestoreReceiverWithoutExportingIt() {
        val pm = context.packageManager
        for (action in ScheduleRestoreReceiver.ACTIONS) {
            val receivers = pm.queryBroadcastReceivers(Intent(action).setPackage(context.packageName), 0)
            assertThat(receivers.map { it.activityInfo.name }).contains(ScheduleRestoreReceiver::class.java.name)
        }
        for (receiver in listOf(ScheduleRestoreReceiver::class.java, PublishAlarmReceiver::class.java, LateActionReceiver::class.java)) {
            val info = pm.getReceiverInfo(ComponentName(context, receiver), PackageManager.ComponentInfoFlags.of(0))
            assertThat(info.exported).isFalse()
        }
    }

    @Test
    fun thePeriodicCheckRunsEveryThirtyMinutesAndRestoresTheAlarm() {
        ScheduleCheckWorker.schedule(workManager)
        ScheduleCheckWorker.schedule(workManager)
        val periodic = workManager.getWorkInfosForUniqueWork(ScheduleCheckWorker.NAME).get().single()
        assertThat(periodic.periodicityInfo!!.repeatIntervalMillis).isEqualTo(TimeUnit.MINUTES.toMillis(30))

        f.queue("post", Schedule.Explicit(f.clock.now.plusSeconds(3600)), listOf(alice))
        val worker = TestListenableWorkerBuilder<ScheduleCheckWorker>(context).build()
        val result = runBlocking { worker.doWork() }

        assertThat(result).isEqualTo(ListenableWorker.Result.success())
        assertThat(shadowAlarms.scheduledAlarms).hasSize(1)
        assertThat(publishWork().single().state).isEqualTo(WorkInfo.State.ENQUEUED)
    }

    @Test
    fun aRunOfTheSenderSetsTheNextAlarm() {
        // M1: after a run the alarm moves to the next pending time; the sent post no longer counts.
        val server = org.evsyukov.post.send.FakeMastodon(f.send.servers) { f.clock.now }
        f.queue("now", Schedule.Now(f.clock.now), listOf(alice))
        f.queue("next", Schedule.Slot(LocalDateTime.parse("2026-11-16T10:00")), listOf(alice))
        val publisher = org.evsyukov.post.send.Publisher(
            posts = f.send.posts,
            accounts = f.send.accounts,
            settings = { f.send.settings },
            freezer = org.evsyukov.post.send.PlanFreezer(org.evsyukov.post.text.AndroidTextSegmenter()),
            executors = listOf(org.evsyukov.post.send.MastodonExecutor(f.send.servers.http, f.send.sessions, f.send.accounts, f.clock, sleep = {})),
            preparer = f.send.preparer,
            filesDir = f.send.filesDir,
            notifier = f.send.notifier,
            scheduleRetry = {},
            trigger = { },
            clock = f.clock,
            zone = { zone },
            afterRun = { alarms.refresh() },
        )

        blocking { publisher.run() }

        assertThat(server.statuses.map { it.text }).containsExactly("now")
        assertThat(shadowAlarms.scheduledAlarms.single().triggerAtMs).isEqualTo(Instant.parse("2026-11-16T10:00:00Z").toEpochMilli())
    }
}
