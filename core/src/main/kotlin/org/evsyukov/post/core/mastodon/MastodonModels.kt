package org.evsyukov.post.core.mastodon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

// Entities of the Mastodon client API that Post reads. Only the fields Post uses are declared;
// every field a fork may leave out is nullable or has a default (networks.md N6a).
// Shapes: docs.joinmastodon.org, entities Instance, V1_Instance, Account, Status, MediaAttachment,
// Token, Application (mastodon/documentation main, read 2026-11-15).

/** `GET /api/v2/instance` (Mastodon 4.0+). */
@Serializable
data class InstanceV2(
    val domain: String? = null,
    val title: String? = null,
    val version: String? = null,
    val configuration: InstanceConfiguration? = null,
    @SerialName("api_versions") val apiVersions: ApiVersions? = null,
)

/**
 * `GET /api/v1/instance`. Mastodon 3.4.2+ has `configuration`; Pleroma, Akkoma, glitch-soc and
 * Hometown add `max_toot_chars` and other top-level limits; Pleroma and Akkoma list their post
 * formats in `pleroma.metadata.post_formats` (formatting.md F1).
 */
@Serializable
data class InstanceV1(
    val uri: String? = null,
    val title: String? = null,
    val version: String? = null,
    val configuration: InstanceConfiguration? = null,
    @SerialName("max_toot_chars") val maxTootChars: Int? = null,
    @SerialName("max_media_attachments") val maxMediaAttachments: Int? = null,
    @SerialName("poll_limits") val pollLimits: PollLimits? = null,
    @SerialName("upload_limit") val uploadLimit: Long? = null,
    @SerialName("description_limit") val descriptionLimit: Int? = null,
    val pleroma: PleromaInstance? = null,
    @SerialName("api_versions") val apiVersions: ApiVersions? = null,
)

@Serializable
data class InstanceConfiguration(
    val statuses: StatusesConfiguration? = null,
    @SerialName("media_attachments") val mediaAttachments: MediaConfiguration? = null,
    val polls: PollsConfiguration? = null,
)

@Serializable
data class StatusesConfiguration(
    @SerialName("max_characters") val maxCharacters: Int? = null,
    @SerialName("max_media_attachments") val maxMediaAttachments: Int? = null,
    @SerialName("characters_reserved_per_url") val charactersReservedPerUrl: Int? = null,
    /** Post formats (`content_type` values): glitch-soc and GoToSocial. */
    @SerialName("supported_mime_types") val supportedMimeTypes: List<String>? = null,
)

@Serializable
data class MediaConfiguration(
    @SerialName("supported_mime_types") val supportedMimeTypes: List<String>? = null,
    @SerialName("image_size_limit") val imageSizeLimit: Long? = null,
    @SerialName("image_matrix_limit") val imageMatrixLimit: Long? = null,
    @SerialName("video_size_limit") val videoSizeLimit: Long? = null,
    @SerialName("video_frame_rate_limit") val videoFrameRateLimit: Int? = null,
    @SerialName("video_matrix_limit") val videoMatrixLimit: Long? = null,
    @SerialName("description_limit") val descriptionLimit: Int? = null,
)

@Serializable
data class PollsConfiguration(
    @SerialName("max_options") val maxOptions: Int? = null,
    @SerialName("max_characters_per_option") val maxCharactersPerOption: Int? = null,
    @SerialName("min_expiration") val minExpiration: Long? = null,
    @SerialName("max_expiration") val maxExpiration: Long? = null,
)

/** Pleroma and Akkoma `poll_limits` in `/api/v1/instance`. */
@Serializable
data class PollLimits(
    @SerialName("max_options") val maxOptions: Int? = null,
    @SerialName("max_option_chars") val maxOptionChars: Int? = null,
    @SerialName("min_expiration") val minExpiration: Long? = null,
    @SerialName("max_expiration") val maxExpiration: Long? = null,
)

@Serializable
data class ApiVersions(val mastodon: Int? = null)

@Serializable
data class PleromaInstance(val metadata: PleromaMetadata? = null)

@Serializable
data class PleromaMetadata(@SerialName("post_formats") val postFormats: List<String>? = null)

/** A NodeInfo document (schema 1.x or 2.x, nodeinfo.diaspora.software). */
@Serializable
data class NodeInfo(
    val version: String? = null,
    val software: NodeInfoSoftware? = null,
    val metadata: NodeInfoMetadata? = null,
)

@Serializable
data class NodeInfoSoftware(val name: String? = null, val version: String? = null)

/** Pleroma and Akkoma put their post formats here (formatting.md F1, step 3). */
@Serializable
data class NodeInfoMetadata(val postFormats: List<String>? = null)

/** `GET /.well-known/oauth-authorization-server` (Mastodon 4.3+, RFC 8414). */
@Serializable
data class AuthServerMetadata(
    val issuer: String? = null,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String? = null,
    @SerialName("token_endpoint") val tokenEndpoint: String? = null,
    @SerialName("revocation_endpoint") val revocationEndpoint: String? = null,
    @SerialName("app_registration_endpoint") val appRegistrationEndpoint: String? = null,
    @SerialName("scopes_supported") val scopesSupported: List<String> = emptyList(),
    @SerialName("response_types_supported") val responseTypesSupported: List<String> = emptyList(),
    @SerialName("code_challenge_methods_supported") val codeChallengeMethodsSupported: List<String> = emptyList(),
    @SerialName("grant_types_supported") val grantTypesSupported: List<String> = emptyList(),
)

/**
 * The app credentials from `POST /api/v1/apps`. The documentation asks to treat both values
 * as passwords, so [toString] shows neither.
 */
@Serializable
data class AppRegistration(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_secret") val clientSecret: String,
) {
    override fun toString(): String = "AppRegistration(clientId=***, clientSecret=***)"
}

/** The access token from `POST /oauth/token`. Mastodon tokens do not expire (networks.md N5). */
@Serializable
data class Token(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    val scope: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
) {
    override fun toString(): String = "Token(accessToken=***, tokenType=$tokenType, scope=$scope, createdAt=$createdAt)"
}

/** `GET /api/v1/accounts/verify_credentials` (networks.md N6). [acct] is `username` for local accounts. */
@Serializable
data class Account(
    val id: String,
    val username: String,
    val acct: String,
    @SerialName("display_name") val displayName: String = "",
    val avatar: String? = null,
    val url: String? = null,
)

@Serializable
data class MediaAttachment(
    val id: String,
    /** `image`, `gifv`, `video`, `audio` or `unknown`. */
    val type: String = "unknown",
    /** Null while the server is still processing the file. */
    val url: String? = null,
    @SerialName("preview_url") val previewUrl: String? = null,
    val description: String? = null,
)

/** The answer to `POST /api/v2/media`: [processed] is true for HTTP 200, false for 202 (networks.md N7). */
data class MediaUpload(
    val id: String,
    val type: String,
    val processed: Boolean,
    val url: String?,
)

/** The answer to `GET /api/v1/media/:id` (networks.md N7). */
sealed class MediaState {
    /** HTTP 206: still processing. */
    data object Processing : MediaState()

    /** HTTP 200: ready to attach. */
    data class Ready(val attachment: MediaAttachment) : MediaState()

    /** HTTP 422: processing failed; [message] is the server's error text. */
    data class Failed(val message: String?) : MediaState()
}

@Serializable
data class Status(
    val id: String,
    val uri: String? = null,
    val url: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("edited_at") val editedAt: String? = null,
    @SerialName("in_reply_to_id") val inReplyToId: String? = null,
    /** As the server says it; forks add values such as `local` or `list`, so it is not an enum. */
    val visibility: String? = null,
    /** Rendered HTML. */
    val content: String = "",
    /** Source text: present in the answer to delete, and for own statuses on some servers. */
    val text: String? = null,
    @SerialName("spoiler_text") val spoilerText: String = "",
    val sensitive: Boolean = false,
    val language: String? = null,
    @SerialName("media_attachments") val mediaAttachments: List<MediaAttachment> = emptyList(),
) {
    /** [createdAt] as an instant, or null when it is missing or not an ISO 8601 date-time. */
    fun createdAtInstant(): Instant? = createdAt?.let {
        try {
            OffsetDateTime.parse(it).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

enum class Visibility(val wire: String) {
    PUBLIC("public"),
    UNLISTED("unlisted"),
    PRIVATE("private"),
    DIRECT("direct"),
}

/** A poll; Mastodon does not accept a poll together with media. */
data class PollParams(
    val options: List<String>,
    val expiresInSeconds: Long,
    val multiple: Boolean = false,
)

/**
 * Parameters of `POST /api/v1/statuses` (networks.md N9). Null values are not sent.
 *
 * @property quoteApprovalPolicy `public`, `followers` or `nobody`; needs Mastodon API version 7.
 * @property contentType `text/plain`, `text/markdown` or `text/html`; only for servers that list
 *   it (formatting.md F1, [MastodonCapabilities.statusContentType]).
 */
data class StatusParams(
    val status: String?,
    val mediaIds: List<String> = emptyList(),
    val spoilerText: String? = null,
    val sensitive: Boolean = false,
    val visibility: Visibility = Visibility.PUBLIC,
    val language: String? = null,
    val poll: PollParams? = null,
    val inReplyToId: String? = null,
    val quoteApprovalPolicy: String? = null,
    val contentType: String? = null,
)

/** Alt text and focal point of an attachment kept in an edited status (networks.md N28). */
data class MediaAttribute(
    val id: String,
    val description: String? = null,
    val focus: Pair<Float, Float>? = null,
)

/**
 * Parameters of `PUT /api/v1/statuses/:id` (networks.md N28, N30). An edit replaces the status:
 * Mastodon clears a field the request leaves out, so the text, content warning, sensitive flag
 * and media ids are always sent. Visibility and the reply target cannot change.
 */
data class StatusEditParams(
    val status: String?,
    val mediaIds: List<String> = emptyList(),
    val mediaAttributes: List<MediaAttribute> = emptyList(),
    val spoilerText: String? = null,
    val sensitive: Boolean = false,
    val language: String? = null,
    val poll: PollParams? = null,
    val quoteApprovalPolicy: String? = null,
    val contentType: String? = null,
)

/** Result of `DELETE /api/v1/statuses/:id`. A status that is already gone counts as deleted (R39). */
sealed class DeleteResult {
    data object Deleted : DeleteResult()

    /** HTTP 404. */
    data object AlreadyGone : DeleteResult()
}
