package org.evsyukov.post.ui.composer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.R
import org.evsyukov.post.translate.EngineResult
import org.evsyukov.post.translate.TranslationEngine
import org.evsyukov.post.translate.TranslationFailure
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The translate action of the composer (screens.md, Composer): one edit, the language follows, Undo returns. */
@RunWith(AndroidJUnit4::class)
class ComposerTranslateTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The engine behind the composer; it records what it was given and answers with [answer]. */
    private class FakeEngine : TranslationEngine {
        var answer: (String) -> EngineResult = { EngineResult.Translated(it.uppercase()) }
        var seen: String? = null

        override suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): EngineResult {
            seen = text
            return answer(text)
        }
    }

    private val engine = FakeEngine()
    private lateinit var h: ComposerHarness

    @Before
    fun setUp() {
        h = ComposerHarness(compose, engine)
    }

    @After
    fun tearDown() = h.close()

    /** A composer of an account with the settings translating into [code], as the Defaults screen sets it. */
    private fun composerTranslatingInto(code: String) {
        val alice = h.mastodon("alice")
        h.defaultTargets(alice)
        runBlocking { h.container.settings.setTranslateTo(code) }
        h.show()
    }

    @Test
    fun withoutATargetLanguageThereIsNoTranslateAction() {
        val alice = h.mastodon("alice")
        h.defaultTargets(alice)
        h.show()

        assertThat(compose.onAllNodes(hasTestTag(ComposerTags.TRANSLATE)).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun thePressTranslatesTheTextAndTheLanguageFollows() {
        composerTranslatingInto("de")
        h.type("Hallo **Welt**")

        h.click(ComposerTags.TRANSLATE)

        h.waitUntil { h.model.text.text.toString() == "HALLO **WELT**" }
        assertThat(engine.seen).doesNotContain("**")
        assertThat(h.model.language).isEqualTo("de")
    }

    @Test
    fun undoBringsBackTheTextAndTheLanguage() {
        composerTranslatingInto("de")
        h.type("Hallo **Welt**")
        val before = h.model.language

        h.click(ComposerTags.TRANSLATE)
        h.waitUntil { h.model.text.text.toString() == "HALLO **WELT**" }
        compose.onNodeWithText(h.string(R.string.posts_undo)).performClick()
        compose.waitForIdle()

        assertThat(h.model.text.text.toString()).isEqualTo("Hallo **Welt**")
        assertThat(h.model.language).isEqualTo(before)
    }

    @Test
    fun aFailedTranslationLeavesTheDraftAsItWas() {
        composerTranslatingInto("de")
        engine.answer = { EngineResult.Failed(TranslationFailure.MODEL_UNAVAILABLE) }
        h.type("Hallo")
        val before = h.model.language

        h.click(ComposerTags.TRANSLATE)
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(h.string(R.string.composer_translate_model))).fetchSemanticsNodes().isNotEmpty()
        }

        assertThat(h.model.text.text.toString()).isEqualTo("Hallo")
        assertThat(h.model.language).isEqualTo(before)
    }
}
