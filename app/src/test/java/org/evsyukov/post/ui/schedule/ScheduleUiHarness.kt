package org.evsyukov.post.ui.schedule

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.AppContainer
import org.evsyukov.post.PublishTrigger
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.schedule.WeeklySchedule
import org.evsyukov.post.data.FakeCipher
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.Post
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.theme.PostTheme
import java.time.DayOfWeek
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicInteger

/** A fresh app container with one Mastodon account, for the Schedule screen, the Send later sheet and the Queue tab. */
class ScheduleUiHarness(private val compose: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val triggers = AtomicInteger()
    val container = AppContainer(context, publishTrigger = PublishTrigger { triggers.incrementAndGet() }) { FakeCipher() }
    val alice: Long = runBlocking {
        container.accounts.add(
            NewAccount(Network.MASTODON, "https://alice.example", "alice@alice.example", "r-alice", "Alice", null),
            MastodonSecret("t-alice"),
        )
    }

    /** Ends the activity first, so no screen still reads the database when it closes. */
    fun close() {
        runCatching { compose.activityRule.scenario.close() }
        if (container.database.isOpen) container.database.close()
    }

    fun show(content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                PostTheme { content() }
            }
        }
        compose.waitForIdle()
    }

    fun slots(vararg days: Pair<DayOfWeek, List<String>>) = runBlocking {
        container.schedule.save(WeeklySchedule(days.toMap().mapValues { (_, times) -> times.map(LocalTime::parse) }))
    }

    fun schedule(): WeeklySchedule = runBlocking { container.schedule.get() }

    fun queue(text: String, at: Schedule): Long = runBlocking {
        container.posts.saveAndQueue(DraftInput(text = text, targets = listOf(alice)), at)
    }

    fun post(id: Long): Post? = runBlocking { container.posts.get(id) }

    fun queueNow(): List<Post> = runBlocking { container.posts.observeQueue().first() }

    fun waitFor(tag: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    fun waitUntil(condition: () -> Boolean) = compose.waitUntil(5_000, condition)

    fun exists(tag: String): Boolean = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag)

    /** Brings the node with [tag] into view: a lazy list composes only what is on screen. */
    fun reveal(tag: String) {
        compose.waitForIdle()
        if (!exists(tag)) {
            val lists = compose.onAllNodes(hasScrollToNodeAction())
            for (i in 0 until lists.fetchSemanticsNodes().size) {
                if (runCatching { lists[i].performScrollToNode(hasTestTag(tag)) }.isSuccess) break
            }
        }
        waitFor(tag)
        runCatching { node(tag).performScrollTo() }
    }

    fun click(tag: String) {
        reveal(tag)
        node(tag).performClick()
        compose.waitForIdle()
    }

    fun string(id: Int, vararg args: Any): String = context.getString(id, *args)
}
