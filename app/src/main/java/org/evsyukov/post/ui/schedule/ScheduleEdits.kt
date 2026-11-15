package org.evsyukov.post.ui.schedule

import org.evsyukov.post.core.schedule.WeeklySchedule
import java.time.DayOfWeek
import java.time.LocalTime

/** The weekdays Monday to Friday, the target of "Copy to weekdays". */
val WEEKDAYS: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

/**
 * The schedule with the time [old] of [day] replaced by [new]: [old] null adds [new], [new] null deletes [old].
 * The schedule keeps its times sorted and distinct, so adding a time the day has changes nothing.
 */
fun WeeklySchedule.withTime(day: DayOfWeek, old: LocalTime?, new: LocalTime?): WeeklySchedule {
    val times = timesOn(day).filter { it != old } + listOfNotNull(new)
    return WeeklySchedule(this.times + (day to times))
}

/** "Copy to weekdays" and "Copy to all days" in the day menu: the days of [targets] take the times of [day]. */
fun WeeklySchedule.copyDay(day: DayOfWeek, targets: Set<DayOfWeek>): WeeklySchedule {
    val times = timesOn(day)
    return WeeklySchedule(this.times + targets.associateWith { times })
}
