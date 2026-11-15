package org.evsyukov.post.ui.settings.accounts

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.AppContainer
import org.evsyukov.post.R
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.FakeCipher
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.PostApp
import org.evsyukov.post.ui.settings.SettingsTags
import org.evsyukov.post.ui.theme.PostTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The accounts screens wired to the app's objects: list, details, label, remove, add and sign in again. */
@RunWith(AndroidJUnit4::class)
class AccountsNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val container = AppContainer(ApplicationProvider.getApplicationContext<Context>()) { FakeCipher() }

    @Before
    fun setUp() {
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) { PostTheme { PostApp() } }
        }
        compose.onNodeWithContentDescription(string(R.string.settings)).performClick()
        compose.onNodeWithTag(SettingsTags.ACCOUNTS).performClick()
    }

    @After
    fun tearDown() = container.database.close()

    @Test
    fun anAccountShowsUpOpensChangesItsLabelAndIsRemoved() {
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(AccountsTags.EMPTY)).fetchSemanticsNodes().isNotEmpty() }
        val id = runBlocking {
            container.accounts.add(
                NewAccount(Network.MASTODON, "https://mastodon.example", "me@mastodon.example", "1", "Me", null),
                MastodonSecret("t"),
            )
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(AccountsTags.row(id))).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag(AccountsTags.row(id)).performClick()
        compose.onNodeWithTag(AccountDetailsTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithTag(AccountDetailsTags.LABEL).performTextInput("work")
        compose.waitUntil(5_000) { runBlocking { container.accounts.get(id)!!.label } == "work" }

        compose.onNodeWithTag(AccountDetailsTags.REMOVE).performScrollTo().performClick()
        compose.onNodeWithTag(AccountDetailsTags.CONFIRM_REMOVE).performClick()
        compose.waitUntil(5_000) { runBlocking { container.accounts.observe().first().isEmpty() } }
        compose.onNodeWithTag(AccountsTags.SCREEN).assertIsDisplayed()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(AccountsTags.EMPTY)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun addAccountOpensTheFormOfTheChosenNetwork() {
        compose.onNodeWithTag(AccountsTags.ADD).performClick()
        compose.onNodeWithTag(AccountsTags.CHOOSE_MASTODON).performClick()
        compose.onNodeWithTag(AddMastodonTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.add_mastodon_title)).assertIsDisplayed()

        compose.onNodeWithContentDescription(string(R.string.back)).performClick()
        compose.onNodeWithTag(AccountsTags.ADD).performClick()
        compose.onNodeWithTag(AccountsTags.CHOOSE_BLUESKY).performClick()
        compose.onNodeWithTag(AddBlueskyTags.SCREEN).assertIsDisplayed()
    }

    @Test
    fun signInAgainOpensTheFormWithTheServerFilledIn() {
        val id = runBlocking {
            container.accounts.add(
                NewAccount(Network.MASTODON, "https://mastodon.example", "me@mastodon.example", "1", "Me", null),
                MastodonSecret("t"),
            )
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(AccountsTags.row(id))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(AccountsTags.row(id)).performClick()

        compose.onNodeWithTag(AccountDetailsTags.SIGN_IN_AGAIN).performScrollTo().performClick()

        compose.onNodeWithTag(AddMastodonTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.sign_in_again_title)).assertIsDisplayed()
        compose.onNodeWithTag(AddMastodonTags.ADDRESS).assertTextContains("https://mastodon.example")
    }

    private fun string(@StringRes id: Int) = compose.activity.getString(id)
}
