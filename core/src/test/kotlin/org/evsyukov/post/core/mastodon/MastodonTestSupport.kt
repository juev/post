package org.evsyukov.post.core.mastodon

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartReader
import okio.Buffer
import java.net.URLDecoder

/** A JSON answer with the given status. */
internal fun json(body: String, code: Int = 200): MockResponse =
    MockResponse.Builder().code(code).setHeader("Content-Type", "application/json; charset=utf-8").body(body).build()

/** An HTML answer with the given status. */
internal fun html(body: String, code: Int = 200): MockResponse =
    MockResponse.Builder().code(code).setHeader("Content-Type", "text/html; charset=utf-8").body(body).build()

/** The start of the web app page Akkoma serves for unknown paths (RedirectController, index.html). */
internal val AKKOMA_INDEX_HTML = """
    <!doctype html><html lang="en"><head><meta charset="utf-8"><title>Akkoma</title></head>
    <body><noscript>To use Akkoma, please enable JavaScript.</noscript><div id="app"></div></body></html>
""".trimIndent()

internal fun fixture(name: String): String =
    requireNotNull(MastodonCapabilitiesTest::class.java.getResource("/mastodon/$name")) { "fixture $name" }.readText()

internal fun RecordedRequest.bodyText(): String = body?.utf8() ?: ""

/** The fields of an `application/x-www-form-urlencoded` body, in order. */
internal fun RecordedRequest.form(): List<Pair<String, String>> {
    assertContentType("application/x-www-form-urlencoded")
    return bodyText().split("&").filter { it.isNotEmpty() }.map {
        val (name, value) = it.split("=", limit = 2)
        URLDecoder.decode(name, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
    }
}

internal fun RecordedRequest.jsonBody(): JsonObject {
    assertContentType("application/json")
    return Json.parseToJsonElement(bodyText()).jsonObject
}

internal class Part(val name: String, val fileName: String?, val contentType: String?, val bytes: ByteArray) {
    val text: String get() = bytes.toString(Charsets.UTF_8)
}

/** The parts of a `multipart/form-data` body. */
internal fun RecordedRequest.multipart(): List<Part> {
    val type = requireNotNull(headers["Content-Type"]).toMediaType()
    check(type.type == "multipart" && type.subtype == "form-data") { "not multipart: $type" }
    val reader = MultipartReader(Buffer().write(requireNotNull(body)), requireNotNull(type.parameter("boundary")))
    val parts = mutableListOf<Part>()
    reader.use {
        while (true) {
            val part = it.nextPart() ?: break
            val disposition = requireNotNull(part.headers["Content-Disposition"])
            val name = Regex("""\bname="([^"]*)"""").find(disposition)!!.groupValues[1]
            val fileName = Regex("""\bfilename="([^"]*)"""").find(disposition)?.groupValues?.get(1)
            parts += Part(name, fileName, part.headers["Content-Type"], part.body.readByteArray())
        }
    }
    return parts
}

private fun RecordedRequest.assertContentType(expected: String) {
    val actual = headers["Content-Type"] ?: error("no Content-Type")
    check(actual.startsWith(expected)) { "Content-Type $actual, expected $expected" }
}

/** A status as `POST /api/v1/statuses` returns it (docs.joinmastodon.org entities/Status, trimmed). */
internal val STATUS_JSON = """
    {
      "id": "103254962155278888",
      "created_at": "2019-12-05T11:34:47.196Z",
      "edited_at": null,
      "in_reply_to_id": "103254962155278800",
      "in_reply_to_account_id": "14715",
      "sensitive": true,
      "spoiler_text": "cw",
      "visibility": "unlisted",
      "language": "en",
      "uri": "https://mastodon.example/users/me/statuses/103254962155278888",
      "url": "https://mastodon.example/@me/103254962155278888",
      "replies_count": 0,
      "content": "<p>test content</p>",
      "account": {"id": "14715", "username": "me", "acct": "me", "display_name": "Me"},
      "media_attachments": [
        {"id": "22345792", "type": "image", "url": "https://files.example/original/a.jpeg", "preview_url": "https://files.example/small/a.jpeg", "description": "alt", "meta": {"focus": {"x": -0.27, "y": 0.51}}}
      ],
      "mentions": [], "tags": [], "emojis": [], "card": null, "poll": null
    }
""".trimIndent()

/** A processed image as `POST /api/v2/media` returns it with 200 (docs.joinmastodon.org methods/media). */
internal val MEDIA_JSON = """
    {"id": "22348641", "type": "image", "url": "https://files.example/original/e9.jpeg", "preview_url": "https://files.example/small/e9.jpeg",
     "remote_url": null, "text_url": null, "meta": {"focus": {"x": -0.42, "y": 0.69}}, "description": "test uploaded via api", "blurhash": "UFBWY"}
""".trimIndent()

/** A video still processing, as `POST /api/v2/media` returns it with 202: no url. */
internal val MEDIA_PROCESSING_JSON = """
    {"id": "22348642", "type": "video", "url": null, "preview_url": "https://files.example/small/cebc.jpeg", "description": null}
""".trimIndent()
