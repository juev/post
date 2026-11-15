package org.evsyukov.post.core.schedule

import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import org.junit.Assert.assertThrows
import org.junit.Test

/** S6-S10 and Q3-Q8: moving queued posts between slots. 2026-11-16 is a Monday. */
class QueueTest {

    private val a = slotPost(1, "2026-11-16T09:00")
    private val b = slotPost(2, "2026-11-16T13:00")
    private val c = slotPost(3, "2026-11-16T18:00")

    @Test
    fun `S8 example dragging C to the top gives C 09 00, A 13 00, B 18 00`() {
        assertThat(reorder(listOf(a, b, c), fromIndex = 2, toIndex = 0)).containsExactly(
            c.id, local("2026-11-16T09:00"),
            a.id, local("2026-11-16T13:00"),
            b.id, local("2026-11-16T18:00"),
        )
    }

    @Test
    fun `S8 drag scenario B above A swaps their slots, X with an explicit time stays, Undo restores`() {
        val x = explicitPost(9, atLocal("2026-11-16T11:00", BERLIN))
        // The Queue tab shows A 09:00, X 11:00, B 13:00; only the slot-based posts take part in the drag.
        val dropped = reorder(listOf(a, b), fromIndex = 1, toIndex = 0)
        assertThat(dropped).containsExactly(b.id, local("2026-11-16T09:00"), a.id, local("2026-11-16T13:00"))
        assertThat(dropped).doesNotContainKey(x.id)

        // Undo is the reverse drag on the new order: A 09:00, B 13:00 again.
        val afterDrop = listOf(slotPost(2, "2026-11-16T09:00"), slotPost(1, "2026-11-16T13:00"))
        assertThat(reorder(afterDrop, fromIndex = 1, toIndex = 0))
            .containsExactly(a.id, local("2026-11-16T09:00"), b.id, local("2026-11-16T13:00"))
    }

    @Test
    fun `S8 every move keeps the set of slot times and gives the earliest to the first post`() {
        val posts = listOf(
            slotPost(1, "2026-11-16T09:00"),
            slotPost(2, "2026-11-16T13:00"),
            slotPost(3, "2026-11-17T09:00"),
            slotPost(4, "2026-11-23T09:00"),
        )
        val times = posts.map { (it.time as SendTime.Slot).local }
        for (from in posts.indices) for (to in posts.indices) {
            val moved = posts.toMutableList().apply { add(to, removeAt(from)) }
            val result = reorder(posts, from, to)
            assertThat(result.values).containsExactlyElementsIn(times)
            assertThat(moved.map { result.getValue(it.id) }).isEqualTo(times)
        }
    }

    @Test
    fun `S8 a drop in place keeps every time`() {
        assertThat(reorder(listOf(a, b, c), 1, 1))
            .containsExactly(a.id, local("2026-11-16T09:00"), b.id, local("2026-11-16T13:00"), c.id, local("2026-11-16T18:00"))
    }

    @Test
    fun `S8 the earliest time goes to the first post even when the list was not in time order`() {
        assertThat(reorder(listOf(b, a), 0, 0))
            .containsExactly(b.id, local("2026-11-16T09:00"), a.id, local("2026-11-16T13:00"))
    }

    @Test
    fun `S9 posts with an explicit time, started, held or failed posts cannot be dragged`() {
        val ineligible = listOf(
            explicitPost(9, atLocal("2026-11-16T11:00", BERLIN)),
            slotPost(10, "2026-11-16T15:00").copy(started = true),
            slotPost(11, "2026-11-16T15:00").copy(held = true),
            slotPost(12, "2026-11-16T15:00").copy(failed = true),
        )
        for (post in ineligible) {
            assertThrows(IllegalArgumentException::class.java) { reorder(listOf(a, post, b), 0, 2) }
            // The post is rejected even when it is not the one being moved.
            assertThrows(IllegalArgumentException::class.java) { reorder(listOf(post, a), 1, 1) }
        }
    }

    @Test
    fun `reorder rejects a repeated post and indexes outside the list`() {
        assertThrows(IllegalArgumentException::class.java) { reorder(listOf(a, a), 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { reorder(listOf(a, b), 2, 0) }
        assertThrows(IllegalArgumentException::class.java) { reorder(listOf(a, b), 0, -1) }
        assertThrows(IllegalArgumentException::class.java) { reorder(emptyList(), 0, 0) }
    }

    @Test
    fun `S10 Move up and Move down follow the drag rule`() {
        assertThat(moveUp(listOf(a, b, c), 2))
            .containsExactly(a.id, local("2026-11-16T09:00"), c.id, local("2026-11-16T13:00"), b.id, local("2026-11-16T18:00"))
        assertThat(moveDown(listOf(a, b, c), 0))
            .containsExactly(b.id, local("2026-11-16T09:00"), a.id, local("2026-11-16T13:00"), c.id, local("2026-11-16T18:00"))
    }

    @Test
    fun `S10 the first post cannot move up and the last cannot move down`() {
        assertThrows(IllegalArgumentException::class.java) { moveUp(listOf(a, b, c), 0) }
        assertThrows(IllegalArgumentException::class.java) { moveDown(listOf(a, b, c), 2) }
        assertThrows(IllegalArgumentException::class.java) { moveDown(listOf(a), 0) }
    }

    @Test
    fun `S10 Move up rejects the same posts as dragging`() {
        val held = slotPost(11, "2026-11-16T15:00").copy(held = true)
        assertThrows(IllegalArgumentException::class.java) { moveUp(listOf(a, held), 1) }
    }

    @Test
    fun `S6 Redistribute puts slot-based posts in their current order into the new schedule's free slots`() {
        val x = explicitPost(9, atLocal("2026-11-16T11:00", BERLIN))
        val held = slotPost(10, "2026-11-16T07:00").copy(held = true)
        val started = slotPost(11, "2026-11-16T07:30").copy(started = true)
        val failed = slotPost(12, "2026-11-16T06:00").copy(failed = true)
        val newSchedule = schedule(TUESDAY to listOf("10:00"), WEDNESDAY to listOf("10:00"))
        val now = atLocal("2026-11-16T08:00", BERLIN)

        val result = redistribute(listOf(b, held, x, a, started, failed), newSchedule, BERLIN, now)

        // B is ahead of A in the given order, so it takes the first free slot.
        assertThat(result).containsExactly(b.id, local("2026-11-17T10:00"), a.id, local("2026-11-18T10:00"))
    }

    @Test
    fun `S6 Redistribute ignores slots that are too close and explicit posts sharing a slot's time`() {
        val newSchedule = schedule(MONDAY to listOf("09:00", "13:00"))
        // An explicit post at a slot's time does not occupy it (S5).
        val x = explicitPost(9, atLocal("2026-11-16T13:00", BERLIN))
        val now = atLocal("2026-11-16T08:59:30", BERLIN)
        assertThat(redistribute(listOf(a, x, b), newSchedule, BERLIN, now))
            .containsExactly(a.id, local("2026-11-16T13:00"), b.id, local("2026-11-23T09:00"))
    }

    @Test
    fun `S6 Redistribute with an empty schedule moves nothing`() {
        assertThat(redistribute(listOf(a, b), WeeklySchedule.EMPTY, BERLIN, atLocal("2026-11-16T08:00", BERLIN))).isEmpty()
    }

    @Test
    fun `Q6 Reschedule gives each held post, explicit ones too, the next free slot in queue order`() {
        val schedule = schedule(MONDAY to listOf("09:00", "13:00", "18:00"))
        val held1 = slotPost(1, "2026-11-16T09:00").copy(held = true)
        val held2 = explicitPost(2, atLocal("2026-11-16T09:30", BERLIN)).copy(held = true)
        val now = atLocal("2026-11-16T11:00", BERLIN)
        val occupied = setOf(local("2026-11-16T13:00"))

        assertThat(reschedule(listOf(held1, held2), schedule, BERLIN, now, occupied))
            .containsExactly(held1.id, local("2026-11-16T18:00"), held2.id, local("2026-11-23T09:00"))
    }

    @Test
    fun `Q6 Reschedule rejects posts that are not held or have started, and with no schedule moves nothing`() {
        val schedule = schedule(MONDAY to listOf("09:00"))
        val now = atLocal("2026-11-16T11:00", BERLIN)
        assertThrows(IllegalArgumentException::class.java) { reschedule(listOf(a), schedule, BERLIN, now, emptySet()) }
        assertThrows(IllegalArgumentException::class.java) {
            reschedule(listOf(a.copy(held = true, started = true)), schedule, BERLIN, now, emptySet())
        }
        assertThat(reschedule(listOf(a.copy(held = true)), WeeklySchedule.EMPTY, BERLIN, now, emptySet())).isEmpty()
    }

    @Test
    fun `Q8 due posts go in queue order, by time and then by queue order`() {
        val now = atLocal("2026-11-16T09:00", BERLIN)
        val slot0900 = slotPost(1, "2026-11-16T09:00", order = 2)
        val explicit0900 = explicitPost(2, now, order = 1)
        val explicit0830 = explicitPost(3, atLocal("2026-11-16T08:30", BERLIN), order = 5)
        val held = slotPost(4, "2026-11-16T08:00").copy(held = true)
        val started = slotPost(5, "2026-11-16T08:10").copy(started = true)
        val failed = slotPost(6, "2026-11-16T08:20").copy(failed = true)
        val future = slotPost(7, "2026-11-16T09:01")

        val due = dueOrder(listOf(future, slot0900, held, explicit0900, started, failed, explicit0830), BERLIN, now)

        assertThat(due).containsExactly(3L, 2L, 1L).inOrder()
    }

    @Test
    fun `Q8 a slot post inside the spring gap is due at the end of the gap`() {
        val gapPost = slotPost(1, "2026-03-29T02:30")
        assertThat(dueOrder(listOf(gapPost), BERLIN, at("2026-03-29T00:59:59Z"))).isEmpty()
        assertThat(dueOrder(listOf(gapPost), BERLIN, at("2026-03-29T01:00:00Z"))).containsExactly(1L)
    }

    @Test
    fun `queue order sorts by send time in the device zone, then order, then id`() {
        val p1 = slotPost(1, "2026-11-16T09:00", order = 1)
        val p2 = explicitPost(2, atLocal("2026-11-16T08:00", BERLIN), order = 9)
        val p3 = explicitPost(3, atLocal("2026-11-16T09:00", BERLIN), order = 1)
        val p4 = explicitPost(4, atLocal("2026-11-16T09:00", BERLIN), order = 0)
        assertThat(inQueueOrder(listOf(p1, p2, p3, p4), BERLIN).map { it.id }).containsExactly(2L, 4L, 1L, 3L).inOrder()
        // In Moscow the slot's 09:00 (06:00 UTC) comes an hour before the explicit Berlin 08:00 (07:00 UTC).
        assertThat(inQueueOrder(listOf(p1, p2, p3, p4), MOSCOW).map { it.id }.first()).isEqualTo(1L)
    }
}
