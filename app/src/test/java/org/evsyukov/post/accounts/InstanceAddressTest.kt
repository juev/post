package org.evsyukov.post.accounts

import com.google.common.truth.Truth.assertThat
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

/** screens.md, Add Mastodon account: the forms of an instance address users type; HTTPS only (R4). */
class InstanceAddressTest {
    private val release = ServerPolicy(allowLocalHttp = false)
    private val debug = ServerPolicy(allowLocalHttp = true)

    private fun origin(input: String, policy: ServerPolicy = release) = InstanceAddress.parse(input, policy)?.let(::originOf)

    @Test
    fun aBareHostAnAddressAndAHandleAllNameTheInstance() {
        assertThat(origin("mastodon.social")).isEqualTo("https://mastodon.social")
        assertThat(origin("https://mastodon.social/")).isEqualTo("https://mastodon.social")
        assertThat(origin("  HTTPS://Mastodon.Social  ")).isEqualTo("https://mastodon.social")
        assertThat(origin("@user@mastodon.social")).isEqualTo("https://mastodon.social")
        assertThat(origin("user@mastodon.social")).isEqualTo("https://mastodon.social")
        assertThat(origin("https://mastodon.social/@user")).isEqualTo("https://mastodon.social")
        assertThat(origin("mastodon.example:8443")).isEqualTo("https://mastodon.example:8443")
    }

    @Test
    fun anInternationalizedHostBecomesItsAsciiForm() {
        assertThat(origin("пример.рф")).isEqualTo("https://xn--e1afmkfd.xn--p1ai")
        assertThat(origin("@кто@пример.рф")).isEqualTo("https://xn--e1afmkfd.xn--p1ai")
        assertThat(origin("https://bücher.example/")).isEqualTo("https://xn--bcher-kva.example")
    }

    @Test
    fun plainHttpIsRefusedInARelease() {
        assertThat(origin("http://mastodon.social")).isNull()
        assertThat(origin("http://localhost:3000")).isNull()
        assertThat(origin("http://10.0.2.2:3000")).isNull()
    }

    @Test
    fun aDebugBuildReachesLocalTestServersOverHttpOnly() {
        assertThat(origin("http://localhost:3000", debug)).isEqualTo("http://localhost:3000")
        assertThat(origin("http://10.0.2.2:8080", debug)).isEqualTo("http://10.0.2.2:8080")
        assertThat(origin("http://mastodon.social", debug)).isNull()
        assertThat(origin("http://192.168.1.2:3000", debug)).isNull()
    }

    @Test
    fun textThatNamesNoServerIsRefused() {
        assertThat(origin("")).isNull()
        assertThat(origin("   ")).isNull()
        assertThat(origin("mastodon")).isNull()
        assertThat(origin("@user")).isNull()
        assertThat(origin("mastodon .social")).isNull()
        assertThat(origin("ftp://mastodon.social")).isNull()
        assertThat(origin("https://")).isNull()
    }

    @Test
    fun theServerPolicyTakesHttpsAndLocalHttpOnlyInDebug() {
        assertThat(release.accepts("https://pds.example/".toHttpUrl())).isTrue()
        assertThat(release.accepts("http://localhost:2583/".toHttpUrl())).isFalse()
        assertThat(debug.accepts("http://localhost:2583/".toHttpUrl())).isTrue()
        assertThat(debug.accepts("http://pds.example/".toHttpUrl())).isFalse()
    }
}
