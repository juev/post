package org.evsyukov.post.ui.composer

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.PostApplication
import org.evsyukov.post.core.markdown.MANUAL_BREAK
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.AccountLimits
import org.evsyukov.post.data.accounts.AccountOptions
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.secrets.BlueskySecret
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.theme.PostTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.Locale

/**
 * Screenshots of the composer for a person to look at: empty, with highlighted Markdown and a manual break
 * and a Bluesky thread in the status line, and the preview, details and account options sheets, in light
 * and dark, English and Russian. The whole screen is captured, sheets included; the device settings stay
 * as they are, and the accounts made here are removed at the end.
 */
@RunWith(AndroidJUnit4::class)
class ComposerScreenshots {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val container = ApplicationProvider.getApplicationContext<PostApplication>().container
    private val created = mutableListOf<Long>()

    private val outDir: File by lazy {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")
        dir.also { it.mkdirs() }
    }

    private val variants = listOf(
        Locale.ENGLISH to false, Locale.ENGLISH to true, Locale.forLanguageTag("ru") to false, Locale.forLanguageTag("ru") to true,
    )

    @Before
    fun seed() = runBlocking {
        val caps = MastodonCapabilities(maxCharacters = 500, mastodonApiVersion = 7, supportsQuotePolicy = true)
        val denis = container.accounts.add(
            NewAccount(Network.MASTODON, "https://mastodon.social", "denis@mastodon.social", "1", "Denis", null, label = "denis"),
            MastodonSecret("screenshot"),
        )
        container.accounts.updateLimits(denis, AccountLimits.Mastodon(caps), Instant.now())
        container.accounts.updateDefaults(denis, AccountOptions(languages = listOf("en")))
        val work = container.accounts.add(
            NewAccount(Network.MASTODON, "https://fosstodon.org", "work@fosstodon.org", "2", "Work", null, label = "work"),
            MastodonSecret("screenshot"),
        )
        val sky = container.accounts.add(
            NewAccount(Network.BLUESKY, "https://bsky.social", "denis.bsky.social", "did:plc:screenshot", "Denis", null),
            BlueskySecret("a", "r"),
        )
        created += listOf(denis, work, sky)
        container.settings.setDefaultTargets(listOf(denis, sky))
    }

    @After
    fun cleanUp() = runBlocking {
        for (id in created) container.accounts.remove(id)
        container.settings.setDefaultTargets(emptyList())
        for (post in container.posts.observeDrafts().first()) container.posts.delete(post.id)
    }

    @Test
    fun captureTheComposerAndItsSheetsInLightAndDarkInEnglishAndRussian() {
        var variant by mutableStateOf(variants.first())
        compose.setContent {
            val (locale, dark) = variant
            key(variant) {
                Localized(locale, dark) {
                    // The localized context is no activity, so the activity's owners are passed on by hand.
                    CompositionLocalProvider(
                        LocalAppContainer provides container,
                        LocalActivityResultRegistryOwner provides compose.activity,
                        LocalOnBackPressedDispatcherOwner provides compose.activity,
                    ) {
                        ComposerScreen(onOpenPosts = {}, onOpenSchedule = {}, onOpenSettings = {})
                    }
                }
            }
        }
        waitFor(ComposerTags.EDITOR)
        waitFor(ComposerTags.statusEntry(1), unmerged = true)

        each("composer-empty") { variant = it }

        compose.onNodeWithTag(ComposerTags.EDITOR).performTextInput(TEXT)
        compose.waitForIdle()
        hideKeyboard()
        // The status line shows the Bluesky thread once the counters settle.
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(ComposerTags.statusEntry(1)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(500)
        each("composer-markdown") { variant = it }

        each("composer-preview") {
            variant = it
            waitFor(ComposerTags.OVERFLOW)
            compose.onNodeWithTag(ComposerTags.OVERFLOW).performScrollTo().performClick()
            waitFor(ComposerTags.MENU_PREVIEW)
            compose.onNodeWithTag(ComposerTags.MENU_PREVIEW).performClick()
            waitFor(ComposerTags.PREVIEW)
        }
        each("composer-details") {
            variant = it
            waitFor(ComposerTags.STATUS)
            compose.onNodeWithTag(ComposerTags.STATUS).performClick()
            waitFor(ComposerTags.DETAILS)
        }
        each("composer-account-options") {
            variant = it
            waitFor(ComposerTags.target(created[0]))
            compose.onNodeWithTag(ComposerTags.target(created[0])).performTouchInput { longClick() }
            waitFor(ComposerTags.ACCOUNT_OPTIONS)
        }
    }

    /** Sets each variant with [show] and captures the screen as `<name>-<lang>-<theme>.png`. */
    private fun each(name: String, show: (Pair<Locale, Boolean>) -> Unit) {
        for (v in variants) {
            show(v)
            compose.waitForIdle()
            hideKeyboard()
            // Sheets and the theme animate in.
            Thread.sleep(800)
            compose.waitForIdle()
            capture("$name-${v.first.language}-${if (v.second) "dark" else "light"}.png")
        }
    }

    private fun waitFor(tag: String, unmerged: Boolean = false) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = unmerged).fetchSemanticsNodes().isNotEmpty() }

    private fun hideKeyboard() {
        compose.runOnUiThread {
            compose.activity.window.let { WindowCompat.getInsetsController(it, it.decorView).hide(WindowInsetsCompat.Type.ime()) }
        }
    }

    private fun capture(file: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(outDir, file).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        val TEXT = "**Bold** and *italic*, `code` and ~~struck~~ words.\n" +
            "> A quote of someone wise\n" +
            "- a list item\n" +
            "[A link](https://example.com/page)\n" +
            MANUAL_BREAK + "\n" +
            "The second piece goes on for a while. " +
            "It has several sentences, so that Bluesky needs a thread for it. " +
            "Each sentence adds a few more words to the text. " +
            "Mastodon still takes it in one part, since its limit is larger. " +
            "The split keeps sentences whole and balances the parts. " +
            "One more sentence makes sure of it."
    }
}

/** [content] with the strings of [locale] and the [dark] theme, leaving the device settings alone. */
@Composable
private fun Localized(locale: Locale, dark: Boolean, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val config = Configuration(LocalConfiguration.current).apply {
        setLocale(locale)
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
    }
    val context = base.createConfigurationContext(config)
    CompositionLocalProvider(
        LocalContext provides context,
        LocalConfiguration provides config,
        LocalResources provides context.resources,
    ) {
        PostTheme(darkTheme = dark) { Surface { content() } }
    }
}
