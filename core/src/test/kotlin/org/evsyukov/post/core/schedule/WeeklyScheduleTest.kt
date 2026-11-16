package org.evsyukov.post.core.schedule

import com.google.common.truth.Truth.assertThat
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.LocalTime
import org.junit.Test

/** S1: the weekly schedule. */
class WeeklyScheduleTest {

    @Test
    fun `S1 times are cut to minutes, sorted and distinct`() {
        val schedule = WeeklySchedule(
            mapOf(
                MONDAY to listOf(
                    LocalTime.of(18, 0),
                    LocalTime.of(9, 0, 42),
                    LocalTime.of(13, 0),
                    LocalTime.of(9, 0),
                    LocalTime.of(13, 0, 0, 5),
                ),
            ),
        )
        assertThat(schedule.times)
            .containsExactly(MONDAY, listOf(LocalTime.of(9, 0), LocalTime.of(13, 0), LocalTime.of(18, 0)))
    }

    @Test
    fun `S1 days without times are dropped and an all-empty schedule is empty`() {
        val schedule = WeeklySchedule(mapOf(MONDAY to emptyList(), SATURDAY to listOf(LocalTime.of(11, 0))))
        assertThat(schedule.times.keys).containsExactly(SATURDAY)
        assertThat(schedule.isEmpty()).isFalse()

        assertThat(WeeklySchedule(mapOf(SUNDAY to emptyList())).isEmpty()).isTrue()
        assertThat(WeeklySchedule.EMPTY.isEmpty()).isTrue()
        assertThat(WeeklySchedule(mapOf(SUNDAY to emptyList()))).isEqualTo(WeeklySchedule.EMPTY)
    }

    @Test
    fun `S1 schedules with the same slots are equal whatever the input order`() {
        val a = schedule(MONDAY to listOf("13:00", "09:00"), FRIDAY to listOf("18:00"))
        val b = schedule(FRIDAY to listOf("18:00", "18:00"), MONDAY to listOf("09:00", "13:00:30"))
        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(a).isNotEqualTo(schedule(MONDAY to listOf("09:00")))
    }

    @Test
    fun `timesOn lists a day's slots and nothing for a free day`() {
        val schedule = schedule(MONDAY to listOf("13:00", "09:00"))
        assertThat(schedule.timesOn(MONDAY)).containsExactly(LocalTime.of(9, 0), LocalTime.of(13, 0)).inOrder()
        assertThat(schedule.timesOn(SUNDAY)).isEmpty()
    }

    @Test
    fun `S1 the default schedule has three daytime slots three hours apart on every day`() {
        val slots = listOf(LocalTime.of(10, 0), LocalTime.of(13, 0), LocalTime.of(16, 0))
        assertThat(WeeklySchedule.DEFAULT.times.keys).containsExactlyElementsIn(java.time.DayOfWeek.entries)
        for (day in java.time.DayOfWeek.entries) {
            assertThat(WeeklySchedule.DEFAULT.timesOn(day)).containsExactlyElementsIn(slots).inOrder()
        }
    }
}
