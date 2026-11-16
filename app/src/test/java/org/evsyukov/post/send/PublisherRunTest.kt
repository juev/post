package org.evsyukov.post.send

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.PublishTrigger
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.text.AndroidTextSegmenter
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * A run of the sender as a whole (R27, M1): a post it cannot handle does not hold up the posts after it, and the
 * next run is set up whatever happened.
 */
@RunWith(AndroidJUnit4::class)
class PublisherRunTest {
    private val f = SendFixture()
    private val server = FakeMastodon(f.servers) { f.clock.now }
    private val afterRuns = AtomicInteger()

    @After
    fun tearDown() = f.close()

    /** The sender of the fixture, except that the history it reads for an edit cannot be read. */
    private fun publisher() = Publisher(
        posts = f.posts,
        accounts = f.accounts,
        settings = { f.settings },
        freezer = PlanFreezer(AndroidTextSegmenter()),
        executors = listOf(MastodonExecutor(f.servers.http, f.sessions, f.accounts, f.clock, sleep = {})),
        preparer = f.preparer,
        filesDir = f.filesDir,
        notifier = f.notifier,
        scheduleRetry = {},
        trigger = PublishTrigger {},
        clock = f.clock,
        zone = { ZoneOffset.UTC },
        afterRun = { afterRuns.incrementAndGet() },
        // Read for an edit only: the edit below is the post that cannot be handled.
        history = { throw IllegalStateException("history record $it is unreadable") },
    )

    @Test
    fun aPostThatCannotBeHandledLetsTheNextOneGoAndTheAlarmIsSetAgain() {
        val alice = f.mastodon()
        // An edit of a published post reads its record first; here reading it fails.
        val broken = f.queue("edit", listOf(alice))
        runBlocking { f.db.openHelper.writableDatabase.execSQL("UPDATE posts SET kind = 'EDIT', historyId = 7 WHERE id = $broken") }
        val fine = f.queue("an ordinary post", listOf(alice))

        runBlocking { publisher().run() }

        assertThat(server.statuses.map { it.text }).containsExactly("an ordinary post")
        assertThat(f.post(fine)?.state ?: PostState.SENT).isEqualTo(PostState.SENT)
        // The broken post is still there to try again, with its delivery untouched.
        assertThat(f.post(broken)!!.state).isEqualTo(PostState.QUEUED)
        assertThat(f.post(broken)!!.deliveries.single().state).isEqualTo(DeliveryState.WAITING)
        assertThat(afterRuns.get()).isEqualTo(1)
    }

    @Test
    fun anEditSavedWhileAnEarlierPostSendsIsWhatGoesOut() {
        // Q2: the second post has not started while the first one sends, so the user can still edit it.
        val alice = f.mastodon()
        f.queue("first", listOf(alice))
        val second = f.queue("second", listOf(alice))
        server.afterCreate = { created ->
            if (created.text == "first") blocking { f.posts.saveDraft(DraftInput(id = second, text = "second, fixed", targets = listOf(alice))) }
        }

        f.run()

        assertThat(server.statuses.map { it.text }).containsExactly("first", "second, fixed").inOrder()
    }

    @Test
    fun aPostMovedLaterWhileAnEarlierPostSendsWaitsForItsNewTime() {
        val alice = f.mastodon()
        f.queue("first", listOf(alice))
        val second = f.queue("second", listOf(alice))
        val tomorrow = f.clock.now.plus(Duration.ofDays(1))
        server.afterCreate = { created ->
            if (created.text == "first") blocking { f.posts.updateSchedules(mapOf(second to Schedule.Explicit(tomorrow))) }
        }

        f.run()

        assertThat(server.statuses.map { it.text }).containsExactly("first")
        val post = f.post(second)!!
        assertThat(post.state).isEqualTo(PostState.QUEUED)
        assertThat(post.deliveries.single().attempts).isEqualTo(0)
    }

    @Test
    fun aPostMovedIntoTheDueTimeWhileTheRunSendsGoesOutInTheSameRun() {
        // S8: a drag swaps the times of a due post and a later one while the run is busy with an earlier post.
        val alice = f.mastodon()
        val now = f.clock.now
        f.queue("first", listOf(alice))
        val due = f.queue("due", listOf(alice))
        val later = blocking { f.posts.saveAndQueue(DraftInput(text = "later", targets = listOf(alice)), Schedule.Explicit(now.plus(Duration.ofHours(4)))) }
        server.afterCreate = { created ->
            if (created.text == "first") {
                blocking { f.posts.updateSchedules(mapOf(due to Schedule.Explicit(now.plus(Duration.ofHours(4))), later to Schedule.Explicit(now))) }
            }
        }

        f.run()

        assertThat(server.statuses.map { it.text }).containsExactly("first", "later").inOrder()
        assertThat(f.post(due)!!.state).isEqualTo(PostState.QUEUED)
    }

    @Test
    fun aPostWaitingForARetryKeepsTheNextPostToTheSameAccountBehindIt() {
        // Q8: posts due together go out in queue order per account, even when the first one has to wait.
        val alice = f.mastodon()
        server.statusAnswer = { attempt, _ -> if (attempt == 1) FakeMastodon.error(503, "Service Unavailable") else null }
        val first = f.queue("first", listOf(alice))
        val second = f.queue("second", listOf(alice))

        f.run()

        assertThat(server.statuses).isEmpty()
        assertThat(f.post(first)!!.deliveries.single().state).isEqualTo(DeliveryState.WAITING)
        assertThat(f.post(second)!!.deliveries.single().attempts).isEqualTo(0)

        // A run before the retry is due does not let the second one pass either.
        f.clock.advance(Duration.ofSeconds(10))
        f.run()
        assertThat(server.statuses).isEmpty()

        f.clock.advance(Duration.ofSeconds(20))
        f.run()

        assertThat(server.statuses.map { it.text }).containsExactly("first", "second").inOrder()
        assertThat(f.post(second)?.state ?: PostState.SENT).isEqualTo(PostState.SENT)
    }

    @Test
    fun aPostWaitingForARetryDoesNotHoldBackAnotherAccount() {
        val alice = f.mastodon()
        val bob = f.mastodon(host = "b.example", remoteId = "202")
        val other = FakeMastodon(f.servers, "b.example") { f.clock.now }
        server.statusAnswer = { _, _ -> FakeMastodon.error(503, "Service Unavailable") }
        f.queue("first", listOf(alice))
        f.queue("second", listOf(bob))

        f.run()

        assertThat(other.statuses.map { it.text }).containsExactly("second")
    }
}
