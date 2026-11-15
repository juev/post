package org.evsyukov.post.send.bluesky

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** The link card fetcher against a local web server (product.md R14, R34; networks.md N23). */
class LinkCardFetcherTest {
    private val server = MockWebServer()
    private val routes = HashMap<String, MockResponse>()
    private val seen = CopyOnWriteArrayList<String>()
    private val thumbnails = CopyOnWriteArrayList<Int>()
    private val fetcher = LinkCardFetcher(OkHttpClient()) { bytes ->
        thumbnails += bytes.size
        ByteArray(10)
    }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request.url.encodedPath
                return routes[request.url.encodedPath] ?: MockResponse.Builder().code(404).build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun html(body: String, type: String = "text/html; charset=utf-8") =
        MockResponse.Builder().code(200).addHeader("Content-Type", type).body(body).build()

    private fun image(size: Int, type: String = "image/png") =
        MockResponse.Builder().code(200).addHeader("Content-Type", type).body(Buffer().write(ByteArray(size))).build()

    private fun fetch(path: String) = runBlocking { fetcher.fetch(server.url(path).toString()) }

    @Test
    fun openGraphTagsAndARelativeImageAfterARedirect() {
        routes["/short"] = MockResponse.Builder().code(301).addHeader("Location", "/article/one").build()
        routes["/article/one"] = html(
            """<html><head><title>Plain</title><meta property="og:title" content="OG title">
            <meta property="og:description" content="OG description"><meta property="og:image" content="../img/cover.png"></head></html>""",
        )
        routes["/img/cover.png"] = image(500)

        val card = fetch("/short")!!

        assertThat(card.uri).isEqualTo(server.url("/short").toString())
        assertThat(card.title).isEqualTo("OG title")
        assertThat(card.description).isEqualTo("OG description")
        assertThat(card.thumb).hasLength(10)
        assertThat(thumbnails).containsExactly(500)
        assertThat(seen).containsExactly("/short", "/article/one", "/img/cover.png").inOrder()
    }

    @Test
    fun theTitleAndDescriptionMetaStandInWithoutOpenGraph() {
        routes["/page"] = html("""<html><head><title> Page title </title><meta name="description" content="About it"></head></html>""")

        val card = fetch("/page")!!

        assertThat(card.title).isEqualTo("Page title")
        assertThat(card.description).isEqualTo("About it")
        assertThat(card.thumb).isNull()
        assertThat(seen).containsExactly("/page")
    }

    @Test
    fun aPageThatIsNotHtmlGivesNoCard() {
        routes["/file"] = html("%PDF-1.7", type = "application/pdf")

        assertThat(fetch("/file")).isNull()
    }

    @Test
    fun anErrorPageGivesNoCard() {
        routes["/gone"] = MockResponse.Builder().code(404).addHeader("Content-Type", "text/html").body("<title>Not found</title>").build()

        assertThat(fetch("/gone")).isNull()
    }

    @Test
    fun onlyTheFirstMegabyteOfAPageIsRead() {
        val head = """<html><head><meta property="og:title" content="Long"></head><body>"""
        routes["/long"] = html(head + "x".repeat(3 * 1024 * 1024) + """<meta property="og:description" content="late"></body></html>""")

        val card = fetch("/long")!!

        assertThat(card.title).isEqualTo("Long")
        assertThat(card.description).isEmpty()
    }

    @Test
    fun anImageThatCannotBeFetchedGivesNoCard() {
        routes["/page"] = html("""<html><head><meta property="og:title" content="T"><meta property="og:image" content="/missing.png"></head></html>""")

        assertThat(fetch("/page")).isNull()
    }

    @Test
    fun anImageThatIsNotAnImageGivesNoCard() {
        routes["/page"] = html("""<html><head><meta property="og:title" content="T"><meta property="og:image" content="/fake.png"></head></html>""")
        routes["/fake.png"] = html("<html></html>")

        assertThat(fetch("/page")).isNull()
        assertThat(thumbnails).isEmpty()
    }

    @Test
    fun aThumbnailOverOneMegabyteGivesNoCard() {
        val big = LinkCardFetcher(OkHttpClient()) { ByteArray(LinkCardFetcher.MAX_THUMB_BYTES + 1) }
        routes["/page"] = html("""<html><head><meta property="og:title" content="T"><meta property="og:image" content="/i.png"></head></html>""")
        routes["/i.png"] = image(100)

        assertThat(runBlocking { big.fetch(server.url("/page").toString()) }).isNull()
    }

    @Test
    fun aLinkThatIsNotHttpGivesNoCardWithoutARequest() {
        assertThat(runBlocking { fetcher.fetch("mailto:someone@example.com") }).isNull()
        assertThat(seen).isEmpty()
    }
}
