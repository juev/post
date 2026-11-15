package org.evsyukov.post.accounts

import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Every host on one MockWebServer: the client rewrites each request to the server and keeps the
 * original URL in a header, so tests use real-looking HTTPS hosts and see which host got what.
 * Routes are keyed `"METHOD host/path"`; an unrouted request gets 404.
 */
class FakeServers : Closeable {
    private val server = MockWebServer()
    private val routes = java.util.concurrent.ConcurrentHashMap<String, (Seen) -> MockResponse>()
    val seen = CopyOnWriteArrayList<Seen>()

    /** A request as the app sent it. */
    class Seen(val url: HttpUrl, val method: String, val headers: okhttp3.Headers, val body: String) {
        val host: String get() = url.host
        val path: String get() = url.encodedPath

        fun form(name: String): String? = body.split('&').map { it.split('=', limit = 2) }
            .firstOrNull { it[0] == name }?.getOrNull(1)?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8) }
    }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val original = request.headers[ORIGINAL]!!.toHttpUrl()
                val item = Seen(original, request.method, request.headers, request.body?.utf8().orEmpty())
                seen += item
                val route = routes["${request.method} ${original.host}${original.encodedPath}"]
                return route?.invoke(item) ?: MockResponse.Builder().code(404).body("""{"error":"not found"}""").build()
            }
        }
        server.start()
    }

    val http: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val original = chain.request()
        val target = server.url(original.url.encodedPath).newBuilder().encodedQuery(original.url.encodedQuery).build()
        chain.proceed(original.newBuilder().url(target).header(ORIGINAL, original.url.toString()).build())
    }.build()

    fun route(method: String, hostAndPath: String, response: (Seen) -> MockResponse) {
        routes["$method $hostAndPath"] = response
    }

    fun json(method: String, hostAndPath: String, body: String, code: Int = 200) = route(method, hostAndPath) { json(body, code) }

    fun requests(hostAndPath: String): List<Seen> = seen.filter { "${it.host}${it.path}" == hostAndPath }

    override fun close() = server.close()

    companion object {
        private const val ORIGINAL = "X-Original-Url"

        fun json(body: String, code: Int = 200): MockResponse =
            MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
    }
}
