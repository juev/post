package org.evsyukov.post.core.bluesky

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.time.Instant

/**
 * Record JSON compared with hand-written expectations and validated against the lexicon files of the
 * cited commits (test resources `bluesky/lexicons`).
 */
class RecordsTest {
    private val blobJson = """{"${'$'}type":"blob","ref":{"${'$'}link":"bafkreibme22gw2h7y2h7tg2fhqotaqjucnbc24deqo72b6mkl2egezxhvy"},"mimeType":"image/jpeg","size":123456}"""
    private val blob = BlobRef(Json.parseToJsonElement(blobJson).jsonObject)
    private val videoBlob = BlobRef(
        Json.parseToJsonElement("""{"${'$'}type":"blob","ref":{"${'$'}link":"bafkreivideo"},"mimeType":"video/mp4","size":9000000}""").jsonObject,
    )
    private val createdAt = "2026-11-15T09:30:00.120Z"
    private val root = StrongRef("at://did:plc:alice/app.bsky.feed.post/3m5abcdefgh22", "bafyreiroot")
    private val parent = StrongRef("at://did:plc:alice/app.bsky.feed.post/3m5abcdefgh33", "bafyreiparent")

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun minimalPostHasTypeTextAndCreatedAtOnly() {
        val json = PostRecord(text = "Hello", createdAt = createdAt).toJson()
        assertThat(json).isEqualTo(parse("""{"${'$'}type":"app.bsky.feed.post","text":"Hello","createdAt":"$createdAt"}"""))
        assertThat(Lexicons.validateRecord(Collections.POST, json)).isEmpty()
    }

    @Test
    fun fullReplyPostMatchesTheLexicon() {
        val record = PostRecord(
            text = "Ответ 🌊 #тег",
            createdAt = createdAt,
            facets = listOf(Facet(ByteSlice(16, 23), listOf(FacetFeature.Tag("тег")))),
            reply = ReplyRef(root, parent),
            embed = ImagesEmbed(listOf(EmbedImage(blob, "A wave", AspectRatio(4, 3)), EmbedImage(blob, "", null))),
            langs = listOf("ru", "en"),
            labels = SelfLabels.of(listOf(ContentLabel.GRAPHIC_MEDIA, ContentLabel.NUDITY)),
        )
        val expected = parse(
            """
            {
              "${'$'}type": "app.bsky.feed.post",
              "text": "Ответ 🌊 #тег",
              "createdAt": "$createdAt",
              "facets": [{"index": {"byteStart": 16, "byteEnd": 23},
                          "features": [{"${'$'}type": "app.bsky.richtext.facet#tag", "tag": "тег"}]}],
              "reply": {"root": {"uri": "${root.uri}", "cid": "bafyreiroot"},
                        "parent": {"uri": "${parent.uri}", "cid": "bafyreiparent"}},
              "embed": {"${'$'}type": "app.bsky.embed.images",
                        "images": [{"image": $blobJson, "alt": "A wave", "aspectRatio": {"width": 4, "height": 3}},
                                   {"image": $blobJson, "alt": ""}]},
              "langs": ["ru", "en"],
              "labels": {"${'$'}type": "com.atproto.label.defs#selfLabels",
                         "values": [{"val": "graphic-media"}, {"val": "nudity"}]}
            }
            """,
        )
        assertThat(record.toJson()).isEqualTo(expected)
        assertThat(Lexicons.validateRecord(Collections.POST, record.toJson())).isEmpty()
    }

    @Test
    fun blobIsEmbeddedExactlyAsUploaded() {
        val raw = parse("""{"${'$'}type":"blob","ref":{"${'$'}link":"bafkreix"},"mimeType":"image/png","size":10,"extra":true}""")
        val json = PostRecord("", createdAt, embed = ImagesEmbed(listOf(EmbedImage(BlobRef(raw), "x")))).toJson()
        assertThat(json["embed"]!!.jsonObject["images"].toString()).isEqualTo("""[{"image":$raw,"alt":"x"}]""")
    }

    @Test
    fun allFourSelfLabelValuesUseLexiconNames() {
        val labels = SelfLabels.of(ContentLabel.entries)!!
        val json = PostRecord("x", createdAt, labels = labels).toJson()
        assertThat(json["labels"]).isEqualTo(
            parse(
                """{"${'$'}type":"com.atproto.label.defs#selfLabels","values":[{"val":"sexual"},{"val":"nudity"},{"val":"porn"},{"val":"graphic-media"}]}""",
            ),
        )
        assertThat(SelfLabels.of(emptyList())).isNull()
        assertThat(Lexicons.validateRecord(Collections.POST, json)).isEmpty()
    }

    @Test
    fun galleryItemsAreTypedUnionMembersWithRequiredAspectRatio() {
        val items = List(6) { GalleryImage(blob, "img $it", AspectRatio(3, 2)) }
        val json = PostRecord("", createdAt, embed = GalleryEmbed(items)).toJson()
        val embed = json["embed"]!!.jsonObject
        assertThat(embed.str("\$type")).isEqualTo("app.bsky.embed.gallery")
        assertThat(embed["items"].toString()).startsWith(
            """[{"image":$blobJson,"alt":"img 0","aspectRatio":{"width":3,"height":2},"${'$'}type":"app.bsky.embed.gallery#image"}""",
        )
        assertThat(Lexicons.validateRecord(Collections.POST, json)).isEmpty()
    }

    @Test
    fun galleryKeepsTheSoftLimitOfTen() {
        GalleryEmbed(List(10) { GalleryImage(blob, "", AspectRatio(1, 1)) })
        assertThat(runCatching { GalleryEmbed(List(11) { GalleryImage(blob, "", AspectRatio(1, 1)) }) }.isFailure).isTrue()
        assertThat(runCatching { GalleryEmbed(emptyList()) }.isFailure).isTrue()
    }

    @Test
    fun imagesEmbedTakesOneToFour() {
        assertThat(runCatching { ImagesEmbed(List(5) { EmbedImage(blob, "") }) }.isFailure).isTrue()
        assertThat(runCatching { ImagesEmbed(emptyList()) }.isFailure).isTrue()
    }

    @Test
    fun gifVideoEmbedHasGifPresentation() {
        val embed = VideoEmbed(videoBlob, alt = "Dancing", aspectRatio = AspectRatio(16, 9), presentation = VideoPresentation.forMime("image/gif"))
        val json = PostRecord("", createdAt, embed = embed).toJson()
        assertThat(json["embed"]).isEqualTo(
            parse(
                """{"${'$'}type":"app.bsky.embed.video","video":${videoBlob.json},"alt":"Dancing","aspectRatio":{"width":16,"height":9},"presentation":"gif"}""",
            ),
        )
        assertThat(VideoPresentation.forMime("video/mp4")).isEqualTo(VideoPresentation.DEFAULT)
        assertThat(Lexicons.validateRecord(Collections.POST, json)).isEmpty()
    }

    @Test
    fun videoEmbedWithoutOptionalFieldsHasOnlyTheBlob() {
        val json = PostRecord("", createdAt, embed = VideoEmbed(videoBlob)).toJson()
        assertThat(json["embed"]!!.jsonObject.keys).containsExactly("\$type", "video")
        assertThat(Lexicons.validateRecord(Collections.POST, json)).isEmpty()
    }

    @Test
    fun externalEmbedCarriesTheCard() {
        val withThumb = PostRecord("", createdAt, embed = ExternalEmbed(External("https://example.com/a", "Title", "Desc", blob))).toJson()
        assertThat(withThumb["embed"]).isEqualTo(
            parse(
                """{"${'$'}type":"app.bsky.embed.external","external":{"uri":"https://example.com/a","title":"Title","description":"Desc","thumb":$blobJson}}""",
            ),
        )
        assertThat(Lexicons.validateRecord(Collections.POST, withThumb)).isEmpty()
        val noThumb = PostRecord("", createdAt, embed = ExternalEmbed(External("https://example.com/a", "", ""))).toJson()
        assertThat(noThumb["embed"]!!.jsonObject["external"]!!.jsonObject.keys).containsExactly("uri", "title", "description")
    }

    @Test
    fun postAllowsAtMostThreeLanguages() {
        assertThat(runCatching { PostRecord("x", createdAt, langs = listOf("en", "ru", "de", "fr")) }.isFailure).isTrue()
    }

    @Test
    fun threadgateRulesAndTheirAbsence() {
        val post = "at://did:plc:alice/app.bsky.feed.post/3m5abcdefgh22"
        val some = ThreadgateRecord(post, createdAt, listOf(ThreadgateRule.Mentioned, ThreadgateRule.Followers, ThreadgateRule.Following)).toJson()
        assertThat(some).isEqualTo(
            parse(
                """{"${'$'}type":"app.bsky.feed.threadgate","post":"$post","createdAt":"$createdAt","allow":[
                   {"${'$'}type":"app.bsky.feed.threadgate#mentionRule"},
                   {"${'$'}type":"app.bsky.feed.threadgate#followerRule"},
                   {"${'$'}type":"app.bsky.feed.threadgate#followingRule"}]}""",
            ),
        )
        assertThat(Lexicons.validateRecord(Collections.THREADGATE, some)).isEmpty()
        // An empty list means nobody may reply and must be written; null means everyone and is omitted.
        val nobody = ThreadgateRecord(post, createdAt, emptyList()).toJson()
        assertThat(nobody["allow"].toString()).isEqualTo("[]")
        assertThat(Lexicons.validateRecord(Collections.THREADGATE, nobody)).isEmpty()
        assertThat(ThreadgateRecord(post, createdAt).toJson().keys).containsExactly("\$type", "post", "createdAt")
    }

    @Test
    fun postgateDisablesQuotes() {
        val post = "at://did:plc:alice/app.bsky.feed.post/3m5abcdefgh22"
        val json = PostgateRecord(post, createdAt, listOf(PostgateRule.DisableEmbedding)).toJson()
        assertThat(json).isEqualTo(
            parse(
                """{"${'$'}type":"app.bsky.feed.postgate","post":"$post","createdAt":"$createdAt",
                   "embeddingRules":[{"${'$'}type":"app.bsky.feed.postgate#disableRule"}]}""",
            ),
        )
        assertThat(Lexicons.validateRecord(Collections.POSTGATE, json)).isEmpty()
    }

    @Test
    fun createHelpersUseTheCollectionAndTheGivenKey() {
        val rkey = "3m5abcdefgh22"
        val uri = atUri("did:plc:alice", Collections.POST, rkey)
        assertThat(uri).isEqualTo("at://did:plc:alice/app.bsky.feed.post/3m5abcdefgh22")
        val post = PostRecord("x", createdAt).create(rkey)
        val gate = ThreadgateRecord(uri, createdAt, emptyList()).create(rkey)
        val quote = PostgateRecord(uri, createdAt, listOf(PostgateRule.DisableEmbedding)).create(rkey)
        assertThat(listOf(post.collection, gate.collection, quote.collection))
            .containsExactly("app.bsky.feed.post", "app.bsky.feed.threadgate", "app.bsky.feed.postgate").inOrder()
        assertThat(setOf(post.rkey, gate.rkey, quote.rkey)).containsExactly(rkey)
        assertThat(post.value).isEqualTo(PostRecord("x", createdAt).toJson())
    }

    @Test
    fun datetimeHasMillisecondsAndZ() {
        assertThat(atprotoDatetime(Instant.parse("2026-11-15T09:30:00Z"))).isEqualTo("2026-11-15T09:30:00.000Z")
        assertThat(atprotoDatetime(Instant.parse("2026-11-15T09:30:00.120456Z"))).isEqualTo("2026-11-15T09:30:00.120Z")
        // N24: one millisecond per thread part stays visible.
        val first = Instant.parse("2026-11-15T09:30:00.999Z")
        assertThat(atprotoDatetime(first.plusMillis(1))).isEqualTo("2026-11-15T09:30:01.000Z")
    }

    @Test
    fun validatorCatchesAMissingTypeAndAnUnknownField() {
        // Guards the guard: the validator must fail on records the PDS would reject.
        val noUnionType = parse("""{"${'$'}type":"app.bsky.feed.post","text":"x","createdAt":"$createdAt","embed":{"images":[]}}""")
        assertThat(Lexicons.validateRecord(Collections.POST, noUnionType)).isNotEmpty()
        val unknown = parse("""{"${'$'}type":"app.bsky.feed.post","text":"x","createdAt":"$createdAt","title":"no"}""")
        assertThat(Lexicons.validateRecord(Collections.POST, unknown)).isNotEmpty()
        val typedRef = parse(
            """{"${'$'}type":"app.bsky.feed.post","text":"x","createdAt":"$createdAt",
               "reply":{"${'$'}type":"app.bsky.feed.post#replyRef","root":{"uri":"a","cid":"b"},"parent":{"uri":"a","cid":"b"}}}""",
        )
        assertThat(Lexicons.validateRecord(Collections.POST, typedRef)).isNotEmpty()
        val noAspect = parse(
            """{"${'$'}type":"app.bsky.feed.post","text":"","createdAt":"$createdAt",
               "embed":{"${'$'}type":"app.bsky.embed.gallery","items":[{"${'$'}type":"app.bsky.embed.gallery#image","image":$blobJson,"alt":""}]}}""",
        )
        assertThat(Lexicons.validateRecord(Collections.POST, noAspect)).isNotEmpty()
    }
}
