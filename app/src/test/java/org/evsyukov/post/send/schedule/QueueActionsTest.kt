package org.evsyukov.post.send.schedule

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.PublishTrigger
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.posts.DeliveryPlan
import org.evsyukov.post.data.posts.Post
import org.evsyukov.post.data.posts.PostsRepository
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.send.blocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime

/** Queue actions on the database (scheduling.md S3–S10, Q3) and the Send later slots (R24, S4). */
@RunWith(AndroidJUnit4::class)
class QueueActionsTest {
    private val f = SchedulingFixture()
    private val alice = f.send.mastodon()

    @After
    fun tearDown() = f.close()

    private fun slotOf(id: Long) = (f.post(id).schedule as Schedule.Slot).local.toString()

    @Test
    fun threePostsOnMondayAtEightTakeNineOneAndTheNextWeekdaysFirstSlot() {
        // Verification scenario: slots Mon 09:00 and 13:00 (and Tue 09:00), three posts queued Monday 08:00.
        f.slots(DayOfWeek.MONDAY to listOf("09:00", "13:00"), DayOfWeek.TUESDAY to listOf("09:00"))
        repeat(3) { index ->
            val next = sendLaterSlots(f.queueNow(), blocking { f.schedule.get() }, java.time.ZoneOffset.UTC, f.clock.now).first()
            f.queue("post $index", Schedule.Slot(next), listOf(alice))
        }

        assertThat(f.queueNow().map { (it.schedule as Schedule.Slot).local.toString() })
            .containsExactly("2026-11-16T09:00", "2026-11-16T13:00", "2026-11-17T09:00").inOrder()
    }

    @Test
    fun theSheetOffersSixFreeSlotsAndNoneWithoutASchedule() {
        val schedule = blocking { f.schedule.get() }
        assertThat(sendLaterSlots(f.queueNow(), schedule, java.time.ZoneOffset.UTC, f.clock.now)).isEmpty()

        f.slots(DayOfWeek.MONDAY to listOf("08:00", "08:01", "09:00"), DayOfWeek.WEDNESDAY to listOf("10:00"))
        f.slot("taken", "2026-11-16T09:00", listOf(alice))
        val slots = sendLaterSlots(f.queueNow(), blocking { f.schedule.get() }, java.time.ZoneOffset.UTC, f.clock.now)

        // 08:00 is now and too soon; 08:01 is exactly a minute ahead and allowed (S4); 09:00 is taken.
        assertThat(slots.map { it.toString() }).containsExactly(
            "2026-11-16T08:01", "2026-11-18T10:00", "2026-11-23T08:00", "2026-11-23T08:01", "2026-11-23T09:00", "2026-11-25T10:00",
        ).inOrder()
    }

    @Test
    fun draggingBAboveAPassesTheExplicitPostWhichKeepsItsTimeAndUndoRestores() {
        // Verification scenario: A 09:00, B 13:00 (slots), X 11:00 (explicit).
        val a = f.slot("A", "2026-11-16T09:00", listOf(alice))
        val x = f.queue("X", Schedule.Explicit(Instant.parse("2026-11-16T11:00:00Z")), listOf(alice))
        val b = f.slot("B", "2026-11-16T13:00", listOf(alice))

        val undo = blocking { f.actions.reorder(b, 0) }!!

        assertThat(slotOf(b)).isEqualTo("2026-11-16T09:00")
        assertThat(slotOf(a)).isEqualTo("2026-11-16T13:00")
        assertThat(f.post(x).schedule).isEqualTo(Schedule.Explicit(Instant.parse("2026-11-16T11:00:00Z")))
        assertThat(f.queueNow().map { it.text }).containsExactly("B", "X", "A").inOrder()

        blocking { f.actions.undo(undo) }

        assertThat(slotOf(a)).isEqualTo("2026-11-16T09:00")
        assertThat(slotOf(b)).isEqualTo("2026-11-16T13:00")
    }

    @Test
    fun draggingCToTheTopShiftsTheOthersDown() {
        // S8: A 09:00, B 13:00, C 18:00; C to the top gives C 09:00, A 13:00, B 18:00.
        val a = f.slot("A", "2026-11-16T09:00", listOf(alice))
        val b = f.slot("B", "2026-11-16T13:00", listOf(alice))
        val c = f.slot("C", "2026-11-16T18:00", listOf(alice))

        blocking { f.actions.reorder(c, 0) }

        assertThat(listOf(slotOf(c), slotOf(a), slotOf(b))).containsExactly("2026-11-16T09:00", "2026-11-16T13:00", "2026-11-16T18:00").inOrder()
        // Dropping where it started changes nothing and gives no Undo.
        assertThat(blocking { f.actions.reorder(c, 0) }).isNull()
    }

    @Test
    fun moveUpAndMoveDownSwapWithTheNeighbourAndStopAtTheEnds() {
        val a = f.slot("A", "2026-11-16T09:00", listOf(alice))
        val b = f.slot("B", "2026-11-16T13:00", listOf(alice))

        assertThat(blocking { f.actions.moveUp(a) }).isNull()
        assertThat(blocking { f.actions.moveDown(b) }).isNull()

        val undo = blocking { f.actions.moveDown(a) }!!
        assertThat(slotOf(a)).isEqualTo("2026-11-16T13:00")
        assertThat(slotOf(b)).isEqualTo("2026-11-16T09:00")
        assertThat(undo).isEqualTo(
            mapOf(a to Schedule.Slot(LocalDateTime.parse("2026-11-16T09:00")), b to Schedule.Slot(LocalDateTime.parse("2026-11-16T13:00"))),
        )

        blocking { f.actions.moveUp(a) }
        assertThat(slotOf(a)).isEqualTo("2026-11-16T09:00")
    }

    @Test
    fun explicitHeldAndStartedPostsDoNotMove() {
        val x = f.queue("X", Schedule.Explicit(Instant.parse("2026-11-16T11:00:00Z")), listOf(alice))
        val a = f.slot("A", "2026-11-16T09:00", listOf(alice))
        blocking { f.send.posts.hold(listOf(a)) }

        assertThat(blocking { f.actions.moveDown(x) }).isNull()
        assertThat(blocking { f.actions.reorder(a, 0) }).isNull()
        assertThat(f.post(a).state).isEqualTo(PostState.HELD)
    }

    @Test
    fun sendNowMakesThePostDueAndFreesItsSlot() {
        f.slots(DayOfWeek.MONDAY to listOf("09:00"))
        val a = f.slot("A", "2026-11-16T09:00", listOf(alice))
        assertThat(occupiedSlots(f.queueNow(), java.time.ZoneOffset.UTC)).isNotEmpty()

        blocking { f.actions.sendNow(a) }

        assertThat(f.post(a).schedule).isEqualTo(Schedule.Now(f.clock.now))
        assertThat(f.triggers.get()).isEqualTo(1)
        assertThat(occupiedSlots(f.queueNow(), java.time.ZoneOffset.UTC)).isEmpty()
    }

    @Test
    fun deletingAPostFreesItsSlotAndLaterPostsStay() {
        // S7.
        val a = f.slot("A", "2026-11-16T09:00", listOf(alice))
        val b = f.slot("B", "2026-11-16T13:00", listOf(alice))

        assertThat(blocking { f.actions.delete(a) }).isTrue()

        assertThat(slotOf(b)).isEqualTo("2026-11-16T13:00")
        assertThat(occupiedSlots(f.queueNow(), java.time.ZoneOffset.UTC).map { it.toString() }).containsExactly("2026-11-16T13:00")
    }

    @Test
    fun aPostTheSenderClaimsWhileDeleteLooksAtItStaysAndKeepsSending() {
        // S7 with the sender: it claims the post's delivery right after Delete has read the post. The post must
        // stay, or the sender would publish a post the user deleted and then lose the record of it.
        val a = f.slot("A", "2026-11-16T09:00", listOf(alice))
        val delivery = f.post(a).deliveries.single().id
        val claim = suspend { f.send.posts.startDelivery(delivery, DeliveryPlan(emptyList())) }
        // The claim lands after any read Delete makes of the post and before it deletes.
        val racing = object : PostsRepository by f.send.posts {
            override suspend fun get(id: Long): Post? = f.send.posts.get(id).also { claim() }

            override suspend fun deleteUnlessSending(id: Long): Boolean {
                claim()
                return f.send.posts.deleteUnlessSending(id)
            }
        }
        val actions = QueueActions(racing, f.schedule, PublishTrigger {}, f.late, f.clock) { java.time.ZoneOffset.UTC }

        assertThat(blocking { actions.delete(a) }).isFalse()

        assertThat(f.post(a).deliveries.single().state).isEqualTo(DeliveryState.SENDING)
    }

    @Test
    fun aSendingPostCannotBeDeleted() {
        val a = f.slot("A", "2026-11-16T09:00", listOf(alice))
        blocking { f.send.posts.startDelivery(f.post(a).deliveries.single().id, DeliveryPlan(emptyList())) }

        assertThat(blocking { f.actions.delete(a) }).isFalse()
        assertThat(f.queueNow().map { it.id }).containsExactly(a)
    }

    @Test
    fun redistributeMovesSlotPostsInOrderToTheNewScheduleAndLeavesExplicitOnes() {
        // S6: the schedule changed to Tue 10:00 and 12:00; posts keep their times until Redistribute.
        val a = f.slot("A", "2026-11-16T09:00", listOf(alice))
        val b = f.slot("B", "2026-11-16T13:00", listOf(alice))
        val x = f.queue("X", Schedule.Explicit(Instant.parse("2026-11-16T11:00:00Z")), listOf(alice))
        f.slots(DayOfWeek.TUESDAY to listOf("10:00", "12:00"))
        assertThat(slotOf(a)).isEqualTo("2026-11-16T09:00")

        assertThat(blocking { f.actions.redistribute() }).isEqualTo(2)

        assertThat(slotOf(a)).isEqualTo("2026-11-17T10:00")
        assertThat(slotOf(b)).isEqualTo("2026-11-17T12:00")
        assertThat(f.post(x).schedule).isEqualTo(Schedule.Explicit(Instant.parse("2026-11-16T11:00:00Z")))
    }

    @Test
    fun changeTimeQueuesAHeldPostAgainAtTheChosenTime() {
        val a = f.queue("A", Schedule.Explicit(f.clock.now), listOf(alice))
        blocking { f.send.posts.hold(listOf(a)) }

        blocking { f.actions.changeTime(a, Schedule.Explicit(f.clock.now.plusSeconds(3600))) }

        assertThat(f.post(a).state).isEqualTo(PostState.QUEUED)
        assertThat(f.post(a).sendAt).isEqualTo(f.clock.now.plusSeconds(3600))
    }
}
