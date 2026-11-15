package org.evsyukov.post.data.schedule

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.evsyukov.post.core.schedule.WeeklySchedule
import org.evsyukov.post.data.db.PostDatabase
import org.evsyukov.post.data.db.ScheduleSlotEntity
import java.time.DayOfWeek
import java.time.LocalTime

/** The weekly schedule of slots (R26, scheduling.md S1). */
interface ScheduleRepository {
    fun observe(): Flow<WeeklySchedule>

    suspend fun get(): WeeklySchedule

    /** Replaces the schedule. Queued posts keep their times (S6). */
    suspend fun save(schedule: WeeklySchedule)
}

class RoomScheduleRepository(db: PostDatabase) : ScheduleRepository {
    private val dao = db.scheduleDao()

    override fun observe(): Flow<WeeklySchedule> = dao.observeAll().map { it.toSchedule() }

    override suspend fun get(): WeeklySchedule = dao.all().toSchedule()

    override suspend fun save(schedule: WeeklySchedule) {
        dao.replaceAll(
            schedule.times.flatMap { (day, times) ->
                times.map { ScheduleSlotEntity(day.value, it.hour * MINUTES_PER_HOUR + it.minute) }
            },
        )
    }

    private fun List<ScheduleSlotEntity>.toSchedule(): WeeklySchedule = WeeklySchedule(
        groupBy({ DayOfWeek.of(it.dayOfWeek) }) {
            LocalTime.of(it.minuteOfDay / MINUTES_PER_HOUR, it.minuteOfDay % MINUTES_PER_HOUR)
        },
    )

    private companion object {
        const val MINUTES_PER_HOUR = 60
    }
}
