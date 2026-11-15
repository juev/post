package org.evsyukov.post.core.schedule

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** A slot must be at least this far after now to be assigned (S4). */
val MIN_SLOT_LEAD: Duration = Duration.ofMinutes(1)

/**
 * The slots of [schedule] whose instant in [zone] is at least one minute after [from] (S4), in time
 * order, at most [count] of them. Each slot is its local date and time.
 *
 * Slots are taken day by day in local time, so a slot inside an overlap is listed once, at its first
 * occurrence; once that occurrence has passed, the slot is not offered again at the second one (S2).
 * A slot inside a gap keeps its local time and resolves to the end of the gap ([resolveLocal]).
 * An empty schedule has no slots.
 */
fun upcomingSlots(schedule: WeeklySchedule, zone: ZoneId, from: Instant, count: Int): List<LocalDateTime> {
    require(count >= 0) { "count must not be negative: $count" }
    return slotSequence(schedule, zone, from.plus(MIN_SLOT_LEAD)).take(count).toList()
}

/**
 * The earliest slot at least one minute after [now] that no queued post holds (S3, S4), or null for
 * an empty schedule. [occupied] holds the slot times of the queued slot-based posts.
 */
fun nextFreeSlot(
    schedule: WeeklySchedule,
    zone: ZoneId,
    now: Instant,
    occupied: Set<LocalDateTime>,
): LocalDateTime? = freeSlots(schedule, zone, now, occupied, 1).firstOrNull()

/**
 * The next [count] free slots in time order, the first of them being [nextFreeSlot]. The Send later
 * sheet shows them (R24, screens.md); an empty schedule gives an empty list, and the sheet then shows
 * only the date and time picker.
 */
fun freeSlots(
    schedule: WeeklySchedule,
    zone: ZoneId,
    now: Instant,
    occupied: Set<LocalDateTime>,
    count: Int,
): List<LocalDateTime> {
    require(count >= 0) { "count must not be negative: $count" }
    return slotSequence(schedule, zone, now.plus(MIN_SLOT_LEAD))
        .filter { it !in occupied }
        .take(count)
        .toList()
}

/** A slot on the Schedule screen with the id of the queued post that holds it, or null when it is free. */
data class SlotView(val local: LocalDateTime, val postId: Long?)

/**
 * The Coming slots of the Schedule screen (screens.md): every slot of the next [days] days, each with
 * the post that holds it. [occupiedBy] maps a slot time to the id of the queued post assigned to it.
 *
 * The window starts at [now] and ends [days] days later in local time, so with the default of seven
 * days every slot of the week appears once. A slot that is due (its instant is not after [now]) is no
 * longer coming. Unlike [freeSlots] it keeps slots of the next minute: they may still hold a post.
 */
fun comingSlots(
    schedule: WeeklySchedule,
    zone: ZoneId,
    now: Instant,
    occupiedBy: Map<LocalDateTime, Long>,
    days: Int = 7,
): List<SlotView> {
    require(days >= 0) { "days must not be negative: $days" }
    val end = LocalDateTime.ofInstant(now, zone).plusDays(days.toLong())
    return slotSequence(schedule, zone, now.plusNanos(1))
        .takeWhile { it < end }
        .map { SlotView(it, occupiedBy[it]) }
        .toList()
}

/**
 * All slots of [schedule] whose instant in [zone] is not before [earliest], in time order. The
 * sequence is endless for a non-empty schedule and empty for an empty one.
 *
 * Within a day slot times are sorted, and [resolveLocal] never maps a later local time of a day to an
 * earlier instant, so the order by local time is also the order by instant.
 */
internal fun slotSequence(schedule: WeeklySchedule, zone: ZoneId, earliest: Instant): Sequence<LocalDateTime> {
    if (schedule.isEmpty()) return emptySequence()
    // A slot of the previous local date can still resolve to earliest when a gap spans midnight, so the
    // search starts a day early and the filter drops the slots that have passed.
    val firstDate = LocalDateTime.ofInstant(earliest, zone).toLocalDate().minusDays(1)
    return generateSequence(firstDate) { it.plusDays(1) }
        .flatMap { date -> schedule.timesOn(date.dayOfWeek).asSequence().map { date.atTime(it) } }
        .filter { !resolveLocal(it, zone).isBefore(earliest) }
}
