package org.evsyukov.post.ui.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.evsyukov.post.AppContainer
import org.evsyukov.post.R
import org.evsyukov.post.data.FakeCipher
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.PostApp
import org.evsyukov.post.ui.schedule.ScheduleTags
import org.evsyukov.post.ui.theme.PostTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** screens.md, Settings: a list of sections; each opens its screen and back returns to the list. */
@RunWith(AndroidJUnit4::class)
class SettingsRootTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val container = AppContainer(ApplicationProvider.getApplicationContext<Context>()) { FakeCipher() }

    @Before
    fun setUp() {
        compose.setContent { CompositionLocalProvider(LocalAppContainer provides container) { PostTheme { PostApp() } } }
        compose.onNodeWithContentDescription(string(R.string.settings)).performClick()
    }

    @After
    fun tearDown() {
        // Ends the activity first, so no screen still reads the database when it closes (#15).
        runCatching { compose.activityRule.scenario.close() }
        if (container.database.isOpen) container.database.close()
    }

    @Test
    fun settingsListsAccountsDefaultsSendingScheduleAndAbout() {
        for (title in listOf(R.string.settings_accounts, R.string.settings_defaults, R.string.settings_sending, R.string.schedule, R.string.settings_about)) {
            compose.onNodeWithText(string(title)).assertIsDisplayed()
        }
    }

    @Test
    fun defaultsSendingAndAboutOpenTheirScreens() {
        for ((row, screen) in listOf(
            SettingsTags.DEFAULTS to SettingsSectionTags.DEFAULTS,
            SettingsTags.SENDING to SettingsSectionTags.SENDING,
            SettingsTags.ABOUT to SettingsSectionTags.ABOUT,
        )) {
            compose.onNodeWithTag(row).performClick()
            compose.onNodeWithTag(screen).assertIsDisplayed()
            compose.onNodeWithContentDescription(string(R.string.back)).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(SettingsTags.SCREEN).assertIsDisplayed()
        }
    }

    @Test
    fun scheduleOpensTheScheduleScreen() {
        compose.onNodeWithTag(SettingsTags.SCHEDULE).performClick()

        compose.onNodeWithTag(ScheduleTags.SCREEN).assertIsDisplayed()
    }

    private fun string(@StringRes id: Int) = compose.activity.getString(id)
}
