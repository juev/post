package org.evsyukov.post.ui.composer

import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.PostApplication
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.media.ConflictKind
import org.evsyukov.post.core.media.FixKind
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
 * Screenshots of attachments for a person to look at: the strip with its badges, the attachment sheet with
 * alt text counters and a focus point, and the media conflicts in the details sheet before and after a fix,
 * in light and dark, English and Russian. Files come from the fixtures and go through the importer as a
 * picked file would; accounts, drafts and copied files made here are removed at the end.
 */
@RunWith(AndroidJUnit4::class)
class AttachmentScreenshots {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val container = ApplicationProvider.getApplicationContext<PostApplication>().container
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val created = mutableListOf<Long>()
    private lateinit var model: ComposerModel

    private val outDir: File by lazy {
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")
        dir.also { it.mkdirs() }
    }

    private val variants = listOf(
        Locale.ENGLISH to false, Locale.ENGLISH to true, Locale.forLanguageTag("ru") to false, Locale.forLanguageTag("ru") to true,
    )

    @Before
    fun seed() = runBlocking<Unit> {
        val mastodon = container.accounts.add(
            NewAccount(Network.MASTODON, "https://mastodon.social", "denis@mastodon.social", "1", "Denis", null, label = "denis"),
            MastodonSecret("screenshot"),
        )
        container.accounts.updateLimits(mastodon, AccountLimits.Mastodon(MastodonCapabilities(descriptionLimit = 1500)), Instant.now())
        container.accounts.updateDefaults(mastodon, AccountOptions(languages = listOf("en")))
        val sky = container.accounts.add(
            NewAccount(Network.BLUESKY, "https://bsky.social", "denis.bsky.social", "did:plc:screenshot", "Denis", null),
            BlueskySecret("a", "r"),
        )
        created += listOf(mastodon, sky)
        container.settings.setDefaultTargets(listOf(mastodon, sky))
    }

    @After
    fun cleanUp() = runBlocking<Unit> {
        if (::model.isInitialized) model.discardChanges()
        for (id in created) container.accounts.remove(id)
        container.settings.setDefaultTargets(emptyList())
        for (post in container.posts.observeDrafts().first()) container.posts.delete(post.id)
        instrumentation.targetContext.cacheDir.listFiles { file -> file.name.startsWith("shot-") }?.forEach { it.delete() }
    }

    private fun fixture(name: String, copy: Int): Uri {
        val file = File(instrumentation.targetContext.cacheDir, "shot-$copy-$name")
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(file)
    }

    @Test
    fun captureTheStripTheSheetAndTheConflictsInLightAndDarkInEnglishAndRussian() {
        var variant by mutableStateOf(variants.first())
        compose.setContent {
            val (locale, dark) = variant
            key(variant) {
                Localized(locale, dark) {
                    CompositionLocalProvider(
                        LocalAppContainer provides container,
                        LocalActivityResultRegistryOwner provides compose.activity,
                        LocalOnBackPressedDispatcherOwner provides compose.activity,
                    ) {
                        model = viewModel(viewModelStoreOwner = compose.activity, key = "composer-new") { ComposerModel(container, null) }
                        ComposerScreen(onOpenPosts = {}, onOpenSchedule = {}, onOpenSettings = {})
                    }
                }
            }
        }
        waitFor(ComposerTags.EDITOR)
        compose.onNodeWithTag(ComposerTags.EDITOR).performTextInput("Six photos from the weekend and a voice note.")
        compose.waitForIdle()

        val uris = listOf("photo_exif_rot90.jpg", "alpha.png", "photo_exif_rot90.jpg", "animated.gif", "still.gif", "photo_exif_rot90.jpg", "tone.mp3")
            .mapIndexed { index, name -> fixture(name, index) }
        runBlocking { model.addAttachments(uris) }
        val ids = model.attachments.map { it.id }
        model.setAlt(ids[0], "A red and blue gradient")
        model.setAlt(ids[1], "A translucent red square")
        compose.waitForIdle()
        hideKeyboard()
        compose.waitUntil(10_000) { model.renders?.first?.attachments?.size == ids.size }
        each("attachments-strip") { variant = it }

        model.setFocus(ids[0], -0.4f, 0.3f)
        each("attachments-sheet") {
            variant = it
            waitFor(AttachmentTags.item(ids[0]))
            compose.onNodeWithTag(AttachmentTags.item(ids[0]), useUnmergedTree = true).performClick()
            waitFor(AttachmentTags.SHEET)
        }
        each("attachments-conflicts") {
            variant = it
            waitFor(ComposerTags.STATUS)
            compose.onNodeWithTag(ComposerTags.STATUS).performClick()
            waitFor(ComposerTags.DETAILS)
        }
        model.chooseFix(Network.MASTODON, ConflictKind.MIXED_TYPES, FixKind.MOVE_VIDEO_TO_REPLY)
        model.chooseFix(Network.MASTODON, ConflictKind.TOO_MANY_FILES, FixKind.MOVE_TO_REPLY)
        model.chooseFix(Network.BLUESKY, ConflictKind.UNSUPPORTED_TYPE, FixKind.SEND_WITHOUT_FILE)
        each("attachments-fixed") {
            variant = it
            waitFor(ComposerTags.STATUS)
            compose.onNodeWithTag(ComposerTags.STATUS).performClick()
            waitFor(ComposerTags.DETAILS)
            runCatching { compose.onNodeWithTag(ComposerTags.DETAILS_PREVIEW).performScrollTo() }
        }
        each("attachments-preview") {
            variant = it
            waitFor(ComposerTags.OVERFLOW)
            compose.onNodeWithTag(ComposerTags.OVERFLOW).performScrollTo().performClick()
            waitFor(ComposerTags.MENU_PREVIEW)
            compose.onNodeWithTag(ComposerTags.MENU_PREVIEW).performClick()
            waitFor(ComposerTags.PREVIEW)
        }
    }

    /** Sets each variant with [show] and captures the screen as `<name>-<lang>-<theme>.png`. */
    private fun each(name: String, show: (Pair<Locale, Boolean>) -> Unit) {
        for (v in variants) {
            show(v)
            compose.waitForIdle()
            hideKeyboard()
            // Sheets, thumbnails and the theme settle.
            Thread.sleep(1_000)
            compose.waitForIdle()
            capture("$name-${v.first.language}-${if (v.second) "dark" else "light"}.png")
        }
    }

    private fun waitFor(tag: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    private fun hideKeyboard() {
        compose.runOnUiThread {
            compose.activity.window.let { WindowCompat.getInsetsController(it, it.decorView).hide(WindowInsetsCompat.Type.ime()) }
        }
    }

    private fun capture(file: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(outDir, file).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
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
