package org.evsyukov.post.ui.settings

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.evsyukov.post.core.markdown.BlueskyLinkMode
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.accounts.AccountOptions
import org.evsyukov.post.data.db.AccountState
import org.evsyukov.post.data.settings.Settings
import org.evsyukov.post.data.settings.TooManyFilesDefault
import org.evsyukov.post.ui.theme.PostTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.Locale

/**
 * Screenshots of Settings → Defaults, Sending and About in light and dark, English and Russian, for a
 * person to look at. Each image is drawn with its own configuration; the device settings stay as they
 * are. The images go to the additional test output of the connected test task.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenshots {
    @get:Rule
    val compose = createComposeRule()

    private val outDir: File by lazy {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")
        dir.also { it.mkdirs() }
    }

    private fun account(id: Long, network: Network, name: String, handle: String) = Account(
        id = id, network = network, server = "https://s.example", handle = handle, remoteId = "r$id", displayName = name, avatarUrl = null,
        label = "", position = 0, state = AccountState.OK, limits = null, limitsFetchedAt = null, defaults = AccountOptions(), createdAt = Instant.EPOCH,
    )

    private val accounts = listOf(
        account(1, Network.MASTODON, "Denis", "denis@mastodon.social"),
        account(2, Network.MASTODON, "Work", "work@fosstodon.org"),
        account(3, Network.BLUESKY, "Alice", "alice.bsky.social"),
    )

    private val settings = Settings(defaultTargets = listOf(3, 1), defaultFixTooManyFiles = TooManyFilesDefault.MOVE_TO_REPLY)

    private val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
        "defaults" to { DefaultsSettingsContent(settings, accounts, {}, {}) },
        "sending" to { SendingSettingsContent(settings, {}, {}, {}) },
        "about" to { AboutScreen(onBack = {}) },
    )

    /** The list of a long screen and the last row in it, for a second image of its end. */
    private val ends = mapOf(
        "defaults" to (DefaultsTags.LIST to SettingsSectionTags.option(DefaultsTags.BLUESKY_LINKS, BlueskyLinkMode.TEXT_AND_SHORT_URL)),
        "sending" to (SendingTags.LIST to SendingTags.KEEP_METADATA),
    )

    @Test
    fun captureDefaultsSendingAndAboutInLightAndDarkInEnglishAndRussian() {
        var variant by mutableStateOf(Variant("defaults", Locale.ENGLISH, dark = false))
        compose.setContent {
            val current = variant
            // A new key per image, so the scroll position of the previous one does not carry over.
            key(current) { Localized(current.locale, current.dark) { screens.first { it.first == current.screen }.second() } }
        }
        for ((name, _) in screens) {
            for (locale in listOf(Locale.ENGLISH, Locale.forLanguageTag("ru"))) {
                for (dark in listOf(false, true)) {
                    variant = Variant(name, locale, dark)
                    compose.waitForIdle()
                    val suffix = "${locale.language}-${if (dark) "dark" else "light"}"
                    capture("settings-$name-$suffix.png")
                    val (list, last) = ends[name] ?: continue
                    compose.onNodeWithTag(list).performScrollToNode(hasTestTag(last))
                    compose.waitForIdle()
                    capture("settings-$name-end-$suffix.png")
                }
            }
        }
    }

    private fun capture(file: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(outDir, file).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private data class Variant(val screen: String, val locale: Locale, val dark: Boolean)
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
