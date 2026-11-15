package org.evsyukov.post.send.schedule

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.PublishTrigger
import org.evsyukov.post.core.schedule.WeeklySchedule
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.Post
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.data.schedule.RoomScheduleRepository
import org.evsyukov.post.send.SendFixture
import java.io.Closeable
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The sender of [SendFixture] with the scheduling parts around it: the schedule, the queue actions and the
 * late check before every run. Everything runs in UTC; the clock starts on Monday 2026-11-16 08:00.
 */
class SchedulingFixture : Closeable {
    private val previousZone: TimeZone = TimeZone.getDefault()

    init {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    val send = SendFixture()
    val clock get() = send.clock
    val schedule = RoomScheduleRepository(send.db)
    val late = RecordingLateNotifier()
    val triggers = AtomicInteger()
    val actions = QueueActions(send.posts, schedule, PublishTrigger { triggers.incrementAndGet() }, late, send.clock) { ZoneOffset.UTC }
    val holder = LateHolder(send.posts, { send.settings }, { schedule.get() }, late) { ZoneOffset.UTC }

    init {
        send.beforeSend = { holder.holdLate(it) }
    }

    fun slots(vararg days: Pair<DayOfWeek, List<String>>) = runBlocking {
        schedule.save(WeeklySchedule(days.toMap().mapValues { (_, times) -> times.map(LocalTime::parse) }))
    }

    fun queue(text: String, at: Schedule, targets: List<Long>): Long = runBlocking {
        send.posts.saveAndQueue(DraftInput(text = text, targets = targets), at)
    }

    fun slot(text: String, local: String, targets: List<Long>) = queue(text, Schedule.Slot(LocalDateTime.parse(local)), targets)

    fun post(id: Long): Post = runBlocking { send.posts.get(id)!! }

    fun queueNow(): List<Post> = runBlocking { send.posts.observeQueue().first() }

    override fun close() {
        send.close()
        TimeZone.setDefault(previousZone)
    }
}

/** Remembers what the late notification showed. */
class RecordingLateNotifier : LateNotifier {
    val shown = CopyOnWriteArrayList<Pair<Int, Boolean>>()
    var cancelled = 0

    override fun showLate(count: Int, canReschedule: Boolean) {
        shown += count to canReschedule
    }

    override fun cancelLate() {
        cancelled++
    }
}
