package org.evsyukov.post.core.schedule

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * The weekly schedule of slots (scheduling.md, S1): local wall-clock times for each weekday.
 *
 * The constructor normalizes its input, so two schedules with the same slots are equal:
 * times are cut to whole minutes, sorted and made distinct, and weekdays without times are dropped.
 * It is a plain class rather than a data class because a data class cannot normalize its property;
 * equality, hash code and string form are those of [times].
 */
class WeeklySchedule(times: Map<DayOfWeek, List<LocalTime>>) {

    /** Normalized slot times by weekday; a weekday without slots has no entry. */
    val times: Map<DayOfWeek, List<LocalTime>> = times
        .mapValues { (_, list) -> list.map { it.truncatedTo(ChronoUnit.MINUTES) }.distinct().sorted() }
        .filterValues { it.isNotEmpty() }
        .toSortedMap()

    /** An empty schedule is allowed (S1); then there is no next free slot. */
    fun isEmpty(): Boolean = times.isEmpty()

    /** The sorted slot times of [day], empty when the day has none. */
    fun timesOn(day: DayOfWeek): List<LocalTime> = times[day].orEmpty()

    override fun equals(other: Any?): Boolean = other is WeeklySchedule && other.times == times

    override fun hashCode(): Int = times.hashCode()

    override fun toString(): String = "WeeklySchedule(times=$times)"

    companion object {
        val EMPTY = WeeklySchedule(emptyMap())
    }
}
