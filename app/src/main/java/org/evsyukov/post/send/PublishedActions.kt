package org.evsyukov.post.send

import kotlinx.coroutines.flow.first
import org.evsyukov.post.PublishTrigger
import org.evsyukov.post.data.db.PostKind
import org.evsyukov.post.data.history.HistoryRepository
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.PostsRepository
import org.evsyukov.post.data.posts.Schedule
import java.time.Clock

/**
 * What the user does with a published post (product.md R37–R41): deletion goes through the queue like a post, so
 * it is saved locally first, retried, and lands in the Failed group when it fails (R41).
 */
class PublishedActions(
    private val posts: PostsRepository,
    private val history: HistoryRepository,
    private val trigger: PublishTrigger,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * R39: queues the deletion of the record's copies on [accountIds] (every account by default in the UI) and
     * starts sending. Returns the id of the DELETE post.
     *
     * @throws IllegalArgumentException when the record is gone or [accountIds] is empty or names an account
     *   that holds no copy.
     */
    suspend fun delete(historyId: Long, accountIds: List<Long>): Long {
        val record = history.get(historyId) ?: throw IllegalArgumentException("no history record $historyId")
        val holders = record.items.map { it.accountId }.toSet()
        require(accountIds.isNotEmpty() && accountIds.all { it in holders }) { "accounts $accountIds do not all hold the post" }
        val id = posts.saveAndQueue(
            DraftInput(text = record.text, options = record.options, targets = accountIds, kind = PostKind.DELETE, historyId = historyId),
            Schedule.Now(clock.instant()),
        )
        trigger.publishNow()
        return id
    }

    /** Whether an edit or a deletion of the record waits or failed in the queue; another one would race it. */
    suspend fun pending(historyId: Long): Boolean = posts.observeQueue().first().any { it.historyId == historyId }
}
