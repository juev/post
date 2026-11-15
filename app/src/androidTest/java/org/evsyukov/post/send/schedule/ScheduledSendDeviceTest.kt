package org.evsyukov.post.send.schedule

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.evsyukov.post.PostApplication
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.data.secrets.MastodonSecret
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Scheduled sending on the device, with the real app container and MockWebServer as a Mastodon server
 * (scheduling.md M1–M3, Q4–Q6 and the verification scenarios): a post sent on time in Doze, the alarm restored
 * by the boot receiver, and a late post held with its notification.
 */
@RunWith(AndroidJUnit4::class)
class ScheduledSendDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<PostApplication>()
    private val container = app.container
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val server = MockWebServer()
    private val statuses = CopyOnWriteArrayList<Pair<Instant, String>>()
    private var accountId = 0L
    private val posts = mutableListOf<Long>()

    @Before
    fun setUp() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "POST" && request.url.encodedPath == "/api/v1/statuses") {
                    val id = "s${statuses.size + 1}"
                    statuses += Instant.now() to request.body?.utf8().orEmpty()
                    return json("""{"id":"$id","uri":"http://localhost/$id","url":"http://localhost/@e2e/$id","created_at":"${Instant.now()}","content":"","visibility":"public"}""")
                }
                return MockResponse.Builder().code(404).body("""{"error":"not found"}""").build()
            }
        }
        server.start()
        accountId = container.accounts.add(
            NewAccount(Network.MASTODON, "http://localhost:${server.port}", "sched@localhost", "sched-${System.nanoTime()}", "Sched", null),
            MastodonSecret("sched-token"),
        )
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() = runBlocking {
        shell("dumpsys deviceidle unforce")
        shell("dumpsys battery reset")
        for (id in posts) container.posts.delete(id)
        container.history.observe().first()
            .filter { record -> record.items.any { it.accountId == accountId } }
            .forEach { container.history.delete(it.id) }
        container.accounts.remove(accountId)
        container.lateNotifier.cancelLate()
        server.close()
    }

    private fun shell(command: String): String {
        val fd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }
    }

    private suspend fun queue(text: String, at: Schedule): Long =
        container.posts.saveAndQueue(DraftInput(text = text, targets = listOf(accountId)), at).also { posts += it }

    private fun ourAlarm(): Boolean = alarmLines().any { "PUBLISH_ALARM" in it }

    /**
     * The app's lines among the pending alarms of `dumpsys alarm`. The dump goes on with a removal history that
     * still names cancelled alarms, so it is cut there.
     */
    private fun alarmLines(): List<String> = shell("dumpsys alarm")
        .substringBefore("Pending user blocked background alarms")
        .substringBefore("Removal history")
        .lineSequence().filter { "org.evsyukov.post" in it }.toList()

    private suspend fun waitForAlarm(present: Boolean) {
        try {
            withTimeout(10_000) { while (ourAlarm() != present) delay(200) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("alarm present=${!present}, expected $present; dumpsys alarm lines of the app: ${alarmLines()}", e)
        }
    }

    @Test
    fun aPostTwoMinutesAheadIsSentWithinAMinuteOfItsTimeInDoze() = runBlocking<Unit> {
        // The database keeps milliseconds.
        val due = Instant.ofEpochMilli(Instant.now().plusSeconds(120).toEpochMilli())
        val id = queue("on time in doze", Schedule.Explicit(due))
        // M1: the queue hook sets the exact alarm; USE_EXACT_ALARM is granted at install.
        try {
            withTimeout(10_000) { while (container.publishAlarms.state?.at != due) delay(100) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("alarm state ${container.publishAlarms.state}, expected at $due", e)
        }
        assertThat(container.publishAlarms.state!!.path).isEqualTo(PublishAlarms.Path.EXACT)
        assertThat(ourAlarm()).isTrue()

        shell("dumpsys battery unplug")
        val idle = shell("dumpsys deviceidle force-idle")
        assertThat(idle).contains("Now forced in to deep idle mode")

        withTimeout(Duration.ofMinutes(4).toMillis()) {
            while (container.posts.get(id)?.state != PostState.SENT) delay(500)
        }
        val sentAt = statuses.single { "on time in doze" in it.second }.first
        assertThat(Duration.between(due, sentAt)).isAtMost(Duration.ofMinutes(1))
        assertThat(sentAt).isAtLeast(due.minusSeconds(1))
    }

    @Test
    fun theBootReceiverRestoresALostAlarm() = runBlocking<Unit> {
        val due = Instant.ofEpochMilli(Instant.now().plus(Duration.ofHours(1)).toEpochMilli())
        queue("an hour ahead", Schedule.Explicit(due))
        waitForAlarm(present = true)

        // A reboot loses alarms: cancel ours the way the system drops it. The app's own queue hook and a run of
        // the job started with the process may set it again meanwhile, so it is cancelled until it stays gone.
        val alarmManager = app.getSystemService(android.app.AlarmManager::class.java)
        val alarm = android.app.PendingIntent.getBroadcast(
            app, 1,
            Intent(app, PublishAlarmReceiver::class.java).setAction(PublishAlarms.ACTION_ALARM),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        withTimeout(30_000) {
            while (true) {
                alarmManager.cancel(alarm)
                delay(500)
                if (!ourAlarm()) break
            }
        }

        // Only the system may send BOOT_COMPLETED; the receiver is called as the system would call it.
        ScheduleRestoreReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        waitForAlarm(present = true)
        assertThat(container.publishAlarms.state!!.at).isEqualTo(due)
    }

    @Test
    fun aPostQueuedInThePastIsHeldNotSentAndTheNotificationSendsItOnSendNow() = runBlocking<Unit> {
        val id = queue("missed by two hours", Schedule.Explicit(Instant.now().minus(Duration.ofHours(2))))

        container.publishScheduler.enqueue()

        withTimeout(30_000) { while (container.posts.get(id)?.state != PostState.HELD) delay(200) }
        assertThat(statuses).isEmpty()
        val manager = app.getSystemService(NotificationManager::class.java)
        val late = withTimeout(10_000) {
            var found: Notification? = null
            while (found == null) {
                found = manager.activeNotifications.firstOrNull { it.id == AndroidLateNotifier.LATE_ID }?.notification
                if (found == null) delay(100)
            }
            found!!
        }
        assertThat(late.extras.getString(Notification.EXTRA_TITLE)).isEqualTo("1 post missed its time")
        // No schedule: Send now and Review only.
        assertThat(late.actions.map { it.title.toString() }).containsExactly("Send now", "Review").inOrder()

        late.actions[0].actionIntent.send()

        withTimeout(60_000) { while (container.posts.get(id)?.state != PostState.SENT) delay(200) }
        assertThat(statuses.map { it.second }.single()).contains("missed by two hours")
        withTimeout(10_000) {
            while (manager.activeNotifications.any { it.id == AndroidLateNotifier.LATE_ID }) delay(100)
        }
    }

    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()
}
