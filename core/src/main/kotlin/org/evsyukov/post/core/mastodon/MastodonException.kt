package org.evsyukov.post.core.mastodon

import java.io.IOException

/**
 * A failed call to a Mastodon API server. Messages never carry tokens, client secrets,
 * authorization codes or PKCE verifiers: the client removes them from server text before
 * it builds an exception (product.md R4).
 *
 * The classification helpers map to the delivery rules of product.md R5 and R29:
 * [isUnauthorized] marks the account "needs sign-in", [isRateLimited] and [isTransient]
 * are retried, the rest stop the delivery with the server's message.
 */
sealed class MastodonException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /**
     * The server answered with a non-success status.
     *
     * @property error the `error` field of the JSON body (a code such as `invalid_client`
     *   on OAuth endpoints, a human-readable message elsewhere), or null for a non-JSON body.
     * @property description the `error_description` field, mostly sent by OAuth endpoints.
     * @property retryAfterSeconds seconds to wait, from `Retry-After` or else `X-RateLimit-Reset`.
     * @property authError the `error` parameter of `WWW-Authenticate` (RFC 6750 §3), such as
     *   `invalid_token` or `insufficient_scope`.
     */
    class Http(
        val status: Int,
        val error: String?,
        val description: String?,
        val retryAfterSeconds: Long?,
        val authError: String? = null,
    ) : MastodonException(httpMessage(status, error, description))

    /** No usable answer: no connection, a timeout, a dropped connection or a cancelled call. */
    class Network(cause: IOException, message: String) : MastodonException(message, cause)

    /**
     * A success status with a body that is not what the API documents. Not part of the
     * requested API shape: without it a broken server answer would surface as an unchecked
     * serialization exception, so it is reported as its own case.
     */
    class Malformed(message: String) : MastodonException(message)

    /** 401, or 403 whose error is `invalid_token`. A 401 `invalid_client` is [isInvalidClient] instead. */
    val isUnauthorized: Boolean
        get() = this is Http && !isInvalidClient &&
            (status == 401 || (status == 403 && (error == INVALID_TOKEN || authError == INVALID_TOKEN)))

    /** 429. [Http.retryAfterSeconds] says when the limit resets, when the server tells. */
    val isRateLimited: Boolean get() = this is Http && status == 429

    /** 5xx, 408 or a network failure. 429 is reported by [isRateLimited], not here. */
    val isTransient: Boolean get() = this is Network || (this is Http && (status == 408 || status in 500..599))

    /** 422: the server refused the content, such as a too long text or an unsupported file. */
    val isValidation: Boolean get() = this is Http && status == 422

    /** 404: no such record, or a server without the method. */
    val isNotFound: Boolean get() = this is Http && status == 404

    /**
     * 401 with error `invalid_client` (`POST /oauth/token`): the server no longer knows the app
     * registration, which servers before 4.3 could remove, so the app registers again.
     */
    val isInvalidClient: Boolean get() = this is Http && status == 401 && error == INVALID_CLIENT

    private companion object {
        const val INVALID_TOKEN = "invalid_token"
        const val INVALID_CLIENT = "invalid_client"

        fun httpMessage(status: Int, error: String?, description: String?): String =
            listOfNotNull("HTTP $status", error, description).joinToString(": ")
    }
}
