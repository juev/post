package org.evsyukov.post.ui.posts

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.AppContainer
import org.evsyukov.post.PublishTrigger
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.FakeCipher
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.PostApp
import org.evsyukov.post.ui.composer.ComposerTags
import org.evsyukov.post.ui.theme.PostTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Posts → Drafts opens a draft in the composer as it is saved, every time (R15). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1600dp")
class DraftsNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val container = AppContainer(context, publishTrigger = PublishTrigger { }) { FakeCipher() }
    private val alice = runBlocking {
        container.accounts.add(NewAccount(Network.MASTODON, "https://a.example", "alice@a.example", "r-a", "Alice", null), MastodonSecret("t"))
    }
    private val draft = runBlocking { container.posts.saveDraft(DraftInput(text = "my saved draft", targets = listOf(alice))) }

    @After
    fun tearDown() {
        runCatching { compose.activityRule.scenario.close() }
        if (container.database.isOpen) container.database.close()
    }

    private fun waitFor(tag: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun click(tag: String) {
        waitFor(tag)
        // The overflow sits in the scrolling toolbar.
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun waitForText(text: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun aDraftOpenedAgainAfterClearShowsItsSavedText() {
        compose.setContent { CompositionLocalProvider(LocalAppContainer provides container) { PostTheme { PostApp() } } }
        click(ComposerTags.POSTS)
        click(PostsTags.TAB_DRAFTS)
        click(DraftsTags.row(draft))
        waitForText("my saved draft")

        // Clear starts a new empty post; the saved draft stays in Drafts.
        click(ComposerTags.OVERFLOW)
        click(ComposerTags.MENU_CLEAR)
        click(ComposerTags.CLEAR_CONFIRM)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("my saved draft")).fetchSemanticsNodes().isEmpty() }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        click(PostsTags.TAB_DRAFTS)
        click(DraftsTags.row(draft))

        waitFor(ComposerTags.EDITOR)
        waitForText("my saved draft")
    }
}
