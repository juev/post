package org.evsyukov.post.send.schedule

import org.evsyukov.post.core.schedule.QueuedRef
import org.evsyukov.post.core.schedule.SendTime
import org.evsyukov.post.core.schedule.inQueueOrder
import org.evsyukov.post.core.schedule.isLate
import org.evsyukov.post.core.schedule.toInstant
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.posts.Post
import org.evsyukov.post.data.posts.Schedule
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The times the sender has to wake up for (scheduling.md M1): the send time of every queued post that has
 * not started, a slot resolved in [zone] (S2), and the next attempt of every waiting delivery (M6, R29).
 * Held and failed posts wait for the user and give none.
 */
fun pendingWakeTimes(queue: List<Post>, zone: ZoneId): List<Instant> {
    val times = mutableListOf<Instant>()
    for (post in queue) {
        if (post.state == PostState.HELD || post.state == PostState.FAILED) continue
        if (post.state == PostState.QUEUED && !post.started) post.sendTimeIn(zone)?.let(times::add)
        post.deliveries
            .filter { it.state == DeliveryState.WAITING }
            .mapNotNullTo(times) { it.nextAttemptAt }
    }
    return times.sorted()
}

/**
 * The time of the one exact alarm (M1): the earliest pending time after [now], or null when nothing waits.
 * A time already passed is left to the run that is going on or waiting for the network: an alarm in the past
 * would fire at once, again and again, for a post the run cannot send yet.
 */
fun nextWake(queue: List<Post>, zone: ZoneId, now: Instant): Instant? =
    pendingWakeTimes(queue, zone).firstOrNull { it.isAfter(now) }

/** The send time of a queued post in [zone]: a slot is resolved again, so it follows the device (S2). */
fun Post.sendTimeIn(zone: ZoneId): Instant? = toQueuedRef()?.time?.toInstant(zone)

/**
 * The posts the job puts on hold at [now] (Q4, Q5): queued, no delivery started, and the send time passed more
 * than [threshold] ago. A post due but not late is sent as usual.
 */
fun latePosts(queue: List<Post>, zone: ZoneId, now: Instant, threshold: Duration): List<Long> =
    queue.filter { post ->
        post.state == PostState.QUEUED && !post.started && post.sendTimeIn(zone)?.let { isLate(it, now, threshold) } == true
    }.map { it.id }

/** The posts of [queue] as core sees them, in queue order (Q8). */
fun queueRefs(queue: List<Post>, zone: ZoneId): List<QueuedRef> = inQueueOrder(queue.mapNotNull { it.toQueuedRef() }, zone)

/** The held posts in queue order: the Late group (Q6, Q7). */
fun heldInOrder(queue: List<Post>, zone: ZoneId): List<QueuedRef> = queueRefs(queue, zone).filter { it.held }

/**
 * The slot-based posts that wait for their slot, in queue order: the posts dragging and Move up/down change
 * (S8–S10). Explicit, started, held and failed posts are not among them (S9).
 */
fun movableSlotPosts(queue: List<Post>, zone: ZoneId): List<QueuedRef> =
    queueRefs(queue, zone).filter { it.time is SendTime.Slot && !it.started && !it.held && !it.failed }

/**
 * The slots held by waiting slot-based posts (S3): a held, failed or started post no longer holds one (plan.md,
 * step 9 notes). [except] leaves out a post whose time is about to change.
 */
fun occupiedSlots(queue: List<Post>, zone: ZoneId, except: Long? = null): Set<LocalDateTime> =
    movableSlotPosts(queue, zone).filter { it.id != except }.map { (it.time as SendTime.Slot).local }.toSet()

/** The slot times of [ids] as they are now, to put them back on Undo (S10). */
fun slotSchedules(queue: List<Post>, ids: Collection<Long>): Map<Long, Schedule> =
    queue.filter { it.id in ids }.mapNotNull { post -> (post.schedule as? Schedule.Slot)?.let { post.id to it } }.toMap()

/** How many free slots the Send later sheet offers: the next free slot and five more (screens.md). */
const val SEND_LATER_SLOTS = 6

/**
 * The rows of the Send later sheet (R24): the next free slot first, then the next five free slots, at least a
 * minute after [now] (S4). Empty without a schedule. [except] is a queued post whose time is being changed: its
 * own slot counts as free.
 */
fun sendLaterSlots(
    queue: List<Post>,
    schedule: org.evsyukov.post.core.schedule.WeeklySchedule,
    zone: ZoneId,
    now: Instant,
    except: Long? = null,
): List<LocalDateTime> =
    org.evsyukov.post.core.schedule.freeSlots(schedule, zone, now, occupiedSlots(queue, zone, except), SEND_LATER_SLOTS)
