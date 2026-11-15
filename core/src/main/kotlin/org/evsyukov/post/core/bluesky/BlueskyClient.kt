package org.evsyukov.post.core.bluesky

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * Tokens of a signed-in account. [toString] never shows the tokens.
 *
 * [pdsFromDidDoc] is the PDS named by the `didDoc` of the answer, when it is about [did]: the
 * reference client switches to it after sign-in (`bluesky-social/atproto` `7dd086d4de`,
 * `packages/api/src/atp-agent.ts:508-528`); null keeps the host that was called.
 */
data class Session(
    val did: String,
    val handle: String,
    val accessJwt: String,
    val refreshJwt: String,
    val pdsFromDidDoc: HttpUrl?,
    val active: Boolean?,
    val status: String?,
) {
    override fun toString(): String =
        "Session(did=$did, handle=$handle, accessJwt=<redacted>, refreshJwt=<redacted>, " +
            "pdsFromDidDoc=$pdsFromDidDoc, active=$active, status=$status)"
}

/**
 * A blob reference exactly as the server returned it, so that it is embedded unchanged
 * (data model spec: `$type: blob`, `ref.$link`, `mimeType`, `size`; or the legacy `cid` + `mimeType`).
 */
@Serializable(with = BlobRefSerializer::class)
class BlobRef(val json: JsonObject) {
    /** CID of the blob: `ref.$link`, or `cid` of the legacy format. */
    val cid: String? get() = json.optObject("ref")?.optString("\$link") ?: json.optString("cid")
    val mimeType: String? get() = json.optString("mimeType")
    val size: Long? get() = json.optLong("size")

    override fun equals(other: Any?): Boolean = other is BlobRef && other.json == json
    override fun hashCode(): Int = json.hashCode()
    override fun toString(): String = "BlobRef(cid=$cid, mimeType=$mimeType, size=$size)"
}

/** Writes a [BlobRef] as its original JSON object. */
internal object BlobRefSerializer : KSerializer<BlobRef> {
    override val descriptor: SerialDescriptor = SerialDescriptor("BlobRef", JsonObject.serializer().descriptor)
    override fun serialize(encoder: Encoder, value: BlobRef) = encoder.encodeSerializableValue(JsonObject.serializer(), value.json)
    override fun deserialize(decoder: Decoder): BlobRef = BlobRef(decoder.decodeSerializableValue(JsonObject.serializer()))
}

/** One operation of `com.atproto.repo.applyWrites`. */
sealed interface WriteOp {
    val collection: String
    val rkey: String

    /** Creates [value] (a record JSON with its `$type`) at [collection]/[rkey]. */
    data class Create(override val collection: String, override val rkey: String, val value: JsonObject) : WriteOp

    data class Delete(override val collection: String, override val rkey: String) : WriteOp
}

/**
 * One entry of `applyWrites` `results`, in the order of the writes. A delete result has no [uri] and
 * no [cid]; a create result that lacks them is passed on as null so that the caller reads the record
 * with [BlueskyClient.getRecord].
 */
data class WriteResult(val uri: String?, val cid: String?)

/** A record found by `getRecord`. [cid] is optional in the lexicon. */
data class RecordRef(val uri: String, val cid: String?)

/**
 * XRPC client of one PDS for app-password accounts: sessions, blobs and record writes.
 *
 * Lexicons: `bluesky-social/atproto` `7dd086d4de`, `lexicons/com/atproto/server/{createSession,
 * refreshSession,getServiceAuth}.json` and `lexicons/com/atproto/repo/{uploadBlob,applyWrites,
 * getRecord}.json`. Calls never follow redirects (see [Xrpc]).
 */
class BlueskyClient(
    val pds: HttpUrl,
    http: OkHttpClient,
    json: Json,
    clock: () -> Long = System::currentTimeMillis,
) {
    private val xrpc = Xrpc(pds, http, json, clock, followRedirects = false)

    /**
     * `com.atproto.server.createSession` with a handle (or other identifier) and an app password.
     * Errors to expect: 401 for a wrong password, [XrpcException.isAuthFactorRequired],
     * [XrpcException.isAccountTakedown], 429 (30 calls per 5 minutes per account and IP,
     * `packages/pds/src/api/com/atproto/server/createSession.ts:30-35`).
     */
    suspend fun createSession(identifier: String, password: String, authFactorToken: String? = null): Session {
        val nsid = "com.atproto.server.createSession"
        val body = buildJsonObject {
            put("identifier", identifier)
            put("password", password)
            if (authFactorToken != null) put("authFactorToken", authFactorToken)
        }
        val request = Request.Builder().url(xrpc.url(nsid)).post(xrpc.jsonBody(body)).build()
        return session(xrpc.call(request, nsid, listOfNotNull(password, authFactorToken)))
    }

    /**
     * `com.atproto.server.refreshSession`, authorized by the refresh token. A rejected refresh token
     * comes back as [XrpcException.isExpiredToken] (revoked tokens too, `refreshSession.ts:53-55`) or
     * [XrpcException.isInvalidToken]: the account then needs sign-in.
     */
    suspend fun refreshSession(refreshJwt: String): Session {
        val nsid = "com.atproto.server.refreshSession"
        val request = Request.Builder().url(xrpc.url(nsid)).header("Authorization", "Bearer $refreshJwt")
            .post(ByteArray(0).toRequestBody(null)).build()
        return session(xrpc.call(request, nsid, listOf(refreshJwt)))
    }

    private fun session(fields: Fields): Session {
        val did = fields.string("did")
        if (!IdentityResolver.isValidDid(did)) throw fields.invalid("'did' is not a DID")
        val doc = DidDocument.parse(fields["didDoc"])?.takeIf { it.id == did }
        return Session(
            did = did,
            handle = fields.string("handle"),
            accessJwt = fields.string("accessJwt"),
            refreshJwt = fields.string("refreshJwt"),
            pdsFromDidDoc = doc?.pdsEndpoint(),
            active = fields.obj.optBoolean("active"),
            status = fields.obj.optString("status"),
        )
    }

    /**
     * `com.atproto.server.getServiceAuth`: a token for another service. [expSeconds] is the absolute
     * expiry in Unix epoch seconds, as the lexicon's `exp`; null leaves the server default (60 s).
     */
    suspend fun getServiceAuth(accessJwt: String, aud: String, lxm: String?, expSeconds: Long?): String {
        val nsid = "com.atproto.server.getServiceAuth"
        val url = xrpc.url(nsid, listOf("aud" to aud, "lxm" to lxm, "exp" to expSeconds?.toString()))
        val request = Request.Builder().url(url).header("Authorization", "Bearer $accessJwt").get().build()
        return xrpc.call(request, nsid, listOf(accessJwt)).string("token")
    }

    /** `com.atproto.repo.uploadBlob`: the file as the body with its MIME type as `Content-Type`. */
    suspend fun uploadBlob(accessJwt: String, file: File, mime: String): BlobRef {
        val nsid = "com.atproto.repo.uploadBlob"
        val request = Request.Builder().url(xrpc.url(nsid)).header("Authorization", "Bearer $accessJwt")
            .post(file.asRequestBody(mime.toMediaType())).build()
        return BlobRef(xrpc.call(request, nsid, listOf(accessJwt)).obj("blob"))
    }

    /**
     * `com.atproto.repo.applyWrites` in one commit. Results come in the order of [writes]; the
     * lexicon does not require `results`, so a server may omit them and the list is then empty.
     * At most 200 writes (`packages/pds/src/api/com/atproto/repo/applyWrites.ts:85-86`).
     *
     * A create at a key that already holds a record fails; on the reference PDS with a bare 500 (see
     * [XrpcException.isRecordAlreadyExists]).
     */
    suspend fun applyWrites(accessJwt: String, repo: String, writes: List<WriteOp>, validate: Boolean = true): List<WriteResult> {
        require(writes.isNotEmpty()) { "no writes" }
        require(writes.size <= MAX_WRITES) { "more than $MAX_WRITES writes" }
        val nsid = "com.atproto.repo.applyWrites"
        val body = buildJsonObject {
            put("repo", repo)
            put("validate", validate)
            put(
                "writes",
                buildJsonArray {
                    for (write in writes) {
                        add(
                            buildJsonObject {
                                when (write) {
                                    is WriteOp.Create -> {
                                        put("\$type", "com.atproto.repo.applyWrites#create")
                                        put("collection", write.collection)
                                        put("rkey", write.rkey)
                                        put("value", write.value)
                                    }
                                    is WriteOp.Delete -> {
                                        put("\$type", "com.atproto.repo.applyWrites#delete")
                                        put("collection", write.collection)
                                        put("rkey", write.rkey)
                                    }
                                }
                            },
                        )
                    }
                },
            )
        }
        val request = Request.Builder().url(xrpc.url(nsid)).header("Authorization", "Bearer $accessJwt")
            .post(xrpc.jsonBody(body)).build()
        val results = xrpc.call(request, nsid, listOf(accessJwt)).obj.optArray("results") ?: return emptyList()
        return results.map { entry ->
            val result = entry as? JsonObject
            WriteResult(result?.optString("uri"), result?.optString("cid"))
        }
    }

    /**
     * `com.atproto.repo.getRecord` without auth; null when the record does not exist
     * ([XrpcException.isRecordNotFound]).
     */
    suspend fun getRecord(repo: String, collection: String, rkey: String): RecordRef? {
        val nsid = "com.atproto.repo.getRecord"
        val url = xrpc.url(nsid, listOf("repo" to repo, "collection" to collection, "rkey" to rkey))
        val request = Request.Builder().url(url).get().build()
        val fields = try {
            xrpc.call(request, nsid)
        } catch (e: XrpcException) {
            if (e.isRecordNotFound) return null
            throw e
        }
        return RecordRef(fields.string("uri"), fields.obj.optString("cid"))
    }

    companion object {
        const val MAX_WRITES = 200
    }
}
