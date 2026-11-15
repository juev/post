package org.evsyukov.post.data.secrets

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

/** R4 on a device: AES-256-GCM with a key in the Android Keystore. */
@RunWith(AndroidJUnit4::class)
class KeystoreTokenCipherTest {
    private val cipher = KeystoreTokenCipher(ALIAS)
    private val plain = "access-token-ünïcødé-✓".toByteArray()

    @After
    fun deleteKeys() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.deleteEntry(ALIAS)
        store.deleteEntry(OTHER_ALIAS)
    }

    @Test
    fun aSealedSecretOpensAgainAlsoWithANewInstanceOfTheSameKey() {
        val sealed = cipher.encrypt(plain)

        assertThat(cipher.decrypt(sealed)).isEqualTo(plain)
        // The key lives in the Keystore, not in the object: a new process opens what an old one sealed.
        assertThat(KeystoreTokenCipher(ALIAS).decrypt(sealed)).isEqualTo(plain)
    }

    @Test
    fun theCiphertextDiffersFromThePlaintextAndBetweenTwoEncryptions() {
        val first = cipher.encrypt(plain)
        val second = cipher.encrypt(plain)

        // A random 12-byte IV, then the ciphertext and a 16-byte tag.
        assertThat(first.size).isEqualTo(IV_BYTES + plain.size + TAG_BYTES)
        assertThat(first).isNotEqualTo(second)
        assertThat(first.copyOfRange(0, IV_BYTES)).isNotEqualTo(second.copyOfRange(0, IV_BYTES))
        assertThat(contains(first, plain)).isFalse()
        assertThat(contains(first, "access-token".toByteArray())).isFalse()
    }

    @Test
    fun aTamperedOrTruncatedCiphertextDoesNotDecrypt() {
        val sealed = cipher.encrypt(plain)

        for (index in listOf(0, IV_BYTES, IV_BYTES + plain.size / 2, sealed.lastIndex)) {
            val tampered = sealed.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            assertThrows("byte $index changed", Exception::class.java) { cipher.decrypt(tampered) }
        }
        assertThrows(Exception::class.java) { cipher.decrypt(sealed.copyOf(sealed.size - 1)) }
        assertThrows(Exception::class.java) { cipher.decrypt(sealed.copyOf(IV_BYTES)) }
        // The original still opens: nothing above changed the key.
        assertThat(cipher.decrypt(sealed)).isEqualTo(plain)
    }

    @Test
    fun anotherKeyCannotOpenTheSecret() {
        val sealed = cipher.encrypt(plain)

        assertThrows(Exception::class.java) { KeystoreTokenCipher(OTHER_ALIAS).decrypt(sealed) }
    }

    @Test
    fun theSecretStoreOverTheKeystoreReportsADamagedSecretAsUnreadable() {
        val store = SecretStore(cipher)
        val sealed = store.seal(BlueskySecret("access.jwt", "refresh.jwt"))

        assertThat(store.unsealAccountSecret(sealed)).isEqualTo(BlueskySecret("access.jwt", "refresh.jwt"))
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 0x01).toByte()
        assertThrows(SecretUnreadable::class.java) { store.unsealAccountSecret(sealed) }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { start -> needle.indices.all { haystack[start + it] == needle[it] } }

    private companion object {
        const val ALIAS = "post-secrets-test"
        const val OTHER_ALIAS = "post-secrets-test-other"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}
