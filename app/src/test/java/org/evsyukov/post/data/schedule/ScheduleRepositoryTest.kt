package org.evsyukov.post.data.schedule

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.core.schedule.WeeklySchedule
import org.evsyukov.post.data.db.PostDatabase
import org.evsyukov.post.data.db.writeDefaultScheduleIfEmpty
import org.evsyukov.post.data.inMemoryDatabase
import org.evsyukov.post.data.rows
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.LocalTime

/** The weekly schedule (S1) stored as rows of `schedule_slots`. */
@RunWith(AndroidJUnit4::class)
class ScheduleRepositoryTest {
    private val db = inMemoryDatabase()
    private val repo = RoomScheduleRepository(db)

    @After
    fun tearDown() = db.close()

    @Test
    fun noSlotsGiveAnEmptySchedule() = runBlocking<Unit> {
        // S1: an empty schedule is allowed. The in-memory database of the tests has no default schedule.
        assertThat(repo.get()).isEqualTo(WeeklySchedule.EMPTY)
        assertThat(repo.observe().first().isEmpty()).isTrue()
    }

    @Test
    fun aNewDatabaseFileStartsWithTheDefaultScheduleAndAClearedOneStaysEmpty() = runBlocking<Unit> {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "default-schedule-test.db"
        context.deleteDatabase(name)
        try {
            val first = PostDatabase.builder(context, name).allowMainThreadQueries().build()
            try {
                val schedule = RoomScheduleRepository(first)
                assertThat(schedule.get()).isEqualTo(WeeklySchedule.DEFAULT)
                // The user clears the schedule.
                schedule.save(WeeklySchedule.EMPTY)
            } finally {
                first.close()
            }

            val reopened = PostDatabase.builder(context, name).allowMainThreadQueries().build()
            try {
                assertThat(RoomScheduleRepository(reopened).get()).isEqualTo(WeeklySchedule.EMPTY)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun theDefaultIsNotWrittenOverASchedule() = runBlocking<Unit> {
        val own = WeeklySchedule(mapOf(DayOfWeek.MONDAY to listOf(LocalTime.of(9, 0))))
        repo.save(own)

        db.openHelper.writableDatabase.writeDefaultScheduleIfEmpty()

        assertThat(repo.get()).isEqualTo(own)
    }

    @Test
    fun theDefaultIsWrittenIntoAnEmptySchedule() = runBlocking<Unit> {
        db.openHelper.writableDatabase.writeDefaultScheduleIfEmpty()

        assertThat(repo.get()).isEqualTo(WeeklySchedule.DEFAULT)
    }

    @Test
    fun theScheduleComesBackAsItWasSaved() = runBlocking<Unit> {
        // The example of S1: Monday–Friday 09:00, 13:00, 18:00 and Saturday 11:00; plus the day's edges.
        val weekdays = listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        val times = weekdays.associateWith { listOf(LocalTime.of(18, 0), LocalTime.of(9, 0), LocalTime.of(13, 0)) } +
            mapOf(DayOfWeek.SATURDAY to listOf(LocalTime.of(11, 0)), DayOfWeek.SUNDAY to listOf(LocalTime.MIDNIGHT, LocalTime.of(23, 59)))
        val schedule = WeeklySchedule(times)

        repo.save(schedule)

        assertThat(repo.get()).isEqualTo(schedule)
        assertThat(repo.observe().first()).isEqualTo(schedule)
        assertThat(repo.get().timesOn(DayOfWeek.MONDAY)).containsExactly(LocalTime.of(9, 0), LocalTime.of(13, 0), LocalTime.of(18, 0)).inOrder()
        // One row per slot: ISO day of week and minutes after midnight.
        assertThat(db.rows("SELECT dayOfWeek, minuteOfDay FROM schedule_slots WHERE dayOfWeek IN (6, 7) ORDER BY dayOfWeek, minuteOfDay"))
            .containsExactly(listOf(6L, 660L), listOf(7L, 0L), listOf(7L, 1439L)).inOrder()
    }

    @Test
    fun savingReplacesTheWholeSchedule() = runBlocking<Unit> {
        repo.save(WeeklySchedule(mapOf(DayOfWeek.MONDAY to listOf(LocalTime.of(9, 0)), DayOfWeek.FRIDAY to listOf(LocalTime.of(17, 30)))))
        val replacement = WeeklySchedule(mapOf(DayOfWeek.TUESDAY to listOf(LocalTime.of(8, 15))))

        repo.save(replacement)
        assertThat(repo.get()).isEqualTo(replacement)

        repo.save(WeeklySchedule.EMPTY)
        assertThat(repo.get()).isEqualTo(WeeklySchedule.EMPTY)
    }
}
