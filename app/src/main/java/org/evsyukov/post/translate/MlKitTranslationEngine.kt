package org.evsyukov.post.translate

import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException

/**
 * Translation by Google's ML Kit on the device. The model of a language pair is downloaded on demand
 * (about 30 MB) and works offline afterwards; the draft text never leaves the phone, but ML Kit sends
 * Google its own usage and performance metrics (docs/privacy.md).
 *
 * The model download runs on any connection: the user asked for the translation, and a failure to fetch
 * the model is reported as such, leaving the draft untouched.
 */
class MlKitTranslationEngine : TranslationEngine {
    override suspend fun translate(text: String, sourceLanguage: String, targetLanguage: String): EngineResult {
        val source = TranslateLanguage.fromLanguageTag(sourceLanguage)
            ?: return EngineResult.Failed(TranslationFailure.UNSUPPORTED_LANGUAGE)
        val target = TranslateLanguage.fromLanguageTag(targetLanguage)
            ?: return EngineResult.Failed(TranslationFailure.UNSUPPORTED_LANGUAGE)
        return try {
            val client = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build(),
            )
            try {
                client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                EngineResult.Translated(client.translate(text).await())
            } finally {
                client.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MlKitException) {
            // A model that is missing, refused or could not be stored; the composer says the model is unavailable.
            EngineResult.Failed(TranslationFailure.MODEL_UNAVAILABLE)
        } catch (e: Exception) {
            EngineResult.Failed(TranslationFailure.ENGINE)
        }
    }
}
