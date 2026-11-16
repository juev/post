package org.evsyukov.post.data.history

import org.evsyukov.post.data.db.PostKind
import org.evsyukov.post.data.posts.DeliveryPlan
import org.evsyukov.post.data.posts.DeliveryProgress
import org.evsyukov.post.data.posts.PublishedPart

/**
 * What the account's copy is after a delivery of an edit or a deletion acted on it (R38, R39, #4): the parts
 * the delivery published, edited or left alone take their place by index, the parts it deleted go, and the
 * old parts it did not reach stay. [succeeded] tells a delivery that finished from one cut short; only a
 * finished edit has its Mastodon options on every part. Returns null when no part of the copy is left.
 *
 * It applies both when a delivery succeeds and when the user deletes an edit that was applied in part, so the
 * record names what is on the server either way.
 */
internal fun HistoryItem.after(kind: PostKind, plan: DeliveryPlan?, progress: DeliveryProgress, succeeded: Boolean): HistoryItem? {
    val gone = progress.deleted.toSet()
    fun PublishedPart.left(): Boolean = listOfNotNull(id, uri).none { it in gone }
    val parts = when (kind) {
        PostKind.NEW -> return this
        PostKind.DELETE -> parts.filter { it.left() }
        PostKind.EDIT -> {
            val published = progress.parts.mapNotNull { part -> part.published?.let { part.index to it } }.toMap()
            val count = maxOf(parts.size, (published.keys.maxOrNull() ?: -1) + 1)
            (0 until count).mapNotNull { index ->
                published[index]?.let { part ->
                    // As Publisher.complete writes it: the text and content warning of the plan.
                    val planned = plan?.parts?.getOrNull(index)
                    if (planned == null) part else part.copy(text = planned.text, spoilerText = planned.spoilerText)
                } ?: parts.getOrNull(index)?.takeIf { it.left() }
            }
        }
    }
    if (parts.isEmpty()) return null
    val options = if (kind == PostKind.EDIT && succeeded) plan?.mastodon ?: mastodon else mastodon
    return copy(parts = parts, mastodon = options)
}

/** [items] with the item of [accountId] replaced by [item], or dropped when [item] is null. */
internal fun List<HistoryItem>.withItem(accountId: Long, item: HistoryItem?): List<HistoryItem> =
    if (item == null) filter { it.accountId != accountId } else map { if (it.accountId == accountId) item else it }
