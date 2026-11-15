package org.evsyukov.post.send.schedule

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.db.PostKind
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.posts.Delivery
import org.evsyukov.post.data.posts.DeliveryProgress
import org.evsyukov.post.data.posts.Post
import org.evsyukov.post.data.posts.PostOptions
import org.evsyukov.post.data.posts.Schedule
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The times the alarm is set for (scheduling.md M1, S2) and the posts the job holds (Q4, M6). */
class QueueRulesTest {
    private val now = Instant.parse("2026-11-16T08:00:00Z")

    @Test
    fun theAlarmGoesToTheEarliestPendingTime() {
        val queue = listOf(
            post(1, Schedule.Explicit(now.plusSeconds(3600))),
            post(2, Schedule.Slot(LocalDateTime.parse("2026-11-16T08:30"))),
            post(3, Schedule.Explicit(now.plusSeconds(7200))),
        )

        assertThat(nextWake(queue, ZoneOffset.UTC, now)).isEqualTo(Instant.parse("2026-11-16T08:30:00Z"))
    }

    @Test
    fun aSlotIsResolvedInTheZoneTheDeviceHasNow() {
        // S2: 09:00 local is 06:00 UTC in Moscow and 08:00 UTC in Berlin; the alarm follows the device.
        val queue = listOf(post(1, Schedule.Slot(LocalDateTime.parse("2026-11-17T09:00"))))

        assertThat(nextWake(queue, ZoneId.of("Europe/Moscow"), now)).isEqualTo(Instant.parse("2026-11-17T06:00:00Z"))
        assertThat(nextWake(queue, ZoneId.of("Europe/Berlin"), now)).isEqualTo(Instant.parse("2026-11-17T08:00:00Z"))
    }

    @Test
    fun aSlotInADaylightSavingGapFiresAtTheEndOfTheGap() {
        // Berlin skips 02:00–03:00 on 2027-03-28; a 02:30 slot fires at 03:00 local, 01:00 UTC (S2).
        val queue = listOf(post(1, Schedule.Slot(LocalDateTime.parse("2027-03-28T02:30"))))

        assertThat(nextWake(queue, ZoneId.of("Europe/Berlin"), now)).isEqualTo(Instant.parse("2027-03-28T01:00:00Z"))
    }

    @Test
    fun aSlotThatOccursTwiceFiresAtItsFirstOccurrence() {
        // Berlin repeats 02:00–03:00 on 2027-10-31; 02:30 first occurs in summer time, 00:30 UTC.
        val queue = listOf(post(1, Schedule.Slot(LocalDateTime.parse("2027-10-31T02:30"))))

        assertThat(nextWake(queue, ZoneId.of("Europe/Berlin"), now)).isEqualTo(Instant.parse("2027-10-31T00:30:00Z"))
    }

    @Test
    fun aWaitingRetryCountsAndHeldOrFailedPostsDoNot() {
        val retryAt = now.plusSeconds(30)
        val queue = listOf(
            post(1, Schedule.Now(now.minusSeconds(60)), state = PostState.SENDING, deliveries = listOf(delivery(1, attempts = 1, nextAttemptAt = retryAt))),
            post(2, Schedule.Explicit(now.plusSeconds(10)), state = PostState.HELD),
            post(3, Schedule.Explicit(now.plusSeconds(20)), state = PostState.FAILED, deliveries = listOf(delivery(3, state = DeliveryState.FAILED, attempts = 1))),
            post(4, Schedule.Explicit(now.plusSeconds(600))),
        )

        assertThat(nextWake(queue, ZoneOffset.UTC, now)).isEqualTo(retryAt)
    }

    @Test
    fun aTimeAlreadyPassedIsLeftToTheRunAndAnEmptyQueueNeedsNoAlarm() {
        val queue = listOf(post(1, Schedule.Now(now.minusSeconds(5))))

        assertThat(nextWake(queue, ZoneOffset.UTC, now)).isNull()
        assertThat(nextWake(emptyList(), ZoneOffset.UTC, now)).isNull()
    }

    @Test
    fun aPostIsLateOnlyPastTheThreshold() {
        val threshold = Duration.ofMinutes(30)
        val queue = listOf(
            post(1, Schedule.Explicit(now.minus(threshold))),
            post(2, Schedule.Explicit(now.minus(threshold).minusSeconds(1))),
            post(3, Schedule.Explicit(now.minusSeconds(60))),
        )

        // Q4: exactly 30 minutes past is due, not late; a second more is late.
        assertThat(latePosts(queue, ZoneOffset.UTC, now, threshold)).containsExactly(2L)
    }

    @Test
    fun aStartedOrHeldPostIsNeverHeldAgain() {
        val old = now.minus(Duration.ofHours(2))
        val queue = listOf(
            post(1, Schedule.Explicit(old), state = PostState.SENDING, deliveries = listOf(delivery(1, attempts = 1))),
            post(2, Schedule.Explicit(old), state = PostState.HELD),
            post(3, Schedule.Explicit(old)),
        )

        assertThat(latePosts(queue, ZoneOffset.UTC, now, Duration.ofMinutes(30))).containsExactly(3L)
    }

    @Test
    fun onlyWaitingSlotPostsHoldSlotsAndMove() {
        val nine = LocalDateTime.parse("2026-11-16T09:00")
        val one = LocalDateTime.parse("2026-11-16T13:00")
        val queue = listOf(
            post(1, Schedule.Slot(nine)),
            post(2, Schedule.Explicit(now.plusSeconds(10_000))),
            post(3, Schedule.Slot(one)),
            post(4, Schedule.Slot(LocalDateTime.parse("2026-11-16T07:00")), state = PostState.HELD),
        )

        assertThat(movableSlotPosts(queue, ZoneOffset.UTC).map { it.id }).containsExactly(1L, 3L).inOrder()
        assertThat(occupiedSlots(queue, ZoneOffset.UTC)).containsExactly(nine, one)
        assertThat(occupiedSlots(queue, ZoneOffset.UTC, except = 1)).containsExactly(one)
    }

    private fun delivery(postId: Long, state: DeliveryState = DeliveryState.WAITING, attempts: Int = 0, nextAttemptAt: Instant? = null) =
        Delivery(postId * 10, postId, 1, state, null, DeliveryProgress(), null, attempts, null, nextAttemptAt)

    private fun post(
        id: Long,
        schedule: Schedule,
        state: PostState = PostState.QUEUED,
        deliveries: List<Delivery> = listOf(delivery(id)),
    ): Post {
        val sendAt = when (schedule) {
            is Schedule.Now -> schedule.at
            is Schedule.Explicit -> schedule.at
            is Schedule.Slot -> schedule.local.toInstant(ZoneOffset.UTC)
        }
        return Post(id, PostKind.NEW, state, "post $id", PostOptions(), listOf(1), schedule, sendAt, id, null, now, now, emptyList(), deliveries)
    }
}
