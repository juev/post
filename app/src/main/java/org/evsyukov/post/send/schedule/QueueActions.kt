package org.evsyukov.post.send.schedule

import kotlinx.coroutines.flow.first
import org.evsyukov.post.PublishTrigger
import org.evsyukov.post.core.schedule.WeeklySchedule
import org.evsyukov.post.core.schedule.moveDown
import org.evsyukov.post.core.schedule.moveUp
import org.evsyukov.post.core.schedule.redistribute
import org.evsyukov.post.core.schedule.reorder
import org.evsyukov.post.core.schedule.reschedule
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.posts.PostsRepository
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.data.schedule.ScheduleRepository
import org.evsyukov.post.data.settings.Settings
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * What the user does with queued posts: from the Queue tab, the Schedule screen and the late notification
 * (scheduling.md S6–S10, Q3, Q6, Q7). Every change goes through [PostsRepository.updateSchedules] in one
 * transaction; the alarm follows the queue on its own (M1).
 */
class QueueActions(
    private val posts: PostsRepository,
    private val schedule: ScheduleRepository,
    private val trigger: PublishTrigger,
    private val lateNotifier: LateNotifier,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private suspend fun queue() = posts.observeQueue().first()

    /** Q3: the post is due now and its slot is free again; sending starts. */
    suspend fun sendNow(postId: Long) {
        posts.updateSchedules(mapOf(postId to Schedule.Now(clock.instant())))
        trigger.publishNow()
    }

    /** Change time: a slot or an explicit time from the Send later sheet. A held post is queued again. */
    suspend fun changeTime(postId: Long, time: Schedule) {
        posts.updateSchedules(mapOf(postId to time))
    }

    /**
     * Delete from the queue (S7): the post goes with its files and frees its slot; later posts stay. A post
     * with a delivery in progress cannot go; returns false then.
     */
    suspend fun delete(postId: Long): Boolean {
        val post = posts.get(postId) ?: return false
        if (post.deliveries.any { it.state == DeliveryState.SENDING }) return false
        return posts.delete(postId)
    }

    /**
     * Drag and drop (S8, S9): the slot-based post [postId] moves to position [toIndex] among the waiting
     * slot-based posts. Returns the times to put back on Undo (S10), or null when nothing moved.
     */
    suspend fun reorder(postId: Long, toIndex: Int): Map<Long, Schedule>? =
        move(postId) { list, from -> if (from == toIndex) null else reorder(list, from, toIndex) }

    /** Move up in the row menu (S10). Returns the times to put back on Undo, or null at the top. */
    suspend fun moveUp(postId: Long): Map<Long, Schedule>? =
        move(postId) { list, from -> if (from == 0) null else moveUp(list, from) }

    /** Move down in the row menu (S10). Returns the times to put back on Undo, or null at the bottom. */
    suspend fun moveDown(postId: Long): Map<Long, Schedule>? =
        move(postId) { list, from -> if (from == list.lastIndex) null else moveDown(list, from) }

    private suspend fun move(
        postId: Long,
        change: (List<org.evsyukov.post.core.schedule.QueuedRef>, Int) -> Map<Long, LocalDateTime>?,
    ): Map<Long, Schedule>? {
        val queue = queue()
        val list = movableSlotPosts(queue, zone())
        val from = list.indexOfFirst { it.id == postId }
        if (from < 0) return null
        val times = change(list, from) ?: return null
        val before = slotSchedules(queue, times.keys)
        val changed = times.filter { (id, local) -> (before[id] as? Schedule.Slot)?.local != local }
        if (changed.isEmpty()) return null
        posts.updateSchedules(changed.mapValues { Schedule.Slot(it.value) })
        return before.filterKeys { it in changed }
    }

    /** Undo of a reorder (S10): the posts take their times back. */
    suspend fun undo(previous: Map<Long, Schedule>) {
        posts.updateSchedules(previous)
    }

    /** Redistribute on the Schedule screen (S6): waiting slot-based posts take the free slots in their order. */
    suspend fun redistribute(): Int {
        val changes = redistribute(queueRefs(queue(), zone()), schedule.get(), zone(), clock.instant())
        posts.updateSchedules(changes.mapValues { Schedule.Slot(it.value) })
        return changes.size
    }

    /**
     * Send now for the Late group (Q6): every held post, in queue order, is due now. Each gets a time a
     * millisecond apart, so the sender keeps their order (Q8).
     */
    suspend fun sendHeldNow(): Int {
        val held = heldInOrder(queue(), zone())
        if (held.isEmpty()) return 0
        val now = clock.instant()
        posts.updateSchedules(held.mapIndexed { index, post -> post.id to Schedule.Now(now.minusMillis((held.size - index).toLong())) }.toMap())
        lateNotifier.cancelLate()
        trigger.publishNow()
        return held.size
    }

    /**
     * Reschedule for the Late group (Q6): each held post, in queue order, takes the next free slot; a post with
     * an explicit time gets a slot too. With an empty schedule nothing moves and 0 is returned.
     */
    suspend fun rescheduleHeld(): Int {
        val queue = queue()
        val zone = zone()
        val held = heldInOrder(queue, zone)
        if (held.isEmpty()) return 0
        val slots = reschedule(held, schedule.get(), zone, clock.instant(), occupiedSlots(queue, zone))
        if (slots.isEmpty()) return 0
        posts.updateSchedules(slots.mapValues { Schedule.Slot(it.value) })
        if (slots.size == held.size) lateNotifier.cancelLate()
        return slots.size
    }

    /** Whether Reschedule is offered: it needs a schedule (Q6, plan.md step 9 notes). */
    suspend fun canReschedule(): Boolean = !schedule.get().isEmpty()
}

/**
 * Before the job sends anything, it puts late posts on hold (scheduling.md Q4, Q5, M6) and tells the user with
 * one notification that counts every held post (Q6). Holding frees the post's slot.
 */
class LateHolder(
    private val posts: PostsRepository,
    private val settings: suspend () -> Settings,
    private val schedule: suspend () -> WeeklySchedule,
    private val notifier: LateNotifier,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    /** Holds the posts late at [now]; returns their ids. */
    suspend fun holdLate(now: Instant): List<Long> {
        val late = latePosts(posts.observeQueue().first(), zone(), now, settings().lateThreshold.duration)
        if (late.isEmpty()) return emptyList()
        val held = posts.hold(late)
        if (held.isNotEmpty()) {
            val count = posts.observeQueue().first().count { it.state == PostState.HELD }
            notifier.showLate(count, canReschedule = !schedule().isEmpty())
        }
        return held
    }
}
