package org.evsyukov.post.ui.settings.accounts

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.evsyukov.post.accounts.SignInProblem
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.accounts.AccountLimits
import org.evsyukov.post.data.accounts.AccountOptions
import org.evsyukov.post.data.accounts.ReplyRule
import org.evsyukov.post.data.db.AccountState
import org.evsyukov.post.oauth.MastodonFlowState
import org.evsyukov.post.ui.settings.SettingsScreen
import org.evsyukov.post.ui.theme.PostTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.Locale

/**
 * Screenshots of Settings and the accounts screens in light and dark, English and Russian, for a person
 * to look at (product.md R33, R35). Without the language and theme of the device being changed: each
 * image is drawn with its own configuration. The images go to the additional test output that the
 * connected test task copies to `app/build/outputs/connected_android_test_additional_output`.
 */
@RunWith(AndroidJUnit4::class)
class AccountsScreenshots {
    @get:Rule
    val compose = createComposeRule()

    private val outDir: File by lazy {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")
        dir.also { it.mkdirs() }
    }

    private fun account(id: Long, network: Network, name: String, handle: String, label: String = "", state: AccountState = AccountState.OK, limits: AccountLimits? = null, defaults: AccountOptions = AccountOptions()) =
        Account(
            id = id, network = network, server = "https://s.example", handle = handle, remoteId = "r$id", displayName = name, avatarUrl = null,
            label = label, position = 0, state = state, limits = limits, limitsFetchedAt = limits?.let { Instant.parse("2026-11-16T08:00:00Z") },
            defaults = defaults, createdAt = Instant.EPOCH,
        )

    private val mastodonCaps = MastodonCapabilities(maxCharacters = 5000, maxMediaAttachments = 4, postFormats = setOf("text/plain", "text/markdown"))
    private val accounts = listOf(
        account(1, Network.MASTODON, "Denis", "denis@mastodon.social", label = "personal", limits = AccountLimits.Mastodon(mastodonCaps)),
        account(2, Network.MASTODON, "Work", "work@fosstodon.org", state = AccountState.NEEDS_SIGN_IN),
        account(3, Network.BLUESKY, "Alice", "alice.bsky.social", defaults = AccountOptions(replyRules = setOf(ReplyRule.FOLLOWING))),
    )

    private val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
        "settings" to { SettingsScreen(onBack = {}) },
        "accounts" to { AccountsContent(accounts, {}, {}, { _, _ -> }, {}) },
        "add-mastodon" to {
            AddMastodonContent("mastodon.social", {}, true, MastodonFlowState.Failed(SignInProblem.NETWORK), false, {}, {}, {})
        },
        "add-bluesky" to {
            AddBlueskyContent("alice.bsky.social", {}, "my-password", {}, "", {}, BlueskyFormState.Idle, false, {}, {})
        },
        "details-mastodon" to { AccountDetailsContent(accounts[0], {}, {}, {}, {}, {}) },
        "details-bluesky" to { AccountDetailsContent(accounts[2], {}, {}, {}, {}, {}) },
    )

    @Test
    fun captureEveryScreenInLightAndDarkInEnglishAndRussian() {
        var variant by mutableStateOf(Variant("settings", Locale.ENGLISH, dark = false))
        compose.setContent {
            val current = variant
            Localized(current.locale, current.dark) { screens.first { it.first == current.screen }.second() }
        }
        for ((name, _) in screens) {
            for (locale in listOf(Locale.ENGLISH, Locale.forLanguageTag("ru"))) {
                for (dark in listOf(false, true)) {
                    variant = Variant(name, locale, dark)
                    compose.waitForIdle()
                    val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                    File(outDir, "$name-${locale.language}-${if (dark) "dark" else "light"}.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            }
        }
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
