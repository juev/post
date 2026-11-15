@file:OptIn(ExperimentalSerializationApi::class)

package org.evsyukov.post.core.bluesky

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/*
 * Records Post writes, field for field from the lexicons of `bluesky-social/bsky` `416b0ad1d9`
 * (`lexicons/app/bsky/feed/{post,threadgate,postgate}.json`, `embed/{images,gallery,video,external,
 * defs}.json`, `richtext/facet.json`) and `bluesky-social/atproto` `7dd086d4de`
 * (`lexicons/com/atproto/label/defs.json`, `repo/strongRef.json`).
 *
 * `$type` follows the lexicon spec (`atproto-website` `59daa1ff`, `specs/lexicon/en.mdx:271, 294`):
 * records and every member of a union carry it; objects reached through a plain `ref` (reply refs,
 * facets, image entries, aspect ratios) do not.
 */

/** Collection NSIDs of the records Post writes. */
object Collections {
    const val POST = "app.bsky.feed.post"
    const val THREADGATE = "app.bsky.feed.threadgate"
    const val POSTGATE = "app.bsky.feed.postgate"
}

/** `at://<repo>/<collection>/<rkey>`. */
fun atUri(repo: String, collection: String, rkey: String): String = "at://$repo/$collection/$rkey"

/**
 * A lexicon `datetime` with millisecond precision in UTC, such as `2026-11-15T09:30:00.120Z`: the
 * lexicon spec asks for at least milliseconds, zero-padded, with a `Z` suffix.
 */
fun atprotoDatetime(instant: Instant): String = DATETIME.format(instant)

private val DATETIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** `com.atproto.repo.strongRef`. */
@Serializable
data class StrongRef(val uri: String, val cid: String)

/** `app.bsky.feed.post#replyRef`: the thread's first post and the post replied to. */
@Serializable
data class ReplyRef(val root: StrongRef, val parent: StrongRef)

/** `app.bsky.embed.defs#aspectRatio`; both sides at least 1. */
@Serializable
data class AspectRatio(val width: Int, val height: Int) {
    init {
        require(width >= 1 && height >= 1) { "aspect ratio sides must be positive" }
    }
}

/** Self-label values Post offers (networks.md N22; `com.atproto.label.defs#labelValue`). */
enum class ContentLabel(val value: String) {
    SEXUAL("sexual"),
    NUDITY("nudity"),
    PORN("porn"),
    GRAPHIC_MEDIA("graphic-media"),
}

/** `com.atproto.label.defs#selfLabel`. */
@Serializable
data class SelfLabel(@SerialName("val") val value: String)

/** `com.atproto.label.defs#selfLabels`, the union member of `app.bsky.feed.post#labels`. */
@Serializable
data class SelfLabels(val values: List<SelfLabel>) {
    @EncodeDefault
    @SerialName("\$type")
    val type: String = "com.atproto.label.defs#selfLabels"

    init {
        require(values.size <= 10) { "at most 10 self-labels" }
    }

    companion object {
        /** The labels as a `labels` value, or null for none. */
        fun of(labels: Collection<ContentLabel>): SelfLabels? =
            labels.distinct().takeIf { it.isNotEmpty() }?.let { list -> SelfLabels(list.map { SelfLabel(it.value) }) }
    }
}

/** The `embed` union of a post. */
@Serializable
@JsonClassDiscriminator("\$type")
sealed interface Embed

/** `app.bsky.embed.images`: 1 to 4 images. */
@Serializable
@SerialName("app.bsky.embed.images")
data class ImagesEmbed(val images: List<EmbedImage>) : Embed {
    init {
        require(images.size in 1..MAX_IMAGES) { "1 to $MAX_IMAGES images" }
    }

    companion object {
        const val MAX_IMAGES = 4
    }
}

/** `app.bsky.embed.images#image`: `alt` is required by the lexicon, possibly empty. */
@Serializable
data class EmbedImage(val image: BlobRef, val alt: String, val aspectRatio: AspectRatio? = null)

/**
 * `app.bsky.embed.gallery`, present in the lexicons at `bsky` `416b0ad1d9` and used by social-app
 * `9747031e9b` (`src/lib/api/index.ts:357-386`). The lexicon caps `items` at 20 and asks authoring
 * clients to keep a soft limit of 10, which this class enforces.
 */
@Serializable
@SerialName("app.bsky.embed.gallery")
data class GalleryEmbed(val items: List<GalleryImage>) : Embed {
    init {
        require(items.size in 1..SOFT_LIMIT) { "1 to $SOFT_LIMIT gallery items" }
    }

    companion object {
        const val SOFT_LIMIT = 10
    }
}

/** `app.bsky.embed.gallery#image`, a union member (so it carries `$type`); `aspectRatio` is required. */
@Serializable
data class GalleryImage(val image: BlobRef, val alt: String, val aspectRatio: AspectRatio) {
    @EncodeDefault
    @SerialName("\$type")
    val type: String = "app.bsky.embed.gallery#image"
}

/** `presentation` of a video embed. */
@Serializable
enum class VideoPresentation {
    @SerialName("default")
    DEFAULT,

    @SerialName("gif")
    GIF,

    ;

    companion object {
        /** `gif` for a GIF uploaded through the video service, as social-app does (networks.md N21). */
        fun forMime(mimeType: String): VideoPresentation =
            if (mimeType.equals("image/gif", ignoreCase = true)) GIF else DEFAULT
    }
}

/** `app.bsky.embed.video`: the blob from the video service's job status. Captions are not used. */
@Serializable
@SerialName("app.bsky.embed.video")
data class VideoEmbed(
    val video: BlobRef,
    val alt: String? = null,
    val aspectRatio: AspectRatio? = null,
    val presentation: VideoPresentation? = null,
) : Embed

/** `app.bsky.embed.external`: a link card. */
@Serializable
@SerialName("app.bsky.embed.external")
data class ExternalEmbed(val external: External) : Embed

/** `app.bsky.embed.external#external`; the thumbnail blob may be up to 1 MB. */
@Serializable
data class External(val uri: String, val title: String, val description: String, val thumb: BlobRef? = null)

/** `app.bsky.feed.post`. [createdAt] is a lexicon datetime, see [atprotoDatetime]. */
@Serializable
data class PostRecord(
    val text: String,
    val createdAt: String,
    val facets: List<Facet>? = null,
    val reply: ReplyRef? = null,
    val embed: Embed? = null,
    val langs: List<String>? = null,
    val labels: SelfLabels? = null,
) {
    @EncodeDefault
    @SerialName("\$type")
    val type: String = Collections.POST

    init {
        require(langs == null || langs.size <= 3) { "at most 3 languages" }
    }

    fun toJson(): JsonObject = encodeRecord(serializer(), this)

    /** The `applyWrites` create of this post at [rkey]. */
    fun create(rkey: String): WriteOp.Create = WriteOp.Create(Collections.POST, rkey, toJson())
}

/** Members of `app.bsky.feed.threadgate#allow` that Post offers. */
@Serializable
@JsonClassDiscriminator("\$type")
sealed interface ThreadgateRule {
    @Serializable
    @SerialName("app.bsky.feed.threadgate#mentionRule")
    data object Mentioned : ThreadgateRule

    @Serializable
    @SerialName("app.bsky.feed.threadgate#followerRule")
    data object Followers : ThreadgateRule

    @Serializable
    @SerialName("app.bsky.feed.threadgate#followingRule")
    data object Following : ThreadgateRule
}

/**
 * `app.bsky.feed.threadgate`, written with the rkey of the thread's first post. [allow] null leaves the
 * field out (anyone can reply); an empty list means nobody can.
 */
@Serializable
data class ThreadgateRecord(val post: String, val createdAt: String, val allow: List<ThreadgateRule>? = null) {
    @EncodeDefault
    @SerialName("\$type")
    val type: String = Collections.THREADGATE

    init {
        require(allow == null || allow.size <= 5) { "at most 5 rules" }
    }

    fun toJson(): JsonObject = encodeRecord(serializer(), this)

    /** The `applyWrites` create at [rkey], which must equal the post's rkey. */
    fun create(rkey: String): WriteOp.Create = WriteOp.Create(Collections.THREADGATE, rkey, toJson())
}

/** Members of `app.bsky.feed.postgate#embeddingRules`. */
@Serializable
@JsonClassDiscriminator("\$type")
sealed interface PostgateRule {
    /** Nobody may quote the post. */
    @Serializable
    @SerialName("app.bsky.feed.postgate#disableRule")
    data object DisableEmbedding : PostgateRule
}

/** `app.bsky.feed.postgate`, written with the rkey of the post it governs. */
@Serializable
data class PostgateRecord(val post: String, val createdAt: String, val embeddingRules: List<PostgateRule>? = null) {
    @EncodeDefault
    @SerialName("\$type")
    val type: String = Collections.POSTGATE

    fun toJson(): JsonObject = encodeRecord(serializer(), this)

    /** The `applyWrites` create at [rkey], which must equal the post's rkey. */
    fun create(rkey: String): WriteOp.Create = WriteOp.Create(Collections.POSTGATE, rkey, toJson())
}

/**
 * Records are encoded with this fixed configuration, whatever Json the app uses elsewhere: absent
 * optional fields are left out instead of written as null.
 */
private val RECORD_JSON = Json {
    encodeDefaults = false
    explicitNulls = false
}

private fun <T> encodeRecord(serializer: KSerializer<T>, value: T): JsonObject =
    RECORD_JSON.encodeToJsonElement(serializer, value).jsonObject
