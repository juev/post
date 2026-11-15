package org.evsyukov.post.ui.composer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.core.schedule.WeeklySchedule
import org.evsyukov.post.core.schedule.freeSlots
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.ui.schedule.SendLaterTags
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** The composer's part in scheduling: the Send later sheet (R24), the late badge (Q7) and editing a queued post (R25, Q2). */
@RunWith(AndroidJUnit4::class)
class ComposerSchedulingTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var h: ComposerHarness

    @Before
    fun setUp() {
        h = ComposerHarness(compose)
    }

    @After
    fun tearDown() = h.close()

    /** The badges sit inside the Posts button, whose merged node keeps only its own tag. */
    private fun badge(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun everyDay(vararg times: String) = runBlocking {
        h.container.schedule.save(WeeklySchedule(DayOfWeek.entries.associateWith { times.map(LocalTime::parse) }))
    }

    @Test
    fun sendLaterQueuesThePostInTheNextFreeSlot() {
        val alice = h.mastodon("alice")
        h.defaultTargets(alice)
        everyDay("07:00", "19:00")
        h.show()
        h.type("Into a slot")

        h.click(ComposerTags.SEND_LATER)
        h.waitFor(SendLaterTags.NEXT_FREE)
        h.click(SendLaterTags.NEXT_FREE)

        h.waitUntil { runBlocking { h.container.posts.observeQueue().first() }.isNotEmpty() }
        val post = runBlocking { h.container.posts.observeQueue().first() }.single()
        val expected = freeSlots(runBlocking { h.container.schedule.get() }, ZoneId.systemDefault(), Instant.now(), emptySet(), 1).single()
        assertThat(post.text).isEqualTo("Into a slot")
        assertThat(post.schedule).isEqualTo(Schedule.Slot(expected))
    }

    @Test
    fun withoutAScheduleSendLaterOffersThePickerAndALinkToSchedule() {
        val alice = h.mastodon("alice")
        h.defaultTargets(alice)
        h.show()
        h.type("No slots")

        h.click(ComposerTags.SEND_LATER)

        h.waitFor(SendLaterTags.NO_SCHEDULE)
        h.waitFor(SendLaterTags.OPEN_SCHEDULE)
        assertThat(compose.onAllNodes(hasTestTag(SendLaterTags.NEXT_FREE)).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun aHeldPostPutsAWarningBadgeOnPosts() {
        val alice = h.mastodon("alice")
        h.show()
        h.waitFor(ComposerTags.POSTS)
        assertThat(badge(ComposerTags.POSTS_LATE_BADGE)).isFalse()

        val id = runBlocking {
            h.container.posts.saveAndQueue(DraftInput(text = "missed", targets = listOf(alice)), Schedule.Explicit(Instant.now().minusSeconds(7200)))
        }
        runBlocking { h.container.posts.hold(listOf(id)) }

        h.waitUntil { badge(ComposerTags.POSTS_LATE_BADGE) }
        compose.onNode(hasContentDescription("1 post missed its time"), useUnmergedTree = true).assertExists()

        // Once the Late group is empty the badge goes back to the queue count.
        runBlocking { h.container.queueActions.sendHeldNow() }
        h.waitUntil { !badge(ComposerTags.POSTS_LATE_BADGE) }
        h.waitUntil { badge(ComposerTags.POSTS_BADGE) }
    }

    @Test
    fun editingAQueuedPostSavesItAndKeepsItsTime() {
        val alice = h.mastodon("alice")
        val slot = java.time.LocalDateTime.now().plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0)
        val id = runBlocking {
            h.container.posts.saveAndQueue(DraftInput(text = "Old text", targets = listOf(alice)), Schedule.Slot(slot))
        }
        h.show(draftId = id)
        h.waitUntil { h.text() == "Old text" }
        compose.onNode(hasContentDescription("Save changes"), useUnmergedTree = true).assertExists()

        h.type(" and more")
        h.click(ComposerTags.SEND)

        h.waitUntil { h.exits.get() == 1 }
        val post = runBlocking { h.container.posts.get(id) }!!
        assertThat(post.text).isEqualTo("Old text and more")
        assertThat(post.state).isEqualTo(PostState.QUEUED)
        assertThat(post.schedule).isEqualTo(Schedule.Slot(slot))
        assertThat(h.triggers.get()).isEqualTo(0)
    }
}
