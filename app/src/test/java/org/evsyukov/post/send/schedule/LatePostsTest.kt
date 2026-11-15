package org.evsyukov.post.send.schedule

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.schedule.LateThreshold
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.send.FakeMastodon
import org.evsyukov.post.send.blocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime

/** Late posts are never sent on their own (scheduling.md Q4–Q6, M6, verification scenarios). */
@RunWith(AndroidJUnit4::class)
class LatePostsTest {
    private val f = SchedulingFixture()
    private val server = FakeMastodon(f.send.servers) { f.clock.now }
    private val alice = f.send.mastodon()

    @After
    fun tearDown() = f.close()

    @Test
    fun aPostExactlyAtTheThresholdIsSentAndOneSecondLaterIsHeld() {
        val start = f.clock.now
        val due = f.queue("due", Schedule.Explicit(start), listOf(alice))
        f.clock.advance(Duration.ofMinutes(30))
        f.send.run()
        assertThat(f.post(due).state).isEqualTo(PostState.SENT)

        val late = f.queue("late", Schedule.Explicit(f.clock.now), listOf(alice))
        f.clock.advance(Duration.ofMinutes(30).plusSeconds(1))
        f.send.run()

        assertThat(f.post(late).state).isEqualTo(PostState.HELD)
        assertThat(server.statuses.map { it.text }).containsExactly("due")
    }

    @Test
    fun theNetworkBackAfterTenMinutesSendsAndAfterTwoHoursHolds() {
        // M6: the job waited for the network; the post is checked when it finally runs.
        val first = f.queue("ten minutes", Schedule.Explicit(f.clock.now), listOf(alice))
        f.clock.advance(Duration.ofMinutes(10))
        f.send.run()
        assertThat(f.post(first).state).isEqualTo(PostState.SENT)

        val second = f.queue("two hours", Schedule.Explicit(f.clock.now), listOf(alice))
        f.clock.advance(Duration.ofHours(2))
        f.send.run()
        assertThat(f.post(second).state).isEqualTo(PostState.HELD)
        assertThat(f.late.shown).containsExactly(1 to false)
        assertThat(server.statuses.map { it.text }).containsExactly("ten minutes")
    }

    @Test
    fun aThresholdOfThreeHoursSendsTwoHourLatePostsWithoutAsking() {
        f.send.settings = f.send.settings.copy(lateThreshold = LateThreshold.HOURS_3)
        val a = f.queue("a", Schedule.Explicit(f.clock.now), listOf(alice))
        val b = f.queue("b", Schedule.Explicit(f.clock.now.plusSeconds(60)), listOf(alice))
        f.clock.advance(Duration.ofHours(2).plusMinutes(5))

        f.send.run()

        assertThat(f.post(a).state).isEqualTo(PostState.SENT)
        assertThat(f.post(b).state).isEqualTo(PostState.SENT)
        assertThat(f.late.shown).isEmpty()
    }

    @Test
    fun thePhoneOffForTwoHoursHoldsBothPostsWithOneNotificationThenRescheduleKeepsTheirOrder() {
        f.slots(DayOfWeek.MONDAY to listOf("09:00", "13:00", "18:00"))
        // Two slot posts at 09:00 and 13:00; the phone comes back at 15:00.
        val a = f.slot("A", "2026-11-16T09:00", listOf(alice))
        val b = f.slot("B", "2026-11-16T13:00", listOf(alice))
        f.clock.now = java.time.Instant.parse("2026-11-16T15:00:00Z")

        f.send.run()

        assertThat(server.statuses).isEmpty()
        assertThat(f.post(a).state).isEqualTo(PostState.HELD)
        assertThat(f.post(b).state).isEqualTo(PostState.HELD)
        // One notification for both, and Reschedule is offered because there is a schedule.
        assertThat(f.late.shown.last()).isEqualTo(2 to true)

        assertThat(blocking { f.actions.rescheduleHeld() }).isEqualTo(2)
        assertThat(f.post(a).schedule).isEqualTo(Schedule.Slot(LocalDateTime.parse("2026-11-16T18:00")))
        assertThat(f.post(b).schedule).isEqualTo(Schedule.Slot(LocalDateTime.parse("2026-11-23T09:00")))
        assertThat(f.post(a).state).isEqualTo(PostState.QUEUED)
        assertThat(f.late.cancelled).isEqualTo(1)
    }

    @Test
    fun rescheduleGivesAnExplicitPostASlotAndSkipsSlotsTakenByWaitingPosts() {
        f.slots(DayOfWeek.MONDAY to listOf("16:00", "17:00", "18:00"))
        val explicit = f.queue("explicit", Schedule.Explicit(f.clock.now), listOf(alice))
        val waiting = f.slot("waiting", "2026-11-16T16:00", listOf(alice))
        f.clock.now = java.time.Instant.parse("2026-11-16T10:00:00Z")
        f.send.run()
        assertThat(f.post(explicit).state).isEqualTo(PostState.HELD)

        blocking { f.actions.rescheduleHeld() }

        assertThat(f.post(explicit).schedule).isEqualTo(Schedule.Slot(LocalDateTime.parse("2026-11-16T17:00")))
        assertThat(f.post(waiting).schedule).isEqualTo(Schedule.Slot(LocalDateTime.parse("2026-11-16T16:00")))
    }

    @Test
    fun sendNowSendsAllHeldPostsInQueueOrder() {
        // B was queued first but is due later: queue order is by time (Q8), so A goes first.
        val b = f.queue("B", Schedule.Explicit(f.clock.now.plusSeconds(600)), listOf(alice))
        val a = f.queue("A", Schedule.Explicit(f.clock.now), listOf(alice))
        f.clock.advance(Duration.ofHours(3))
        f.send.run()
        assertThat(f.post(a).state).isEqualTo(PostState.HELD)
        assertThat(f.post(b).state).isEqualTo(PostState.HELD)

        assertThat(blocking { f.actions.sendHeldNow() }).isEqualTo(2)
        assertThat(f.triggers.get()).isEqualTo(1)
        f.send.run()

        assertThat(server.statuses.map { it.text }).containsExactly("A", "B").inOrder()
        assertThat(f.late.cancelled).isEqualTo(1)
    }

    @Test
    fun rescheduleWithAnEmptyScheduleMovesNothingAndTheNotificationOffersNoReschedule() {
        val a = f.queue("A", Schedule.Explicit(f.clock.now), listOf(alice))
        f.clock.advance(Duration.ofHours(1))
        f.send.run()
        assertThat(f.late.shown.single()).isEqualTo(1 to false)

        assertThat(blocking { f.actions.rescheduleHeld() }).isEqualTo(0)
        assertThat(f.post(a).state).isEqualTo(PostState.HELD)
        assertThat(blocking { f.actions.canReschedule() }).isFalse()
    }

    @Test
    fun moreLatePostsUpdateTheNotificationWithTheTotal() {
        f.queue("A", Schedule.Explicit(f.clock.now), listOf(alice))
        f.clock.advance(Duration.ofHours(1))
        f.send.run()
        f.queue("B", Schedule.Explicit(f.clock.now), listOf(alice))
        f.clock.advance(Duration.ofHours(1))
        f.send.run()

        assertThat(f.late.shown.map { it.first }).containsExactly(1, 2).inOrder()
        // Nothing new on hold: the notification is left as it is.
        f.send.run()
        assertThat(f.late.shown).hasSize(2)
    }
}
