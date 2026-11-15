package org.evsyukov.post.core.mastodon

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * Error classification (product.md R5, R29), rate limits (networks.md N8, N31), network failures,
 * redirects and the rule that tokens never reach exception text (product.md R4).
 */
class MastodonErrorsTest {
    private val server = MockWebServer().apply { start() }
    private val now = Instant.parse("2026-11-15T12:00:00Z")
    private val http = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()
    private val client = MastodonClient(server.url("/"), http, clock = Clock.fixed(now, ZoneOffset.UTC))
    private val token = "tok-SECRET-123"
    private val anyStatus = StatusParams(status = "hi")

    @After fun close() = server.close()

    private fun failing(block: suspend () -> Unit): MastodonException =
        assertThrows(MastodonException::class.java) { runBlocking { block() } }

    private fun createFails(response: MockResponse): MastodonException {
        server.enqueue(response)
        return failing { client.createStatus(token, anyStatus, "k") }
    }

    private fun MastodonException.assertNoSecret(vararg secrets: String) {
        secrets.forEach {
            assertThat(message).doesNotContain(it)
            assertThat(toString()).doesNotContain(it)
            if (this is MastodonException.Http) {
                assertThat(error.orEmpty()).doesNotContain(it)
                assertThat(description.orEmpty()).doesNotContain(it)
            }
        }
    }

    private fun MastodonException.flags() = listOf(isUnauthorized, isRateLimited, isTransient, isValidation, isNotFound, isInvalidClient)

    @Test
    fun unauthorizedIs401() {
        val e = createFails(json("""{"error":"The access token is invalid"}""", 401))

        assertThat(e).isInstanceOf(MastodonException.Http::class.java)
        e as MastodonException.Http
        assertThat(e.status).isEqualTo(401)
        assertThat(e.error).isEqualTo("The access token is invalid")
        assertThat(e.flags()).containsExactly(true, false, false, false, false, false).inOrder()
        assertThat(e.message).isEqualTo("HTTP 401: The access token is invalid")
        e.assertNoSecret(token)
    }

    @Test
    fun forbiddenIsUnauthorizedOnlyWithInvalidToken() {
        assertThat(createFails(json("""{"error":"invalid_token"}""", 403)).isUnauthorized).isTrue()

        val header = MockResponse.Builder().code(403)
            .setHeader("WWW-Authenticate", "Bearer realm=\"Doorkeeper\", error=\"invalid_token\", error_description=\"The access token was revoked\"")
            .body("""{"error":"The access token was revoked"}""").build()
        assertThat(createFails(header).isUnauthorized).isTrue()

        val scope = MockResponse.Builder().code(403)
            .setHeader("WWW-Authenticate", "Bearer realm=\"Doorkeeper\", error=\"insufficient_scope\"")
            .body("""{"error":"This action is outside the authorized scopes"}""").build()
        val e = createFails(scope)
        assertThat(e.isUnauthorized).isFalse()
        assertThat((e as MastodonException.Http).authError).isEqualTo("insufficient_scope")

        assertThat(createFails(json("""{"error":"Your login is currently disabled"}""", 403)).isUnauthorized).isFalse()
    }

    // R5 on Pleroma and Akkoma: an unknown or revoked token leaves the request without a user, and
    // EnsureAuthenticatedPlug answers 403 "Invalid credentials." (Akkoma develop, router.ex
    // pipeline :authenticated_api, ensure_authenticated_plug.ex).
    @Test
    fun pleromaAndAkkomaInvalidCredentialsIsUnauthorized() {
        val e = createFails(json("""{"error":"Invalid credentials.","identifier":""}""", 403))
        assertThat(e.flags()).containsExactly(true, false, false, false, false, false).inOrder()

        server.enqueue(json("""{"error":"Invalid credentials."}""", 403))
        assertThat(failing { client.verifyCredentials(token) }.isUnauthorized).isTrue()

        // The same plug's other refusal is not about the token.
        val twoFactor = createFails(json("""{"error":"Two-factor authentication enabled, you must use a access token."}""", 403))
        assertThat(twoFactor.isUnauthorized).isFalse()
        // Akkoma's answer to a failed code exchange is a 400 and stays a plain error (see isInvalidClient).
        server.enqueue(json("""{"error":"Invalid credentials"}""", 400))
        val exchange = failing { client.exchangeCode("cid", "csecret", REDIRECT_URI, "code", "verifier", "read") }
        assertThat(exchange.flags()).containsExactly(false, false, false, false, false, false)
    }

    // Before 4.3 a server could remove the app registration; the app then registers again.
    @Test
    fun invalidClientOnTokenExchangeIsReportedAndCarriesNoSecrets() {
        server.enqueue(
            json(
                """{"error":"invalid_client","error_description":"Unknown client csecret-456 for code code-abc and verifier ver-789"}""",
                401,
            ),
        )

        val e = failing { client.exchangeCode("cid-123", "csecret-456", REDIRECT_URI, "code-abc", "ver-789", "read") }

        assertThat(e.isInvalidClient).isTrue()
        assertThat(e.isUnauthorized).isFalse()
        e.assertNoSecret("csecret-456", "code-abc", "ver-789", "cid-123")
        assertThat((e as MastodonException.Http).description).isEqualTo("Unknown client *** for code *** and verifier ***")
    }

    @Test
    fun aTokenEchoedByTheServerIsRedacted() {
        val e = createFails(json("""{"error":"Bad token $token","error_description":"Bearer%20$token"}""", 401))
        e.assertNoSecret(token)
        assertThat((e as MastodonException.Http).error).isEqualTo("Bad token ***")
    }

    @Test
    fun revokeErrorsCarryNoSecrets() {
        server.enqueue(json("""{"error":"unauthorized_client","error_description":"cannot revoke $token for csecret"}""", 403))
        val e = failing { client.revokeToken("cid", "csecret", token) }
        e.assertNoSecret(token, "csecret")
        assertThat(e.isUnauthorized).isFalse()
    }

    @Test
    fun validationIs422WithTheServerMessage() {
        val e = createFails(json("""{"error":"Validation failed: Text character limit of 500 exceeded"}""", 422))
        assertThat(e.flags()).containsExactly(false, false, false, true, false, false).inOrder()
        assertThat((e as MastodonException.Http).error).isEqualTo("Validation failed: Text character limit of 500 exceeded")
    }

    // N31: a server without edit answers 404 or 405.
    @Test
    fun notFoundIs404() {
        server.enqueue(json("""{"error":"Record not found"}""", 404))
        val e = failing { client.editStatus(token, "1", StatusEditParams(status = "x")) }
        assertThat(e.flags()).containsExactly(false, false, false, false, true, false).inOrder()

        server.enqueue(MockResponse.Builder().code(405).body("Method Not Allowed").build())
        val methodNotAllowed = failing { client.editStatus(token, "1", StatusEditParams(status = "x")) }
        assertThat((methodNotAllowed as MastodonException.Http).status).isEqualTo(405)
        assertThat(methodNotAllowed.flags()).containsExactly(false, false, false, false, false, false)
    }

    // Mastodon's rack-attack answer: X-RateLimit-Reset as ISO 8601 with microseconds, no Retry-After.
    @Test
    fun rateLimitUsesResetAgainstTheServerDate() {
        val response = MockResponse.Builder().code(429)
            .setHeader("Date", "Sun, 15 Nov 2026 13:00:00 GMT")
            .setHeader("X-RateLimit-Limit", "30")
            .setHeader("X-RateLimit-Remaining", "0")
            .setHeader("X-RateLimit-Reset", "2026-11-15T13:12:30.500000Z")
            .body("""{"error":"Too many requests"}""").build()

        val e = createFails(response)

        assertThat(e.flags()).containsExactly(false, true, false, false, false, false).inOrder()
        // 12 min 30.5 s after the server's Date, rounded up; the device clock (12:00) is not used.
        assertThat((e as MastodonException.Http).retryAfterSeconds).isEqualTo(751)
    }

    @Test
    fun rateLimitWithoutDateUsesTheClock() {
        val response = MockResponse.Builder().code(429).setHeader("X-RateLimit-Reset", "2026-11-15T12:05:00.000000Z").build()
        assertThat((createFails(response) as MastodonException.Http).retryAfterSeconds).isEqualTo(300)

        val epoch = MockResponse.Builder().code(429).setHeader("X-RateLimit-Reset", (now.epochSecond + 90).toString()).build()
        assertThat((createFails(epoch) as MastodonException.Http).retryAfterSeconds).isEqualTo(90)

        val delay = MockResponse.Builder().code(429).setHeader("X-RateLimit-Reset", "45").build()
        assertThat((createFails(delay) as MastodonException.Http).retryAfterSeconds).isEqualTo(45)

        val past = MockResponse.Builder().code(429).setHeader("X-RateLimit-Reset", "2026-11-15T11:00:00Z").build()
        assertThat((createFails(past) as MastodonException.Http).retryAfterSeconds).isEqualTo(0)
    }

    @Test
    fun retryAfterWinsAndAcceptsSecondsOrADate() {
        val seconds = MockResponse.Builder().code(429).setHeader("Retry-After", "120")
            .setHeader("X-RateLimit-Reset", "2026-11-15T13:00:00Z").build()
        assertThat((createFails(seconds) as MastodonException.Http).retryAfterSeconds).isEqualTo(120)

        val date = MockResponse.Builder().code(503).setHeader("Retry-After", "Sun, 15 Nov 2026 12:01:00 GMT").build()
        val e = createFails(date) as MastodonException.Http
        assertThat(e.retryAfterSeconds).isEqualTo(60)
        assertThat(e.isTransient).isTrue()

        val none = createFails(MockResponse.Builder().code(429).setHeader("Retry-After", "soon").build()) as MastodonException.Http
        assertThat(none.retryAfterSeconds).isNull()
    }

    // Mastodon sends X-RateLimit-* with every rate-limited API answer (Api::RateLimitHeaders); for
    // creating a status the reset ends the 3-hour window of the statuses family (RateLimiter). A
    // repeat with the same Idempotency-Key while the first request runs gets 503 (RaceConditionError).
    @Test
    fun rateLimitResetOfAnAnswerOtherThan429IsNotARetryHint() {
        fun answer(code: Int) = MockResponse.Builder().code(code)
            .setHeader("Date", "Sun, 15 Nov 2026 12:00:00 GMT")
            .setHeader("X-RateLimit-Limit", "300")
            .setHeader("X-RateLimit-Remaining", "250")
            .setHeader("X-RateLimit-Reset", "2026-11-15T15:00:00.000000Z")
            .body("""{"error":"There was a temporary problem serving your request, please try again"}""")

        val busy = createFails(answer(503).build()) as MastodonException.Http
        assertThat(busy.isTransient).isTrue()
        assertThat(busy.retryAfterSeconds).isNull()
        assertThat((createFails(answer(422).build()) as MastodonException.Http).retryAfterSeconds).isNull()
        assertThat((createFails(answer(401).build()) as MastodonException.Http).retryAfterSeconds).isNull()

        // Retry-After keeps its meaning with any status.
        val withRetryAfter = createFails(answer(503).setHeader("Retry-After", "30").build()) as MastodonException.Http
        assertThat(withRetryAfter.retryAfterSeconds).isEqualTo(30)
        // The same headers on a 429 give the time to the reset.
        assertThat((createFails(answer(429).build()) as MastodonException.Http).retryAfterSeconds).isEqualTo(10_800)
    }

    // A broken header must not escape as an unchecked java.time exception.
    @Test
    fun outOfRangeRateLimitHeadersGiveNoHintInsteadOfACrash() {
        fun reset(value: String) = createFails(MockResponse.Builder().code(429).setHeader("X-RateLimit-Reset", value).build())

        val beyondInstant = reset("99999999999999999")
        assertThat(beyondInstant).isInstanceOf(MastodonException.Http::class.java)
        assertThat((beyondInstant as MastodonException.Http).retryAfterSeconds).isNull()
        assertThat(beyondInstant.isRateLimited).isTrue()

        // Inside the range of Instant, but too far for milliseconds in a Long.
        val farEpoch = reset("9999999999999999") as MastodonException.Http
        assertThat(farEpoch.retryAfterSeconds).isEqualTo(9_999_999_999_999_999L - now.epochSecond)
        val farDate = reset("+999999999-12-31T23:59:59Z") as MastodonException.Http
        assertThat(farDate.retryAfterSeconds).isGreaterThan(0L)

        val longOverflow = createFails(MockResponse.Builder().code(429).setHeader("Retry-After", "99999999999999999999").build())
        assertThat((longOverflow as MastodonException.Http).retryAfterSeconds).isNull()
    }

    @Test
    fun serverErrorsAndRequestTimeoutAreTransientEvenWithoutJson() {
        for (code in listOf(500, 502, 503, 504, 408)) {
            val e = createFails(MockResponse.Builder().code(code).body("<html><body>Bad Gateway</body></html>").build())
            assertThat(e.isTransient).isTrue()
            assertThat(e.isRateLimited).isFalse()
            assertThat((e as MastodonException.Http).status).isEqualTo(code)
            assertThat(e.error).isNull()
        }
    }

    @Test
    fun aTimeoutIsATransientNetworkError() {
        val e = createFails(json(STATUS_JSON).newBuilder().headersDelay(2, TimeUnit.SECONDS).build())

        assertThat(e).isInstanceOf(MastodonException.Network::class.java)
        assertThat(e.isTransient).isTrue()
        assertThat(e.flags()).containsExactly(false, false, true, false, false, false).inOrder()
        assertThat(e.cause).isInstanceOf(java.io.InterruptedIOException::class.java)
        e.assertNoSecret(token)
    }

    @Test
    fun aDroppedConnectionIsATransientNetworkError() {
        val e = createFails(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build())
        assertThat(e).isInstanceOf(MastodonException.Network::class.java)
        assertThat(e.isTransient).isTrue()
        e.assertNoSecret(token)
    }

    // product.md R4: the token never follows a redirect.
    @Test
    fun redirectsAreNotFollowed() {
        val redirect = MockResponse.Builder().code(302).setHeader("Location", server.url("/elsewhere").toString()).build()
        server.enqueue(redirect)
        server.enqueue(json(STATUS_JSON))

        val e = failing { client.createStatus(token, anyStatus, "k") }

        assertThat((e as MastodonException.Http).status).isEqualTo(302)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun anUnexpectedSuccessBodyIsMalformedAndDoesNotQuoteIt() {
        server.enqueue(json("""{"access_token": {"nested": "$token"}, "token_type": "Bearer"}"""))
        val e = failing { client.exchangeCode("cid", "csecret", REDIRECT_URI, "code", "verifier", "read") }

        assertThat(e).isInstanceOf(MastodonException.Malformed::class.java)
        assertThat(e.flags()).containsExactly(false, false, false, false, false, false)
        e.assertNoSecret(token)
        assertThat(e.message).isEqualTo("Unexpected answer from /oauth/token (HTTP 200)")

        server.enqueue(json("not json at all $token"))
        failing { client.verifyCredentials(token) }.assertNoSecret(token)
    }

    @Test
    fun cancellingTheCoroutineCancelsTheCall() = runBlocking<Unit> {
        server.enqueue(json(STATUS_JSON).newBuilder().headersDelay(30, TimeUnit.SECONDS).build())
        val slowHttp = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
        val slow = MastodonClient(server.url("/"), slowHttp)

        val call = async(Dispatchers.IO) { slow.createStatus(token, anyStatus, "k") }
        assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull()
        assertThat(slowHttp.dispatcher.runningCallsCount()).isEqualTo(1)
        call.cancel()
        val outcome = runCatching { withTimeout(3_000) { call.await() } }

        val failure = outcome.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(failure).isNotInstanceOf(kotlinx.coroutines.TimeoutCancellationException::class.java)
        // The OkHttp call itself stops, not only the coroutine.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (slowHttp.dispatcher.runningCallsCount() > 0 && System.nanoTime() < deadline) Thread.sleep(10)
        assertThat(slowHttp.dispatcher.runningCallsCount()).isEqualTo(0)
    }
}
