package org.evsyukov.post.core.mastodon

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Where the browser returns after authorization (networks.md N4). */
const val REDIRECT_URI = "org.evsyukov.post://oauth/mastodon"

private const val SCOPES_BASE = "write:statuses write:media read:statuses"

private val random = SecureRandom()
private val base64Url = Base64.getUrlEncoder().withoutPadding()

/** PKCE with S256 (RFC 7636), used on every sign-in (plan: Mastodon OAuth). */
object Pkce {
    /**
     * A new code verifier: 32 random bytes as base64url without padding, 43 characters of the
     * unreserved alphabet, as RFC 7636 §4.1 recommends.
     */
    fun verifier(): String = randomToken()

    /** `BASE64URL(SHA256(ASCII(verifier)))` without padding (RFC 7636 §4.2). */
    fun challenge(verifier: String): String {
        require(verifier.length in 43..128 && verifier.all { it in UNRESERVED }) {
            "A code verifier has 43 to 128 unreserved characters"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64Url.encodeToString(digest)
    }

    private val UNRESERVED: Set<Char> = (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')).toSet()
}

/** A new OAuth `state`: 32 random bytes as base64url, compared with the one the redirect brings back. */
fun newState(): String = randomToken()

/**
 * Scopes Post asks for. `profile` (Mastodon 4.3+) is enough for verify_credentials; older servers
 * and forks that do not list it in the server metadata get `read:accounts` instead (networks.md N4).
 * `read:statuses` lets Post look for a status whose creation answer was lost (N12).
 */
fun scopesFor(meta: AuthServerMetadata?): String =
    if (meta != null && "profile" in meta.scopesSupported) "$SCOPES_BASE profile" else "$SCOPES_BASE read:accounts"

private fun randomToken(): String {
    val bytes = ByteArray(32)
    random.nextBytes(bytes)
    return base64Url.encodeToString(bytes)
}
