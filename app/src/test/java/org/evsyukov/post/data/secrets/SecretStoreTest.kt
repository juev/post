package org.evsyukov.post.data.secrets

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.FakeCipher
import org.evsyukov.post.data.containsBytes
import org.evsyukov.post.data.db.AccountEntity
import org.evsyukov.post.data.db.AccountState
import org.evsyukov.post.data.db.MastodonAppEntity
import org.junit.Assert.assertThrows
import org.junit.Test

/** R4: secrets are sealed by the cipher, open again, fail as SecretUnreadable and are never printed. */
class SecretStoreTest {
    private val cipher = FakeCipher()
    private val store = SecretStore(cipher)

    @Test
    fun accountSecretsOfBothNetworksOpenAsTheyWereSealed() {
        val mastodon = MastodonSecret("mastodon-token")
        val bluesky = BlueskySecret("access.jwt.value", "refresh.jwt.value")

        assertThat(store.unsealAccountSecret(store.seal(mastodon))).isEqualTo(mastodon)
        assertThat(store.unsealAccountSecret(store.seal(bluesky))).isEqualTo(bluesky)
        assertThat(store.unsealClientSecret(store.seal(MastodonClientSecret("client-secret")))).isEqualTo(MastodonClientSecret("client-secret"))
    }

    @Test
    fun sealedBytesComeFromTheCipherAndHoldNoPlaintext() {
        val sealed = store.seal(BlueskySecret("access.jwt.value", "refresh.jwt.value"))

        assertThat(sealed.containsBytes("access.jwt.value".toByteArray())).isFalse()
        assertThat(sealed.containsBytes("refresh.jwt.value".toByteArray())).isFalse()
        // The store hands the whole JSON to the cipher, not a copy of the tokens next to it.
        assertThat(cipher.decrypt(sealed).decodeToString()).contains("refresh.jwt.value")
    }

    @Test
    fun bytesThatDoNotDecryptAreSecretUnreadable() {
        for (garbage in listOf(ByteArray(0), byteArrayOf(1, 2, 3), ByteArray(64) { it.toByte() })) {
            assertThrows(SecretUnreadable::class.java) { store.unsealAccountSecret(garbage) }
            assertThrows(SecretUnreadable::class.java) { store.unsealClientSecret(garbage) }
        }
    }

    @Test
    fun aDecryptedValueThatIsNotASecretIsUnreadableAndItsTextIsNotInTheError() {
        // Decrypts, but is not the JSON of a secret: a damaged or foreign value with a token in it.
        val notJson = cipher.encrypt("""{"type":"mastodon","accessToken":"leaked-token""".toByteArray())
        val wrongType = cipher.encrypt("""{"type":"nostr","key":"leaked-token"}""".toByteArray())

        for (sealed in listOf(notJson, wrongType)) {
            val error = assertThrows(SecretUnreadable::class.java) { store.unsealAccountSecret(sealed) }
            var cause: Throwable? = error
            while (cause != null) {
                assertThat(cause.message.orEmpty()).doesNotContain("leaked-token")
                assertThat(cause.toString()).doesNotContain("leaked-token")
                cause = cause.cause
            }
        }
    }

    @Test
    fun aMastodonSecretDoesNotOpenAsAClientSecretAndTheOtherWayRound() {
        assertThrows(SecretUnreadable::class.java) { store.unsealClientSecret(store.seal(MastodonSecret("t"))) }
        assertThrows(SecretUnreadable::class.java) { store.unsealAccountSecret(store.seal(MastodonClientSecret("s"))) }
    }

    @Test
    fun secretsAreNeverPrinted() {
        val printed = listOf(
            MastodonSecret("mastodon-token").toString(),
            BlueskySecret("access-jwt", "refresh-jwt").toString(),
            MastodonClientSecret("client-secret").toString(),
            AccountEntity(
                network = Network.MASTODON,
                server = "https://mastodon.social",
                handle = "me",
                remoteId = "1",
                displayName = "Me",
                avatarUrl = null,
                label = "",
                position = 0,
                state = AccountState.OK,
                limitsJson = null,
                limitsFetchedAt = null,
                defaultsJson = "{}",
                secret = "mastodon-token".toByteArray(),
                createdAt = 0,
            ).toString(),
            MastodonAppEntity("https://mastodon.social", "id", "client-secret".toByteArray(), "uri", "scopes", 0).toString(),
        )

        for (text in printed) {
            for (secret in listOf("mastodon-token", "access-jwt", "refresh-jwt", "client-secret")) {
                assertThat(text).doesNotContain(secret)
            }
        }
        // The entity prints no bytes of the blob either.
        assertThat(printed[3]).doesNotContain("[B@")
    }
}
