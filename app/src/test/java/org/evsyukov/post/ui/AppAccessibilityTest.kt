package org.evsyukov.post.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.navigation3.runtime.NavKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.history.HistoryItem
import org.evsyukov.post.data.posts.DeliveryPlan
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.PostOptions
import org.evsyukov.post.data.posts.PublishedPart
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.ui.composer.ComposerTags
import org.evsyukov.post.ui.posts.DetailsTags
import org.evsyukov.post.ui.posts.PostsTags
import org.evsyukov.post.ui.posts.QueueTags
import org.evsyukov.post.ui.schedule.ScheduleTags
import org.evsyukov.post.ui.schedule.ScheduleUiHarness
import org.evsyukov.post.ui.settings.AboutTags
import org.evsyukov.post.ui.settings.DefaultsTags
import org.evsyukov.post.ui.settings.SendingTags
import org.evsyukov.post.ui.settings.SettingsTags
import org.evsyukov.post.ui.settings.accounts.AccountDetailsTags
import org.evsyukov.post.ui.settings.accounts.AccountsTags
import org.evsyukov.post.ui.settings.accounts.AddBlueskyTags
import org.evsyukov.post.ui.settings.accounts.AddMastodonTags
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import kotlin.math.abs

/**
 * R33: every control a screen reader reaches on the main screens has a label, and every one can be hit with a
 * finger (48 dp). The screens are filled, so their rows, menus and retry buttons are there to check.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1600dp")
class AppAccessibilityTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = ScheduleUiHarness(compose)

    private val queued = h.queue("waiting post", Schedule.Explicit(Instant.now().plusSeconds(7200)))
    private val failed: Long = runBlocking {
        val id = h.queue("failed post", Schedule.Explicit(Instant.now().minusSeconds(60)))
        val delivery = h.container.posts.get(id)!!.deliveries.single()
        h.container.posts.startDelivery(delivery.id, DeliveryPlan(emptyList()))
        h.container.posts.deliveryFailed(delivery.id, "the server said no", null)
        id
    }
    private val held: Long = runBlocking {
        val id = h.queue("late post", Schedule.Explicit(Instant.now().minusSeconds(7200)))
        h.container.posts.hold(listOf(id))
        id
    }
    private val sent: Long = runBlocking {
        h.container.posts.saveDraft(DraftInput(text = "a draft", targets = listOf(h.alice)))
        val item = HistoryItem(
            accountId = h.alice,
            network = Network.MASTODON,
            server = "https://alice.example",
            handle = "alice@alice.example",
            parts = listOf(PublishedPart(id = "s1", url = "https://alice.example/@alice/s1", text = "sent post")),
        )
        h.container.history.insert(null, Instant.now().minusSeconds(3600), "sent post", PostOptions(), listOf(item))!!
    }

    init {
        h.slots(DayOfWeek.MONDAY to listOf("09:00", "18:00"))
    }

    @After
    fun tearDown() = h.close()

    private fun open(key: NavKey?, screenTag: String) {
        h.show { PostApp(openOnStart = key) }
        h.waitFor(screenTag)
        compose.waitForIdle()
    }

    private fun clickables(): List<SemanticsNode> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).fetchSemanticsNodes()

    private fun SemanticsNode.label(): String {
        val config = config
        return listOfNotNull(
            config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" "),
            config.getOrNull(SemanticsProperties.Text)?.joinToString(" "),
            config.getOrNull(SemanticsProperties.EditableText)?.text,
            config.getOrNull(SemanticsProperties.StateDescription),
        ).joinToString(" ").trim()
    }

    private fun SemanticsNode.describe() = "${config.getOrNull(SemanticsProperties.TestTag) ?: "no tag"} at $boundsInRoot"

    /** The controls of the screen shown now without a label, or smaller than a finger. */
    private fun problems(): List<String> {
        val density = compose.density.density
        val minimum = 48 * density - 1
        return clickables().mapNotNull { node ->
            // The touch area, which a small icon button widens to 48 dp; a control scrolled out of a toolbar has
            // none on screen and is checked when it scrolls in.
            val touch = node.touchBoundsInRoot
            when {
                node.label().isEmpty() -> "unlabelled: ${node.describe()}"
                touch.isEmpty -> null
                touch.width < minimum || touch.height < minimum -> "small (${touch.width / density}x${touch.height / density} dp): ${node.label()}"
                else -> null
            }
        }
    }

    private fun assertAccessible(key: NavKey?, screenTag: String, then: () -> Unit = {}) {
        open(key, screenTag)
        then()
        compose.waitForIdle()
        assertThat(clickables()).isNotEmpty()
        assertThat(problems()).isEmpty()
    }

    @Test
    fun composer() = assertAccessible(null, ComposerTags.SCREEN)

    @Test
    fun queueTab() = assertAccessible(PostsKey, PostsTags.TAB_QUEUE) {
        h.waitFor(QueueTags.row(failed))
        h.waitFor(QueueTags.row(held))
        h.waitFor(QueueTags.row(queued))
    }

    @Test
    fun draftsTab() = assertAccessible(PostsKey, PostsTags.TAB_DRAFTS) { h.click(PostsTags.TAB_DRAFTS) }

    @Test
    fun sentTab() = assertAccessible(PostsKey, PostsTags.TAB_SENT) { h.click(PostsTags.TAB_SENT) }

    @Test
    fun detailsOfAFailedPost() = assertAccessible(PostDetailsKey(postId = failed), DetailsTags.SCREEN) { h.waitFor(DetailsTags.RETRY) }

    @Test
    fun detailsOfASentPost() = assertAccessible(PostDetailsKey(historyId = sent), DetailsTags.SCREEN)

    @Test
    fun schedule() = assertAccessible(ScheduleKey, ScheduleTags.SCREEN)

    @Test
    fun settings() = assertAccessible(SettingsKey, SettingsTags.SCREEN)

    @Test
    fun accounts() = assertAccessible(AccountsKey, AccountsTags.SCREEN)

    @Test
    fun accountDetails() = assertAccessible(AccountDetailsKey(h.alice), AccountDetailsTags.SCREEN)

    @Test
    fun addMastodon() = assertAccessible(AddMastodonKey(), AddMastodonTags.SCREEN)

    @Test
    fun addBluesky() = assertAccessible(AddBlueskyKey(), AddBlueskyTags.SCREEN)

    @Test
    fun defaults() = assertAccessible(DefaultsSettingsKey, DefaultsTags.SCREEN)

    @Test
    fun sending() = assertAccessible(SendingSettingsKey, SendingTags.SCREEN)

    @Test
    fun about() = assertAccessible(AboutKey, AboutTags.SCREEN)
}

/** Large screens: on a tablet or in landscape the screens keep a readable width, in the middle. */
@RunWith(AndroidJUnit4::class)
class LargeScreenLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = ScheduleUiHarness(compose)

    @After
    fun tearDown() = h.close()

    private fun assertCentredAndCapped(screenTag: String) {
        h.waitFor(screenTag)
        compose.waitForIdle()
        val density = compose.density.density
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val screen = compose.onNode(hasTestTag(screenTag)).fetchSemanticsNode().boundsInRoot
        assertThat(screen.width / density).isWithin(1f).of(MaxContentWidth.value)
        assertThat(abs(screen.left - (root.width - screen.right))).isLessThan(density)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun theComposerIsCappedOnATabletInLandscape() {
        h.show { PostApp() }
        assertCentredAndCapped(ComposerTags.SCREEN)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp")
    fun postsAreCappedOnATabletInLandscape() {
        h.show { PostApp(openOnStart = PostsKey) }
        assertCentredAndCapped(PostsTags.SCREEN)
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun settingsAreCappedOnATabletInPortrait() {
        h.show { PostApp(openOnStart = SettingsKey) }
        assertCentredAndCapped(SettingsTags.SCREEN)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun aPhoneUsesTheWholeWidth() {
        h.show { PostApp(openOnStart = SettingsKey) }
        h.waitFor(SettingsTags.SCREEN)
        compose.waitForIdle()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val screen = compose.onNode(hasTestTag(SettingsTags.SCREEN)).fetchSemanticsNode().boundsInRoot
        assertThat(screen.width).isEqualTo(root.width)
    }
}
