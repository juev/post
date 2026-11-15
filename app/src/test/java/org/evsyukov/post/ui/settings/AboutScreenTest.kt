package org.evsyukov.post.ui.settings

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.BuildConfig
import org.evsyukov.post.R
import org.evsyukov.post.ui.theme.PostTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Settings → About (screens.md): icon, version, links to the source and the privacy note, license. */
@RunWith(AndroidJUnit4::class)
class AboutScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val opened = mutableListOf<String>()

    private fun show() {
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        compose.setContent { CompositionLocalProvider(LocalUriHandler provides handler) { PostTheme { AboutScreen(onBack = {}) } } }
    }

    @Test
    fun theIconAndTheVersionNameAndCodeOfThisBuildAreShown() {
        show()

        compose.onNodeWithContentDescription(string(R.string.about_icon)).assertIsDisplayed()
        compose.onNodeWithTag(AboutTags.VERSION)
            .assertTextEquals(string(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE))
        assertThat(BuildConfig.VERSION_NAME).isNotEmpty()
    }

    @Test
    fun theLinksOpenTheRepositoryThePrivacyNoteAndTheLicense() {
        show()

        compose.onNodeWithTag(AboutTags.SOURCE).performScrollTo().performClick()
        compose.onNodeWithTag(AboutTags.PRIVACY).performScrollTo().performClick()
        compose.onNodeWithTag(AboutTags.LICENSE).performScrollTo().performClick()

        assertThat(opened).containsExactly(
            "https://github.com/juev/post",
            "https://github.com/juev/post/blob/main/docs/privacy.md",
            "https://github.com/juev/post/blob/main/LICENSE",
        ).inOrder()
    }

    @Test
    fun theLicenseIsMit() {
        show()

        compose.onNodeWithText(string(R.string.about_license_mit)).performScrollTo().assertIsDisplayed()
        assertThat(string(R.string.about_license_mit)).contains("MIT")
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)
}
