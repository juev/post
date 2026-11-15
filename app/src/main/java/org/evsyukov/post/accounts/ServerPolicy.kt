package org.evsyukov.post.accounts

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Which servers an account may live on. Release builds take HTTPS servers only. Debug builds also take
 * plain HTTP to `localhost` and to `10.0.2.2` (the host as the emulator sees it), with any port, so
 * instrumented tests and the end-to-end run reach local fake servers; the debug network security
 * config allows cleartext to exactly these hosts.
 */
class ServerPolicy(private val allowLocalHttp: Boolean) {
    fun accepts(url: HttpUrl): Boolean = when (url.scheme) {
        "https" -> true
        "http" -> allowLocalHttp && url.host in LOCAL_HOSTS
        else -> false
    }

    companion object {
        val LOCAL_HOSTS = setOf("localhost", "10.0.2.2")
    }
}

/** The origin of [url]: scheme, host and a port other than the default, without a trailing slash. */
fun originOf(url: HttpUrl): String {
    val defaultPort = HttpUrl.defaultPort(url.scheme)
    val port = if (url.port == defaultPort) "" else ":${url.port}"
    return "${url.scheme}://${url.host}$port"
}

/** The root URL of an [origin] produced by [originOf]. */
fun rootOf(origin: String): HttpUrl = "$origin/".toHttpUrlOrNull() ?: throw IllegalArgumentException("not an origin")

/**
 * The instance a user typed in Add Mastodon account (screens.md): `mastodon.social`,
 * `https://mastodon.social/`, `@user@mastodon.social`, `user@mastodon.social` or an address with an
 * internationalized host, which becomes its ASCII (punycode) form. A path after the host is ignored.
 */
object InstanceAddress {
    /**
     * The root URL of the instance, or null when [input] names no host or the server is not allowed by
     * [policy] (plain HTTP in a release build, for example).
     */
    fun parse(input: String, policy: ServerPolicy): HttpUrl? {
        var rest = input.trim()
        if (rest.isEmpty() || rest.any { it.isWhitespace() }) return null
        val scheme = when {
            rest.startsWith("https://", ignoreCase = true) -> "https"
            rest.startsWith("http://", ignoreCase = true) -> "http"
            "://" in rest -> return null
            else -> "https"
        }
        rest = rest.substringAfter("://").removePrefix("@")
        val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        if (authority.isEmpty()) return null
        val url = "$scheme://$authority/".toHttpUrlOrNull() ?: return null
        if (!url.host.contains('.') && url.host !in ServerPolicy.LOCAL_HOSTS) return null
        if (!policy.accepts(url)) return null
        return url
    }
}
