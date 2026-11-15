package org.evsyukov.post.share

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/** What another app shared (product.md R19): the text for the draft and the files to copy. */
data class SharedContent(val text: String, val uris: List<Uri>) {
    companion object {
        val EMPTY = SharedContent("", emptyList())

        /**
         * The content of an `ACTION_SEND` or `ACTION_SEND_MULTIPLE` [intent]: `EXTRA_TEXT`, with `EXTRA_SUBJECT`
         * in front when the text does not already contain it, and the files of `EXTRA_STREAM` and of the clip
         * data, each once, in order. Any other action shares nothing.
         */
        fun from(intent: Intent): SharedContent {
            if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return EMPTY
            val texts = intent.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.map { it.toString() }
                ?: listOfNotNull(runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT) }.getOrNull()?.toString())
            val text = texts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")
            val subject = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT) }.getOrNull()?.toString()
            return SharedContent(withSubject(subject, text), streams(intent))
        }

        /** "subject\n\ntext"; just the text when the subject is blank or already part of it, as a browser's page title often is. */
        fun withSubject(subject: String?, text: String): String {
            val s = subject?.trim().orEmpty()
            val t = text.trim()
            return when {
                s.isEmpty() || t.contains(s) -> t
                t.isEmpty() -> s
                else -> "$s\n\n$t"
            }
        }

        private fun streams(intent: Intent): List<Uri> {
            val extra = if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            } else {
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            }
            // The system copies EXTRA_STREAM into the clip data, which carries the read grant; a sender may set only the clip.
            val clip = intent.clipData?.let { data -> (0 until data.itemCount).mapNotNull { data.getItemAt(it).uri } }.orEmpty()
            return (extra + clip).distinct()
        }
    }
}
