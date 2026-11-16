package org.evsyukov.post.send

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.PublishTrigger
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.text.AndroidTextSegmenter
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
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
}
