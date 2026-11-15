package org.evsyukov.post.core.bluesky

import okhttp3.Response
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * A failed XRPC call: an HTTP error status with the `{error, message}` body of the XRPC spec, or a
 * 2xx answer this client cannot read ([error] = [INVALID_RESPONSE]).
 *
 * The message is the server's, never the request: tokens and passwords are not part of it, and any
 * secret the server might echo back is replaced before the exception is built.
 *
 * Sources: atproto XRPC spec (`atproto-website` `59daa1ff`, `specs/xrpc/en.mdx`, "Summary of HTTP
 * Status Codes"); the reference PDS at `bluesky-social/atproto` `7dd086d4de`, cited per property.
 */
class XrpcException(
    /** HTTP status of the answer. */
    val status: Int,
    /** XRPC error name, such as `ExpiredToken`; null when the body had none. */
    val error: String?,
    /** The server's human-readable message, if any. */
    override val message: String?,
    /** `RateLimit-Reset` (or `Retry-After` turned into an epoch time), when the answer carried one. */
    val rateLimitResetEpochSeconds: Long?,
) : Exception(message) {

    /**
     * The access token has expired: refresh the session and repeat the call. The reference PDS answers
     * 400 `ExpiredToken` (`packages/pds/src/auth-verifier.ts:553-557`); the reference client also
     * accepts 401 (`packages/api/src/atp-agent.ts:221-223`).
     */
    val isExpiredToken: Boolean get() = (status == 400 || status == 401) && error == "ExpiredToken"

    /**
     * The server rejected the credentials themselves. The reference PDS answers 400 `InvalidToken` for
     * a token it cannot verify (`auth-verifier.ts:559-563`, `:592`) and 401 `AuthMissing` or a bare
     * `AuthenticationRequired` without usable auth (`auth-verifier.ts:137`, `:338`, `:548`), so
     * `InvalidToken` is matched with 400 as well as 401.
     */
    val isInvalidToken: Boolean
        get() = (error == "InvalidToken" && (status == 400 || status == 401)) ||
            (status == 401 && (error == null || error == "AuthenticationRequired" || error == "AuthMissing"))

    /** 429: wait until [rateLimitResetEpochSeconds] when it is known. */
    val isRateLimited: Boolean get() = status == 429

    /**
     * Repeating later may succeed: 408, 5xx except 501 (the XRPC spec says not to retry 501), and the
     * video service's `ServiceOverloaded` and `UploadNotReady` (`app.bsky.video.*` lexicons, `bsky`
     * `416b0ad1d9`: "retry later", "check getUploadStatus and retry"). Network failures are
     * [IOException]s, not this class; [isTransient] in the companion covers both.
     */
    val isTransient: Boolean
        get() = status == 408 || (status in 500..599 && status != 501) || error in TRANSIENT_ERRORS

    /** `createSession` needs the code sent to the account's email (`server/createSession.json`). */
    val isAuthFactorRequired: Boolean get() = error == "AuthFactorTokenRequired"

    /** The account is taken down (`createSession.json`, `refreshSession.json`; `auth-verifier.ts:524-527`). */
    val isAccountTakedown: Boolean get() = error == "AccountTakedown"

    /** `getRecord` found nothing at the key (`repo/getRecord.json`; `api/com/atproto/repo/getRecord.ts:18-23`). */
    val isRecordNotFound: Boolean get() = error == "RecordNotFound"

    /**
     * HEURISTIC, not backed by the reference PDS. An `applyWrites` create at a key that already holds a
     * record fails in `MST.add` with a plain `Error("There is already a value at key: …")`
     * (`packages/repo/src/mst/mst.ts:244-246`, reached from `repo.ts:127-130` via
     * `actor-store/repo/transactor.ts:164-167`); the handler converts only `BadCommitSwapError`
     * (`api/com/atproto/repo/applyWrites.ts:172-178`), and xrpc-server turns a plain `Error` into
     * `InternalServerError` (`xrpc-server/src/errors.ts:108-110`) whose message is replaced by
     * "Internal Server Error" (`errors.ts:61-68`). The reference PDS therefore answers **500
     * `InternalServerError`**, indistinguishable from any other server fault.
     *
     * This property only matches servers that do say it: 400 `InvalidRequest` (or no error name)
     * with a message containing "already exists" or "already a value at key". After any failed or
     * unknown create, the caller must look the key up with [BlueskyClient.getRecord] instead of
     * relying on this.
     */
    val isRecordAlreadyExists: Boolean
        get() {
            if (status != 400 || (error != null && error != "InvalidRequest")) return false
            val text = message?.lowercase() ?: return false
            return "already exists" in text || "already a value at key" in text
        }

    override fun toString(): String = "XrpcException(status=$status, error=$error, message=$message)"

    companion object {
        /** [error] of a 2xx answer that is not what the lexicon describes. */
        const val INVALID_RESPONSE = "InvalidResponse"

        private val TRANSIENT_ERRORS = setOf("ServiceOverloaded", "UploadNotReady")
        private const val MAX_ERROR_BODY = 16L * 1024
        private const val MAX_MESSAGE = 500
        private const val REDACTED = "<redacted>"
        private const val MIN_SECRET_LENGTH = 8

        /** Network failures and transient XRPC answers; anything else will fail the same way again. */
        fun isTransient(failure: Throwable): Boolean = when (failure) {
            is XrpcException -> failure.isTransient
            is IOException -> true
            else -> false
        }

        internal fun invalidResponse(status: Int, nsid: String, detail: String) =
            XrpcException(status, INVALID_RESPONSE, "$nsid: $detail", null)

        /**
         * Reads the error answer. Bodies that are not JSON (proxies return HTML) give a null error
         * and message (XRPC spec: "Clients should be robust to non-JSON error responses").
         */
        internal fun from(response: Response, nowMillis: Long, secrets: List<String>): XrpcException {
            val body = try {
                response.peekBody(MAX_ERROR_BODY).string()
            } catch (_: IOException) {
                null
            }
            val fields = body?.let { parseObjectOrNull(it) }
            // The error name is an identifier the classification depends on: it is never rewritten.
            val error = fields?.optString("error")
            val message = fields?.optString("message")?.let { scrub(it, secrets).take(MAX_MESSAGE) }
            return XrpcException(response.code, error, message, rateLimitReset(response, nowMillis))
        }

        /** Replaces echoed credentials. Shorter strings are not credentials and would mangle ordinary words. */
        private fun scrub(text: String, secrets: List<String>): String =
            secrets.filter { it.length >= MIN_SECRET_LENGTH }.fold(text) { acc, secret -> acc.replace(secret, REDACTED) }

        /**
         * The reference PDS sends `RateLimit-Reset` in epoch seconds and, on 429, `Retry-After` in
         * seconds (`xrpc-server/src/rate-limiter-http.ts:48-51`, `:81-86`).
         */
        private fun rateLimitReset(response: Response, nowMillis: Long): Long? {
            response.header("ratelimit-reset")?.trim()?.toLongOrNull()?.let { return it }
            val retryAfter = response.header("retry-after")?.trim() ?: return null
            retryAfter.toLongOrNull()?.let { return nowMillis / 1000 + it }
            return try {
                ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
            } catch (_: RuntimeException) {
                null
            }
        }
    }
}
