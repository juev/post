package org.evsyukov.post.core.mastodon

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class MastodonOAuthTest {
    private val unreserved = Regex("[A-Za-z0-9._~-]+")

    // RFC 7636, Appendix B: the S256 example.
    @Test
    fun challengeMatchesTheRfc7636Example() {
        assertThat(Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
            .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    }

    @Test
    fun verifierHasTheLengthAndAlphabetOfRfc7636AndIsRandom() {
        val verifiers = List(50) { Pkce.verifier() }
        verifiers.forEach {
            assertThat(it.length).isAtLeast(43)
            assertThat(it.length).isAtMost(128)
            assertThat(it).matches(unreserved.pattern)
        }
        assertThat(verifiers.toSet()).hasSize(50)
        // A generated verifier is accepted by the challenge it is used with.
        assertThat(Pkce.challenge(verifiers.first())).hasLength(43)
    }

    @Test
    fun challengeRejectsAVerifierOutsideRfc7636() {
        assertThrows(IllegalArgumentException::class.java) { Pkce.challenge("short") }
        assertThrows(IllegalArgumentException::class.java) { Pkce.challenge("a".repeat(129)) }
        assertThrows(IllegalArgumentException::class.java) { Pkce.challenge("a".repeat(42) + "+") }
    }

    @Test
    fun stateIsRandomAndUrlSafe() {
        val states = List(50) { newState() }
        states.forEach { assertThat(it).matches(unreserved.pattern) }
        assertThat(states.first().length).isAtLeast(32)
        assertThat(states.toSet()).hasSize(50)
    }

    // networks.md N4 and the plan: profile on 4.3+, read:accounts otherwise, read:statuses for N12.
    @Test
    fun scopesUseProfileOnlyWhenTheServerListsIt() {
        val modern = AuthServerMetadata(scopesSupported = listOf("read", "write:statuses", "write:media", "read:statuses", "profile"))
        val withoutProfile = AuthServerMetadata(scopesSupported = listOf("read", "write", "read:accounts"))

        assertThat(scopesFor(modern)).isEqualTo("write:statuses write:media read:statuses profile")
        assertThat(scopesFor(withoutProfile)).isEqualTo("write:statuses write:media read:statuses read:accounts")
        assertThat(scopesFor(null)).isEqualTo("write:statuses write:media read:statuses read:accounts")
    }

    @Test
    fun redirectUriIsTheRegisteredScheme() {
        assertThat(REDIRECT_URI).isEqualTo("org.evsyukov.post://oauth/mastodon")
    }
}
