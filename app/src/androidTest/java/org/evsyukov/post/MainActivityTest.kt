package org.evsyukov.post

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.evsyukov.post.ui.composer.ComposerTags
import org.evsyukov.post.ui.settings.SettingsTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The launched app, with PostApplication and the window theme: it starts on the composer (screens.md). */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun theAppStartsOnTheComposerAndSettingsOpensOverIt() {
        compose.onNodeWithTag(ComposerTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.app_name)).assertIsDisplayed()

        compose.onNodeWithContentDescription(compose.activity.getString(R.string.settings)).performClick()
        compose.onNodeWithTag(SettingsTags.SCREEN).assertIsDisplayed()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag(ComposerTags.SCREEN).assertIsDisplayed()
        compose.onNodeWithTag(SettingsTags.SCREEN).assertDoesNotExist()
    }
}
