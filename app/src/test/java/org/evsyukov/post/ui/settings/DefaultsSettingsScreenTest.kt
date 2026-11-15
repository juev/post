package org.evsyukov.post.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.AppContainer
import org.evsyukov.post.R
import org.evsyukov.post.core.markdown.BlueskyLinkMode
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.FakeCipher
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.data.settings.Settings
import org.evsyukov.post.data.settings.ShareAction
import org.evsyukov.post.data.settings.TooManyFilesDefault
import org.evsyukov.post.data.settings.UnsupportedTypeDefault
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.theme.PostTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Settings → Defaults on the app's settings (screens.md; R10, R16, R18, A12, F11). */
@RunWith(AndroidJUnit4::class)
class DefaultsSettingsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val container = AppContainer(ApplicationProvider.getApplicationContext<Context>()) { FakeCipher() }

    @After
    fun tearDown() {
        if (container.database.isOpen) container.database.close()
    }

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) { PostTheme { DefaultsSettingsScreen(onBack = {}) } }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(DefaultsTags.LIST)).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The node with [tag], scrolled into view first: the list is longer than the test screen. */
    private fun node(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(DefaultsTags.LIST).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun option(group: String, value: Enum<*>) = node(SettingsSectionTags.option(group, value))

    private fun stored(): Settings = runBlocking { container.settings.current() }

    private fun waitStored(check: (Settings) -> Boolean) = compose.waitUntil(5_000) { check(stored()) }

    private fun addAccount(name: String): Long = runBlocking {
        container.accounts.add(
            NewAccount(Network.MASTODON, "https://mastodon.example", "$name@mastodon.example", name, name.replaceFirstChar { it.uppercase() }, null),
            MastodonSecret("t-$name"),
        )
    }

    @Test
    fun aNewInstallShowsTheDefaultsOfTheSpec() {
        show()

        node(DefaultsTags.TARGETS_EMPTY).assertIsDisplayed()
        option(DefaultsTags.SHARE_ACTION, ShareAction.COMPOSER).assertIsSelected()
        option(DefaultsTags.SHARE_ACTION, ShareAction.SEND_NOW).assertIsNotSelected()
        node(DefaultsTags.REQUIRE_ALT).assertIsOff()
        option(DefaultsTags.TOO_MANY_FILES, TooManyFilesDefault.ASK).assertIsSelected()
        option(DefaultsTags.UNSUPPORTED_TYPE, UnsupportedTypeDefault.ASK).assertIsSelected()
        option(DefaultsTags.BLUESKY_LINKS, BlueskyLinkMode.FACET_ON_TEXT).assertIsSelected()
    }

    @Test
    fun storedValuesAreShown() {
        runBlocking {
            container.settings.setShareAction(ShareAction.SEND_LATER)
            container.settings.setRequireAltText(true)
            container.settings.setDefaultFixTooManyFiles(TooManyFilesDefault.MOVE_TO_REPLY)
            container.settings.setDefaultFixUnsupportedType(UnsupportedTypeDefault.SEND_WITHOUT_FILE)
            container.settings.setBlueskyLinkMode(BlueskyLinkMode.TEXT_AND_SHORT_URL)
        }
        show()

        option(DefaultsTags.SHARE_ACTION, ShareAction.SEND_LATER).assertIsSelected()
        option(DefaultsTags.SHARE_ACTION, ShareAction.COMPOSER).assertIsNotSelected()
        node(DefaultsTags.REQUIRE_ALT).assertIsOn()
        option(DefaultsTags.TOO_MANY_FILES, TooManyFilesDefault.MOVE_TO_REPLY).assertIsSelected()
        option(DefaultsTags.UNSUPPORTED_TYPE, UnsupportedTypeDefault.SEND_WITHOUT_FILE).assertIsSelected()
        option(DefaultsTags.BLUESKY_LINKS, BlueskyLinkMode.TEXT_AND_SHORT_URL).assertIsSelected()
    }

    @Test
    fun shareActionIsSaved() {
        show()

        for (action in listOf(ShareAction.SEND_NOW, ShareAction.SEND_LATER, ShareAction.COMPOSER)) {
            option(DefaultsTags.SHARE_ACTION, action).performClick()
            waitStored { it.shareAction == action }
            option(DefaultsTags.SHARE_ACTION, action).assertIsSelected()
        }
    }

    @Test
    fun requireAltTextIsSaved() {
        show()

        node(DefaultsTags.REQUIRE_ALT).performClick()
        waitStored { it.requireAltText }
        node(DefaultsTags.REQUIRE_ALT).assertIsOn().performClick()
        waitStored { !it.requireAltText }
        node(DefaultsTags.REQUIRE_ALT).assertIsOff()
    }

    @Test
    fun mediaFixesAreSaved() {
        show()

        option(DefaultsTags.TOO_MANY_FILES, TooManyFilesDefault.MOVE_TO_REPLY).performClick()
        waitStored { it.defaultFixTooManyFiles == TooManyFilesDefault.MOVE_TO_REPLY }
        option(DefaultsTags.UNSUPPORTED_TYPE, UnsupportedTypeDefault.SEND_WITHOUT_FILE).performClick()
        waitStored { it.defaultFixUnsupportedType == UnsupportedTypeDefault.SEND_WITHOUT_FILE }
        option(DefaultsTags.TOO_MANY_FILES, TooManyFilesDefault.MOVE_TO_REPLY).assertIsSelected()
        option(DefaultsTags.UNSUPPORTED_TYPE, UnsupportedTypeDefault.SEND_WITHOUT_FILE).assertIsSelected()

        option(DefaultsTags.TOO_MANY_FILES, TooManyFilesDefault.ASK).performClick()
        waitStored { it.defaultFixTooManyFiles == TooManyFilesDefault.ASK }
        option(DefaultsTags.UNSUPPORTED_TYPE, UnsupportedTypeDefault.ASK).performClick()
        waitStored { it.defaultFixUnsupportedType == UnsupportedTypeDefault.ASK }
    }

    @Test
    fun blueskyLinkModeIsSaved() {
        show()

        option(DefaultsTags.BLUESKY_LINKS, BlueskyLinkMode.TEXT_AND_SHORT_URL).performClick()
        waitStored { it.blueskyLinkMode == BlueskyLinkMode.TEXT_AND_SHORT_URL }
        option(DefaultsTags.BLUESKY_LINKS, BlueskyLinkMode.TEXT_AND_SHORT_URL).assertIsSelected()
        option(DefaultsTags.BLUESKY_LINKS, BlueskyLinkMode.FACET_ON_TEXT).performClick()
        waitStored { it.blueskyLinkMode == BlueskyLinkMode.FACET_ON_TEXT }
    }

    @Test
    fun defaultTargetsArePickedInTheOrderOfTapsAndReordered() {
        val alice = addAccount("alice")
        val bob = addAccount("bob")
        val carol = addAccount("carol")
        show()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(DefaultsTags.target(alice))).fetchSemanticsNodes().isNotEmpty() }
        node(DefaultsTags.target(alice)).assertIsOff()

        node(DefaultsTags.target(carol)).performClick()
        waitStored { it.defaultTargets == listOf(carol) }
        node(DefaultsTags.target(alice)).performClick()
        waitStored { it.defaultTargets == listOf(carol, alice) }
        node(DefaultsTags.target(carol)).assertIsOn()
        node(DefaultsTags.target(bob)).assertIsOff()

        // R16: the order of the targets; the move buttons are labelled with the account.
        node(DefaultsTags.moveUp(carol)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(string(R.string.defaults_target_move_up, "Alice")).assertIsEnabled().performClick()
        waitStored { it.defaultTargets == listOf(alice, carol) }
        node(DefaultsTags.moveDown(carol)).assertIsNotEnabled()
        node(DefaultsTags.moveDown(alice)).performClick()
        waitStored { it.defaultTargets == listOf(carol, alice) }

        node(DefaultsTags.target(carol)).performClick()
        waitStored { it.defaultTargets == listOf(alice) }
        compose.onAllNodes(hasTestTag(DefaultsTags.moveUp(carol))).assertCountEquals(0)
    }

    @Test
    fun aRemovedAccountIsNotShownAndLeavesTheTargetsOnTheNextChange() {
        val alice = addAccount("alice")
        val bob = addAccount("bob")
        runBlocking { container.settings.setDefaultTargets(listOf(999L, alice)) }
        show()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(DefaultsTags.target(alice))).fetchSemanticsNodes().isNotEmpty() }

        node(DefaultsTags.target(alice)).assertIsOn()
        compose.onAllNodes(hasTestTag(DefaultsTags.target(999L))).assertCountEquals(0)
        // Alice is the first shown target although she is second in the stored list.
        node(DefaultsTags.moveUp(alice)).assertIsNotEnabled()

        node(DefaultsTags.target(bob)).performClick()
        waitStored { it.defaultTargets == listOf(alice, bob) }
    }

    @Test
    fun toggledAndMovedTargetsDropRemovedAccounts() {
        val existing = setOf(1L, 2L, 3L)
        assertThat(toggledTarget(listOf(9L, 1L), existing, 2L)).containsExactly(1L, 2L).inOrder()
        assertThat(toggledTarget(listOf(1L, 2L), existing, 1L)).containsExactly(2L)
        assertThat(movedTarget(listOf(1L, 9L, 2L), existing, 2L, up = true)).containsExactly(2L, 1L).inOrder()
        assertThat(movedTarget(listOf(1L, 2L), existing, 1L, up = true)).containsExactly(1L, 2L).inOrder()
        assertThat(movedTarget(listOf(1L, 2L), existing, 2L, up = false)).containsExactly(1L, 2L).inOrder()
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)
}
