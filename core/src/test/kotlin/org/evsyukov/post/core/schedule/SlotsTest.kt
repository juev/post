package org.evsyukov.post.core.schedule

import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertThrows
import org.junit.Test

/** S1-S4 and the slot lists of the Send later sheet and the Schedule screen. */
class SlotsTest {

    private val monday = schedule(MONDAY to listOf("09:00", "13:00"))

    // 2026-11-16 is a Monday.

    @Test
    fun `verification scenario three posts queued Monday at 08 00 get 09 00, 13 00 and the next weekday's first slot`() {
        val now = atLocal("2026-11-16T08:00", BERLIN)
        val occupied = mutableSetOf<LocalDateTime>()
        val assigned = List(3) { nextFreeSlot(monday, BERLIN, now, occupied)!!.also { occupied += it } }
        // Only Monday has slots, so the next weekday with a slot is the following Monday.
        assertThat(assigned)
            .containsExactly(local("2026-11-16T09:00"), local("2026-11-16T13:00"), local("2026-11-23T09:00"))
            .inOrder()
    }

    @Test
    fun `verification scenario with a Tuesday slot the third post goes to Tuesday`() {
        val schedule = schedule(MONDAY to listOf("09:00", "13:00"), TUESDAY to listOf("10:00"))
        val now = atLocal("2026-11-16T08:00", BERLIN)
        val occupied = mutableSetOf<LocalDateTime>()
        val assigned = List(3) { nextFreeSlot(schedule, BERLIN, now, occupied)!!.also { occupied += it } }
        assertThat(assigned)
            .containsExactly(local("2026-11-16T09:00"), local("2026-11-16T13:00"), local("2026-11-17T10:00"))
            .inOrder()
    }

    @Test
    fun `S4 now exactly on a slot skips it, the slot must be at least a minute ahead`() {
        assertThat(nextFreeSlot(monday, BERLIN, atLocal("2026-11-16T09:00", BERLIN), emptySet()))
            .isEqualTo(local("2026-11-16T13:00"))
    }

    @Test
    fun `S4 a slot exactly one minute ahead is taken, one under a minute is not`() {
        assertThat(nextFreeSlot(monday, BERLIN, atLocal("2026-11-16T08:59", BERLIN), emptySet()))
            .isEqualTo(local("2026-11-16T09:00"))
        assertThat(nextFreeSlot(monday, BERLIN, atLocal("2026-11-16T08:59", BERLIN).plusSeconds(1), emptySet()))
            .isEqualTo(local("2026-11-16T13:00"))
        assertThat(nextFreeSlot(monday, BERLIN, atLocal("2026-11-16T08:59:59.999", BERLIN), emptySet()))
            .isEqualTo(local("2026-11-16T13:00"))
    }

    @Test
    fun `S3 an occupied slot is skipped and a slot holds one post`() {
        val now = atLocal("2026-11-16T08:00", BERLIN)
        assertThat(nextFreeSlot(monday, BERLIN, now, setOf(local("2026-11-16T09:00"))))
            .isEqualTo(local("2026-11-16T13:00"))
        // An occupied time that is not a slot changes nothing.
        assertThat(nextFreeSlot(monday, BERLIN, now, setOf(local("2026-11-16T10:00"))))
            .isEqualTo(local("2026-11-16T09:00"))
    }

    @Test
    fun `week wrap-around from Sunday evening and from after Monday's last slot`() {
        val schedule = schedule(MONDAY to listOf("09:00"))
        // 2026-11-22 is a Sunday.
        assertThat(nextFreeSlot(schedule, BERLIN, atLocal("2026-11-22T20:00", BERLIN), emptySet()))
            .isEqualTo(local("2026-11-23T09:00"))
        assertThat(nextFreeSlot(schedule, BERLIN, atLocal("2026-11-16T10:00", BERLIN), emptySet()))
            .isEqualTo(local("2026-11-23T09:00"))
        // The only slot is taken this week, so the post goes to the week after.
        assertThat(nextFreeSlot(schedule, BERLIN, atLocal("2026-11-16T10:00", BERLIN), setOf(local("2026-11-23T09:00"))))
            .isEqualTo(local("2026-11-30T09:00"))
    }

    @Test
    fun `S1 an empty schedule has no next free slot and no slots at all`() {
        val now = atLocal("2026-11-16T08:00", BERLIN)
        assertThat(nextFreeSlot(WeeklySchedule.EMPTY, BERLIN, now, emptySet())).isNull()
        assertThat(freeSlots(WeeklySchedule.EMPTY, BERLIN, now, emptySet(), 5)).isEmpty()
        assertThat(upcomingSlots(WeeklySchedule.EMPTY, BERLIN, now, 5)).isEmpty()
        assertThat(comingSlots(WeeklySchedule.EMPTY, BERLIN, now, emptyMap())).isEmpty()
    }

    @Test
    fun `upcomingSlots lists slots in time order across days, at most count`() {
        val schedule = schedule(MONDAY to listOf("13:00", "09:00"), WEDNESDAY to listOf("18:00"))
        val from = atLocal("2026-11-16T12:00", BERLIN)
        assertThat(upcomingSlots(schedule, BERLIN, from, 4))
            .containsExactly(
                local("2026-11-16T13:00"),
                local("2026-11-18T18:00"),
                local("2026-11-23T09:00"),
                local("2026-11-23T13:00"),
            ).inOrder()
        assertThat(upcomingSlots(schedule, BERLIN, from, 0)).isEmpty()
        assertThrows(IllegalArgumentException::class.java) { upcomingSlots(schedule, BERLIN, from, -1) }
    }

    @Test
    fun `S2 slots inside the Berlin spring gap keep their local time and resolve to 03 00`() {
        // 2026-03-29 is a Sunday; 02:00 CET jumps to 03:00 CEST at 01:00 UTC.
        val schedule = schedule(SUNDAY to listOf("02:30", "03:00"))
        val slots = upcomingSlots(schedule, BERLIN, at("2026-03-28T12:00:00Z"), 3)
        assertThat(slots)
            .containsExactly(local("2026-03-29T02:30"), local("2026-03-29T03:00"), local("2026-04-05T02:30"))
            .inOrder()
        // Both slots are distinct, each holds its own post, and both fall at the end of the gap.
        assertThat(resolveLocal(slots[0], BERLIN)).isEqualTo(at("2026-03-29T01:00:00Z"))
        assertThat(resolveLocal(slots[1], BERLIN)).isEqualTo(at("2026-03-29T01:00:00Z"))
        assertThat(nextFreeSlot(schedule, BERLIN, at("2026-03-28T12:00:00Z"), setOf(local("2026-03-29T02:30"))))
            .isEqualTo(local("2026-03-29T03:00"))
    }

    @Test
    fun `S4 the one minute lead is measured to the resolved instant of a gap slot`() {
        val schedule = schedule(SUNDAY to listOf("02:30"))
        // The gap slot resolves to 01:00 UTC: exactly one minute after 00:59 it is taken...
        assertThat(nextFreeSlot(schedule, BERLIN, at("2026-03-29T00:59:00Z"), emptySet()))
            .isEqualTo(local("2026-03-29T02:30"))
        // ...and a second later it is too close, so the next week's slot follows.
        assertThat(nextFreeSlot(schedule, BERLIN, at("2026-03-29T00:59:01Z"), emptySet()))
            .isEqualTo(local("2026-04-05T02:30"))
    }

    @Test
    fun `S2 a slot in a gap that ends at the next midnight still counts on its own day`() {
        // America/Nuuk, Saturday 2026-03-28: 23:00 (-02:00) jumps to Sunday 00:00 (-01:00) at 01:00 UTC.
        val nuuk = ZoneId.of("America/Nuuk")
        val schedule = schedule(SATURDAY to listOf("23:30"))
        assertThat(resolveLocal(local("2026-03-28T23:30"), nuuk)).isEqualTo(at("2026-03-29T01:00:00Z"))
        // One minute before the end of the gap the slot is exactly one minute ahead, though the
        // local date at that instant is already Sunday.
        assertThat(nextFreeSlot(schedule, nuuk, at("2026-03-29T00:59:00Z"), emptySet()))
            .isEqualTo(local("2026-03-28T23:30"))
    }

    @Test
    fun `S2 a slot inside the Berlin autumn overlap is listed once, at its first occurrence`() {
        // 2026-10-25 is a Sunday; local 02:00-03:00 happens twice, first in CEST.
        val schedule = schedule(SUNDAY to listOf("02:30"))
        val fromFirstPass = at("2026-10-25T00:00:00Z") // 02:00 CEST
        val slots = upcomingSlots(schedule, BERLIN, fromFirstPass, 2)
        assertThat(slots).containsExactly(local("2026-10-25T02:30"), local("2026-11-01T02:30")).inOrder()
        assertThat(resolveLocal(slots[0], BERLIN)).isEqualTo(at("2026-10-25T00:30:00Z"))
    }

    @Test
    fun `S2 once the first occurrence passed the overlap slot is not offered again in the repeated hour`() {
        val schedule = schedule(SUNDAY to listOf("02:30"))
        val inRepeatedHour = at("2026-10-25T01:10:00Z") // 02:10 CET, before the second 02:30
        assertThat(nextFreeSlot(schedule, BERLIN, inRepeatedHour, emptySet())).isEqualTo(local("2026-11-01T02:30"))
        assertThat(upcomingSlots(schedule, BERLIN, inRepeatedHour, 1)).containsExactly(local("2026-11-01T02:30"))
    }

    @Test
    fun `S2 New York gap and overlap slots`() {
        // 2026-03-08 and 2026-11-01 are Sundays.
        val gap = schedule(SUNDAY to listOf("02:30"))
        val gapSlot = nextFreeSlot(gap, NEW_YORK, at("2026-03-07T12:00:00Z"), emptySet())!!
        assertThat(gapSlot).isEqualTo(local("2026-03-08T02:30"))
        assertThat(resolveLocal(gapSlot, NEW_YORK)).isEqualTo(at("2026-03-08T07:00:00Z"))

        val overlap = schedule(SUNDAY to listOf("01:30"))
        assertThat(upcomingSlots(overlap, NEW_YORK, at("2026-11-01T05:00:00Z"), 2)) // 01:00 EDT
            .containsExactly(local("2026-11-01T01:30"), local("2026-11-08T01:30")).inOrder()
        assertThat(nextFreeSlot(overlap, NEW_YORK, at("2026-11-01T06:10:00Z"), emptySet())) // 01:10 EST
            .isEqualTo(local("2026-11-08T01:30"))
    }

    @Test
    fun `a zone without daylight saving keeps Berlin's change days ordinary`() {
        val schedule = schedule(SUNDAY to listOf("02:30"))
        val slots = upcomingSlots(schedule, MOSCOW, at("2026-03-28T12:00:00Z"), 2)
        assertThat(slots).containsExactly(local("2026-03-29T02:30"), local("2026-04-05T02:30")).inOrder()
        assertThat(resolveLocal(slots[0], MOSCOW)).isEqualTo(at("2026-03-28T23:30:00Z"))
        // 08:00 in Moscow is 05:00 UTC; the Monday 09:00 slot is free.
        assertThat(nextFreeSlot(monday, MOSCOW, at("2026-11-16T05:00:00Z"), emptySet()))
            .isEqualTo(local("2026-11-16T09:00"))
    }

    @Test
    fun `R24 freeSlots lists the next free slots for the Send later sheet, the first being the next free slot`() {
        val weekdays = listOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)
        val schedule = WeeklySchedule(weekdays.associateWith { listOf("09:00", "13:00", "18:00").map(LocalTime::parse) })
        val now = atLocal("2026-11-16T10:00", BERLIN)
        val occupied = setOf(local("2026-11-16T13:00"), local("2026-11-17T09:00"))
        val free = freeSlots(schedule, BERLIN, now, occupied, 6)
        assertThat(free)
            .containsExactly(
                local("2026-11-16T18:00"),
                local("2026-11-17T13:00"),
                local("2026-11-17T18:00"),
                local("2026-11-18T09:00"),
                local("2026-11-18T13:00"),
                local("2026-11-18T18:00"),
            ).inOrder()
        assertThat(free.first()).isEqualTo(nextFreeSlot(schedule, BERLIN, now, occupied))
        assertThrows(IllegalArgumentException::class.java) { freeSlots(schedule, BERLIN, now, occupied, -1) }
    }

    @Test
    fun `Coming slots show every slot of the next seven days once, with its post or free`() {
        val schedule = schedule(MONDAY to listOf("09:00", "13:00", "18:00"), TUESDAY to listOf("09:00"))
        val now = atLocal("2026-11-16T12:00", BERLIN)
        val coming = comingSlots(
            schedule,
            BERLIN,
            now,
            mapOf(local("2026-11-16T13:00") to 1L, local("2026-11-17T09:00") to 2L, local("2026-11-30T09:00") to 3L),
        )
        assertThat(coming)
            .containsExactly(
                SlotView(local("2026-11-16T13:00"), 1L),
                SlotView(local("2026-11-16T18:00"), null),
                SlotView(local("2026-11-17T09:00"), 2L),
                // Next Monday 09:00 is within seven days of Monday 12:00; next Monday 13:00 is not.
                SlotView(local("2026-11-23T09:00"), null),
            ).inOrder()
    }

    @Test
    fun `Coming slots keep a held slot of the next minute but drop a slot that is due`() {
        val schedule = schedule(MONDAY to listOf("13:00"))
        val occupiedBy = mapOf(local("2026-11-16T13:00") to 7L)
        assertThat(comingSlots(schedule, BERLIN, atLocal("2026-11-16T12:59:30", BERLIN), occupiedBy, days = 1))
            .containsExactly(SlotView(local("2026-11-16T13:00"), 7L))
        assertThat(comingSlots(schedule, BERLIN, atLocal("2026-11-16T13:00", BERLIN), occupiedBy, days = 1)).isEmpty()
        assertThat(comingSlots(schedule, BERLIN, atLocal("2026-11-16T12:00", BERLIN), occupiedBy, days = 0)).isEmpty()
        assertThrows(IllegalArgumentException::class.java) {
            comingSlots(schedule, BERLIN, atLocal("2026-11-16T12:00", BERLIN), occupiedBy, days = -1)
        }
    }

    @Test
    fun `Coming slots across the autumn overlap list the repeated-hour slot once`() {
        val schedule = schedule(SUNDAY to listOf("02:30", "12:00"))
        val coming = comingSlots(schedule, BERLIN, at("2026-10-24T22:00:00Z"), emptyMap()) // Sun 00:00 CEST
        assertThat(coming.map { it.local })
            .containsExactly(local("2026-10-25T02:30"), local("2026-10-25T12:00"))
            .inOrder()
    }
}
