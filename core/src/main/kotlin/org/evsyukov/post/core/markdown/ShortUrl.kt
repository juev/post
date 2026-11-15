package org.evsyukov.post.core.markdown

import java.net.IDN

/**
 * The address of [url] shortened for display, as social-app shortens it (`toShortUrl` in
 * `src/lib/strings/url-helpers.ts` of `bluesky-social/social-app` at commit
 * `9747031e9b73e33d671f3db94dfc0e917bd7864c`):
 *
 * ```ts
 * const urlp = new URL(url)
 * if (urlp.protocol !== 'http:' && urlp.protocol !== 'https:') return url
 * const path = (urlp.pathname === '/' ? '' : urlp.pathname) + urlp.search + urlp.hash
 * if (path.length > 15) return urlp.host + path.slice(0, 13) + '...'
 * return urlp.host + path
 * ```
 *
 * The scheme and user info go; the host is lowercased and IDN hosts become punycode; a default port goes;
 * the path, query and fragment are percent-encoded where the WHATWG URL parser encodes them; an empty
 * query or fragment goes. Not reproduced: dot-segment removal and IPv4 number normalization. A URL that is
 * not `http` or `https`, or does not parse, is returned unchanged.
 */
internal fun toShortUrl(url: String): String {
    val schemeLength = when {
        url.startsWith("https://", ignoreCase = true) -> 8
        url.startsWith("http://", ignoreCase = true) -> 7
        else -> return url
    }
    val https = schemeLength == 8
    val rest = url.substring(schemeLength)
    val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' || it == '\\' }.let { if (it < 0) rest.length else it }
    val hostAndPort = rest.substring(0, authorityEnd).substringAfterLast('@')
    val host: String
    val port: String
    if (hostAndPort.startsWith("[")) {
        val close = hostAndPort.indexOf(']')
        if (close < 0) return url
        host = hostAndPort.substring(0, close + 1).lowercase()
        port = hostAndPort.substring(close + 1).removePrefix(":")
    } else {
        host = hostAndPort.substringBefore(':')
        port = hostAndPort.substringAfter(':', "")
    }
    val asciiHost = if (host.startsWith("[")) {
        host
    } else {
        if (host.isEmpty() || host.any { it in FORBIDDEN_HOST_CHARS || it.isWhitespace() || it.isISOControl() }) return url
        try {
            IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).lowercase()
        } catch (e: IllegalArgumentException) {
            return url
        }
    }
    if (asciiHost.isEmpty()) return url
    val portSuffix = when {
        port.isEmpty() -> ""
        !port.all { it in '0'..'9' } -> return url
        else -> {
            val number = port.trimStart('0').ifEmpty { "0" }
            if (number.length > 5 || number.toInt() > 65535) return url
            if ((https && number == "443") || (!https && number == "80")) "" else ":$number"
        }
    }

    val afterAuthority = rest.substring(authorityEnd)
    val hashIndex = afterAuthority.indexOf('#')
    val beforeHash = if (hashIndex < 0) afterAuthority else afterAuthority.substring(0, hashIndex)
    val queryIndex = beforeHash.indexOf('?')
    val rawPath = (if (queryIndex < 0) beforeHash else beforeHash.substring(0, queryIndex)).replace('\\', '/')
    val pathname = percentEncode(rawPath.ifEmpty { "/" }, PATH_ENCODE)
    val query = if (queryIndex < 0) "" else beforeHash.substring(queryIndex + 1)
    val fragment = if (hashIndex < 0) "" else afterAuthority.substring(hashIndex + 1)
    val search = if (query.isEmpty()) "" else "?" + percentEncode(query, QUERY_ENCODE)
    val hash = if (fragment.isEmpty()) "" else "#" + percentEncode(fragment, FRAGMENT_ENCODE)

    val path = (if (pathname == "/") "" else pathname) + search + hash
    val shownHost = asciiHost + portSuffix
    return if (path.length > 15) shownHost + path.substring(0, 13) + "..." else shownHost + path
}

// WHATWG URL: forbidden host code points, and the percent-encode sets of the path, special query and fragment.
private const val FORBIDDEN_HOST_CHARS = "#%/:<>?@[\\]^|"
private const val PATH_ENCODE = " \"#<>?`{}"
private const val QUERY_ENCODE = " \"#<>'"
private const val FRAGMENT_ENCODE = " \"<>`"

private fun percentEncode(value: String, set: String): String {
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val cp = value.codePointAt(i)
        val length = Character.charCount(cp)
        if (cp < 0x20 || cp >= 0x7F || set.indexOf(cp.toChar()) >= 0 && cp < 0x80) {
            for (byte in value.substring(i, i + length).toByteArray(Charsets.UTF_8)) {
                out.append('%').append(HEX[(byte.toInt() shr 4) and 0xF]).append(HEX[byte.toInt() and 0xF])
            }
        } else {
            out.appendCodePoint(cp)
        }
        i += length
    }
    return out.toString()
}

private const val HEX = "0123456789ABCDEF"
