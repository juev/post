package org.evsyukov.post.translate

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import kotlinx.coroutines.CancellationException

/**
 * The language of the draft text, as a code such as "ru". The composer asks when the post's language is
 * already the target of a translation: the language chip names the language a previous translation wrote,
 * while the text typed since may be in another one (screens.md, Composer).
 */
interface LanguageDetector {
    /** The language of [text], or null when the text is too short to tell or the detection failed. */
    suspend fun detect(text: String): String?
}

/** Language detection by ML Kit on the device; its model ships with the app and needs no download. */
class MlKitLanguageDetector : LanguageDetector {
    override suspend fun detect(text: String): String? {
        var client: LanguageIdentifier? = null
        return try {
            client = LanguageIdentification.getClient()
            client.identifyLanguage(text).await().takeUnless { it == LanguageIdentifier.UNDETERMINED_LANGUAGE_TAG }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Detection only makes a translation possible; when it fails, the composer says the post is already
            // in the target language, and the user can change the chip.
            null
        } finally {
            client?.close()
        }
    }
}
