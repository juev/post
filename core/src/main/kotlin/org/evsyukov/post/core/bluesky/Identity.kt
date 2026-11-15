package org.evsyukov.post.core.bluesky

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Identity resolution failed for a reason other than the network: retrying will not help. */
class IdentityException(message: String) : Exception(message)

/** A handle verified in both directions, with the PDS that hosts the account. */
data class Identity(val did: String, val handle: String, val pds: HttpUrl)

/** One `service` entry of a DID document. */
data class DidService(val id: String, val type: String?, val endpoint: String?)

/**
 * The parts of a DID document atproto uses (`atproto-website` `59daa1ff`, `specs/did/en.mdx`,
 * "DID Documents").
 */
data class DidDocument(val id: String, val alsoKnownAs: List<String>, val services: List<DidService>) {
    /**
     * The handle the document claims: the first `at://` entry of `alsoKnownAs` with valid handle
     * syntax; later ones are ignored (did spec, line 97).
     */
    fun claimedHandle(): String? = alsoKnownAs.asSequence()
        .filter { it.startsWith("at://") }
        .map { it.removePrefix("at://").lowercase() }
        .firstOrNull { IdentityResolver.isValidHandle(it) }

    /** True when the document claims [handle] (after [IdentityResolver.normalizeHandle]). */
    fun handleMatches(handle: String): Boolean = claimedHandle() == IdentityResolver.normalizeHandle(handle)

    /**
     * The PDS: the first service whose id is `#atproto_pds` or `<did>#atproto_pds`, if its type is
     * `AtprotoPersonalDataServer` and its endpoint an http(s) URL. Mirrors the reference
     * `getPdsEndpoint` (`bluesky-social/atproto` `7dd086d4de`, `packages/common-web/src/did-doc.ts:66-153`).
     */
    fun pdsEndpoint(): HttpUrl? {
        val service = services.firstOrNull { it.id == "#atproto_pds" || it.id == "$id#atproto_pds" } ?: return null
        if (service.type != "AtprotoPersonalDataServer") return null
        val endpoint = service.endpoint ?: return null
        if (!endpoint.startsWith("https://") && !endpoint.startsWith("http://")) return null
        return endpoint.toHttpUrlOrNull()
    }

    companion object {
        /** Reads a DID document; null when it lacks a string `id`. Malformed entries are skipped. */
        fun parse(element: JsonElement?): DidDocument? {
            val obj = element as? JsonObject ?: return null
            val id = obj.optString("id") ?: return null
            val aka = obj.optArray("alsoKnownAs").orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            val services = obj.optArray("service").orEmpty().mapNotNull { entry ->
                val service = entry as? JsonObject ?: return@mapNotNull null
                val serviceId = service.optString("id") ?: return@mapNotNull null
                DidService(serviceId, service.optString("type"), service.optString("serviceEndpoint"))
            }
            return DidDocument(id, aka, services)
        }
    }
}

/**
 * Handle → DID → DID document → PDS, as networks.md N13 and plan.md describe:
 * `com.atproto.identity.resolveHandle` on the public AppView, then `https://<handle>/.well-known/atproto-did`;
 * DNS TXT is not queried. Documents come from the PLC directory or, for `did:web`, from
 * `https://<host>/.well-known/did.json`.
 *
 * Sources: `bluesky-social/atproto` `7dd086d4de` (`lexicons/com/atproto/identity/resolveHandle.json`),
 * atproto specs at `atproto-website` `59daa1ff` (`specs/handle`, `specs/did`), the did:plc spec
 * ("DID Resolution": `https://plc.directory/:did`) and the W3C did:web method.
 */
class IdentityResolver(
    http: OkHttpClient,
    json: Json,
    appView: HttpUrl = DEFAULT_APP_VIEW,
    private val plc: HttpUrl = DEFAULT_PLC,
) {
    // Public, credential-free reads: the handle spec allows redirects for the well-known method.
    private val appView = Xrpc(appView, http, json, System::currentTimeMillis, followRedirects = true)

    /**
     * The DID of [handle]. The AppView answer does not verify the handle in both directions; use
     * [resolve] for sign-in.
     *
     * When both methods fail, the AppView decides: its "not found" (it also tries DNS and the
     * well-known URL) is final, while its network or server failure is rethrown so the caller retries.
     *
     * @throws IdentityException when the handle is malformed or does not resolve
     * @throws IOException or a transient [XrpcException] when the AppView could not answer and the
     *   well-known URL gave no DID either
     */
    suspend fun resolveHandle(handle: String): String {
        val normalized = normalizeHandle(handle)
        if (!isValidHandle(normalized)) throw IdentityException("Not a valid handle")
        var appViewFailure: Exception? = null
        try {
            viaAppView(normalized)?.let { return it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            appViewFailure = e
        } catch (e: XrpcException) {
            appViewFailure = e
        }
        try {
            viaWellKnown(normalized)?.let { return it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            // The AppView's answer below decides.
        } catch (_: XrpcException) {
            // Same.
        }
        if (appViewFailure != null && (XrpcException.isTransient(appViewFailure) || (appViewFailure as? XrpcException)?.isRateLimited == true)) {
            throw appViewFailure
        }
        throw IdentityException("The handle does not resolve to a DID")
    }

    private suspend fun viaAppView(handle: String): String? {
        val nsid = "com.atproto.identity.resolveHandle"
        val request = Request.Builder().url(appView.url(nsid, listOf("handle" to handle))).get().build()
        return try {
            appView.call(request, nsid).string("did").takeIf { isValidDid(it) }
        } catch (e: XrpcException) {
            if (e.error == "HandleNotFound" || e.error == XrpcException.INVALID_RESPONSE) null else throw e
        }
    }

    private suspend fun viaWellKnown(handle: String): String? {
        val url = HttpUrl.Builder().scheme("https").host(handle).addPathSegments(".well-known/atproto-did").build()
        val request = Request.Builder().url(url).get().build()
        // Handle spec: a 2xx text/plain body holding only the DID; clients strip surrounding whitespace.
        return appView.raw(request) { response -> readBounded(response, MAX_DID_BYTES)?.trim()?.takeIf { isValidDid(it) } }
    }

    /**
     * The DID document of [did] (`did:plc` or `did:web`), checked to be about [did].
     *
     * @throws IdentityException for a malformed DID, an unsupported method, a missing or deactivated
     *   DID, or a document about another DID
     */
    suspend fun didDocument(did: String): DidDocument {
        if (!isValidDid(did)) throw IdentityException("Not a valid DID")
        val url = when {
            did.startsWith("did:plc:") -> plc.newBuilder().addPathSegment(did).build()
            did.startsWith("did:web:") -> didWebUrl(did)
            else -> throw IdentityException("Unsupported DID method")
        }
        val request = Request.Builder().url(url).get().build()
        val document = try {
            appView.raw(request) { response -> readBounded(response, MAX_DOCUMENT_BYTES)?.let { parseObjectOrNull(it) } }
        } catch (e: XrpcException) {
            if (e.status == 404 || e.status == 410) throw IdentityException("The DID is not found or deactivated")
            throw e
        }
        val parsed = DidDocument.parse(document) ?: throw IdentityException("The DID document is malformed")
        if (parsed.id != did) throw IdentityException("The DID document belongs to another DID")
        return parsed
    }

    /** Only hostname-level did:web is supported in atproto; a port is allowed for localhost only. */
    private fun didWebUrl(did: String): HttpUrl {
        val identifier = did.removePrefix("did:web:")
        if (':' in identifier) throw IdentityException("Path-based did:web is not supported")
        val hostAndPort = identifier.replace("%3A", ":").replace("%3a", ":")
        if ('%' in hostAndPort) throw IdentityException("Not a valid did:web host")
        val host = hostAndPort.substringBefore(':')
        val port = hostAndPort.substringAfter(':', "").takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (hostAndPort.contains(':') && (port == null || host != "localhost")) throw IdentityException("Not a valid did:web host")
        return try {
            HttpUrl.Builder().scheme("https").host(host).apply { if (port != null) port(port) }
                .addPathSegments(".well-known/did.json").build()
        } catch (_: IllegalArgumentException) {
            throw IdentityException("Not a valid did:web host")
        }
    }

    /** The PDS named in [doc], see [DidDocument.pdsEndpoint]. */
    fun pdsEndpoint(doc: DidDocument): HttpUrl? = doc.pdsEndpoint()

    /** Whether [doc] claims [handle], see [DidDocument.handleMatches]. */
    fun handleMatches(doc: DidDocument, handle: String): Boolean = doc.handleMatches(handle)

    /**
     * Full sign-in resolution (N13): handle → DID → document, which must claim the handle back and
     * name a PDS.
     */
    suspend fun resolve(handle: String): Identity {
        val normalized = normalizeHandle(handle)
        val did = resolveHandle(normalized)
        val doc = didDocument(did)
        if (!doc.handleMatches(normalized)) throw IdentityException("The DID document does not claim this handle")
        val pds = doc.pdsEndpoint() ?: throw IdentityException("The DID document names no PDS")
        return Identity(did, normalized, pds)
    }

    companion object {
        val DEFAULT_APP_VIEW: HttpUrl = "https://public.api.bsky.app/".toHttpUrl()
        val DEFAULT_PLC: HttpUrl = "https://plc.directory/".toHttpUrl()

        private const val MAX_DID_BYTES = 2048L
        private const val MAX_DOCUMENT_BYTES = 256L * 1024
        private const val MAX_DID_LENGTH = 2048

        private val HANDLE = Regex("^([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$")
        private val DID = Regex("^did:[a-z]+:[a-zA-Z0-9._:%-]*[a-zA-Z0-9._-]$")

        /** Trims, drops a leading `@` and lowercases (handle spec: handles are case-insensitive). */
        fun normalizeHandle(input: String): String = input.trim().removePrefix("@").trim().lowercase(java.util.Locale.ROOT)

        /** Handle syntax of the handle spec (reference regex, at most 253 characters). */
        fun isValidHandle(handle: String): Boolean = handle.length <= 253 && HANDLE.matches(handle)

        /** DID syntax of the DID spec (reference regex, at most 2048 characters). */
        fun isValidDid(did: String): Boolean = did.length <= MAX_DID_LENGTH && DID.matches(did)
    }
}
