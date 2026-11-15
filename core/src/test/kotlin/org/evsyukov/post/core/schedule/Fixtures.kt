package org.evsyukov.post.core.schedule

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

internal val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
internal val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
internal val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")

internal fun local(text: String): LocalDateTime = LocalDateTime.parse(text)

internal fun at(text: String): Instant = Instant.parse(text)

/** The instant of the local time [text] in [zone], for times that are neither in a gap nor an overlap. */
internal fun atLocal(text: String, zone: ZoneId): Instant = local(text).atZone(zone).toInstant()

internal fun schedule(vararg days: Pair<DayOfWeek, List<String>>): WeeklySchedule =
    WeeklySchedule(days.associate { (day, times) -> day to times.map(LocalTime::parse) })

internal fun slotPost(id: Long, time: String, order: Long = id) =
    QueuedRef(id = id, time = SendTime.Slot(local(time)), order = order, started = false, held = false, failed = false)

internal fun explicitPost(id: Long, instant: Instant, order: Long = id) =
    QueuedRef(id = id, time = SendTime.Explicit(instant), order = order, started = false, held = false, failed = false)
