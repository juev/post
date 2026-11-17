package org.evsyukov.post.translate

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** The translation of the draft text around its Markdown (screens.md, Composer). */
class PostTranslatorTest {
    /** An engine that answers with [answer] and keeps what it was given. */
    private class FakeEngine(private val answer: (String) -> EngineResult) : TranslationEngine {
        var seen: String? = null
        override suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): EngineResult {
            seen = text
            return answer(text)
        }
    }

    @Test
    fun theEngineSeesTheProseOnlyAndTheDraftComesBackWhole() = runBlocking<Unit> {
        val engine = FakeEngine { EngineResult.Translated(it.uppercase()) }
        val translator = PostTranslator(engine)

        val result = translator.translate("**Hallo** @ann@example.social `code`", "de", "en")

        assertThat(engine.seen).doesNotContain("**")
        assertThat(engine.seen).doesNotContain("@ann")
        assertThat(result).isEqualTo(TranslationResult.Translated("**HALLO** @ann@example.social `code`"))
    }

    @Test
    fun aTranslationThatLosesMarkupIsRefused() = runBlocking<Unit> {
        val engine = FakeEngine { EngineResult.Translated("HALLO") }
        val translator = PostTranslator(engine)

        val result = translator.translate("**Hallo**", "de", "en")

        assertThat(result).isEqualTo(TranslationResult.Failed(TranslationFailure.MARKUP_BROKEN))
    }

    @Test
    fun aFailureOfTheEngineIsPassedOn() = runBlocking<Unit> {
        val engine = FakeEngine { EngineResult.Failed(TranslationFailure.MODEL_UNAVAILABLE) }

        val result = PostTranslator(engine).translate("Hallo", "de", "en")

        assertThat(result).isEqualTo(TranslationResult.Failed(TranslationFailure.MODEL_UNAVAILABLE))
    }
}
