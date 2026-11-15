package org.evsyukov.post.send.bluesky

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * What a link card shows (networks.md N23): the page's title and description, and [thumb], a JPEG of
 * at most [LinkCardFetcher.MAX_THUMB_BYTES], or null when the page names no image.
 */
class LinkCard(val uri: String, val title: String, val description: String, val thumb: ByteArray?)

/** Builds the card of a link, or gives null; it never throws except for cancellation. */
fun interface LinkCardSource {
    suspend fun fetch(uri: String): LinkCard?
}

/**
 * Fetches a link card itself (product.md R14, networks.md N23). Only the linked page and the image it
 * names are requested (R34): an HTML page of at most [MAX_PAGE_BYTES], redirects followed, with short
 * timeouts; `og:title`, `og:description` and `og:image`, or the page's `<title>` and description meta.
 * The image is made a JPEG of at most [MAX_THUMB_BYTES] by [thumbnail]. Any failure gives no card.
 */
class LinkCardFetcher(
    http: OkHttpClient,
    private val thumbnail: (ByteArray) -> ByteArray? = AndroidThumbnails::jpeg,
) : LinkCardSource {
    private val client = http.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun fetch(uri: String): LinkCard? = try {
        withContext(Dispatchers.IO) { build(uri) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun build(uri: String): LinkCard? {
        val url = uri.toHttpUrlOrNull() ?: return null
        val page = Request.Builder().url(url).header("Accept", "text/html,application/xhtml+xml").get().build()
        val document = client.newCall(page).execute().use { response ->
            if (!response.isSuccessful) return null
            val type = response.body.contentType()
            val subtype = "${type?.type}/${type?.subtype}".lowercase()
            if (subtype != "text/html" && subtype != "application/xhtml+xml") return null
            val bytes = read(response, MAX_PAGE_BYTES, truncate = true) ?: return null
            Jsoup.parse(ByteArrayInputStream(bytes), type?.charset()?.name(), response.request.url.toString())
        }
        val title = meta(document, "og:title") ?: document.title().trim().takeIf { it.isNotEmpty() }
        val description = meta(document, "og:description") ?: meta(document, "description")
        if (title == null && description == null) return null
        val thumb = imageUrl(document)?.let { image ->
            val bytes = fetchImage(image) ?: return null
            thumbnail(bytes)?.takeIf { it.size <= MAX_THUMB_BYTES } ?: return null
        }
        return LinkCard(uri, title.orEmpty().take(MAX_TITLE), description.orEmpty().take(MAX_DESCRIPTION), thumb)
    }

    private fun fetchImage(url: HttpUrl): ByteArray? {
        val request = Request.Builder().url(url).header("Accept", "image/*").get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            if (response.body.contentType()?.type?.lowercase() != "image") return null
            read(response, MAX_IMAGE_BYTES, truncate = false)
        }
    }

    private fun imageUrl(document: Document): HttpUrl? {
        val element = document.selectFirst("meta[property=og:image], meta[name=og:image]") ?: return null
        val absolute = element.absUrl("content").takeIf { it.isNotEmpty() } ?: return null
        return absolute.toHttpUrlOrNull()
    }

    private fun meta(document: Document, name: String): String? =
        document.select("meta[property=$name], meta[name=$name]")
            .firstNotNullOfOrNull { it.attr("content").trim().takeIf { value -> value.isNotEmpty() } }

    companion object {
        /** About 1 MB of a page is read; the head with the meta tags comes first. */
        const val MAX_PAGE_BYTES = 1024L * 1024

        /** A larger preview image is not downloaded. */
        const val MAX_IMAGE_BYTES = 10L * 1024 * 1024

        /** `app.bsky.embed.external#external.thumb` `maxSize` (N23). */
        const val MAX_THUMB_BYTES = 1_000_000

        const val MAX_TITLE = 300
        const val MAX_DESCRIPTION = 1000
        private const val TIMEOUT_SECONDS = 5L
        private const val CALL_TIMEOUT_SECONDS = 15L

        /** At most [limit] bytes of the body; more is cut off when [truncate], otherwise null. */
        private fun read(response: Response, limit: Long, truncate: Boolean): ByteArray? {
            val source = response.body.source()
            source.request(limit + 1)
            val size = source.buffer.size
            if (size > limit && !truncate) return null
            return source.buffer.readByteArray(minOf(size, limit))
        }
    }
}

/** A preview image as a JPEG for a link card: at most [MAX_SIDE] px on the long side and 1 MB. */
object AndroidThumbnails {
    const val MAX_SIDE = 1000
    private const val FIRST_QUALITY = 85
    private const val MIN_QUALITY = 40

    fun jpeg(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = MAX_SIDE.toFloat() / max(decoded.width, decoded.height)
        val width = if (scale < 1f) (decoded.width * scale).roundToInt().coerceAtLeast(1) else decoded.width
        val height = if (scale < 1f) (decoded.height * scale).roundToInt().coerceAtLeast(1) else decoded.height
        // JPEG has no alpha: a transparent image goes on white, not black.
        val flat = createBitmap(width, height)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(decoded.scale(width, height), 0f, 0f, null)
        }
        var quality = FIRST_QUALITY
        while (true) {
            val out = ByteArrayOutputStream()
            if (!flat.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw IOException("cannot encode the preview")
            if (out.size() <= LinkCardFetcher.MAX_THUMB_BYTES || quality <= MIN_QUALITY) {
                return out.toByteArray().takeIf { it.size <= LinkCardFetcher.MAX_THUMB_BYTES }
            }
            quality -= 15
        }
    }
}
