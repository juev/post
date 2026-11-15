package org.evsyukov.post.core.mastodon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Client of the Mastodon client API for one server: Mastodon and the forks that speak its API
 * (networks.md N1–N12, N28–N31).
 *
 * Calls never follow redirects and never retry on their own: a token must not reach another
 * host (product.md R4), and retries belong to the delivery, which owns the idempotency keys.
 * The client does not check the scheme of [origin]; the app accepts only HTTPS servers.
 *
 * Request bodies of the status and media-update calls are JSON, which the API understands as well
 * as form data ("Query strings, form data, and JSON submitted via POST body are equally understood
 * by the API", docs.joinmastodon.org/client/intro, section Providing parameters). JSON carries the
 * nested `poll` and `media_attributes` without bracket encoding. App registration and the OAuth
 * endpoints use form encoding, the format RFC 6749 and RFC 7009 define and every fork accepts.
 *
 * @param origin the server, such as `https://mastodon.social/`, without path, query or credentials.
 * @param clock the time used for `Retry-After` dates when the answer has no `Date` header.
 */
class MastodonClient(
    private val origin: HttpUrl,
    http: OkHttpClient,
    private val json: Json = DefaultJson,
    private val clock: Clock = Clock.systemUTC(),
) {
    init {
        require(origin.encodedPath == "/" && origin.query == null && origin.fragment == null) {
            "The origin of a Mastodon server has no path, query or fragment"
        }
        require(origin.username.isEmpty() && origin.password.isEmpty()) { "The origin carries no credentials" }
    }

    private val client: OkHttpClient = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    // ---- Server information (networks.md N1, N2; formatting.md F1) ----

    /** `GET /api/v2/instance` (Mastodon 4.0+; older servers answer 404). */
    suspend fun instanceV2(): InstanceV2 = decode(send(get(url("api/v2/instance"))))

    /** `GET /api/v1/instance`, read for `pleroma.metadata.post_formats` and for servers without v2. */
    suspend fun instanceV1(): InstanceV1 = decode(send(get(url("api/v1/instance"))))

    /**
     * The NodeInfo document: `/.well-known/nodeinfo` lists schema links, and the link of the
     * highest supported schema (1.x or 2.x) is fetched. Null when the server has no NodeInfo
     * (see [optionalDocument] for either request, or no supported link). A link to another
     * origin is not followed, because Post talks only to the account's server (product.md R34).
     */
    suspend fun nodeInfo(): NodeInfo? {
        val index = optionalDocument<NodeInfoIndex>(url(".well-known/nodeinfo")) ?: return null
        val best = index.links
            .filter { schemaRank(it.rel) != null }
            .maxByOrNull { schemaRank(it.rel)!! }
            ?: return null
        val target = best.href?.let { origin.resolve(it) } ?: return null
        if (target.scheme != origin.scheme || target.host != origin.host || target.port != origin.port) return null
        return optionalDocument(target)
    }

    /**
     * `GET /.well-known/oauth-authorization-server` (Mastodon 4.3+). Null when the server has no
     * such document (see [optionalDocument]); the caller then asks for `read:accounts` ([scopesFor]).
     */
    suspend fun authorizationServerMetadata(): AuthServerMetadata? =
        optionalDocument(url(".well-known/oauth-authorization-server"))

    // ---- Sign-in (networks.md N4–N6) ----

    /**
     * `POST /api/v1/apps`. Several redirect URIs go as one newline-separated string: servers
     * before 4.3 and Pleroma accept only a string for `redirect_uris`.
     */
    suspend fun registerApp(clientName: String, redirectUris: List<String>, scopes: String, website: String?): AppRegistration {
        require(redirectUris.isNotEmpty()) { "At least one redirect URI" }
        val form = FormBody.Builder()
            .add("client_name", clientName)
            .add("redirect_uris", redirectUris.joinToString("\n"))
            .add("scopes", scopes)
            .apply { if (website != null) add("website", website) }
            .build()
        return decode(send(Request.Builder().url(url("api/v1/apps")).post(form).build()))
    }

    /**
     * The address of `GET /oauth/authorize` for the browser, with PKCE S256 (networks.md N5).
     * Not suspend, unlike the other calls: it only builds a URL and makes no request.
     */
    fun authorizeUrl(
        clientId: String,
        redirectUri: String,
        scopes: String,
        state: String,
        codeChallenge: String,
        forceLogin: Boolean = true,
    ): HttpUrl = url("oauth/authorize").newBuilder()
        .addQueryParameter("response_type", "code")
        .addQueryParameter("client_id", clientId)
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("scope", scopes)
        .addQueryParameter("state", state)
        .addQueryParameter("code_challenge", codeChallenge)
        .addQueryParameter("code_challenge_method", "S256")
        .apply { if (forceLogin) addQueryParameter("force_login", "true") }
        .build()

    /** `POST /oauth/token` with `grant_type=authorization_code`. */
    suspend fun exchangeCode(
        clientId: String,
        clientSecret: String,
        redirectUri: String,
        code: String,
        codeVerifier: String,
        scopes: String,
    ): Token {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("redirect_uri", redirectUri)
            .add("code_verifier", codeVerifier)
            .add("scope", scopes)
            .build()
        val secrets = listOf(clientId, clientSecret, code, codeVerifier)
        return decode(send(Request.Builder().url(url("oauth/token")).post(form).build(), secrets))
    }

    /** `POST /oauth/revoke`, called when an account is removed (networks.md N5). Idempotent on the server. */
    suspend fun revokeToken(clientId: String, clientSecret: String, token: String) {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("token", token)
            .build()
        send(Request.Builder().url(url("oauth/revoke")).post(form).build(), listOf(clientId, clientSecret, token))
    }

    /** `GET /api/v1/accounts/verify_credentials` (networks.md N6). */
    suspend fun verifyCredentials(token: String): Account =
        decode(send(get(url("api/v1/accounts/verify_credentials"), token), listOf(token)))

    // ---- Media (networks.md N7, N8, N30) ----

    /**
     * `POST /api/v2/media` as multipart form data. The file name is sent as it is; servers check
     * that its extension matches the content.
     *
     * @param focus the focal point, both coordinates in -1.0..1.0.
     * @throws IllegalArgumentException before any request when [file] is not a readable file or
     *   [focus] is out of range: retrying cannot fix either (product.md R29). A file that
     *   disappears while it is being sent still ends as [MastodonException.Network].
     */
    suspend fun uploadMedia(
        token: String,
        file: File,
        mime: String,
        description: String?,
        focus: Pair<Float, Float>?,
    ): MediaUpload {
        val readable = withContext(Dispatchers.IO) { file.isFile && file.canRead() }
        require(readable) { "Not a readable file: ${file.name}" }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(mime.toMediaType()))
            .apply {
                if (description != null) addFormDataPart("description", description)
                if (focus != null) addFormDataPart("focus", focusText(focus))
            }
            .build()
        val secrets = listOf(token)
        val reply = send(Request.Builder().url(url("api/v2/media")).auth(token).post(body).build(), secrets)
        val attachment = decode<MediaAttachment>(reply)
        return MediaUpload(attachment.id, attachment.type, processed = reply.code != 202, url = attachment.url)
    }

    /** `GET /api/v1/media/:id`: 206 while processing, 200 when ready, 422 when processing failed. */
    suspend fun getMedia(token: String, id: String): MediaState {
        val secrets = listOf(token)
        val reply = send(get(url("api/v1/media", id), token), secrets, accept = { it in 200..299 || it == 422 })
        return when (reply.code) {
            206 -> MediaState.Processing
            422 -> MediaState.Failed(errorBody(reply.body, secrets).error)
            else -> MediaState.Ready(decode(reply))
        }
    }

    /** `PUT /api/v1/media/:id`: alt text and focal point of media not yet attached to a status. */
    suspend fun updateMedia(token: String, id: String, description: String?, focus: Pair<Float, Float>?) {
        val payload = buildJsonObject {
            if (description != null) put("description", description)
            if (focus != null) put("focus", focusText(focus))
        }
        send(Request.Builder().url(url("api/v1/media", id)).auth(token).put(payload.toBody()).build(), listOf(token))
    }

    /** `DELETE /api/v1/media/:id` (Mastodon 4.4+). 404, from older servers or for gone media, is ignored. */
    suspend fun deleteMedia(token: String, id: String) {
        send(Request.Builder().url(url("api/v1/media", id)).auth(token).delete().build(), listOf(token), ::successOrNotFound)
    }

    // ---- Statuses (networks.md N9–N12, N28, N31) ----

    /** `POST /api/v1/statuses` with `Idempotency-Key` (networks.md N9, N11). */
    suspend fun createStatus(token: String, params: StatusParams, idempotencyKey: String): Status {
        val payload = buildJsonObject {
            params.status?.let { put("status", it) }
            if (params.mediaIds.isNotEmpty()) putJsonArray("media_ids") { params.mediaIds.forEach { add(it) } }
            params.spoilerText?.let { put("spoiler_text", it) }
            put("sensitive", params.sensitive)
            put("visibility", params.visibility.wire)
            params.language?.let { put("language", it) }
            params.poll?.let { put("poll", pollJson(it)) }
            params.inReplyToId?.let { put("in_reply_to_id", it) }
            params.quoteApprovalPolicy?.let { put("quote_approval_policy", it) }
            params.contentType?.let { put("content_type", it) }
        }
        val request = Request.Builder().url(url("api/v1/statuses")).auth(token)
            .header("Idempotency-Key", idempotencyKey)
            .post(payload.toBody())
            .build()
        val secrets = listOf(token)
        return decode(send(request, secrets))
    }

    /**
     * `PUT /api/v1/statuses/:id` (networks.md N28, N30). The text, content warning, sensitive flag
     * and media ids are always sent, because an edit replaces them. A server without edit answers
     * 404 or 405, reported as [MastodonException.Http] (N31).
     */
    suspend fun editStatus(token: String, id: String, params: StatusEditParams): Status {
        val payload = buildJsonObject {
            put("status", params.status ?: "")
            put("spoiler_text", params.spoilerText ?: "")
            put("sensitive", params.sensitive)
            params.language?.let { put("language", it) }
            putJsonArray("media_ids") { params.mediaIds.forEach { add(it) } }
            if (params.mediaAttributes.isNotEmpty()) {
                putJsonArray("media_attributes") {
                    params.mediaAttributes.forEach { attribute ->
                        add(
                            buildJsonObject {
                                put("id", attribute.id)
                                attribute.description?.let { put("description", it) }
                                attribute.focus?.let { put("focus", focusText(it)) }
                            },
                        )
                    }
                }
            }
            params.poll?.let { put("poll", pollJson(it)) }
            params.quoteApprovalPolicy?.let { put("quote_approval_policy", it) }
            params.contentType?.let { put("content_type", it) }
        }
        val secrets = listOf(token)
        return decode(send(Request.Builder().url(url("api/v1/statuses", id)).auth(token).put(payload.toBody()).build(), secrets))
    }

    /** `DELETE /api/v1/statuses/:id`. A 404 means the status is already gone (product.md R39). */
    suspend fun deleteStatus(token: String, id: String): DeleteResult {
        val reply = send(Request.Builder().url(url("api/v1/statuses", id)).auth(token).delete().build(), listOf(token), ::successOrNotFound)
        return if (reply.code == 404) DeleteResult.AlreadyGone else DeleteResult.Deleted
    }

    /**
     * `GET /api/v1/accounts/:id/statuses`, newest first, without boosts: the statuses to search
     * when the answer to a create was lost (networks.md N12).
     */
    suspend fun accountStatuses(token: String, accountId: String, limit: Int = 40): List<Status> {
        val target = url("api/v1/accounts", accountId, "statuses").newBuilder()
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("exclude_reblogs", "true")
            .build()
        val secrets = listOf(token)
        return decode(send(get(target, token), secrets))
    }

    // ---- Plumbing ----

    private class Reply(val code: Int, val body: String, val headers: Headers, val path: String)

    @kotlinx.serialization.Serializable
    private data class NodeInfoIndex(val links: List<NodeInfoLink> = emptyList())

    @kotlinx.serialization.Serializable
    private data class NodeInfoLink(val rel: String = "", val href: String? = null)

    private class ErrorBody(val error: String?, val description: String?)

    /**
     * `/` plus fixed segments, then [ids] as single encoded segments, so an id cannot change the
     * path. OkHttp resolves `.` and `..` even as single segments, and an empty id would address
     * the collection, so those ids are refused before any request.
     */
    private fun url(path: String, vararg ids: String): HttpUrl = origin.newBuilder()
        .encodedPath("/")
        .addPathSegments(path)
        .apply {
            ids.forEach {
                require(it.isNotEmpty() && it != "." && it != "..") { "Not a record id: \"$it\"" }
                addPathSegment(it)
            }
        }
        .build()

    /**
     * Fetches a discovery document the server may lack. Null for 404 or 410, for a redirect
     * (never followed, see the class description), and for a success answer that is not the
     * document: Akkoma, and Pleroma with the same router, answer unknown `/.well-known/` paths
     * with their web app page, `200 text/html` (Akkoma router.ex, the catch-all route of
     * RedirectController `:redirector_with_preload`). Without the document Post falls back to
     * `read:accounts` and to the other F1 sources, which works on every server. Other errors are
     * thrown, so the caller can tell a missing document from a server that is down.
     */
    private suspend inline fun <reified T> optionalDocument(target: HttpUrl): T? {
        val reply = send(get(target), accept = { it in 200..399 || it == 404 || it == 410 })
        if (reply.code !in 200..299) return null
        return try {
            json.decodeFromString<T>(reply.body)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun get(target: HttpUrl, token: String? = null): Request =
        Request.Builder().url(target).apply { if (token != null) auth(token) }.get().build()

    private fun Request.Builder.auth(token: String): Request.Builder = header("Authorization", "Bearer $token")

    private fun JsonObject.toBody(): RequestBody = toString().toRequestBody(JSON_MEDIA_TYPE)

    private fun pollJson(poll: PollParams): JsonObject = buildJsonObject {
        putJsonArray("options") { poll.options.forEach { add(it) } }
        put("expires_in", poll.expiresInSeconds)
        put("multiple", poll.multiple)
    }

    /**
     * Sends [request] and reads the whole body. Statuses outside [accept] become
     * [MastodonException.Http]; I/O failures become [MastodonException.Network].
     * [secrets] are removed from any text that goes into an exception.
     */
    private suspend fun send(
        request: Request,
        secrets: List<String> = emptyList(),
        accept: (Int) -> Boolean = { it in 200..299 },
    ): Reply {
        val reply = try {
            client.newCall(request).await().use { response ->
                // The body is read from the socket after the call returns: on the IO dispatcher.
                withContext(Dispatchers.IO) {
                    Reply(response.code, response.body.string(), response.headers, request.url.encodedPath)
                }
            }
        } catch (e: IOException) {
            throw MastodonException.Network(e, redact("Network error on ${request.url.encodedPath}: ${e.javaClass.simpleName}: ${e.message}", secrets))
        }
        if (!accept(reply.code)) throw httpError(reply, secrets)
        return reply
    }

    private fun httpError(reply: Reply, secrets: List<String>): MastodonException.Http {
        val body = errorBody(reply.body, secrets)
        return MastodonException.Http(
            status = reply.code,
            error = body.error,
            description = body.description,
            retryAfterSeconds = retryAfterSeconds(reply.code, reply.headers),
            authError = reply.headers["WWW-Authenticate"]?.let { AUTH_ERROR.find(it)?.groupValues?.get(1) },
            path = reply.path,
        )
    }

    private fun errorBody(body: String, secrets: List<String>): ErrorBody {
        val obj = try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: SerializationException) {
            return ErrorBody(null, null)
        } catch (_: IllegalArgumentException) {
            return ErrorBody(null, null)
        }
        fun field(name: String) = (obj[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.let { redact(it, secrets).take(MAX_ERROR_LENGTH) }
        return ErrorBody(field("error"), field("error_description"))
    }

    /**
     * Decodes a success body. The parser's message is left out of [MastodonException.Malformed]:
     * it quotes the body, which may hold a token or client secret.
     */
    private inline fun <reified T> decode(reply: Reply): T = try {
        json.decodeFromString<T>(reply.body)
    } catch (_: SerializationException) {
        throw MastodonException.Malformed("Unexpected answer from ${reply.path} (HTTP ${reply.code})")
    } catch (_: IllegalArgumentException) {
        throw MastodonException.Malformed("Unexpected answer from ${reply.path} (HTTP ${reply.code})")
    }

    /**
     * Seconds until the server accepts calls again.
     *
     * `Retry-After` (seconds or an HTTP date, RFC 9110 §10.2.3) counts with any status.
     * `X-RateLimit-Reset` counts only with 429: Mastodon sends the `X-RateLimit-*` headers with
     * every rate-limited API answer (Api::RateLimitHeaders), and for creating or editing a
     * status the reset is the end of the 3-hour window of the statuses family (RateLimiter), so
     * on a 503 or 422 it says nothing about when to retry. Mastodon writes it as ISO 8601; some
     * other servers send a Unix time or a delay in seconds.
     *
     * Dates are compared with the answer's `Date` header, so a wrong device clock does not
     * matter; without it, with [clock]. A value that cannot be read, or is out of the range of
     * [Instant], gives null.
     */
    private fun retryAfterSeconds(status: Int, headers: Headers): Long? {
        val now = headers["Date"]?.let(::parseHttpDate) ?: clock.instant()
        headers["Retry-After"]?.trim()?.let { value ->
            value.toLongOrNull()?.let { return it.coerceAtLeast(0) }
            parseHttpDate(value)?.let { return secondsUntil(now, it) }
        }
        if (status != 429) return null
        headers["X-RateLimit-Reset"]?.trim()?.let { value ->
            val number = value.toLongOrNull()
            // A small number is a delay in seconds, a large one a Unix time.
            if (number != null && number < UNIX_TIME_THRESHOLD) return number.coerceAtLeast(0)
            val reset = if (number != null) epochSecondOrNull(number) else parseIsoDate(value)
            if (reset != null) return secondsUntil(now, reset)
        }
        return null
    }

    /** Whole seconds from [now] to [then], rounded up, 0 when [then] has passed. Cannot overflow. */
    private fun secondsUntil(now: Instant, then: Instant): Long {
        val duration = Duration.between(now, then)
        if (duration.isNegative) return 0
        return if (duration.nano > 0) duration.seconds + 1 else duration.seconds
    }

    private fun epochSecondOrNull(seconds: Long): Instant? = try {
        Instant.ofEpochSecond(seconds)
    } catch (_: DateTimeException) {
        null
    }

    private fun parseHttpDate(value: String): Instant? = try {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    } catch (_: DateTimeException) {
        null
    }

    private fun parseIsoDate(value: String): Instant? = try {
        OffsetDateTime.parse(value).toInstant()
    } catch (_: DateTimeException) {
        null
    }

    /** `major * 1000 + minor` of a supported NodeInfo schema link (1.x or 2.x), else null. */
    private fun schemaRank(rel: String): Int? {
        val match = NODEINFO_SCHEMA.matchEntire(rel.trim()) ?: return null
        val major = match.groupValues[1].toInt()
        return if (major in 1..2) major * 1000 + match.groupValues[2].toInt() else null
    }

    companion object {
        /** Lenient JSON for server answers: unknown fields are ignored, nulls fall back to defaults. */
        val DefaultJson: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val NODEINFO_SCHEMA = Regex("""https?://nodeinfo\.diaspora\.software/ns/schema/(\d{1,3})\.(\d{1,3})""")
        private val AUTH_ERROR = Regex("""\berror="?([A-Za-z_]+)"?""")
        private const val MAX_ERROR_LENGTH = 1000
        private const val UNIX_TIME_THRESHOLD = 1_000_000_000L

        private fun successOrNotFound(code: Int): Boolean = code in 200..299 || code == 404

        /** `x,y` with two decimals and a dot, whatever the default locale. */
        internal fun focusText(focus: Pair<Float, Float>): String {
            val (x, y) = focus
            require(x in -1f..1f && y in -1f..1f) { "Focus coordinates are between -1.0 and 1.0" }
            return String.format(Locale.ROOT, "%.2f,%.2f", x, y)
        }

        /** Replaces every secret, raw, form-encoded or percent-encoded (`%20`), with `***`. */
        internal fun redact(text: String, secrets: List<String>): String = secrets
            .filter { it.isNotEmpty() }
            .flatMap {
                val form = URLEncoder.encode(it, Charsets.UTF_8)
                listOf(it, form, form.replace("+", "%20"))
            }
            .distinct()
            .sortedByDescending { it.length }
            .fold(text) { acc, secret -> acc.replace(secret, "***") }
    }
}
