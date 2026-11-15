package org.evsyukov.post.core.bluesky

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Runs the call on OkHttp's dispatcher and cancels it with the coroutine. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, _, _ -> response.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }
        },
    )
}

internal val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
private const val MAX_RESPONSE_BYTES = 1024L * 1024

/**
 * XRPC over one host: `/xrpc/<nsid>` URLs, bearer auth, error parsing and bounded JSON reading.
 *
 * Calls that carry credentials never follow redirects: OkHttp drops `Authorization` on a cross-host
 * redirect but re-sends the body of a 307/308, which for `createSession` holds the password.
 */
internal class Xrpc(
    private val base: HttpUrl,
    http: OkHttpClient,
    private val json: Json,
    private val clock: () -> Long,
    followRedirects: Boolean,
) {
    private val client: OkHttpClient =
        if (followRedirects) http else http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    fun url(nsid: String, params: List<Pair<String, String?>> = emptyList()): HttpUrl {
        val builder = base.newBuilder().addPathSegment("xrpc").addPathSegment(nsid)
        for ((name, value) in params) if (value != null) builder.addQueryParameter(name, value)
        return builder.build()
    }

    fun jsonBody(value: JsonObject): RequestBody = json.encodeToString(JsonObject.serializer(), value).toRequestBody(JSON_MEDIA_TYPE)

    /**
     * Executes [request] and reads a JSON object from a 2xx answer. [secrets] are the credentials the
     * request carries; they are scrubbed from any error message.
     */
    suspend fun call(request: Request, nsid: String, secrets: List<String> = emptyList()): Fields =
        withContext(Dispatchers.IO) {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw XrpcException.from(response, clock(), secrets)
                Fields(readObject(response, nsid), nsid, response.code)
            }
        }

    /** Like [call] for a 2xx answer whose body is ignored, or a typed failure. */
    suspend fun <T> raw(request: Request, secrets: List<String> = emptyList(), read: (Response) -> T): T =
        withContext(Dispatchers.IO) {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw XrpcException.from(response, clock(), secrets)
                read(response)
            }
        }

    private fun readObject(response: Response, nsid: String): JsonObject {
        val text = readBounded(response, MAX_RESPONSE_BYTES)
            ?: throw XrpcException.invalidResponse(response.code, nsid, "response is larger than $MAX_RESPONSE_BYTES bytes")
        // The parser's own message quotes the input, which may hold tokens: it is never passed on.
        return parseObjectOrNull(text) ?: throw XrpcException.invalidResponse(response.code, nsid, "response is not a JSON object")
    }
}

/** The body as UTF-8, or null when it is longer than [limit] bytes. */
internal fun readBounded(response: Response, limit: Long): String? {
    val source = response.body.source()
    source.request(limit + 1)
    if (source.buffer.size > limit) return null
    return source.buffer.readUtf8()
}

internal fun parseObjectOrNull(text: String): JsonObject? = try {
    Json.parseToJsonElement(text) as? JsonObject
} catch (_: IllegalArgumentException) {
    null
}

internal fun JsonObject.optString(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.optLong(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

internal fun JsonObject.optBoolean(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

internal fun JsonObject.optObject(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.optArray(key: String): JsonArray? = this[key] as? JsonArray

/** Fields of a 2xx answer; a missing required field is an [XrpcException.INVALID_RESPONSE]. */
internal class Fields(val obj: JsonObject, private val nsid: String, private val status: Int) {
    fun string(key: String): String =
        obj.optString(key)?.takeIf { it.isNotEmpty() } ?: throw invalid("missing string field '$key'")

    fun long(key: String): Long = obj.optLong(key) ?: throw invalid("missing integer field '$key'")

    fun boolean(key: String): Boolean = obj.optBoolean(key) ?: throw invalid("missing boolean field '$key'")

    fun obj(key: String): JsonObject = obj.optObject(key) ?: throw invalid("missing object field '$key'")

    fun invalid(detail: String) = XrpcException.invalidResponse(status, nsid, detail)

    operator fun get(key: String): JsonElement? = obj[key]
}
