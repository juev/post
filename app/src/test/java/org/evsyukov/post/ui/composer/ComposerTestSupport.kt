package org.evsyukov.post.ui.composer

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.AppContainer
import org.evsyukov.post.PrepareTrigger
import org.evsyukov.post.PublishTrigger
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.FakeCipher
import org.evsyukov.post.data.accounts.AccountLimits
import org.evsyukov.post.data.accounts.AccountOptions
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.secrets.BlueskySecret
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.media.probe.MediaDecodeCheck
import org.evsyukov.post.translate.LanguageDetector
import org.evsyukov.post.translate.TranslationEngine
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.theme.PostTheme
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** A composer on a fresh app container with publish and prepare triggers that count their calls. */
class ComposerHarness(
    private val compose: AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>,
    /** Translates the draft; null leaves the ML Kit engine, which tests never call. */
    translationEngine: TranslationEngine? = null,
    /** Tells the language of the draft; null leaves the ML Kit detector, which tests never call. */
    languageDetector: LanguageDetector? = null,
) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val triggers = AtomicInteger()
    val prepares = AtomicInteger()

    /** Why the device cannot decode a file; null, as on a working device, unless a test says otherwise. */
    var decodeProblem: (File, String) -> String? = { _, _ -> null }
    val container = AppContainer(
        context,
        publishTrigger = PublishTrigger { triggers.incrementAndGet() },
        prepareTrigger = PrepareTrigger { prepares.incrementAndGet() },
        mediaDecodeCheck = MediaDecodeCheck { file, mime -> decodeProblem(file, mime) },
        translationEngine = translationEngine,
        languageDetector = languageDetector,
    ) { FakeCipher() }
    val exits = AtomicInteger()
    val postsOpened = AtomicInteger()
    val settingsOpened = AtomicInteger()

    fun close() {
        // Ends the activity first, so no screen still reads the database when it closes (#15).
        runCatching { compose.activityRule.scenario.close() }
        if (container.database.isOpen) container.database.close()
    }

    fun mastodon(
        name: String,
        defaults: AccountOptions = AccountOptions(),
        capabilities: MastodonCapabilities = MastodonCapabilities(),
    ): Long = runBlocking {
        val id = container.accounts.add(
            NewAccount(Network.MASTODON, "https://$name.example", "$name@$name.example", "r-$name", name, null),
            MastodonSecret("t-$name"),
        )
        container.accounts.updateLimits(id, AccountLimits.Mastodon(capabilities), Instant.EPOCH)
        container.accounts.updateDefaults(id, defaults)
        id
    }

    fun bluesky(name: String, defaults: AccountOptions = AccountOptions()): Long = runBlocking {
        val id = container.accounts.add(
            NewAccount(Network.BLUESKY, "https://pds.example", "$name.bsky.social", "did:plc:$name", name, null),
            BlueskySecret("a", "r"),
        )
        container.accounts.updateDefaults(id, defaults)
        id
    }

    fun defaultTargets(vararg ids: Long) = runBlocking { container.settings.setDefaultTargets(ids.toList()) }

    /** The model of the composer [show] put on screen: the same instance the screen uses. */
    lateinit var model: ComposerModel

    fun show(draftId: Long? = null) {
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                PostTheme {
                    model = viewModel(key = "composer-${draftId ?: "new"}") { ComposerModel(container, draftId) }
                    ComposerScreen(
                        draftId = draftId,
                        onOpenPosts = { postsOpened.incrementAndGet() },
                        onOpenSchedule = {},
                        onOpenSettings = { settingsOpened.incrementAndGet() },
                        onExit = { exits.incrementAndGet() },
                    )
                }
            }
        }
        waitFor(ComposerTags.EDITOR)
    }

    fun waitFor(tag: String) = compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    fun waitUntil(condition: () -> Boolean) = compose.waitUntil(5_000, condition)

    fun node(tag: String): SemanticsNodeInteraction = compose.onNodeWithTag(tag, useUnmergedTree = false)

    fun editor(): SemanticsNodeInteraction = compose.onNodeWithTag(ComposerTags.EDITOR)

    fun type(text: String) {
        editor().performTextInput(text)
        compose.waitForIdle()
    }

    fun select(start: Int, end: Int) {
        editor().performTextInputSelection(TextRange(start, end))
        compose.waitForIdle()
    }

    /** Clicks the node with [tag], scrolled into view first when it sits in a scrolling row or sheet. */
    fun click(tag: String) {
        runCatching { node(tag).performScrollTo() }
        node(tag).performClick()
        compose.waitForIdle()
    }

    fun menu(tag: String) {
        click(ComposerTags.OVERFLOW)
        click(tag)
    }

    /** The text in the editor, as stored. */
    fun text(): String = compose.onNodeWithTag(ComposerTags.EDITOR).fetchSemanticsNode()
        .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text

    fun string(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String = context.resources.getQuantityString(id, count, *args)
}

/** A node whose text, merged from its children, contains [value]. */
fun hasTextPart(value: String) = SemanticsMatcher("has text part '$value'") { node ->
    val texts = node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }
    texts.any { it.text.contains(value) }
}
