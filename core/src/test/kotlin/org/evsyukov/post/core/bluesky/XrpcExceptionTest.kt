package org.evsyukov.post.core.bluesky

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** Error parsing of real answers and the classification the sender relies on (R5, R29, N15, N25). */
class XrpcExceptionTest {
    private val server = MockWebServer().apply { start() }
    private val now = 1_763_200_000_000L
    private val client = BlueskyClient(server.url("/"), OkHttpClient(), testJson, clock = { now })

    @After fun close() = server.close()

    private fun failureOf(response: MockResponse, token: String = "access-1"): XrpcException = runBlocking {
        server.enqueue(response)
        runCatching { client.getServiceAuth(token, "did:web:x", null, null) }.exceptionOrNull() as XrpcException
    }

    private fun e(status: Int, error: String?, message: String? = null) = XrpcException(status, error, message, null)

    @Test
    fun errorAndMessageComeFromTheJsonBody() {
        val e = failureOf(jsonResponse("""{"error":"InvalidRequest","message":"Input/aud must be a did"}""", 400))
        assertThat(e.status).isEqualTo(400)
        assertThat(e.error).isEqualTo("InvalidRequest")
        assertThat(e.message).isEqualTo("Input/aud must be a did")
        assertThat(e.rateLimitResetEpochSeconds).isNull()
        assertThat(e.toString()).isEqualTo("XrpcException(status=400, error=InvalidRequest, message=Input/aud must be a did)")
    }

    @Test
    fun nonJsonErrorPageGivesNoNameButKeepsTheStatus() {
        val e = failureOf(MockResponse.Builder().code(502).addHeader("Content-Type", "text/html").body("<html>Bad gateway</html>").build())
        assertThat(e.status).isEqualTo(502)
        assertThat(e.error).isNull()
        assertThat(e.message).isNull()
        assertThat(e.isTransient).isTrue()
    }

    @Test
    fun echoedTokenIsScrubbedFromTheMessage() {
        val e = failureOf(jsonResponse("""{"error":"InvalidToken","message":"bad token access-secret-xyz"}""", 400), token = "access-secret-xyz")
        assertThat(e.message).doesNotContain("access-secret-xyz")
        assertThat(e.message).contains("<redacted>")
        assertThat(e.toString()).doesNotContain("access-secret-xyz")
    }

    @Test
    fun shortCredentialDoesNotMangleTheErrorNameOrMessage() {
        // A one-letter "token" occurs inside ordinary words; scrubbing it would break classification.
        val e = failureOf(jsonResponse("""{"error":"ExpiredToken","message":"Token has expired"}""", 400), token = "t")
        assertThat(e.error).isEqualTo("ExpiredToken")
        assertThat(e.message).isEqualTo("Token has expired")
        assertThat(e.isExpiredToken).isTrue()
    }

    @Test
    fun errorNameIsNeverRewrittenEvenIfItEqualsTheToken() {
        val e = failureOf(jsonResponse("""{"error":"InvalidToken","message":"no"}""", 400), token = "InvalidToken")
        assertThat(e.isInvalidToken).isTrue()
    }

    @Test
    fun retryAfterInSecondsBecomesAnEpochTime() {
        val e = failureOf(MockResponse.Builder().code(429).addHeader("Retry-After", "120").build())
        assertThat(e.rateLimitResetEpochSeconds).isEqualTo(now / 1000 + 120)
    }

    @Test
    fun retryAfterAsHttpDateIsRead() {
        val e = failureOf(MockResponse.Builder().code(429).addHeader("Retry-After", "Sat, 15 Nov 2025 10:00:00 GMT").build())
        assertThat(e.rateLimitResetEpochSeconds).isEqualTo(1_763_200_800L)
    }

    @Test
    fun rateLimitResetHeaderWinsOverRetryAfter() {
        val e = failureOf(MockResponse.Builder().code(429).addHeader("ratelimit-reset", "1763209999").addHeader("Retry-After", "5").build())
        assertThat(e.rateLimitResetEpochSeconds).isEqualTo(1_763_209_999L)
    }

    @Test
    fun expiredToken() {
        assertThat(e(400, "ExpiredToken").isExpiredToken).isTrue()
        assertThat(e(401, "ExpiredToken").isExpiredToken).isTrue()
        assertThat(e(400, "InvalidToken").isExpiredToken).isFalse()
        assertThat(e(500, "ExpiredToken").isExpiredToken).isFalse()
    }

    @Test
    fun invalidToken() {
        assertThat(e(400, "InvalidToken").isInvalidToken).isTrue()
        assertThat(e(401, "InvalidToken").isInvalidToken).isTrue()
        assertThat(e(401, "AuthenticationRequired").isInvalidToken).isTrue()
        assertThat(e(401, "AuthMissing").isInvalidToken).isTrue()
        assertThat(e(401, null).isInvalidToken).isTrue()
        assertThat(e(401, "ExpiredToken").isInvalidToken).isFalse()
        assertThat(e(401, "AccountTakedown").isInvalidToken).isFalse()
        assertThat(e(401, "AuthFactorTokenRequired").isInvalidToken).isFalse()
        assertThat(e(400, "InvalidRequest").isInvalidToken).isFalse()
    }

    @Test
    fun transientStatuses() {
        for (status in listOf(408, 500, 502, 503, 504)) assertThat(e(status, null).isTransient).isTrue()
        for (status in listOf(400, 401, 403, 404, 413, 429, 501)) assertThat(e(status, null).isTransient).isFalse()
        assertThat(e(400, "ServiceOverloaded").isTransient).isTrue()
        assertThat(e(409, "UploadNotReady").isTransient).isTrue()
    }

    @Test
    fun throwableClassification() {
        assertThat(XrpcException.isTransient(IOException("reset"))).isTrue()
        assertThat(XrpcException.isTransient(SocketTimeoutException())).isTrue()
        assertThat(XrpcException.isTransient(e(503, null))).isTrue()
        assertThat(XrpcException.isTransient(e(400, "InvalidRequest"))).isFalse()
        assertThat(XrpcException.isTransient(IllegalStateException())).isFalse()
        // An XRPC failure is not an IOException, so a network catch block never swallows it.
        assertThat(e(500, null)).isNotInstanceOf(IOException::class.java)
    }

    @Test
    fun rateLimited() {
        assertThat(e(429, "RateLimitExceeded").isRateLimited).isTrue()
        assertThat(e(503, null).isRateLimited).isFalse()
    }

    @Test
    fun recordAlreadyExistsIsOnlyAHeuristicMatch() {
        assertThat(e(400, "InvalidRequest", "Record already exists at key").isRecordAlreadyExists).isTrue()
        assertThat(e(400, null, "There is already a value at key: app.bsky.feed.post/3m5").isRecordAlreadyExists).isTrue()
        // The reference PDS hides the cause behind a generic 500.
        assertThat(e(500, "InternalServerError", "Internal Server Error").isRecordAlreadyExists).isFalse()
        assertThat(e(400, "InvalidSwap", "Record already exists").isRecordAlreadyExists).isFalse()
        assertThat(e(400, "InvalidRequest", "Invalid record").isRecordAlreadyExists).isFalse()
    }

    @Test
    fun accountAndFactorErrors() {
        assertThat(e(401, "AccountTakedown").isAccountTakedown).isTrue()
        assertThat(e(401, "AuthFactorTokenRequired").isAuthFactorRequired).isTrue()
        assertThat(e(400, "RecordNotFound").isRecordNotFound).isTrue()
    }
}
