package org.evsyukov.post.send.bluesky

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.evsyukov.post.core.markdown.MANUAL_BREAK
import org.evsyukov.post.data.accounts.ReplyRule
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.db.PostKind
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.history.HistoryRecord
import org.evsyukov.post.data.posts.AccountPostOptions
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.PostOptions
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.send.PublishedActions
import org.evsyukov.post.send.SendFixture
import org.evsyukov.post.send.blocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deleting a published Bluesky thread and "delete and post again" (product.md R39–R41; networks.md N32, N33),
 * with MockWebServer as the PDS.
 */
@RunWith(AndroidJUnit4::class)
class BlueskyEditDeleteTest {
    private val f = SendFixture(withBluesky = true)
    private val pds = FakePds(f.servers)

    @After
    fun tearDown() = f.close()

    private fun thread(vararg parts: String) = parts.joinToString("\n$MANUAL_BREAK\n")

    /** A thread published with a threadgate on its first part and a postgate on every part. */
    private fun published(account: Long, vararg parts: String): HistoryRecord {
        val options = PostOptions(accounts = mapOf(account to AccountPostOptions(replyRules = setOf(ReplyRule.FOLLOWERS), allowQuotes = false)))
        val id = f.queue(thread(*parts), listOf(account), options)
        f.run()
        check(f.post(id)!!.state == PostState.SENT)
        return blocking { f.history.observe().first().single() }
    }

    private fun rkeys(record: HistoryRecord) = record.items.single().parts.map { it.uri!!.substringAfterLast('/') }

    @Test
    fun deleteRemovesEveryPartAndItsGatesInOneApplyWrites() {
        val alice = f.bluesky()
        val record = published(alice, "one", "two", "three")
        val keys = rkeys(record)
        assertThat(pds.records).hasSize(3 + 1 + 3)

        blocking { PublishedActions(f.posts, f.history, { }, f.clock).delete(record.id, listOf(alice)) }
        f.run()

        // N33: one call; parts last first, the threadgate, every postgate.
        val deleted = pds.deleteCommits.single()
        assertThat(deleted.filter { it.startsWith("app.bsky.feed.post/") })
            .containsExactlyElementsIn(keys.reversed().map { "app.bsky.feed.post/$it" }).inOrder()
        assertThat(deleted).contains("app.bsky.feed.threadgate/${keys.first()}")
        assertThat(deleted.filter { it.startsWith("app.bsky.feed.postgate/") }).hasSize(3)
        assertThat(pds.records).isEmpty()
        // R39: the record leaves history.
        assertThat(blocking { f.history.get(record.id) }).isNull()
    }

    @Test
    fun aPartAlreadyGoneIsLeftOutOfTheDeletion() {
        val alice = f.bluesky()
        val record = published(alice, "one", "two")
        val keys = rkeys(record)
        pds.records.remove("app.bsky.feed.post/${keys[1]}")
        pds.records.remove("app.bsky.feed.postgate/${keys[1]}")

        blocking { PublishedActions(f.posts, f.history, { }, f.clock).delete(record.id, listOf(alice)) }
        f.run()

        assertThat(pds.deleteCommits.single()).containsExactly(
            "app.bsky.feed.post/${keys[0]}",
            "app.bsky.feed.threadgate/${keys[0]}",
            "app.bsky.feed.postgate/${keys[0]}",
        )
        assertThat(blocking { f.history.get(record.id) }).isNull()
    }

    @Test
    fun deleteAndPostAgainRemovesTheOldThreadThenPublishesTheNewOne() {
        val alice = f.bluesky()
        val record = published(alice, "one", "two")
        val oldKeys = rkeys(record)

        val editId = blocking {
            f.posts.saveAndQueue(
                DraftInput(text = thread("one", "two", "three"), options = record.options, targets = listOf(alice), kind = PostKind.EDIT, historyId = record.id),
                Schedule.Now(f.clock.instant()),
            )
        }
        f.run()

        assertThat(pds.deleteCommits.single()).hasSize(2 + 1 + 2)
        assertThat(pds.posts.drop(2).map { it.text }).containsExactly("one", "two", "three").inOrder()
        assertThat(f.post(editId)).isNull()
        // History keeps the new copy: new keys, new text.
        val after = blocking { f.history.get(record.id) }!!
        val newKeys = rkeys(after)
        assertThat(newKeys).hasSize(3)
        assertThat(newKeys).containsNoneIn(oldKeys)
        assertThat(after.items.single().parts.map { it.text }).containsExactly("one", "two", "three").inOrder()
        assertThat(after.text).isEqualTo(thread("one", "two", "three"))
    }

    @Test
    fun aRetryAfterTheOldThreadWentDoesNotDeleteAgain() {
        val alice = f.bluesky()
        val record = published(alice, "one")
        // The first write of the new thread fails with a server error; the deletion already happened.
        var creates = 0
        pds.writeAnswer = { _, request ->
            if (request.body.contains("applyWrites#create") && creates++ == 0) FakePds.error(502, "BadGateway", "upstream") else null
        }

        val editId = blocking {
            f.posts.saveAndQueue(
                DraftInput(text = "new one", options = record.options, targets = listOf(alice), kind = PostKind.EDIT, historyId = record.id),
                Schedule.Now(f.clock.instant()),
            )
        }
        f.run()

        val waiting = f.post(editId)!!.deliveries.single()
        assertThat(waiting.state).isEqualTo(DeliveryState.WAITING)
        assertThat(waiting.progress.oldRemoved).isTrue()

        f.clock.advance(java.time.Duration.ofMinutes(5))
        f.run()

        assertThat(pds.deleteCommits).hasSize(1)
        assertThat(pds.posts.last().text).isEqualTo("new one")
        assertThat(f.post(editId)).isNull()
    }
}
