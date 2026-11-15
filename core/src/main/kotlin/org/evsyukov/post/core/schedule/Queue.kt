package org.evsyukov.post.core.schedule

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * What the schedule rules need to know about a queued post.
 *
 * @property order the post's position among posts with the same send time (`queueOrder`).
 * @property started the first delivery has started; the post can no longer move (S9).
 * @property held the post missed its time and waits in the Late group (Q5).
 * @property failed the post is in the Failed group.
 */
data class QueuedRef(
    val id: Long,
    val time: SendTime,
    val order: Long,
    val started: Boolean = false,
    val held: Boolean = false,
    val failed: Boolean = false,
)

/** A slot-based post that waits for its slot: the kind of post that dragging and Redistribute move. */
private val QueuedRef.isMovable: Boolean
    get() = time is SendTime.Slot && !started && !held && !failed

/**
 * [posts] in queue order: by send time in [zone], then by [QueuedRef.order], then by id.
 * Posts due at the same time go out in this order (Q8).
 */
fun inQueueOrder(posts: List<QueuedRef>, zone: ZoneId): List<QueuedRef> =
    posts.sortedWith(compareBy<QueuedRef> { it.time.toInstant(zone) }.thenBy { it.order }.thenBy { it.id })

/**
 * The slot times after the user drags a slot-based post from [fromIndex] to [toIndex] (S8).
 *
 * [slotPostsInOrder] lists the slot-based posts of the Queue tab in their current order. The set of
 * their slot times stays the same: after the move the posts take those times again, the earliest time
 * to the first post of the new order. Example: A 09:00, B 13:00, C 18:00; moving C from 2 to 0 gives
 * C 09:00, A 13:00, B 18:00.
 *
 * Only waiting slot-based posts can be dragged (S9). Posts with an explicit time keep their time and
 * are not passed here; the dragged posts move past them. A post that has started, is held (Late) or
 * has failed is rejected too, as is an index outside the list.
 *
 * @return the slot time of every given post after the move, by post id.
 * @throws IllegalArgumentException when the list holds a post that cannot be dragged, repeats a post
 *   id, or an index is out of range.
 */
fun reorder(slotPostsInOrder: List<QueuedRef>, fromIndex: Int, toIndex: Int): Map<Long, LocalDateTime> {
    require(fromIndex in slotPostsInOrder.indices) { "fromIndex $fromIndex is outside ${slotPostsInOrder.indices}" }
    require(toIndex in slotPostsInOrder.indices) { "toIndex $toIndex is outside ${slotPostsInOrder.indices}" }
    slotPostsInOrder.forEach { post ->
        require(post.time is SendTime.Slot) { "post ${post.id} has an explicit time and cannot be dragged" }
        require(!post.started) { "post ${post.id} has started sending and cannot be dragged" }
        require(!post.held) { "post ${post.id} is held as late and cannot be dragged" }
        require(!post.failed) { "post ${post.id} has failed and cannot be dragged" }
    }
    require(slotPostsInOrder.map { it.id }.toSet().size == slotPostsInOrder.size) { "post ids repeat" }

    val times = slotPostsInOrder.map { (it.time as SendTime.Slot).local }.sorted()
    val newOrder = slotPostsInOrder.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    return newOrder.zip(times) { post, time -> post.id to time }.toMap()
}

/**
 * Move up from the row menu (S10): the post at [index] swaps places with the one before it, by the
 * rule of [reorder]. The first post cannot move up.
 */
fun moveUp(slotPostsInOrder: List<QueuedRef>, index: Int): Map<Long, LocalDateTime> {
    require(index in 1..slotPostsInOrder.lastIndex) { "post at $index cannot move up" }
    return reorder(slotPostsInOrder, index, index - 1)
}

/**
 * Move down from the row menu (S10): the post at [index] swaps places with the one after it, by the
 * rule of [reorder]. The last post cannot move down.
 */
fun moveDown(slotPostsInOrder: List<QueuedRef>, index: Int): Map<Long, LocalDateTime> {
    require(index in 0 until slotPostsInOrder.lastIndex) { "post at $index cannot move down" }
    return reorder(slotPostsInOrder, index, index + 1)
}

/**
 * Redistribute on the Schedule screen (S6): the waiting slot-based posts of [postsInOrder], in that
 * order, take the free slots of [schedule] from the next free slot on (S4).
 *
 * Posts with an explicit time keep it and do not occupy slots (S5). Posts that have started, are held
 * or have failed keep their time as well; none of them holds a future slot (Q3, Q5). With an empty
 * schedule no post moves.
 *
 * @return the new slot time of each moved post, by post id; posts that keep their time are absent.
 */
fun redistribute(
    postsInOrder: List<QueuedRef>,
    schedule: WeeklySchedule,
    zone: ZoneId,
    now: Instant,
): Map<Long, LocalDateTime> {
    val movable = postsInOrder.filter { it.isMovable }
    val slots = freeSlots(schedule, zone, now, emptySet(), movable.size)
    return movable.zip(slots) { post, slot -> post.id to slot }.toMap()
}

/**
 * Reschedule of the late posts (Q6): each held post of [heldInOrder], in that order, gets the next
 * free slot. A post with an explicit time also gets a slot. [occupied] holds the slot times of the
 * other queued posts. With an empty schedule there are no slots and no post moves.
 *
 * @return the new slot time of each post, by post id.
 * @throws IllegalArgumentException when a post is not held or has started sending.
 */
fun reschedule(
    heldInOrder: List<QueuedRef>,
    schedule: WeeklySchedule,
    zone: ZoneId,
    now: Instant,
    occupied: Set<LocalDateTime>,
): Map<Long, LocalDateTime> {
    heldInOrder.forEach { post ->
        require(post.held) { "post ${post.id} is not held" }
        require(!post.started) { "post ${post.id} has started sending" }
    }
    val slots = freeSlots(schedule, zone, now, occupied, heldInOrder.size)
    return heldInOrder.zip(slots) { post, slot -> post.id to slot }.toMap()
}

/**
 * The ids of the posts that are due at [now], in queue order (Q8): their send time in [zone] is not
 * after [now], and they are not held, started or failed.
 *
 * Lateness is a separate check: the job puts late posts on hold with [isLate] before it sends (Q5).
 */
fun dueOrder(posts: List<QueuedRef>, zone: ZoneId, now: Instant): List<Long> =
    inQueueOrder(posts, zone)
        .filter { !it.held && !it.started && !it.failed && !it.time.toInstant(zone).isAfter(now) }
        .map { it.id }
