package org.evsyukov.post.core.media

import com.google.common.truth.Truth.assertWithMessage

internal const val MIB = 1024L * 1024
internal const val MINUTE_MS = 60_000L

internal val MASTODON = NetworkMediaRules.mastodonDefaults()
internal val BLUESKY = NetworkMediaRules.bluesky()

internal fun image(
    id: Long,
    piece: Int = 0,
    mime: String = "image/jpeg",
    size: Long = 800_000,
    width: Int? = 1600,
    height: Int? = 1200,
    transparency: Boolean = false,
    alt: String = "",
) = MediaItem(id, MediaKind.IMAGE, mime, size, width, height, null, piece, transparency, alt = alt)

internal fun gif(id: Long, piece: Int = 0, size: Long = 3 * MIB, width: Int = 480, height: Int = 270, durationMs: Long = 4_000) =
    MediaItem(id, MediaKind.GIF, "image/gif", size, width, height, durationMs, piece)

internal fun video(
    id: Long,
    piece: Int = 0,
    mime: String = "video/mp4",
    size: Long = 40 * MIB,
    width: Int? = 1920,
    height: Int? = 1080,
    durationMs: Long? = MINUTE_MS,
    frameRate: Double? = 30.0,
) = MediaItem(id, MediaKind.VIDEO, mime, size, width, height, durationMs, piece, frameRate = frameRate)

internal fun audio(id: Long, piece: Int = 0, mime: String = "audio/mpeg", size: Long = 8 * MIB, durationMs: Long = 4 * MINUTE_MS) =
    MediaItem(id, MediaKind.AUDIO, mime, size, null, null, durationMs, piece)

internal fun images(ids: LongRange, piece: Int = 0) = ids.map { image(it, piece) }

/** The media of each part of [piece], in part order. */
internal fun MediaPlan.parts(piece: Int = 0): List<List<Long>> =
    partsByPiece[piece].orEmpty().sortedBy { it.partOffset }.map { it.itemIds }

/** [MediaPlanner.plan] that also checks the plan accounts for every attachment exactly once. */
internal fun planOf(
    items: List<MediaItem>,
    rules: NetworkMediaRules,
    fixes: Map<ConflictKind, FixKind> = emptyMap(),
    hasPoll: Boolean = false,
    videoQuotaExhausted: Boolean = false,
    stripMetadata: Boolean = true,
): MediaPlan {
    val plan = MediaPlanner.plan(items, rules, fixes, hasPoll, videoQuotaExhausted, stripMetadata)
    assertAccountedFor(plan, items)
    return plan
}

/**
 * No file is dropped silently: each one is placed once, left out by a chosen fix, or held by a
 * blocking conflict that waits for a fix, and never two of these at once.
 */
internal fun assertAccountedFor(plan: MediaPlan, items: List<MediaItem>) {
    val ids = items.map { it.id }.toSet()
    val placed = plan.placedItemIds
    val excluded = plan.excluded.keys
    val held = plan.conflicts.filter { it.blocking }.flatMap { it.itemIds }.toSet()
    val message = "plan $plan for $items"
    assertWithMessage("placed twice: $message").that(placed).containsNoDuplicates()
    assertWithMessage("every file accounted for: $message").that(placed.toSet() + excluded + held).isEqualTo(ids)
    assertWithMessage("placed and excluded: $message").that(placed.toSet() intersect excluded).isEmpty()
    assertWithMessage("placed and held by a conflict: $message").that(placed.toSet() intersect held).isEmpty()
    assertWithMessage("excluded and held by a conflict: $message").that(excluded intersect held).isEmpty()
    assertWithMessage("a conversion for every placed file: $message").that(plan.conversions.keys).isEqualTo(placed.toSet())
    for ((_, parts) in plan.partsByPiece) {
        assertWithMessage("no empty part: $message").that(parts.none { it.itemIds.isEmpty() }).isTrue()
        assertWithMessage("distinct offsets: $message").that(parts.map { it.partOffset }).containsNoDuplicates()
    }
}
