package org.evsyukov.post.ui.posts

import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.posts.Post
import org.evsyukov.post.send.schedule.queueRefs
import java.time.ZoneId

/**
 * The groups of the Queue tab (screens.md, Posts; scheduling.md Q7): Late at the top, then Failed, then the
 * posts that wait or are being sent, each in queue order (by time, then queue order; Q8).
 */
data class QueueGroups(val late: List<Post>, val failed: List<Post>, val waiting: List<Post>) {
    val isEmpty: Boolean get() = late.isEmpty() && failed.isEmpty() && waiting.isEmpty()
}

fun queueGroups(queue: List<Post>, zone: ZoneId): QueueGroups {
    val byId = queue.associateBy { it.id }
    val ordered = queueRefs(queue, zone).mapNotNull { byId[it.id] }
    return QueueGroups(
        late = ordered.filter { it.state == PostState.HELD },
        failed = ordered.filter { it.state == PostState.FAILED },
        waiting = ordered.filter { it.state == PostState.QUEUED || it.state == PostState.SENDING },
    )
}

/** [order] with [moved] put where [target] is: what a drag of one row over another does to the list. */
fun moveOver(order: List<Long>, moved: Long, target: Long): List<Long> {
    val from = order.indexOf(moved)
    val to = order.indexOf(target)
    if (from < 0 || to < 0 || from == to) return order
    return order.toMutableList().apply { add(to, removeAt(from)) }
}

/**
 * Where a dropped post lands among the slot-based posts that can move (S8): its index in [displayOrder] once
 * the other rows are left out. Null when it is not one of [movable] or did not move.
 */
fun dropIndex(displayOrder: List<Long>, movable: List<Long>, moved: Long): Int? {
    val movableSet = movable.toSet()
    val newOrder = displayOrder.filter { it in movableSet }
    val index = newOrder.indexOf(moved)
    if (index < 0 || index == movable.indexOf(moved)) return null
    return index
}
