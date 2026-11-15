package org.evsyukov.post.data.secrets

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The tokens of one account (product.md R4). They are sealed into the `secret` column and are never
 * logged: [toString] names the kind of secret only.
 */
@Serializable
sealed interface AccountSecret

/** A Mastodon user token; it does not expire and has no refresh token (networks.md N5). */
@Serializable
@SerialName("mastodon")
data class MastodonSecret(val accessToken: String) : AccountSecret {
    override fun toString(): String = "MastodonSecret(accessToken=***)"
}

/** The session of `com.atproto.server.createSession`, refreshed with its refresh token (N15). */
@Serializable
@SerialName("bluesky")
data class BlueskySecret(val accessJwt: String, val refreshJwt: String) : AccountSecret {
    override fun toString(): String = "BlueskySecret(accessJwt=***, refreshJwt=***)"
}

/** The `client_secret` of Post's registration on a Mastodon instance (N4), sealed like a token. */
@Serializable
data class MastodonClientSecret(val value: String) {
    override fun toString(): String = "MastodonClientSecret(***)"
}

/**
 * A stored secret cannot be read: the Keystore key is gone or changed, or the bytes are damaged.
 * The caller marks the account as needing sign-in (R5) or registers the app again. The message and the
 * cause never carry the secret: a decoding error, whose message may quote its input, is not attached.
 */
class SecretUnreadable(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Seals secrets into the bytes stored in BLOB columns, and opens them again. The calls block: with the
 * Keystore cipher each one goes over binder, and the first one makes the key. The repositories call it
 * off the caller's thread.
 */
class SecretStore(private val cipher: TokenCipher) {
    fun seal(secret: AccountSecret): ByteArray = seal(AccountSecret.serializer(), secret)

    /** @throws SecretUnreadable when [sealed] does not open. */
    fun unsealAccountSecret(sealed: ByteArray): AccountSecret = unseal(AccountSecret.serializer(), sealed)

    fun seal(secret: MastodonClientSecret): ByteArray = seal(MastodonClientSecret.serializer(), secret)

    /** @throws SecretUnreadable when [sealed] does not open. */
    fun unsealClientSecret(sealed: ByteArray): MastodonClientSecret = unseal(MastodonClientSecret.serializer(), sealed)

    private fun <T> seal(serializer: KSerializer<T>, value: T): ByteArray {
        val plain = SecretJson.encodeToString(serializer, value).encodeToByteArray()
        try {
            return cipher.encrypt(plain)
        } finally {
            plain.fill(0)
        }
    }

    private fun <T> unseal(serializer: KSerializer<T>, sealed: ByteArray): T {
        val plain = try {
            cipher.decrypt(sealed)
        } catch (e: Exception) {
            throw SecretUnreadable("the stored secret does not decrypt", e)
        }
        try {
            return SecretJson.decodeFromString(serializer, plain.decodeToString())
        } catch (_: IllegalArgumentException) {
            // SerializationException is one; its message quotes the JSON, so it is dropped here.
            throw SecretUnreadable("the stored secret does not decode")
        } finally {
            plain.fill(0)
        }
    }

    private companion object {
        val SecretJson = Json { ignoreUnknownKeys = true }
    }
}
