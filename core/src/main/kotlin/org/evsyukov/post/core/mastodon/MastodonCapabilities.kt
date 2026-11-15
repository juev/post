package org.evsyukov.post.core.mastodon

import kotlinx.serialization.Serializable

/**
 * What one Mastodon-API server accepts: limits (networks.md N1, N2) and post formats
 * (formatting.md F1). Stored with the account and refreshed once a day.
 *
 * @property postFormatsDeclared true when the server listed its formats in one of the F1
 *   sources; false when [postFormats] is the plain-text fallback.
 */
@Serializable
data class MastodonCapabilities(
    val maxCharacters: Int = Defaults.MAX_CHARACTERS,
    val maxMediaAttachments: Int = Defaults.MAX_MEDIA_ATTACHMENTS,
    val charactersReservedPerUrl: Int = Defaults.CHARACTERS_RESERVED_PER_URL,
    val supportedMediaMimeTypes: Set<String> = Defaults.MEDIA_MIME_TYPES,
    val imageSizeLimit: Long = Defaults.IMAGE_SIZE_LIMIT,
    val imageMatrixLimit: Long = Defaults.IMAGE_MATRIX_LIMIT,
    val videoSizeLimit: Long = Defaults.VIDEO_SIZE_LIMIT,
    val videoFrameRateLimit: Int = Defaults.VIDEO_FRAME_RATE_LIMIT,
    val videoMatrixLimit: Long = Defaults.VIDEO_MATRIX_LIMIT,
    val descriptionLimit: Int = Defaults.DESCRIPTION_LIMIT,
    val pollMaxOptions: Int = Defaults.POLL_MAX_OPTIONS,
    val pollMaxCharactersPerOption: Int = Defaults.POLL_MAX_CHARACTERS_PER_OPTION,
    val pollMinExpirationSeconds: Long = Defaults.POLL_MIN_EXPIRATION,
    val pollMaxExpirationSeconds: Long = Defaults.POLL_MAX_EXPIRATION,
    val postFormats: Set<String> = setOf(TEXT_PLAIN),
    val postFormatsDeclared: Boolean = false,
    val mastodonApiVersion: Int? = null,
    val supportsQuotePolicy: Boolean = false,
    val softwareName: String? = null,
    val version: String? = null,
) {
    /** The richest format the server accepts: Markdown (F8), else HTML (F9), else plain text (F10). */
    fun preferredFormat(): String = when {
        TEXT_MARKDOWN in postFormats -> TEXT_MARKDOWN
        TEXT_HTML in postFormats -> TEXT_HTML
        else -> TEXT_PLAIN
    }

    /**
     * The `content_type` to send with a status: [preferredFormat] when the server listed its
     * formats, null otherwise. Post sends only a type the server lists (formatting.md, notes
     * to the table), so vanilla Mastodon and Hometown get no `content_type` at all.
     */
    fun statusContentType(): String? = if (postFormatsDeclared) preferredFormat() else null

    /** Vanilla Mastodon values from the networks.md table, for servers that do not report a limit. */
    object Defaults {
        const val MAX_CHARACTERS = 500
        const val MAX_MEDIA_ATTACHMENTS = 4
        const val CHARACTERS_RESERVED_PER_URL = 23
        const val IMAGE_SIZE_LIMIT = 16L * 1024 * 1024
        const val IMAGE_MATRIX_LIMIT = 7680L * 4320
        const val VIDEO_SIZE_LIMIT = 99L * 1024 * 1024
        const val VIDEO_FRAME_RATE_LIMIT = 120
        const val VIDEO_MATRIX_LIMIT = 3840L * 2160
        const val DESCRIPTION_LIMIT = 10_000
        const val POLL_MAX_OPTIONS = 4
        const val POLL_MAX_CHARACTERS_PER_OPTION = 50
        const val POLL_MIN_EXPIRATION = 300L

        /** One month as Mastodon serializes `1.month`. */
        const val POLL_MAX_EXPIRATION = 2_629_746L

        /** `MediaAttachment.supported_mime_types` of mastodon/mastodon main: images, video, audio. */
        val MEDIA_MIME_TYPES: Set<String> = linkedSetOf(
            "image/jpeg", "image/png", "image/gif", "image/webp",
            "video/webm", "video/mp4", "video/quicktime", "video/ogg",
            "audio/wave", "audio/wav", "audio/x-wav", "audio/x-pn-wave", "audio/vnd.wave", "audio/ogg",
            "audio/vorbis", "audio/mpeg", "audio/mp3", "audio/webm", "audio/flac", "audio/aac", "audio/m4a",
            "audio/x-m4a", "audio/mp4", "audio/3gpp", "video/x-ms-asf",
        )
    }

    companion object {
        const val TEXT_PLAIN = "text/plain"
        const val TEXT_MARKDOWN = "text/markdown"
        const val TEXT_HTML = "text/html"

        /** Quote policy arrived with Mastodon 4.5, API version 7 (networks.md N2). */
        private const val QUOTE_POLICY_API_VERSION = 7

        /**
         * Builds the capabilities from whatever the server answered; any document may be null.
         *
         * Each limit is taken from the first place that has it: `configuration` of
         * `/api/v2/instance`, `configuration` of `/api/v1/instance` (Mastodon 3.4.2+, forks),
         * the top-level fields of `/api/v1/instance` that Pleroma, Akkoma, glitch-soc and Hometown
         * add (`max_toot_chars`, `poll_limits`, `upload_limit`, `description_limit`), and finally
         * the vanilla default. Post formats follow formatting.md F1.
         */
        fun from(v2: InstanceV2?, v1: InstanceV1?, nodeInfo: NodeInfo?): MastodonCapabilities {
            val c2 = v2?.configuration
            val c1 = v1?.configuration
            val declaredFormats = c2?.statuses?.supportedMimeTypes?.nonEmptySet()
                ?: v1?.pleroma?.metadata?.postFormats?.nonEmptySet()
                ?: nodeInfo?.metadata?.postFormats?.nonEmptySet()
            val apiVersion = v2?.apiVersions?.mastodon ?: v1?.apiVersions?.mastodon
            return MastodonCapabilities(
                maxCharacters = c2?.statuses?.maxCharacters ?: c1?.statuses?.maxCharacters
                    ?: v1?.maxTootChars ?: Defaults.MAX_CHARACTERS,
                maxMediaAttachments = c2?.statuses?.maxMediaAttachments ?: c1?.statuses?.maxMediaAttachments
                    ?: v1?.maxMediaAttachments ?: Defaults.MAX_MEDIA_ATTACHMENTS,
                charactersReservedPerUrl = c2?.statuses?.charactersReservedPerUrl
                    ?: c1?.statuses?.charactersReservedPerUrl ?: Defaults.CHARACTERS_RESERVED_PER_URL,
                supportedMediaMimeTypes = c2?.mediaAttachments?.supportedMimeTypes?.nonEmptySet()
                    ?: c1?.mediaAttachments?.supportedMimeTypes?.nonEmptySet() ?: Defaults.MEDIA_MIME_TYPES,
                imageSizeLimit = c2?.mediaAttachments?.imageSizeLimit ?: c1?.mediaAttachments?.imageSizeLimit
                    ?: v1?.uploadLimit ?: Defaults.IMAGE_SIZE_LIMIT,
                imageMatrixLimit = c2?.mediaAttachments?.imageMatrixLimit ?: c1?.mediaAttachments?.imageMatrixLimit
                    ?: Defaults.IMAGE_MATRIX_LIMIT,
                videoSizeLimit = c2?.mediaAttachments?.videoSizeLimit ?: c1?.mediaAttachments?.videoSizeLimit
                    ?: v1?.uploadLimit ?: Defaults.VIDEO_SIZE_LIMIT,
                videoFrameRateLimit = c2?.mediaAttachments?.videoFrameRateLimit
                    ?: c1?.mediaAttachments?.videoFrameRateLimit ?: Defaults.VIDEO_FRAME_RATE_LIMIT,
                videoMatrixLimit = c2?.mediaAttachments?.videoMatrixLimit ?: c1?.mediaAttachments?.videoMatrixLimit
                    ?: Defaults.VIDEO_MATRIX_LIMIT,
                descriptionLimit = c2?.mediaAttachments?.descriptionLimit ?: c1?.mediaAttachments?.descriptionLimit
                    ?: v1?.descriptionLimit ?: Defaults.DESCRIPTION_LIMIT,
                pollMaxOptions = c2?.polls?.maxOptions ?: c1?.polls?.maxOptions
                    ?: v1?.pollLimits?.maxOptions ?: Defaults.POLL_MAX_OPTIONS,
                pollMaxCharactersPerOption = c2?.polls?.maxCharactersPerOption ?: c1?.polls?.maxCharactersPerOption
                    ?: v1?.pollLimits?.maxOptionChars ?: Defaults.POLL_MAX_CHARACTERS_PER_OPTION,
                pollMinExpirationSeconds = c2?.polls?.minExpiration ?: c1?.polls?.minExpiration
                    ?: v1?.pollLimits?.minExpiration ?: Defaults.POLL_MIN_EXPIRATION,
                pollMaxExpirationSeconds = c2?.polls?.maxExpiration ?: c1?.polls?.maxExpiration
                    ?: v1?.pollLimits?.maxExpiration ?: Defaults.POLL_MAX_EXPIRATION,
                postFormats = declaredFormats ?: setOf(TEXT_PLAIN),
                postFormatsDeclared = declaredFormats != null,
                mastodonApiVersion = apiVersion,
                supportsQuotePolicy = apiVersion != null && apiVersion >= QUOTE_POLICY_API_VERSION,
                softwareName = nodeInfo?.software?.name,
                version = nodeInfo?.software?.version ?: v2?.version ?: v1?.version,
            )
        }

        private fun List<String>.nonEmptySet(): Set<String>? =
            map { it.trim() }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet()).takeIf { it.isNotEmpty() }
    }
}
