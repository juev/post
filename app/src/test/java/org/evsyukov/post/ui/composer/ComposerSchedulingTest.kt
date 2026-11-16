package org.evsyukov.post.ui.composer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.core.schedule.WeeklySchedule
import org.evsyukov.post.core.schedule.freeSlots
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.posts.DeliveryPlan
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.Post
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
import java.util.concurrent.atomic.AtomicReference

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

    // Q2: a queued post can be edited only until its first delivery starts. When it starts (or the post is
    // deleted) while the composer has it open, a save must neither crash nor lose the edits.

    private val tomorrowAtNine = java.time.LocalDateTime.now().plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0)

    private fun queuedAndOpened(alice: Long): Long {
        val id = runBlocking {
            h.container.posts.saveAndQueue(DraftInput(text = "Old text", targets = listOf(alice)), Schedule.Slot(tomorrowAtNine))
        }
        h.show(draftId = id)
        h.waitUntil { h.text() == "Old text" }
        h.type(" and more")
        return id
    }

    private fun startSending(id: Long) = runBlocking {
        val delivery = h.container.posts.get(id)!!.deliveries.single()
        h.container.posts.startDelivery(delivery.id, DeliveryPlan(emptyList()))
    }

    private fun drafts(): List<Post> = runBlocking { h.container.posts.observeDrafts().first() }

    private fun shown(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun savingAQueuedPostThatStartedSendingKeepsTheEditsHereAndLeavesThePostAlone() {
        val alice = h.mastodon("alice")
        val id = queuedAndOpened(alice)
        startSending(id)

        h.click(ComposerTags.SEND)

        h.waitUntil { shown(h.string(org.evsyukov.post.R.string.schedule_edit_not_saved)) }
        assertThat(h.exits.get()).isEqualTo(0)
        assertThat(h.text()).isEqualTo("Old text and more")
        assertThat(runBlocking { h.container.posts.get(id) }!!.text).isEqualTo("Old text")
        // The composer let go of the post: the edits are a new post now, and Send sends them as one.
        assertThat(h.model.draftId).isNull()
        assertThat(h.model.editingQueued).isFalse()
        assertThat(h.model.hasUnsavedChanges()).isTrue()
    }

    @Test
    fun sendLaterOfAQueuedPostThatStartedSendingDoesNotQueueItAgain() {
        val alice = h.mastodon("alice")
        val id = queuedAndOpened(alice)
        startSending(id)

        val result = AtomicReference<ComposerModel.SendResult?>(null)
        h.model.viewModelScope.launch { result.set(h.model.send(Schedule.Explicit(Instant.now().plusSeconds(7200)))) }

        // The send renders off the main thread; the main clock has to move for its continuations to run.
        val end = System.currentTimeMillis() + 10_000
        while (result.get() == null) {
            check(System.currentTimeMillis() < end) { "the send did not finish in 10 s" }
            Thread.sleep(10)
            compose.mainClock.advanceTimeBy(200)
            compose.waitForIdle()
        }
        assertThat(result.get()).isEqualTo(ComposerModel.SendResult.NoLongerEditable)
        val post = runBlocking { h.container.posts.get(id) }!!
        assertThat(post.text).isEqualTo("Old text")
        assertThat(post.schedule).isEqualTo(Schedule.Slot(tomorrowAtNine))
        assertThat(h.text()).isEqualTo("Old text and more")
    }

    @Test
    fun saveDraftOfAQueuedPostThatStartedSendingSavesTheEditsAsANewDraft() {
        val alice = h.mastodon("alice")
        val id = queuedAndOpened(alice)
        startSending(id)

        h.menu(ComposerTags.MENU_SAVE)

        h.waitUntil { drafts().isNotEmpty() }
        val draft = drafts().single()
        assertThat(draft.id).isNotEqualTo(id)
        assertThat(draft.text).isEqualTo("Old text and more")
        assertThat(draft.targets).containsExactly(alice)
        assertThat(runBlocking { h.container.posts.get(id) }!!.text).isEqualTo("Old text")
        h.waitUntil { shown(h.string(org.evsyukov.post.R.string.schedule_edit_kept_as_draft)) }
        assertThat(h.model.hasUnsavedChanges()).isFalse()
    }

    @Test
    fun saveDraftOfAQueuedPostWithoutTargetsShowsWhyAndLeavesThePostAsItWas() {
        // A queued post needs a target; with none, saving it shows the blockers instead of crashing.
        val alice = h.mastodon("alice")
        val id = queuedAndOpened(alice)
        h.click(ComposerTags.target(alice))
        h.waitUntil { h.model.targets.isEmpty() }

        h.menu(ComposerTags.MENU_SAVE)

        h.waitFor(ComposerTags.DETAILS)
        val post = runBlocking { h.container.posts.get(id) }!!
        assertThat(post.text).isEqualTo("Old text")
        assertThat(post.targets).containsExactly(alice)
        assertThat(post.state).isEqualTo(PostState.QUEUED)
        assertThat(h.model.hasUnsavedChanges()).isTrue()
    }

    @Test
    fun saveDraftOfAQueuedPostOverALimitShowsWhyAndLeavesThePostAsItWas() {
        // Q2: editing re-runs the limit checks; a word longer than the server's limit cannot be split (T9).
        val alice = h.mastodon("alice")
        val id = queuedAndOpened(alice)
        h.type(" " + "a".repeat(600))

        h.menu(ComposerTags.MENU_SAVE)

        h.waitFor(ComposerTags.DETAILS)
        assertThat(runBlocking { h.container.posts.get(id) }!!.text).isEqualTo("Old text")
    }

    @Test
    fun keepOnLeaveOfAQueuedPostWithoutTargetsStaysToShowWhy() {
        val alice = h.mastodon("alice")
        val id = queuedAndOpened(alice)
        h.click(ComposerTags.target(alice))
        h.waitUntil { h.model.targets.isEmpty() }

        h.click(ComposerTags.SETTINGS)
        h.waitFor(ComposerTags.LEAVE_KEEP)
        h.click(ComposerTags.LEAVE_KEEP)

        h.waitFor(ComposerTags.DETAILS)
        assertThat(h.settingsOpened.get()).isEqualTo(0)
        assertThat(runBlocking { h.container.posts.get(id) }!!.targets).containsExactly(alice)
    }

    @Test
    fun keepDraftOnLeaveAfterTheQueuedPostWasDeletedSavesTheEditsAndLeaves() {
        val alice = h.mastodon("alice")
        val id = queuedAndOpened(alice)
        runBlocking { h.container.posts.delete(id) }

        h.click(ComposerTags.SETTINGS)
        h.waitFor(ComposerTags.LEAVE_KEEP)
        h.click(ComposerTags.LEAVE_KEEP)

        h.waitUntil { h.settingsOpened.get() == 1 }
        assertThat(drafts().single().text).isEqualTo("Old text and more")
    }
}
