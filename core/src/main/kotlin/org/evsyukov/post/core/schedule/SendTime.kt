package org.evsyukov.post.core.schedule

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * When a queued post is sent (scheduling.md, S2 and S5).
 */
sealed interface SendTime {

    /**
     * A slot of the weekly schedule. It keeps the local date and time of the slot and becomes an
     * instant in the zone the device has when the alarm is set, so it follows the device to a new zone.
     */
    data class Slot(val local: LocalDateTime) : SendTime

    /** An explicit time chosen by the user. It is an absolute instant and does not occupy a slot (S5). */
    data class Explicit(val instant: Instant) : SendTime
}

/** The instant of this send time in [zone]; a slot is resolved with [resolveLocal]. */
fun SendTime.toInstant(zone: ZoneId): Instant = when (this) {
    is SendTime.Slot -> resolveLocal(local, zone)
    is SendTime.Explicit -> instant
}

/**
 * The instant of the local wall-clock time [local] in [zone] (S2).
 *
 * - A local time inside a daylight-saving gap does not exist; it maps to the first minute after the gap,
 *   the instant of the transition. `ZonedDateTime.of` would instead shift it forward by the length of
 *   the gap (02:30 to 03:30 for a one-hour gap), so the transition is read from [java.time.zone.ZoneRules].
 * - A local time inside an overlap occurs twice; it maps to its first occurrence, the earlier instant.
 */
fun resolveLocal(local: LocalDateTime, zone: ZoneId): Instant {
    val rules = zone.rules
    val offsets = rules.getValidOffsets(local)
    if (offsets.isEmpty()) {
        // Only a gap has no valid offset, and then getTransition returns that gap.
        return rules.getTransition(local).instant
    }
    return offsets.minOf { local.toInstant(it) }
}
