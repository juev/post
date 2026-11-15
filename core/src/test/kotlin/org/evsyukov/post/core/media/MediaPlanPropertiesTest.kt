package org.evsyukov.post.core.media

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.random.Random
import kotlinx.serialization.json.Json
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Properties that hold for any attachments, rules and fixes: no file is dropped silently, and the
 * parts of a plan are always valid for the network.
 */
class MediaPlanPropertiesTest {

    private val random = Random(20261115)

    @Test
    fun `R11 no file is dropped silently and every part is valid for the network`() {
        repeat(3000) { round ->
            val rules = randomRules()
            val items = randomItems()
            val fixes = randomFixes()
            val hasPoll = random.nextBoolean()
            val quota = random.nextInt(4) == 0
            val plan = MediaPlanner.plan(items, rules, fixes, hasPoll, quota, random.nextBoolean())
            val context = "round $round: $items, ${rules.network}, $fixes, poll=$hasPoll, quota=$quota\n$plan"

            assertAccountedFor(plan, items)
            val byId = items.associateBy { it.id }
            for ((piece, parts) in plan.partsByPiece) {
                for (part in parts) {
                    val files = part.itemIds.map { byId.getValue(it) }
                    assertWithMessage("piece of each file: $context").that(files.map { it.pieceIndex }.toSet()).containsExactly(piece)
                    assertWithMessage("files per part: $context").that(files.size).isAtMost(rules.maxImagesPerPart)
                    val solo = files.filter { isSolo(it, rules) }
                    if (solo.isNotEmpty()) assertWithMessage("a video alone: $context").that(files).hasSize(1)
                    if (files.any { it.kind == MediaKind.AUDIO }) assertWithMessage("audio: $context").that(rules.audioAllowed).isTrue()
                    for (file in files) {
                        assertWithMessage("a variant the network takes for ${file.id}: $context")
                            .that(acceptedVariant(file, plan.conversions.getValue(file.id), rules)).isTrue()
                    }
                }
            }
            if (hasPoll && !rules.pollAllowedWithMedia && !plan.pollRemoved) {
                assertWithMessage("no media with the poll: $context")
                    .that(plan.partsByPiece[0].orEmpty().none { it.partOffset == 0 }).isTrue()
            }
            if (quota && !plan.sendLater) {
                assertWithMessage("nothing for the video service: $context")
                    .that(plan.placedItemIds.map { byId.getValue(it) }.none { goesToVideoService(it, rules) }).isTrue()
            }
            for (resolved in plan.resolved) {
                assertWithMessage("an offered fix: $context").that(resolved.conflict.kind.fixes).contains(resolved.fix)
                assertWithMessage("chosen: $context").that(fixes[resolved.conflict.kind]).isEqualTo(resolved.fix)
            }
            assertWithMessage("ready: $context").that(plan.ready).isEqualTo(plan.conflicts.none { it.blocking })
            if (plan.accountRemoved) {
                assertWithMessage("removed account: $context").that(plan.partsByPiece).isEmpty()
                assertWithMessage("removed account: $context").that(plan.excluded.keys).containsExactlyElementsIn(items.map { it.id })
            }
        }
    }

    @Test
    fun `the plan is the same for the same input`() {
        repeat(200) {
            val rules = randomRules()
            val items = randomItems()
            val fixes = randomFixes()
            assertThat(MediaPlanner.plan(items, rules, fixes, true)).isEqualTo(MediaPlanner.plan(items, rules, fixes, true))
        }
    }

    @Test
    fun `a plan survives JSON, so a delivery can freeze it`() {
        val items = listOf(image(1, mime = "image/heic"), video(2, mime = "video/x-matroska"), gif(3, piece = 1), audio(4, piece = 2))
        val plan = MediaPlanner.plan(items, BLUESKY, mapOf(ConflictKind.UNSUPPORTED_TYPE to FixKind.SEND_WITHOUT_FILE), false)
        val json = Json.encodeToString(MediaPlan.serializer(), plan)
        assertThat(Json.decodeFromString(MediaPlan.serializer(), json)).isEqualTo(plan)
        assertThat(Json.decodeFromString(NetworkMediaRules.serializer(), Json.encodeToString(NetworkMediaRules.serializer(), MASTODON)))
            .isEqualTo(MASTODON)
    }

    @Test
    fun `every kind of conversion survives JSON`() {
        val mastodon = MediaPlanner.plan(
            listOf(image(1, mime = "image/heic"), video(2, mime = "video/x-matroska", piece = 1), audio(3, piece = 2)),
            MASTODON, emptyMap(), false,
        )
        val bluesky = MediaPlanner.plan(listOf(gif(1)), BLUESKY, emptyMap(), false)
        val kinds = (mastodon.conversions.values + bluesky.conversions.values).map { it::class }.toSet()
        assertThat(kinds).containsExactly(
            Conversion.AsIs::class, Conversion.ConvertImage::class, Conversion.TranscodeVideo::class, Conversion.GifAsVideo::class,
        )
        for (plan in listOf(mastodon, bluesky)) {
            val json = Json.encodeToString(MediaPlan.serializer(), plan)
            assertThat(Json.decodeFromString(MediaPlan.serializer(), json)).isEqualTo(plan)
        }
    }

    @Test
    fun `attachment ids must be unique`() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaPlanner.plan(listOf(image(1), image(1)), MASTODON, emptyMap(), false)
        }
    }

    private fun isSolo(item: MediaItem, rules: NetworkMediaRules) =
        item.kind == MediaKind.VIDEO || item.kind == MediaKind.AUDIO || (item.kind == MediaKind.GIF && rules.gifIsVideo)

    private fun goesToVideoService(item: MediaItem, rules: NetworkMediaRules) =
        item.kind == MediaKind.VIDEO || (item.kind == MediaKind.GIF && rules.gifIsVideo)

    /** The uploaded variant is a type the network takes, and a GIF that goes as it is fits the GIF matrix. */
    private fun acceptedVariant(item: MediaItem, conversion: Conversion, rules: NetworkMediaRules): Boolean = when (conversion) {
        is Conversion.ConvertImage -> rules.acceptsImage(conversion.targetMime)
        is Conversion.TranscodeVideo -> rules.acceptsVideo(conversion.targetMime)
        Conversion.GifAsVideo -> item.kind == MediaKind.GIF && rules.gifIsVideo
        Conversion.AsIs -> {
            val pixels = if (item.width != null && item.height != null) item.width.toLong() * item.height else null
            val gifFits = normalizeMime(item.mime) != "image/gif" || pixels == null || rules.maxGifPixels == null ||
                pixels <= rules.maxGifPixels
            gifFits && when (item.kind) {
                MediaKind.IMAGE, MediaKind.GIF -> rules.acceptsImage(item.mime)
                MediaKind.VIDEO -> rules.acceptsVideo(item.mime)
                MediaKind.AUDIO -> rules.acceptsAudio(item.mime)
            }
        }
    }

    private fun randomRules(): NetworkMediaRules {
        val base = if (random.nextBoolean()) MASTODON else BLUESKY
        return when (random.nextInt(4)) {
            0 -> base
            1 -> base.copy(maxAttachments = random.nextInt(0, 7), galleryMax = base.galleryMax?.let { random.nextInt(2, 12) })
            // A fork or a merge that takes fewer types, maybe none of the conversion targets.
            2 -> base.copy(
                imageMimes = base.imageMimes.filter { random.nextBoolean() }.toSet(),
                videoMimes = base.videoMimes.filter { random.nextBoolean() }.toSet(),
            )
            else -> NetworkMediaRules.strictest(listOf(base, base.copy(maxImageBytes = base.maxImageBytes / 2, maxVideoBytes = base.maxVideoBytes / 3)))
        }
    }

    private fun randomItems(): List<MediaItem> = List(random.nextInt(0, 14)) { index ->
        val id = 100L + index * 7
        val piece = random.nextInt(3)
        when (random.nextInt(10)) {
            in 0..4 -> image(
                id, piece,
                mime = listOf("image/jpeg", "image/png", "image/heic", "image/avif", "image/webp", "image/gif").random(random),
                size = random.nextLong(10_000, 30 * MIB),
                width = listOf(random.nextInt(100, 9000), null).random(random), height = random.nextInt(100, 9000),
                transparency = random.nextBoolean(),
                alt = "a".repeat(listOf(0, 10, 2001, 10_001).random(random)),
            )
            5, 6 -> gif(
                id, piece,
                size = random.nextLong(10_000, 25 * MIB),
                width = listOf(320, 480, 1280, 1281, 1920).random(random),
                height = listOf(240, 270, 720, 1080).random(random),
                durationMs = random.nextLong(500, 20_000),
            )
            7, 8 -> video(
                id, piece,
                mime = listOf("video/mp4", "video/quicktime", "video/x-matroska", "video/webm").random(random),
                size = random.nextLong(1 * MIB, 600 * MIB),
                width = listOf(640, 1280, 1920, 3840, 7680, null).random(random),
                height = listOf(480, 720, 1080, 2160, 4320, null).random(random),
                durationMs = listOf(5_000L, MINUTE_MS, 9 * MINUTE_MS, 14 * MINUTE_MS, 40 * MINUTE_MS, null).random(random),
                frameRate = listOf(24.0, 30.0, 60.0, 240.0, null).random(random),
            )
            else -> audio(id, piece, mime = listOf("audio/mpeg", "audio/ogg", "audio/x-ms-wma").random(random), size = random.nextLong(MIB, 150 * MIB))
        }
    }

    private fun randomFixes(): Map<ConflictKind, FixKind> = ConflictKind.entries
        .filter { random.nextInt(3) != 0 }
        .associateWith { kind ->
            // Mostly offered fixes, sometimes one the kind does not offer.
            if (kind.fixes.isNotEmpty() && random.nextInt(5) != 0) kind.fixes.random(random) else FixKind.entries.random(random)
        }
}
