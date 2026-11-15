package org.evsyukov.post.core.bluesky

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * N13 and plan.md: handle → DID through the AppView, then the well-known URL; DID document from the
 * PLC directory or did:web; the document must claim the handle and name the PDS.
 *
 * Every host is routed to one MockWebServer; the original URL travels in a header, so the tests see
 * which host and path the resolver asked for.
 */
class IdentityResolverTest {
    private val server = MockWebServer()
    private val routes = mutableMapOf<String, () -> MockResponse>()
    private val seen = CopyOnWriteArrayList<RecordedRequest>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                val original = request.headers[ORIGINAL]!!.toHttpUrl()
                val key = "${original.host}${original.encodedPath}"
                return routes[key]?.invoke() ?: MockResponse.Builder().code(404).body("not found").build()
            }
        }
        server.start()
    }

    private val http = OkHttpClient.Builder().addInterceptor { chain ->
        val original = chain.request()
        val target = server.url(original.url.encodedPath).newBuilder().encodedQuery(original.url.encodedQuery).build()
        chain.proceed(original.newBuilder().url(target).header(ORIGINAL, original.url.toString()).build())
    }.build()

    private val resolver = IdentityResolver(http, testJson)
    private val plcDid = "did:plc:ewvi7nxzyoun6zhxrhs64oiz"

    @After fun close() = server.close()

    private fun requested(): List<HttpUrl> = seen.map { it.headers[ORIGINAL]!!.toHttpUrl() }

    private fun doc(id: String, handle: String, pds: String = "https://pds.example.com", serviceId: String = "#atproto_pds") =
        """{"@context":["https://www.w3.org/ns/did/v1","https://w3id.org/security/multikey/v1"],"id":"$id",
            "alsoKnownAs":["at://$handle"],
            "verificationMethod":[{"id":"$id#atproto","type":"Multikey","controller":"$id","publicKeyMultibase":"zQ3sh"}],
            "service":[{"id":"$serviceId","type":"AtprotoPersonalDataServer","serviceEndpoint":"$pds"}]}"""

    private fun route(hostAndPath: String, response: () -> MockResponse) {
        routes[hostAndPath] = response
    }

    @Test
    fun plcChainResolvesHandleDocumentAndPds() = runBlocking<Unit> {
        route("public.api.bsky.app/xrpc/com.atproto.identity.resolveHandle") { jsonResponse("""{"did":"$plcDid"}""") }
        route("plc.directory/$plcDid") { jsonResponse(doc(plcDid, "alice.bsky.social", "https://morel.us-east.host.bsky.network")) }

        val identity = resolver.resolve("  @Alice.Bsky.Social ")

        assertThat(identity).isEqualTo(Identity(plcDid, "alice.bsky.social", "https://morel.us-east.host.bsky.network/".toHttpUrl()))
        val urls = requested()
        assertThat(urls[0].toString()).isEqualTo("https://public.api.bsky.app/xrpc/com.atproto.identity.resolveHandle?handle=alice.bsky.social")
        assertThat(urls[1].toString()).isEqualTo("https://plc.directory/$plcDid")
        assertThat(seen.none { it.headers["Authorization"] != null }).isTrue()
    }

    @Test
    fun didWebChainUsesTheWellKnownDocument() = runBlocking<Unit> {
        val webDid = "did:web:alice.example.org"
        route("public.api.bsky.app/xrpc/com.atproto.identity.resolveHandle") { jsonResponse("""{"did":"$webDid"}""") }
        route("alice.example.org/.well-known/did.json") {
            jsonResponse(doc(webDid, "alice.example.org", "https://pds.example.org", serviceId = "$webDid#atproto_pds"))
        }

        val identity = resolver.resolve("alice.example.org")

        assertThat(identity.did).isEqualTo(webDid)
        assertThat(identity.pds).isEqualTo("https://pds.example.org/".toHttpUrl())
        assertThat(requested().last().toString()).isEqualTo("https://alice.example.org/.well-known/did.json")
    }

    @Test
    fun handleMismatchIsRefused() = runBlocking<Unit> {
        route("public.api.bsky.app/xrpc/com.atproto.identity.resolveHandle") { jsonResponse("""{"did":"$plcDid"}""") }
        route("plc.directory/$plcDid") { jsonResponse(doc(plcDid, "mallory.example.com")) }

        val e = runCatching { resolver.resolve("alice.bsky.social") }.exceptionOrNull()
        assertThat(e).isInstanceOf(IdentityException::class.java)
        assertThat(e!!.message).contains("does not claim")
    }

    @Test
    fun wellKnownIsTheFallbackWhenTheAppViewIsDown() = runBlocking<Unit> {
        route("public.api.bsky.app/xrpc/com.atproto.identity.resolveHandle") { MockResponse.Builder().code(503).build() }
        route("alice.example.org/.well-known/atproto-did") {
            MockResponse.Builder().addHeader("Content-Type", "text/plain").body("  $plcDid\n").build()
        }
        assertThat(resolver.resolveHandle("alice.example.org")).isEqualTo(plcDid)
        assertThat(requested().last().toString()).isEqualTo("https://alice.example.org/.well-known/atproto-did")
    }

    @Test
    fun wellKnownIsAlsoTriedWhenTheAppViewSaysNotFound() = runBlocking<Unit> {
        route("public.api.bsky.app/xrpc/com.atproto.identity.resolveHandle") {
            jsonResponse("""{"error":"HandleNotFound","message":"Unable to resolve handle"}""", 400)
        }
        route("alice.example.org/.well-known/atproto-did") { MockResponse.Builder().body(plcDid).build() }
        assertThat(resolver.resolveHandle("alice.example.org")).isEqualTo(plcDid)
    }

    @Test
    fun handleThatResolvesNowhereIsAnIdentityException() = runBlocking<Unit> {
        route("public.api.bsky.app/xrpc/com.atproto.identity.resolveHandle") {
            jsonResponse("""{"error":"HandleNotFound","message":"Unable to resolve handle"}""", 400)
        }
        route("nobody.example.org/.well-known/atproto-did") { MockResponse.Builder().code(404).build() }
        assertThat(runCatching { resolver.resolveHandle("nobody.example.org") }.exceptionOrNull())
            .isInstanceOf(IdentityException::class.java)
    }

    @Test
    fun wellKnownBodyThatIsNotADidDoesNotCount() = runBlocking<Unit> {
        route("public.api.bsky.app/xrpc/com.atproto.identity.resolveHandle") {
            jsonResponse("""{"error":"HandleNotFound"}""", 400)
        }
        route("alice.example.org/.well-known/atproto-did") { MockResponse.Builder().body("<html>hello</html>").build() }
        assertThat(runCatching { resolver.resolveHandle("alice.example.org") }.exceptionOrNull())
            .isInstanceOf(IdentityException::class.java)
    }

    @Test
    fun unreachableAppViewAndNoWellKnownIsTransient() = runBlocking<Unit> {
        route("public.api.bsky.app/xrpc/com.atproto.identity.resolveHandle") { MockResponse.Builder().code(502).build() }
        val e = runCatching { resolver.resolveHandle("alice.example.org") }.exceptionOrNull()
        assertThat(e).isInstanceOf(XrpcException::class.java)
        assertThat(XrpcException.isTransient(e!!)).isTrue()
    }

    @Test
    fun malformedHandleMakesNoRequest() = runBlocking<Unit> {
        for (bad in listOf("", "alice", "alice..test", "-alice.test", "alice.123", "al ice.test", "alice.test/x")) {
            assertThat(runCatching { resolver.resolveHandle(bad) }.exceptionOrNull()).isInstanceOf(IdentityException::class.java)
        }
        assertThat(seen).isEmpty()
    }

    @Test
    fun documentAboutAnotherDidIsRefused() = runBlocking<Unit> {
        route("plc.directory/$plcDid") { jsonResponse(doc("did:plc:someoneelse", "alice.test")) }
        assertThat(runCatching { resolver.didDocument(plcDid) }.exceptionOrNull()).isInstanceOf(IdentityException::class.java)
    }

    @Test
    fun missingOrTombstonedPlcDidIsAnIdentityException() = runBlocking<Unit> {
        route("plc.directory/$plcDid") { MockResponse.Builder().code(410).body("DID not available").build() }
        assertThat(runCatching { resolver.didDocument(plcDid) }.exceptionOrNull()).isInstanceOf(IdentityException::class.java)
        routes.clear()
        assertThat(runCatching { resolver.didDocument(plcDid) }.exceptionOrNull()).isInstanceOf(IdentityException::class.java)
    }

    @Test
    fun plcOutageIsTransient() = runBlocking<Unit> {
        route("plc.directory/$plcDid") { MockResponse.Builder().code(503).build() }
        val e = runCatching { resolver.didDocument(plcDid) }.exceptionOrNull()
        assertThat(XrpcException.isTransient(e!!)).isTrue()
    }

    @Test
    fun unsupportedOrPathBasedDidsAreRefusedWithoutRequests() = runBlocking<Unit> {
        for (did in listOf("did:key:zQ3shZc2QzApp2oymGvQbzP8eKheVshBHbU4ZYjeXqwSKEn6N", "did:web:example.com:user:alice", "not-a-did", "did:web:example.com%3A8443")) {
            assertThat(runCatching { resolver.didDocument(did) }.exceptionOrNull()).isInstanceOf(IdentityException::class.java)
        }
        assertThat(seen).isEmpty()
    }

    @Test
    fun localhostDidWebMayCarryAPort() = runBlocking<Unit> {
        val did = "did:web:localhost%3A2583"
        route("localhost/.well-known/did.json") { jsonResponse(doc(did, "dev.test", "http://localhost:2583")) }
        val document = resolver.didDocument(did)
        assertThat(requested().single().toString()).isEqualTo("https://localhost:2583/.well-known/did.json")
        assertThat(document.pdsEndpoint()).isEqualTo("http://localhost:2583/".toHttpUrl())
    }

    @Test
    fun pdsEndpointRules() {
        val did = "did:plc:abc"
        fun d(vararg services: DidService) = DidDocument(did, emptyList(), services.toList())
        val pds = "https://pds.example.com"
        assertThat(resolver.pdsEndpoint(d(DidService("#atproto_pds", "AtprotoPersonalDataServer", pds)))).isEqualTo("$pds/".toHttpUrl())
        assertThat(d(DidService("$did#atproto_pds", "AtprotoPersonalDataServer", pds)).pdsEndpoint()).isNotNull()
        assertThat(d(DidService("did:plc:other#atproto_pds", "AtprotoPersonalDataServer", pds)).pdsEndpoint()).isNull()
        assertThat(d(DidService("#atproto_pds", "SomethingElse", pds)).pdsEndpoint()).isNull()
        // The first entry with the id decides, as in the reference getPdsEndpoint.
        assertThat(
            d(DidService("#atproto_pds", "SomethingElse", pds), DidService("#atproto_pds", "AtprotoPersonalDataServer", pds)).pdsEndpoint(),
        ).isNull()
        assertThat(d(DidService("#atproto_pds", "AtprotoPersonalDataServer", "ftp://pds.example.com")).pdsEndpoint()).isNull()
        assertThat(d(DidService("#atproto_pds", "AtprotoPersonalDataServer", null)).pdsEndpoint()).isNull()
        assertThat(d(DidService("#bsky_fg", "BskyFeedGenerator", pds)).pdsEndpoint()).isNull()
    }

    @Test
    fun onlyTheFirstValidHandleInAlsoKnownAsCounts() {
        val doc = DidDocument("did:plc:abc", listOf("https://alice.example", "at://not valid", "at://Alice.Test", "at://other.test"), emptyList())
        assertThat(doc.claimedHandle()).isEqualTo("alice.test")
        assertThat(resolver.handleMatches(doc, "@ALICE.test")).isTrue()
        assertThat(resolver.handleMatches(doc, "other.test")).isFalse()
        assertThat(DidDocument("did:plc:abc", emptyList(), emptyList()).handleMatches("alice.test")).isFalse()
    }

    @Test
    fun didDocumentParsingSkipsMalformedEntries() {
        val parsed = DidDocument.parse(
            testJson.parseToJsonElement(
                """{"id":"did:plc:abc","alsoKnownAs":["at://a.test",5],"service":[7,{"type":"x"},
                   {"id":"#atproto_pds","type":"AtprotoPersonalDataServer","serviceEndpoint":{"uri":"https://x"}}]}""",
            ),
        )!!
        assertThat(parsed.alsoKnownAs).containsExactly("at://a.test")
        assertThat(parsed.services).containsExactly(DidService("#atproto_pds", "AtprotoPersonalDataServer", null))
        assertThat(DidDocument.parse(testJson.parseToJsonElement("""{"alsoKnownAs":[]}"""))).isNull()
    }

    @Test
    fun normalizeHandleTrimsDropsAtAndLowercases() {
        assertThat(IdentityResolver.normalizeHandle("  @Alice.Bsky.Social\n")).isEqualTo("alice.bsky.social")
        assertThat(IdentityResolver.normalizeHandle("ALICE.TEST")).isEqualTo("alice.test")
        // Locale-independent: a Turkish default locale must not produce a dotless i.
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"))
            assertThat(IdentityResolver.normalizeHandle("ILIA.TEST")).isEqualTo("ilia.test")
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun syntaxChecksFollowTheSpecExamples() {
        for (did in listOf("did:plc:ewvi7nxzyoun6zhxrhs64oiz", "did:web:user.example.com", "did:method:val:two", "did:m:v")) {
            assertThat(IdentityResolver.isValidDid(did)).isTrue()
        }
        for (did in listOf("did:METHOD:val", "did:m123:val", "DID:method:val", "did:method:", "did:method:val/two", "did:method:val?two", "did:method:val#two")) {
            assertThat(IdentityResolver.isValidDid(did)).isFalse()
        }
        assertThat(IdentityResolver.isValidHandle("jay.bsky.social")).isTrue()
        assertThat(IdentityResolver.isValidHandle("xn--ls8h.test")).isTrue()
        assertThat(IdentityResolver.isValidHandle("jo@hn.test")).isFalse()
        assertThat(IdentityResolver.isValidHandle("john.0")).isFalse()
    }

    @Test
    fun networkFailureOfTheAppViewWithoutWellKnownPropagatesAsIo() = runBlocking<Unit> {
        val closed = IdentityResolver(http, testJson)
        server.close()
        val e = runCatching { closed.resolveHandle("alice.example.org") }.exceptionOrNull()
        assertThat(e).isInstanceOf(IOException::class.java)
    }

    private companion object {
        const val ORIGINAL = "X-Test-Original-Url"
    }
}
