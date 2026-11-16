package org.evsyukov.post.ui.posts

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.posts.DeliveryPlan
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.ui.schedule.ScheduleUiHarness
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** The Queue tab of Posts (R25; scheduling.md S8–S10, Q3, Q7). */
@RunWith(AndroidJUnit4::class)
// A tall screen, so the whole list is composed at once.
@Config(qualifiers = "w411dp-h1600dp")
class QueueTabTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = ScheduleUiHarness(compose)
    private val edited = mutableListOf<Long>()

    @After
    fun tearDown() = h.close()

    /** A slot [days] days from now at [hour]:00, local time. */
    private fun slot(days: Long, hour: Int) = LocalDateTime.now().plusDays(days).withHour(hour).withMinute(0).withSecond(0).withNano(0)

    private fun show() {
        h.show { PostsScreen(onBack = {}, onEdit = { edited += it }) }
        h.waitFor(PostsTags.TAB_QUEUE)
    }

    private fun slotOf(id: Long) = (h.post(id)!!.schedule as Schedule.Slot).local

    private fun held(text: String): Long {
        val id = h.queue(text, Schedule.Explicit(Instant.now().minusSeconds(7200)))
        runBlocking { h.container.posts.hold(listOf(id)) }
        return id
    }

    private fun failed(text: String): Pair<Long, Long> = runBlocking {
        val id = h.queue(text, Schedule.Explicit(Instant.now().minusSeconds(60)))
        val delivery = h.container.posts.get(id)!!.deliveries.single()
        h.container.posts.startDelivery(delivery.id, DeliveryPlan(emptyList()))
        h.container.posts.deliveryFailed(delivery.id, "the server said no", null)
        id to delivery.id
    }

    /** Leaves an open row menu through Delete and Cancel, which changes nothing. */
    private fun closeMenu() {
        h.click(QueueTags.MENU_DELETE)
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
    }

    private fun top(tag: String): Float = compose.onNode(hasTestTag(tag)).fetchSemanticsNode().boundsInRoot.top

    @Test
    fun lateComesFirstThenFailedThenWaitingByTime() {
        val later = h.queue("later", Schedule.Slot(slot(2, 13)))
        val sooner = h.queue("sooner", Schedule.Explicit(Instant.now().plusSeconds(3600)))
        val (broken, _) = failed("broken")
        val missed = held("missed")
        show()

        h.waitFor(QueueTags.LATE)
        assertThat(top(QueueTags.LATE)).isLessThan(top(QueueTags.FAILED))
        assertThat(top(QueueTags.FAILED)).isLessThan(top(QueueTags.WAITING))
        assertThat(top(QueueTags.row(missed))).isLessThan(top(QueueTags.row(broken)))
        assertThat(top(QueueTags.row(broken))).isLessThan(top(QueueTags.row(sooner)))
        assertThat(top(QueueTags.row(sooner))).isLessThan(top(QueueTags.row(later)))
        // The failure shows with the server's message and a retry for the delivery and the post.
        compose.onNodeWithText("alice@alice.example: the server said no").assertExists()
    }

    @Test
    fun slotPostsHaveAHandleExplicitPostsAPinAndLateOrFailedNeither() {
        val slotPost = h.queue("slot", Schedule.Slot(slot(1, 9)))
        val explicit = h.queue("explicit", Schedule.Explicit(Instant.now().plusSeconds(7200)))
        val missed = held("missed")
        val (broken, _) = failed("broken")
        show()

        h.waitFor(QueueTags.handle(slotPost))
        assertThat(h.exists(QueueTags.pin(slotPost))).isFalse()
        h.waitFor(QueueTags.pin(explicit))
        assertThat(h.exists(QueueTags.handle(explicit))).isFalse()
        assertThat(h.exists(QueueTags.handle(missed))).isFalse()
        assertThat(h.exists(QueueTags.handle(broken))).isFalse()
        compose.onNode(hasContentDescription("Drag to change the order")).assertExists()
        assertThat(compose.onAllNodes(hasContentDescription("alice@alice.example: waiting"), useUnmergedTree = true).fetchSemanticsNodes()).isNotEmpty()
        assertThat(compose.onAllNodes(hasContentDescription("alice@alice.example: failed"), useUnmergedTree = true).fetchSemanticsNodes()).hasSize(1)
    }

    @Test
    fun theLateGroupSendsAllNowInQueueOrder() {
        val a = held("A")
        val b = held("B")
        show()

        h.click(QueueTags.LATE_SEND_NOW)

        h.waitUntil { h.post(a)!!.state == PostState.QUEUED && h.post(b)!!.state == PostState.QUEUED }
        assertThat(h.post(a)!!.schedule).isInstanceOf(Schedule.Now::class.java)
        // Queue order: A was held first and is due first.
        assertThat(h.post(a)!!.sendAt).isLessThan(h.post(b)!!.sendAt)
        h.waitUntil { h.triggers.get() >= 1 }
    }

    @Test
    fun rescheduleIsHiddenWithoutAScheduleAndMovesLatePostsToFreeSlotsWithOne() {
        // A new database has the default schedule (#9); this test starts without one.
        h.slots()
        val a = held("A")
        show()
        h.waitFor(QueueTags.LATE_SEND_NOW)
        assertThat(h.exists(QueueTags.LATE_RESCHEDULE)).isFalse()

        h.slots(*DayOfWeek.entries.map { it to listOf("23:59") }.toTypedArray())
        h.click(QueueTags.LATE_RESCHEDULE)

        h.waitUntil { h.post(a)!!.state == PostState.QUEUED }
        assertThat(h.post(a)!!.schedule).isInstanceOf(Schedule.Slot::class.java)
    }

    @Test
    fun moveDownSwapsTheSlotTimesAndUndoPutsThemBack() {
        val first = slot(1, 9)
        val second = slot(1, 13)
        val a = h.queue("A", Schedule.Slot(first))
        val b = h.queue("B", Schedule.Slot(second))
        show()

        h.click(QueueTags.menu(a))
        // The first post cannot move up.
        assertThat(h.exists(QueueTags.MENU_MOVE_UP)).isFalse()
        h.click(QueueTags.MENU_MOVE_DOWN)

        h.waitUntil { slotOf(a) == second }
        assertThat(slotOf(b)).isEqualTo(first)
        h.waitUntil { compose.onAllNodes(hasText("Order changed")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Undo").performClick()

        h.waitUntil { slotOf(a) == first }
        assertThat(slotOf(b)).isEqualTo(second)
    }

    @Test
    fun anExplicitPostOffersNoMoveAndKeepsItsTimeWhenSlotPostsMovePastIt() {
        // Verification scenario: A 09:00, X 11:00 explicit, B 13:00; B up gives B 09:00, A 13:00, X stays.
        val zone = ZoneId.systemDefault()
        val a = h.queue("A", Schedule.Slot(slot(1, 9)))
        val xAt = slot(1, 11).atZone(zone).toInstant()
        val x = h.queue("X", Schedule.Explicit(xAt))
        val b = h.queue("B", Schedule.Slot(slot(1, 13)))
        show()

        h.click(QueueTags.menu(x))
        assertThat(h.exists(QueueTags.MENU_MOVE_UP)).isFalse()
        assertThat(h.exists(QueueTags.MENU_MOVE_DOWN)).isFalse()
        closeMenu()

        h.click(QueueTags.menu(b))
        h.click(QueueTags.MENU_MOVE_UP)

        h.waitUntil { slotOf(b) == slot(1, 9) }
        assertThat(slotOf(a)).isEqualTo(slot(1, 13))
        assertThat(h.post(x)!!.schedule).isEqualTo(Schedule.Explicit(xAt))
    }

    @Test
    fun sendNowFromTheMenuMakesThePostDueAndFreesItsSlot() {
        val a = h.queue("A", Schedule.Slot(slot(1, 9)))
        show()

        h.click(QueueTags.menu(a))
        h.click(QueueTags.MENU_SEND_NOW)

        h.waitUntil { h.post(a)!!.schedule is Schedule.Now }
        h.waitUntil { h.triggers.get() >= 1 }
    }

    @Test
    fun deleteAsksAndRemovesThePost() {
        val a = h.queue("A", Schedule.Slot(slot(1, 9)))
        show()

        h.click(QueueTags.menu(a))
        h.click(QueueTags.MENU_DELETE)
        h.click(QueueTags.DELETE_CONFIRM)

        h.waitUntil { h.post(a) == null }
        h.waitFor(QueueTags.EMPTY)
    }

    @Test
    fun editIsOfferedBeforeTheFirstDeliveryStartsAndOpensTheComposer() {
        val a = h.queue("A", Schedule.Slot(slot(1, 9)))
        val (broken, _) = failed("broken")
        show()

        h.click(QueueTags.menu(broken))
        assertThat(h.exists(QueueTags.MENU_EDIT)).isFalse()
        closeMenu()

        h.click(QueueTags.menu(a))
        h.click(QueueTags.MENU_EDIT)
        assertThat(edited).containsExactly(a)
    }

    @Test
    fun retryOfAFailedDeliveryMakesItWaitAgain() {
        val (broken, delivery) = failed("broken")
        show()

        h.click(QueueTags.retryDelivery(delivery))

        h.waitUntil { h.post(broken)!!.deliveries.single().state == DeliveryState.WAITING }
    }

    @Test
    fun changeTimeOpensTheSheetAndQueuesThePostInTheChosenSlot() {
        h.slots(*DayOfWeek.entries.map { it to listOf("06:00", "21:00") }.toTypedArray())
        val a = h.queue("A", Schedule.Explicit(Instant.now().plusSeconds(600)))
        show()

        h.click(QueueTags.menu(a))
        h.click(QueueTags.MENU_CHANGE_TIME)
        h.click(org.evsyukov.post.ui.schedule.SendLaterTags.NEXT_FREE)

        h.waitUntil { h.post(a)!!.schedule is Schedule.Slot }
    }

    @Test
    fun anEmptyQueueSaysSo() {
        show()
        h.waitFor(QueueTags.EMPTY)
    }

    @Test
    fun aDragMovesOverOtherMovableRowsAndDropsAtItsPlaceAmongThem() {
        // S8 and S9 as the drag sees them: rows 1, 2 and 4 are slot posts, 3 is explicit.
        val order = listOf(1L, 2L, 3L, 4L)
        val dragged = moveOver(order, moved = 4L, target = 1L)
        assertThat(dragged).containsExactly(4L, 1L, 2L, 3L).inOrder()
        assertThat(dropIndex(dragged, movable = listOf(1L, 2L, 4L), moved = 4L)).isEqualTo(0)
        // Back where it started: nothing to do.
        assertThat(dropIndex(order, movable = listOf(1L, 2L, 4L), moved = 4L)).isNull()
        assertThat(moveOver(order, moved = 9L, target = 1L)).isEqualTo(order)
    }
}
