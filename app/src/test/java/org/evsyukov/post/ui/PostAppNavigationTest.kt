package org.evsyukov.post.ui

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.evsyukov.post.R
import org.evsyukov.post.ui.common.BackTopBarTags
import org.evsyukov.post.ui.composer.ComposerTags
import org.evsyukov.post.ui.posts.PostsTags
import org.evsyukov.post.ui.schedule.ScheduleTags
import org.evsyukov.post.ui.settings.SettingsTags
import org.evsyukov.post.ui.theme.PostTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * screens.md, Navigation: the composer is the start screen; Posts, Schedule and Settings open on top of
 * it from its top bar and return to it with their back arrow or the system back.
 */
@RunWith(AndroidJUnit4::class)
class PostAppNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        compose.setContent { PostTheme { PostApp() } }
    }

    @Test
    fun theAppStartsOnTheComposerWithItsTitleAndActions() {
        compose.onNodeWithTag(ComposerTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.app_name)).assertIsDisplayed()
        // R33: the icon buttons are labelled for screen readers.
        compose.onNodeWithContentDescription(string(R.string.posts)).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.settings)).assertIsDisplayed()
        compose.onNodeWithContentDescription(string(R.string.more)).assertIsDisplayed()
    }

    @Test
    fun settingsOpensFromTheTopBarAndTheBackArrowReturnsToTheComposer() {
        compose.onNodeWithContentDescription(string(R.string.settings)).performClick()
        assertOnly(SettingsTags.SCREEN, title = R.string.settings)

        compose.onNodeWithContentDescription(string(R.string.back)).performClick()
        assertOnly(ComposerTags.SCREEN)
    }

    @Test
    fun theSystemBackReturnsFromSettingsToTheComposer() {
        compose.onNodeWithContentDescription(string(R.string.settings)).performClick()
        assertOnly(SettingsTags.SCREEN, title = R.string.settings)

        systemBack()
        assertOnly(ComposerTags.SCREEN)
    }

    @Test
    fun postsOpensFromTheTopBarAndTheBackArrowReturnsToTheComposer() {
        compose.onNodeWithContentDescription(string(R.string.posts)).performClick()
        assertOnly(PostsTags.SCREEN, title = R.string.posts)

        compose.onNodeWithContentDescription(string(R.string.back)).performClick()
        assertOnly(ComposerTags.SCREEN)
    }

    @Test
    fun theSystemBackReturnsFromPostsToTheComposer() {
        compose.onNodeWithContentDescription(string(R.string.posts)).performClick()
        assertOnly(PostsTags.SCREEN, title = R.string.posts)

        systemBack()
        assertOnly(ComposerTags.SCREEN)
    }

    @Test
    fun scheduleOpensFromTheOverflowMenuAndBackReturnsToTheComposer() {
        compose.onNodeWithContentDescription(string(R.string.more)).performClick()
        compose.onNodeWithText(string(R.string.schedule)).performClick()
        assertOnly(ScheduleTags.SCREEN, title = R.string.schedule)

        systemBack()
        assertOnly(ComposerTags.SCREEN)
    }

    @Test
    fun aDoubleTapOnAnActionOpensItsScreenOnce() {
        compose.mainClock.autoAdvance = false
        val settings = compose.onNodeWithTag(ComposerTags.SETTINGS)
        settings.performClick()
        settings.performClick()
        compose.mainClock.autoAdvance = true
        assertOnly(SettingsTags.SCREEN, title = R.string.settings)

        // One back is enough: the screen was not stacked twice.
        systemBack()
        assertOnly(ComposerTags.SCREEN)
    }

    @Test
    fun aDoubleTapOnTheBackArrowStopsAtTheComposer() {
        compose.onNodeWithContentDescription(string(R.string.settings)).performClick()
        assertOnly(SettingsTags.SCREEN, title = R.string.settings)

        // The screen is still there while it slides out; the second tap must not pop the composer.
        compose.mainClock.autoAdvance = false
        val back = compose.onNodeWithTag(BackTopBarTags.BACK)
        back.performClick()
        back.performClick()
        compose.mainClock.autoAdvance = true
        assertOnly(ComposerTags.SCREEN)
    }

    private fun systemBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    /** [screen] is the one shown, with [title] in its top bar; the screens it covers are gone. */
    private fun assertOnly(screen: String, @StringRes title: Int? = null) {
        compose.waitForIdle()
        for (tag in listOf(ComposerTags.SCREEN, PostsTags.SCREEN, ScheduleTags.SCREEN, SettingsTags.SCREEN)) {
            if (tag == screen) {
                compose.onNodeWithTag(tag).assertIsDisplayed()
            } else {
                compose.onNodeWithTag(tag).assertDoesNotExist()
            }
        }
        if (title != null) compose.onNodeWithText(string(title)).assertIsDisplayed()
    }

    private fun string(@StringRes id: Int) = compose.activity.getString(id)
}
