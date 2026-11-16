package org.evsyukov.post.ui.settings.accounts

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.evsyukov.post.MainActivity
import org.evsyukov.post.R
import org.evsyukov.post.share.ShareTestApplication
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Bluesky sign-in survives a rotation of the whole app (screens.md, Add Bluesky account; N13). */
@RunWith(AndroidJUnit4::class)
@Config(application = ShareTestApplication::class, qualifiers = "w411dp-h891dp")
class SignInRotationTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: ShareTestApplication = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        if (app.container.database.isOpen) app.container.database.close()
    }

    private fun waitFor(tag: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun click(tag: String) {
        waitFor(tag)
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    @Test
    fun theHandleAndThePasswordStayWhenTheScreenRotates() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        compose.onNodeWithContentDescription(app.getString(R.string.settings)).performClick()
        compose.waitForIdle()
        click(org.evsyukov.post.ui.settings.SettingsTags.ACCOUNTS)
        click(AccountsTags.ADD)
        click(AccountsTags.CHOOSE_BLUESKY)
        waitFor(AddBlueskyTags.HANDLE)
        compose.onNodeWithTag(AddBlueskyTags.HANDLE).performTextReplacement("alice.bsky.social")
        compose.onNodeWithTag(AddBlueskyTags.PASSWORD).performTextReplacement("abcd-efgh-ijkl-mnop")
        compose.waitForIdle()
        compose.onNodeWithTag(AddBlueskyTags.SIGN_IN).assertIsEnabled()

        scenario!!.recreate()
        waitFor(AddBlueskyTags.SIGN_IN)

        // Sign in needs both fields: it stays enabled only when the password came back too.
        compose.onNodeWithTag(AddBlueskyTags.SIGN_IN).assertIsEnabled()
    }

    @Test
    fun leavingTheScreenForgetsThePassword() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitForIdle()
        compose.onNodeWithContentDescription(app.getString(R.string.settings)).performClick()
        click(org.evsyukov.post.ui.settings.SettingsTags.ACCOUNTS)
        click(AccountsTags.ADD)
        click(AccountsTags.CHOOSE_BLUESKY)
        waitFor(AddBlueskyTags.HANDLE)
        compose.onNodeWithTag(AddBlueskyTags.HANDLE).performTextReplacement("alice.bsky.social")
        compose.onNodeWithTag(AddBlueskyTags.PASSWORD).performTextReplacement("abcd-efgh-ijkl-mnop")
        compose.waitForIdle()

        compose.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
        compose.waitForIdle()
        click(AccountsTags.ADD)
        click(AccountsTags.CHOOSE_BLUESKY)
        waitFor(AddBlueskyTags.SIGN_IN)

        compose.onNodeWithTag(AddBlueskyTags.SIGN_IN).assertIsNotEnabled()
    }
}
