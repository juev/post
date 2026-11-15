package org.evsyukov.post.core.bluesky

import com.ibm.icu.text.BreakIterator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import java.util.concurrent.TimeUnit

internal val testJson = Json { ignoreUnknownKeys = true }

internal fun jsonResponse(body: String, code: Int = 200): MockResponse =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

internal fun RecordedRequest.bodyJson(): JsonObject = Json.parseToJsonElement(body!!.utf8()).jsonObject

internal fun mockwebserver3.MockWebServer.next(): RecordedRequest = takeRequest(2, TimeUnit.SECONDS)!!

internal fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

/** Graphemes by ICU, the rules the device uses through android.icu. */
internal fun icuGraphemes(text: String): Int {
    val it = BreakIterator.getCharacterInstance().apply { setText(text) }
    var count = 0
    while (it.next() != BreakIterator.DONE) count++
    return count
}

/**
 * A small validator of record JSON against the lexicon files in `src/test/resources/bluesky/lexicons`
 * (copied from the commits cited in their README). It checks what the PDS's `validate: true` would
 * reject in our records: unknown and missing fields, union members without the right `$type`,
 * `$type` on plain refs, array and string length limits, integer minimums and blob shape.
 */
internal object Lexicons {
    private val docs: Map<String, JsonObject> by lazy {
        listOf(
            "app.bsky.feed.post", "app.bsky.feed.threadgate", "app.bsky.feed.postgate", "app.bsky.embed.images",
            "app.bsky.embed.gallery", "app.bsky.embed.video", "app.bsky.embed.external", "app.bsky.embed.defs",
            "app.bsky.richtext.facet", "com.atproto.label.defs", "com.atproto.repo.strongRef",
        ).associateWith { id ->
            val text = Lexicons::class.java.getResource("/bluesky/lexicons/$id.json")!!.readText()
            Json.parseToJsonElement(text).jsonObject
        }
    }

    /** Problems found in [record] against the record lexicon [nsid]; empty when it conforms. */
    fun validateRecord(nsid: String, record: JsonObject): List<String> {
        val problems = mutableListOf<String>()
        val main = def(nsid, "main")
        check(main["type"]!!.jsonPrimitive.content == "record")
        if (record["\$type"]?.jsonPrimitive?.content != nsid) problems += "\$: \$type must be $nsid"
        validate(main["record"]!!.jsonObject, nsid, record, "\$", typeAllowed = true, problems)
        return problems
    }

    private fun def(lexicon: String, name: String): JsonObject =
        docs[lexicon]?.get("defs")?.jsonObject?.get(name)?.jsonObject ?: error("unknown lexicon ref $lexicon#$name")

    /** Resolves a `ref` string in [context] into (lexicon, def name, `$type` value). */
    private fun resolve(ref: String, context: String): Triple<String, String, String> {
        val lexicon = if (ref.startsWith("#")) context else ref.substringBefore('#')
        val name = if ('#' in ref) ref.substringAfter('#') else "main"
        val type = if (name == "main") lexicon else "$lexicon#$name"
        return Triple(lexicon, name, type)
    }

    private fun validate(schema: JsonObject, context: String, value: JsonElement, path: String, typeAllowed: Boolean, problems: MutableList<String>) {
        when (val type = schema["type"]!!.jsonPrimitive.content) {
            "object" -> {
                if (value !is JsonObject) return run { problems += "$path: expected object" }
                val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
                val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                for (key in value.keys) {
                    if (key == "\$type") {
                        if (!typeAllowed) problems += "$path: \$type is not allowed on a plain ref"
                    } else if (key !in properties) {
                        problems += "$path: unknown field $key"
                    }
                }
                for (key in required) if (key !in value) problems += "$path: missing required field $key"
                for ((key, element) in value) {
                    val property = properties[key]?.jsonObject ?: continue
                    validate(property, context, element, "$path.$key", typeAllowed = false, problems)
                }
            }
            "ref" -> {
                val (lexicon, name, _) = resolve(schema["ref"]!!.jsonPrimitive.content, context)
                validate(def(lexicon, name), lexicon, value, path, typeAllowed = false, problems)
            }
            "union" -> {
                val member = (value as? JsonObject)?.get("\$type")?.jsonPrimitive?.content
                    ?: return run { problems += "$path: union member without \$type" }
                val match = schema["refs"]!!.jsonArray.map { resolve(it.jsonPrimitive.content, context) }.firstOrNull { it.third == member }
                    ?: return run { problems += "$path: \$type $member is not in the union" }
                validate(def(match.first, match.second), match.first, value, path, typeAllowed = true, problems)
            }
            "array" -> {
                if (value !is JsonArray) return run { problems += "$path: expected array" }
                schema["maxLength"]?.jsonPrimitive?.longOrNull?.let { if (value.size > it) problems += "$path: more than $it items" }
                value.forEachIndexed { i, element -> validate(schema["items"]!!.jsonObject, context, element, "$path[$i]", false, problems) }
            }
            "string" -> {
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return run { problems += "$path: expected string" }
                schema["maxLength"]?.jsonPrimitive?.longOrNull?.let {
                    if (text.toByteArray(Charsets.UTF_8).size > it) problems += "$path: longer than $it bytes"
                }
                schema["maxGraphemes"]?.jsonPrimitive?.longOrNull?.let { if (icuGraphemes(text) > it) problems += "$path: more than $it graphemes" }
            }
            "integer" -> {
                val number = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return run { problems += "$path: expected integer" }
                schema["minimum"]?.jsonPrimitive?.longOrNull?.let { if (number < it) problems += "$path: below $it" }
            }
            "boolean" -> if ((value as? JsonPrimitive)?.isString != false) problems += "$path: expected boolean"
            "blob" -> {
                val blob = value as? JsonObject
                if (blob == null || blob["\$type"]?.jsonPrimitive?.content != "blob" || blob["ref"]?.jsonObject?.get("\$link") == null ||
                    blob["mimeType"] == null || blob["size"] == null
                ) {
                    problems += "$path: not a blob"
                }
            }
            "unknown" -> Unit
            else -> problems += "$path: schema type $type not supported by the test validator"
        }
    }
}
