package org.evsyukov.post.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * The languages the pickers offer besides the device's and the current one: the post's language (R13) and
 * the language it is translated into. All of them are translated by ML Kit.
 */
val COMMON_LANGUAGES = listOf("en", "ru", "uk", "de", "fr", "es", "it", "pt", "pl", "nl", "tr", "ja", "zh", "ko")

/** The name of the language [code] in the interface language, capitalized, or the code itself. */
@Composable
fun languageDisplayName(code: String): String {
    val locale = LocalConfiguration.current.locales[0]
    return Locale.forLanguageTag(code).getDisplayName(locale).ifBlank { code }.replaceFirstChar { it.titlecase(locale) }
}

/** The language codes a picker offers: [current], the device's language and [COMMON_LANGUAGES]. */
@Composable
fun offeredLanguages(current: String?): List<String> {
    val device = LocalConfiguration.current.locales[0].language
    return (listOfNotNull(current?.takeIf { it.isNotBlank() }, device) + COMMON_LANGUAGES).distinct()
}
