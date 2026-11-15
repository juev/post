package org.evsyukov.post.ui.posts

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.R
import org.evsyukov.post.core.markdown.MANUAL_BREAK
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.db.PostKind
import org.evsyukov.post.data.history.HistoryItem
import org.evsyukov.post.data.posts.DeliveryPlan
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.PlannedPart
import org.evsyukov.post.data.posts.PostOptions
import org.evsyukov.post.data.posts.PublishedPart
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.data.settings.HistoryRetention
import org.evsyukov.post.ui.common.BackTopBarTags
import org.evsyukov.post.ui.schedule.ScheduleUiHarness
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/** The Drafts and Sent tabs of Posts and Post details (R15, R25, R31, R37–R41; screens.md, Posts, Post details). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1600dp")
class PublishedScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = ScheduleUiHarness(compose)
    private val work: Long = runBlocking {
        h.container.accounts.add(
            NewAccount(Network.MASTODON, "https://work.example", "bob@work.example", "r-bob", "Bob", null),
            MastodonSecret("t-bob"),
        )
    }
    private val opened = mutableListOf<Long>()
    private val edits = mutableListOf<Long>()
    private val newFrom = mutableListOf<Long>()
    private val details = mutableListOf<Long>()
    private val navigation = SentNavigation(onOpen = { details += it }, onEdit = { edits += it }, onNewFrom = { newFrom += it })

    @After
    fun tearDown() = h.close()

    private fun string(id: Int) = h.context.getString(id)

    private fun showPosts(tab: String) {
        h.show { PostsScreen(onBack = {}, onEdit = { opened += it }, onOpenDetails = { details += it }, sent = navigation) }
        h.click(tab)
    }

    private fun draft(text: String): Long = runBlocking { h.container.posts.saveDraft(DraftInput(text = text, targets = listOf(h.alice))) }

    private fun item(account: Long, handle: String, vararg parts: String) = HistoryItem(
        accountId = account,
        network = Network.MASTODON,
        server = "https://example",
        handle = handle,
        parts = parts.mapIndexed { index, text -> PublishedPart(id = "s$index", url = "https://example/@$handle/s$index", text = text) },
    )

    private fun record(text: String, sentAt: Instant, items: List<HistoryItem>): Long = runBlocking {
        h.container.history.insert(null, sentAt, text, PostOptions(), items)!!
    }

    private fun top(tag: String): Float = compose.onNode(hasTestTag(tag)).fetchSemanticsNode().boundsInRoot.top

    // Drafts

    @Test
    fun draftsListTheLastSavedFirstAndATapOpensTheComposer() {
        val older = draft("older draft")
        val newer = draft("newer draft")
        showPosts(PostsTags.TAB_DRAFTS)

        h.waitFor(DraftsTags.row(newer))
        assertThat(top(DraftsTags.row(newer))).isLessThan(top(DraftsTags.row(older)))
        // Each row names its targets.
        compose.onAllNodesWithText("alice@alice.example").assertCountEquals(2)

        h.click(DraftsTags.row(older))

        assertThat(opened).containsExactly(older)
    }

    @Test
    fun deletingADraftOffersUndoAndUndoKeepsIt() {
        val id = draft("keep me")
        showPosts(PostsTags.TAB_DRAFTS)
        h.waitFor(DraftsTags.row(id))

        h.click(DraftsTags.menu(id))
        h.click(DraftsTags.MENU_DELETE)

        assertThat(h.exists(DraftsTags.row(id))).isFalse()
        compose.onNodeWithText(string(R.string.posts_undo)).performClick()
        h.waitFor(DraftsTags.row(id))
        assertThat(h.post(id)).isNotNull()
    }

    @Test
    fun aDraftDeletedWithoutUndoIsGone() {
        val id = draft("delete me")
        showPosts(PostsTags.TAB_DRAFTS)
        h.waitFor(DraftsTags.row(id))

        h.click(DraftsTags.menu(id))
        h.click(DraftsTags.MENU_DELETE)
        compose.mainClock.advanceTimeBy(10_000)

        h.waitUntil { h.post(id) == null }
        h.waitFor(DraftsTags.EMPTY)
    }

    // Sent

    @Test
    fun sentListsRecordsNewestFirstWithALinkPerAccount() {
        val old = record("old post", Instant.now().minusSeconds(7200), listOf(item(h.alice, "alice", "old post")))
        val new = record("new post", Instant.now().minusSeconds(60), listOf(item(h.alice, "alice", "new post"), item(work, "bob", "new post")))
        showPosts(PostsTags.TAB_SENT)

        h.waitFor(SentTags.row(new))
        assertThat(top(SentTags.row(new))).isLessThan(top(SentTags.row(old)))
        assertThat(h.exists(SentTags.link(new, h.alice))).isTrue()
        assertThat(h.exists(SentTags.link(new, work))).isTrue()

        h.click(SentTags.row(old))

        assertThat(details).containsExactly(old)
    }

    @Test
    fun withHistoryOffTheTabSaysThatNothingIsKept() {
        runBlocking { h.container.settings.setHistoryRetention(HistoryRetention.OFF) }
        showPosts(PostsTags.TAB_SENT)

        h.waitFor(SentTags.OFF)
        compose.onNodeWithText(string(R.string.sent_history_off)).assertExists()
    }

    @Test
    fun anEmptyHistorySaysHowLongPostsAreKept() {
        showPosts(PostsTags.TAB_SENT)

        h.waitFor(SentTags.EMPTY)
        compose.onNodeWithText(h.context.resources.getQuantityString(R.plurals.sent_empty, 30, 30)).assertExists()
    }

    @Test
    fun theMenuEditsAndStartsANewPost() {
        val id = record("sent", Instant.now(), listOf(item(h.alice, "alice", "sent")))
        showPosts(PostsTags.TAB_SENT)
        h.waitFor(SentTags.row(id))

        h.click(SentTags.menu(id))
        h.click(SentTags.MENU_EDIT)
        h.waitUntil { edits.isNotEmpty() }
        h.click(SentTags.menu(id))
        h.click(SentTags.MENU_NEW)

        assertThat(edits).containsExactly(id)
        assertThat(newFrom).containsExactly(id)
    }

    @Test
    fun anEditIsRefusedWhileAnotherChangeOfThePostWaits() {
        val id = record("sent", Instant.now(), listOf(item(h.alice, "alice", "sent")))
        runBlocking { h.container.publishedActions.delete(id, listOf(h.alice)) }
        showPosts(PostsTags.TAB_SENT)
        h.waitFor(SentTags.row(id))

        h.click(SentTags.menu(id))
        h.click(SentTags.MENU_EDIT)

        h.waitUntil { compose.onAllNodes(androidx.compose.ui.test.hasText(string(R.string.sent_pending))).fetchSemanticsNodes().isNotEmpty() }
        assertThat(edits).isEmpty()
    }

    @Test
    fun deleteAsksWhichAccountsWithAllChosenAndQueuesTheDeletion() {
        val id = record("both", Instant.now(), listOf(item(h.alice, "alice", "both"), item(work, "bob", "both")))
        showPosts(PostsTags.TAB_SENT)
        h.waitFor(SentTags.row(id))

        h.click(SentTags.menu(id))
        h.click(SentTags.MENU_DELETE)
        h.click(PublishedTags.deleteAccount(work))
        h.click(PublishedTags.DELETE_CONFIRM)

        // R39, R41: the deletion is saved as a post and goes through the queue.
        h.waitUntil { h.queueNow().isNotEmpty() }
        val deletion = h.queueNow().single()
        assertThat(deletion.kind).isEqualTo(PostKind.DELETE)
        assertThat(deletion.historyId).isEqualTo(id)
        assertThat(deletion.targets).containsExactly(h.alice)
        h.waitUntil { h.triggers.get() > 0 }
    }

    @Test
    fun aTapOnAQueuedPostOpensItsDetails() {
        val id = h.queue("queued", Schedule.Explicit(Instant.now().plusSeconds(3600)))
        showPosts(PostsTags.TAB_QUEUE)

        h.click(QueueTags.open(id))

        assertThat(details).containsExactly(id)
    }

    // Post details

    private fun showDetails(postId: Long? = null, historyId: Long? = null) {
        h.show {
            PostDetailsScreen(postId = postId, historyId = historyId, onBack = {}, onEdit = { opened += it }, onOpenSchedule = {}, navigation = navigation)
        }
        h.waitFor(DetailsTags.SCREEN)
    }

    @Test
    fun aQueuedPostShowsItsPartsPerAccountAndItsActions() {
        val id = h.queue("first part\n$MANUAL_BREAK\nsecond part", Schedule.Explicit(Instant.now().plusSeconds(3600)))
        showDetails(postId = id)

        h.waitFor(DetailsTags.part(h.alice, 1))
        compose.onNodeWithText("first part").assertExists()
        compose.onNodeWithText("second part").assertExists()
        assertThat(h.exists(DetailsTags.SEND_NOW)).isTrue()
        assertThat(h.exists(DetailsTags.CHANGE_TIME)).isTrue()
        assertThat(h.exists(DetailsTags.RETRY)).isFalse()

        h.click(DetailsTags.EDIT)
        assertThat(opened).containsExactly(id)

        h.click(DetailsTags.SEND_NOW)
        h.waitUntil { h.post(id)!!.schedule is Schedule.Now }
    }

    @Test
    fun aFailedDeliveryShowsTheServerErrorAndRetries() {
        val id = h.queue("broken", Schedule.Explicit(Instant.now().minusSeconds(60)))
        val delivery = runBlocking {
            val delivery = h.container.posts.get(id)!!.deliveries.single()
            h.container.posts.startDelivery(delivery.id, DeliveryPlan(listOf(PlannedPart("broken"))))
            h.container.posts.deliveryFailed(delivery.id, "the server said no", null)
            delivery.id
        }
        showDetails(postId = id)

        h.waitFor(DetailsTags.RETRY)
        compose.onNodeWithText("the server said no").assertExists()
        // Q2: a post whose delivery started can no longer be edited or moved.
        assertThat(h.exists(DetailsTags.EDIT)).isFalse()
        assertThat(h.exists(DetailsTags.SEND_NOW)).isFalse()

        h.click(QueueTags.retryDelivery(delivery))

        h.waitUntil { h.post(id)!!.deliveries.single().state == DeliveryState.WAITING }
    }

    @Test
    fun deleteInDetailsRemovesTheQueuedPost() {
        val id = h.queue("to go", Schedule.Explicit(Instant.now().plusSeconds(3600)))
        showDetails(postId = id)

        h.click(DetailsTags.DELETE)
        h.click(DetailsTags.DELETE_CONFIRM)

        h.waitUntil { h.post(id) == null }
    }

    @Test
    fun aSentPostShowsItsPartsPerAccountWithLinksAndItsActions() {
        val id = record(
            "one\n$MANUAL_BREAK\ntwo",
            Instant.now(),
            listOf(item(h.alice, "alice", "one", "two"), item(work, "bob", "one", "two")),
        )
        showDetails(historyId = id)

        h.waitFor(DetailsTags.part(work, 1))
        assertThat(h.exists(DetailsTags.part(h.alice, 0))).isTrue()
        assertThat(h.exists(DetailsTags.link(h.alice))).isTrue()
        assertThat(h.exists(DetailsTags.link(work))).isTrue()

        h.click(DetailsTags.EDIT_PUBLISHED)
        h.waitUntil { edits.isNotEmpty() }
        h.click(DetailsTags.NEW_FROM)
        assertThat(edits).containsExactly(id)
        assertThat(newFrom).containsExactly(id)

        h.click(DetailsTags.DELETE_PUBLISHED)
        h.click(PublishedTags.DELETE_CONFIRM)
        h.waitUntil { h.queueNow().isNotEmpty() }
        assertThat(h.queueNow().single().targets).containsExactly(h.alice, work).inOrder()
    }

    @Test
    fun anExpiredRecordShowsThatThePostIsGone() {
        showDetails(historyId = 999)

        h.waitFor(DetailsTags.GONE)
        assertThat(runBlocking { h.container.history.observe().first() }).isEmpty()
        assertThat(h.exists(BackTopBarTags.BACK)).isTrue()
    }
}
