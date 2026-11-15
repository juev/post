package org.evsyukov.post.core.bluesky

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.TimeUnit

class BlueskyClientTest {
    @get:Rule val temp = TemporaryFolder()

    private val server = MockWebServer().apply { start() }
    private val http = OkHttpClient()
    private val client = BlueskyClient(server.url("/"), http, testJson, clock = { 1_000_000_000_000L })
    private val did = "did:plc:ewvi7nxzyoun6zhxrhs64oiz"

    @After fun close() = server.close()

    private fun sessionBody(didDoc: String? = null, extra: String = "") =
        """{"did":"$did","handle":"alice.test","accessJwt":"access-1","refreshJwt":"refresh-1"${didDoc?.let { ",\"didDoc\":$it" } ?: ""}$extra}"""

    private val didDoc = """{"@context":["https://www.w3.org/ns/did/v1"],"id":"$did","alsoKnownAs":["at://alice.test"],
        "service":[{"id":"#atproto_pds","type":"AtprotoPersonalDataServer","serviceEndpoint":"https://morel.us-east.host.bsky.network"}]}"""

    // N14, N15: sign-in with the app password; the session holds tokens, never the password.
    @Test
    fun createSessionPostsTheCredentialsAndReadsTheSession() = runBlocking<Unit> {
        server.enqueue(jsonResponse(sessionBody(didDoc, ""","active":true""")))
        val session = client.createSession("alice.test", "app-pass-word-1234")
        val request = server.next()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/xrpc/com.atproto.server.createSession")
        assertThat(request.headers["Authorization"]).isNull()
        assertThat(request.headers["Content-Type"]).startsWith("application/json")
        assertThat(request.bodyJson()).isEqualTo(
            JsonObject(mapOf("identifier" to jsonPrimitive("alice.test"), "password" to jsonPrimitive("app-pass-word-1234"))),
        )
        assertThat(session.did).isEqualTo(did)
        assertThat(session.handle).isEqualTo("alice.test")
        assertThat(session.accessJwt).isEqualTo("access-1")
        assertThat(session.refreshJwt).isEqualTo("refresh-1")
        assertThat(session.pdsFromDidDoc).isEqualTo("https://morel.us-east.host.bsky.network/".toHttpUrl())
        assertThat(session.active).isTrue()
        assertThat(session.status).isNull()
        assertThat(session.toString()).doesNotContain("access-1")
        assertThat(session.toString()).doesNotContain("refresh-1")
    }

    @Test
    fun createSessionSendsTheEmailCodeWhenGiven() = runBlocking<Unit> {
        server.enqueue(jsonResponse(sessionBody()))
        val session = client.createSession("alice.test", "pw", authFactorToken = "ABCDE-12345")
        assertThat(server.next().bodyJson().str("authFactorToken")).isEqualTo("ABCDE-12345")
        assertThat(session.pdsFromDidDoc).isNull()
    }

    @Test
    fun didDocAboutAnotherDidIsIgnored() = runBlocking<Unit> {
        server.enqueue(jsonResponse(sessionBody(didDoc.replace("\"id\":\"$did\"", "\"id\":\"did:plc:someoneelse\""))))
        assertThat(client.createSession("alice.test", "pw").pdsFromDidDoc).isNull()
    }

    @Test
    fun deactivatedAccountStatusIsKept() = runBlocking<Unit> {
        server.enqueue(jsonResponse(sessionBody(extra = ""","active":false,"status":"deactivated"""")))
        val session = client.createSession("alice.test", "pw")
        assertThat(session.active).isFalse()
        assertThat(session.status).isEqualTo("deactivated")
    }

    @Test
    fun authFactorAndTakedownAreClassified() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""{"error":"AuthFactorTokenRequired","message":"A sign in code has been sent to your email address"}""", 401))
        val factor = runCatching { client.createSession("alice.test", "pw") }.exceptionOrNull() as XrpcException
        assertThat(factor.isAuthFactorRequired).isTrue()
        server.enqueue(jsonResponse("""{"error":"AccountTakedown","message":"Account has been taken down"}""", 401))
        val takedown = runCatching { client.createSession("alice.test", "pw") }.exceptionOrNull() as XrpcException
        assertThat(takedown.isAccountTakedown).isTrue()
        assertThat(takedown.isAuthFactorRequired).isFalse()
    }

    @Test
    fun wrongPasswordIsRejectedCredentialsAndNeverEchoesThePassword() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""{"error":"AuthenticationRequired","message":"Invalid identifier or password: s3cret-pw"}""", 401))
        val e = runCatching { client.createSession("alice.test", "s3cret-pw") }.exceptionOrNull() as XrpcException
        assertThat(e.isInvalidToken).isTrue()
        assertThat(e.message).doesNotContain("s3cret-pw")
        assertThat(e.toString()).doesNotContain("s3cret-pw")
        assertThat(e.status).isEqualTo(401)
    }

    // OkHttp re-sends the body of a 307: following it would hand the password to another host.
    @Test
    fun redirectsAreNotFollowedSoThePasswordStaysOnThePds() = runBlocking<Unit> {
        val other = MockWebServer().apply { start() }
        try {
            other.enqueue(jsonResponse(sessionBody()))
            server.enqueue(MockResponse.Builder().code(307).addHeader("Location", other.url("/xrpc/com.atproto.server.createSession")).build())
            val e = runCatching { client.createSession("alice.test", "pw") }.exceptionOrNull()
            assertThat(e).isInstanceOf(XrpcException::class.java)
            assertThat((e as XrpcException).status).isEqualTo(307)
            assertThat(other.takeRequest(300, TimeUnit.MILLISECONDS)).isNull()
        } finally {
            other.close()
        }
    }

    @Test
    fun malformedSessionAnswerFailsWithoutLeakingTokens() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""{"did":"$did","handle":"alice.test","accessJwt":"leaky-token"""))
        val broken = runCatching { client.createSession("alice.test", "pw") }.exceptionOrNull() as XrpcException
        assertThat(broken.error).isEqualTo(XrpcException.INVALID_RESPONSE)
        assertThat(broken.toString()).doesNotContain("leaky-token")
        assertThat(broken.cause).isNull()
        server.enqueue(jsonResponse("""{"did":"$did","handle":"alice.test","accessJwt":"leaky-token"}"""))
        val missing = runCatching { client.createSession("alice.test", "pw") }.exceptionOrNull() as XrpcException
        assertThat(missing.message).contains("refreshJwt")
        assertThat(missing.toString()).doesNotContain("leaky-token")
        assertThat(missing.isTransient).isFalse()
    }

    @Test
    fun refreshSessionUsesTheRefreshToken() = runBlocking<Unit> {
        server.enqueue(jsonResponse(sessionBody(didDoc).replace("access-1", "access-2").replace("refresh-1", "refresh-2")))
        val session = client.refreshSession("refresh-1")
        val request = server.next()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/xrpc/com.atproto.server.refreshSession")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer refresh-1")
        assertThat(request.bodySize).isEqualTo(0)
        assertThat(session.accessJwt).isEqualTo("access-2")
        assertThat(session.refreshJwt).isEqualTo("refresh-2")
        assertThat(session.pdsFromDidDoc).isEqualTo("https://morel.us-east.host.bsky.network/".toHttpUrl())
    }

    @Test
    fun rejectedRefreshMeansSignInAgain() = runBlocking<Unit> {
        // Reference PDS: a revoked refresh token is 400 ExpiredToken (refreshSession.ts:53-55).
        server.enqueue(jsonResponse("""{"error":"ExpiredToken","message":"Token has been revoked"}""", 400))
        val revoked = runCatching { client.refreshSession("refresh-1") }.exceptionOrNull() as XrpcException
        assertThat(revoked.isExpiredToken).isTrue()
        server.enqueue(jsonResponse("""{"error":"InvalidToken","message":"Token could not be verified"}""", 400))
        val invalid = runCatching { client.refreshSession("refresh-1") }.exceptionOrNull() as XrpcException
        assertThat(invalid.isInvalidToken).isTrue()
        assertThat(invalid.isTransient).isFalse()
    }

    @Test
    fun getServiceAuthPassesAudienceMethodAndExpiry() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""{"token":"service-token"}"""))
        val token = client.getServiceAuth("access-1", "did:web:morel.us-east.host.bsky.network", "com.atproto.repo.uploadBlob", 1_763_201_800L)
        val request = server.next()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.url.encodedPath).isEqualTo("/xrpc/com.atproto.server.getServiceAuth")
        assertThat(request.url.queryParameter("aud")).isEqualTo("did:web:morel.us-east.host.bsky.network")
        assertThat(request.url.queryParameter("lxm")).isEqualTo("com.atproto.repo.uploadBlob")
        assertThat(request.url.queryParameter("exp")).isEqualTo("1763201800")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer access-1")
        assertThat(token).isEqualTo("service-token")

        server.enqueue(jsonResponse("""{"token":"t2"}"""))
        client.getServiceAuth("access-1", "did:web:video.bsky.app", null, null)
        val bare = server.next()
        assertThat(bare.url.queryParameterNames).containsExactly("aud")
    }

    @Test
    fun uploadBlobSendsTheFileWithItsTypeAndKeepsTheBlobJson() = runBlocking<Unit> {
        val file = temp.newFile("photo.jpg").apply { writeBytes(ByteArray(3000) { (it % 251).toByte() }) }
        val blob = """{"${'$'}type":"blob","ref":{"${'$'}link":"bafkreiabc"},"mimeType":"image/jpeg","size":3000}"""
        server.enqueue(jsonResponse("""{"blob":$blob}"""))
        val ref = client.uploadBlob("access-1", file, "image/jpeg")
        val request = server.next()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/xrpc/com.atproto.repo.uploadBlob")
        assertThat(request.headers["Content-Type"]).isEqualTo("image/jpeg")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer access-1")
        assertThat(request.body!!.toByteArray()).isEqualTo(file.readBytes())
        assertThat(ref.json.toString()).isEqualTo(blob)
        assertThat(ref.cid).isEqualTo("bafkreiabc")
        assertThat(ref.mimeType).isEqualTo("image/jpeg")
        assertThat(ref.size).isEqualTo(3000)
    }

    @Test
    fun legacyBlobFormatIsKeptAsIs() = runBlocking<Unit> {
        val file = temp.newFile("a.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        server.enqueue(jsonResponse("""{"blob":{"cid":"bafkreilegacy","mimeType":"image/png"}}"""))
        val ref = client.uploadBlob("access-1", file, "image/png")
        assertThat(ref.cid).isEqualTo("bafkreilegacy")
        assertThat(ref.json.keys).containsExactly("cid", "mimeType")
    }

    // N24: one applyWrites per part with the post, threadgate and postgate at the same key.
    @Test
    fun applyWritesSendsTypedOperationsAndReadsResultsInOrder() = runBlocking<Unit> {
        val rkey = "3m5abcdefgh22"
        val uri = atUri(did, Collections.POST, rkey)
        val writes = listOf(
            PostRecord("Hello", "2026-11-15T09:30:00.000Z").create(rkey),
            ThreadgateRecord(uri, "2026-11-15T09:30:00.000Z", emptyList()).create(rkey),
            PostgateRecord(uri, "2026-11-15T09:30:00.000Z", listOf(PostgateRule.DisableEmbedding)).create(rkey),
        )
        server.enqueue(
            jsonResponse(
                """{"commit":{"cid":"bafyreicommit","rev":"3m5abcdefgh2a"},"results":[
                {"${'$'}type":"com.atproto.repo.applyWrites#createResult","uri":"$uri","cid":"bafyreipost","validationStatus":"valid"},
                {"${'$'}type":"com.atproto.repo.applyWrites#createResult","uri":"at://$did/app.bsky.feed.threadgate/$rkey","cid":"bafyreigate"},
                {"${'$'}type":"com.atproto.repo.applyWrites#createResult","uri":"at://$did/app.bsky.feed.postgate/$rkey","cid":"bafyreiquote"}]}""",
            ),
        )
        val results = client.applyWrites("access-1", did, writes)
        val request = server.next()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/xrpc/com.atproto.repo.applyWrites")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer access-1")
        val body = request.bodyJson()
        assertThat(body.str("repo")).isEqualTo(did)
        assertThat(body["validate"]!!.jsonPrimitive.boolean).isTrue()
        val sent = body["writes"]!!.jsonArray.map { it.jsonObject }
        assertThat(sent.map { it.str("\$type") }).containsExactly(
            "com.atproto.repo.applyWrites#create",
            "com.atproto.repo.applyWrites#create",
            "com.atproto.repo.applyWrites#create",
        )
        assertThat(sent.map { it.str("collection") }).containsExactly(Collections.POST, Collections.THREADGATE, Collections.POSTGATE).inOrder()
        assertThat(sent.map { it.str("rkey") }.toSet()).containsExactly(rkey)
        assertThat(sent[0]["value"]).isEqualTo(writes[0].value)
        assertThat(results).containsExactly(
            WriteResult(uri, "bafyreipost"),
            WriteResult("at://$did/app.bsky.feed.threadgate/$rkey", "bafyreigate"),
            WriteResult("at://$did/app.bsky.feed.postgate/$rkey", "bafyreiquote"),
        ).inOrder()
    }

    @Test
    fun applyWritesDeletesAndOptionalResults() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""{"results":[{"${'$'}type":"com.atproto.repo.applyWrites#deleteResult"}]}"""))
        val results = client.applyWrites("access-1", did, listOf(WriteOp.Delete(Collections.POST, "3m5abcdefgh22")), validate = false)
        val body = server.next().bodyJson()
        assertThat(body["validate"]!!.jsonPrimitive.boolean).isFalse()
        assertThat(body["writes"]).isEqualTo(
            JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "\$type" to jsonPrimitive("com.atproto.repo.applyWrites#delete"),
                            "collection" to jsonPrimitive(Collections.POST),
                            "rkey" to jsonPrimitive("3m5abcdefgh22"),
                        ),
                    ),
                ),
            ),
        )
        assertThat(results).containsExactly(WriteResult(null, null))

        // results is not required by the lexicon.
        server.enqueue(jsonResponse("""{"commit":{"cid":"bafy","rev":"3m5"}}"""))
        assertThat(client.applyWrites("access-1", did, listOf(PostRecord("x", "2026-11-15T09:30:00.000Z").create("3m5abcdefgh22")))).isEmpty()
        server.next()
        server.enqueue(jsonResponse("{}"))
        assertThat(client.applyWrites("access-1", did, listOf(WriteOp.Delete(Collections.POST, "3m5abcdefgh22")))).isEmpty()
    }

    @Test
    fun applyWritesRefusesEmptyAndOversizedBatchesBeforeSending() = runBlocking<Unit> {
        assertThat(runCatching { client.applyWrites("a", did, emptyList()) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        val tooMany = List(201) { WriteOp.Delete(Collections.POST, Tid.encode(it.toLong(), 0)) }
        assertThat(runCatching { client.applyWrites("a", did, tooMany) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    // N25 on the reference PDS: the repeated create gets a bare 500, which is not "already exists".
    @Test
    fun repeatedCreateOnTheReferencePdsLooksLikeAServerError() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""{"error":"InternalServerError","message":"Internal Server Error"}""", 500))
        val e = runCatching {
            client.applyWrites("access-1", did, listOf(PostRecord("x", "2026-11-15T09:30:00.000Z").create("3m5abcdefgh22")))
        }.exceptionOrNull() as XrpcException
        assertThat(e.isRecordAlreadyExists).isFalse()
        assertThat(e.isTransient).isTrue()
    }

    @Test
    fun getRecordWithoutAuthReturnsUriAndCid() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""{"uri":"at://$did/app.bsky.feed.post/3m5abcdefgh22","cid":"bafyreipost","value":{"text":"x"}}"""))
        val ref = client.getRecord(did, Collections.POST, "3m5abcdefgh22")
        val request = server.next()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.url.encodedPath).isEqualTo("/xrpc/com.atproto.repo.getRecord")
        assertThat(request.url.queryParameter("repo")).isEqualTo(did)
        assertThat(request.url.queryParameter("collection")).isEqualTo(Collections.POST)
        assertThat(request.url.queryParameter("rkey")).isEqualTo("3m5abcdefgh22")
        assertThat(request.headers["Authorization"]).isNull()
        assertThat(ref).isEqualTo(RecordRef("at://$did/app.bsky.feed.post/3m5abcdefgh22", "bafyreipost"))
    }

    @Test
    fun getRecordReturnsNullOnlyForRecordNotFound() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""{"error":"RecordNotFound","message":"Could not locate record: at://x"}""", 400))
        assertThat(client.getRecord(did, Collections.POST, "3m5abcdefgh22")).isNull()
        server.enqueue(jsonResponse("""{"uri":"at://$did/app.bsky.feed.post/3m5abcdefgh22","value":{}}"""))
        assertThat(client.getRecord(did, Collections.POST, "3m5abcdefgh22")!!.cid).isNull()
        server.enqueue(jsonResponse("""{"error":"InvalidRequest","message":"Could not find repo"}""", 400))
        val e = runCatching { client.getRecord(did, Collections.POST, "3m5abcdefgh22") }.exceptionOrNull()
        assertThat((e as XrpcException).error).isEqualTo("InvalidRequest")
    }

    @Test
    fun expiredAccessTokenIsClassifiedForRefresh() = runBlocking<Unit> {
        server.enqueue(jsonResponse("""{"error":"ExpiredToken","message":"Token has expired"}""", 400))
        val e = runCatching { client.getServiceAuth("access-1", "did:web:x", null, null) }.exceptionOrNull() as XrpcException
        assertThat(e.isExpiredToken).isTrue()
        assertThat(e.isInvalidToken).isFalse()
    }

    @Test
    fun rateLimitCarriesTheResetTime() = runBlocking<Unit> {
        server.enqueue(
            MockResponse.Builder().code(429).addHeader("RateLimit-Reset", "1763205000").addHeader("Retry-After", "60")
                .body("""{"error":"RateLimitExceeded","message":"Rate Limit Exceeded"}""").build(),
        )
        val e = runCatching { client.createSession("alice.test", "pw") }.exceptionOrNull() as XrpcException
        assertThat(e.isRateLimited).isTrue()
        assertThat(e.isTransient).isFalse()
        assertThat(e.rateLimitResetEpochSeconds).isEqualTo(1_763_205_000L)
    }

    @Test
    fun networkFailureIsAnIOExceptionAndTransient() = runBlocking<Unit> {
        val closed = BlueskyClient(server.url("/"), http, testJson)
        server.close()
        val e = runCatching { closed.getRecord(did, Collections.POST, "3m5abcdefgh22") }.exceptionOrNull()
        assertThat(e).isInstanceOf(IOException::class.java)
        assertThat(XrpcException.isTransient(e!!)).isTrue()
    }

    private fun jsonPrimitive(value: String) = kotlinx.serialization.json.JsonPrimitive(value)
}
