package org.evsyukov.post.ui.posts

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.AppContainer
import org.evsyukov.post.PublishTrigger
import org.evsyukov.post.R
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.FakeCipher
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.history.HistoryItem
import org.evsyukov.post.data.posts.PostOptions
import org.evsyukov.post.data.posts.PublishedPart
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
import java.time.Instant

/** Posts → Sent → Edit or New post from this open the composer filled from the record (R31, R40). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1600dp")
class PublishedNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val container = AppContainer(context, publishTrigger = PublishTrigger { }) { FakeCipher() }
    private val alice = runBlocking {
        container.accounts.add(NewAccount(Network.MASTODON, "https://a.example", "alice@a.example", "r-a", "Alice", null), MastodonSecret("t"))
    }
    private val record = runBlocking {
        container.history.insert(
            null, Instant.now(), "published text", PostOptions(),
            listOf(HistoryItem(alice, Network.MASTODON, "https://a.example", "alice@a.example", listOf(PublishedPart(id = "1", text = "published text")))),
        )!!
    }

    @After
    fun tearDown() {
        runCatching { compose.activityRule.scenario.close() }
        if (container.database.isOpen) container.database.close()
    }

    private fun waitFor(tag: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun click(tag: String) {
        waitFor(tag)
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun waitForText(text: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun openFromSent(item: String) {
        click(ComposerTags.POSTS)
        click(PostsTags.TAB_SENT)
        click(SentTags.menu(record))
        click(item)
        waitFor(ComposerTags.EDITOR)
    }

    @Test
    fun editFromSentOpensTheComposerInEditMode() {
        compose.setContent { CompositionLocalProvider(LocalAppContainer provides container) { PostTheme { PostApp() } } }

        openFromSent(SentTags.MENU_EDIT)

        waitForText(context.getString(R.string.edit_title))
        waitForText("published text")
    }

    @Test
    fun aSecondNewPostFromTheSameRecordStartsFilledAgain() {
        compose.setContent { CompositionLocalProvider(LocalAppContainer provides container) { PostTheme { PostApp() } } }

        openFromSent(SentTags.MENU_NEW)
        waitForText("published text")
        click(ComposerTags.SEND)
        // After the send the composer holds an empty draft; the same record is opened again from Posts.
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("published text")).fetchSemanticsNodes().isEmpty() }
        click(ComposerTags.POSTS)
        click(PostsTags.TAB_SENT)
        click(SentTags.menu(record))
        click(SentTags.MENU_NEW)

        waitForText("published text")
    }
}
