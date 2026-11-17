package org.evsyukov.post.translate

import org.evsyukov.post.core.translate.MarkupMask

/** Why a translation did not happen; the composer turns it into a message (screens.md, Composer). */
enum class TranslationFailure {
    /** The model for the pair is not on the device and could not be downloaded: no network, no room, or a refusal. */
    MODEL_UNAVAILABLE,

    /** ML Kit does not translate into or from this language. */
    UNSUPPORTED_LANGUAGE,

    /**
     * The translation came back without the markup it was given. It is dropped rather than applied: a
     * result that lost a marker would damage the draft.
     */
    MARKUP_BROKEN,

    /** The engine failed for any other reason. */
    ENGINE,
}

sealed interface TranslationResult {
    data class Translated(val text: String) : TranslationResult
    data class Failed(val reason: TranslationFailure) : TranslationResult
}

/** What an engine answers; the masking and the checks live in [PostTranslator]. */
sealed interface EngineResult {
    data class Translated(val text: String) : EngineResult
    data class Failed(val reason: TranslationFailure) : EngineResult
}

/**
 * A translation engine. The composer and the settings know only this interface, so another engine, such as
 * a cloud service with the user's key, can replace ML Kit without touching them (R3).
 */
interface TranslationEngine {
    suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): EngineResult
}

/**
 * Translates the draft text, keeping its Markdown and manual break markers intact (screens.md, Composer).
 * The whole text goes to the engine as one call, with markup replaced by placeholders; the result is put
 * back onto the markup, and a result whose placeholders do not survive is refused.
 */
class PostTranslator(private val engine: TranslationEngine) {
    suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): TranslationResult {
        val masked = MarkupMask.mask(text)
        return when (val result = engine.translate(masked.text, sourceLanguage, targetLanguage)) {
            is EngineResult.Translated ->
                MarkupMask.unmask(masked, result.text)?.let(TranslationResult::Translated)
                    ?: TranslationResult.Failed(TranslationFailure.MARKUP_BROKEN)
            is EngineResult.Failed -> TranslationResult.Failed(result.reason)
        }
    }
}
