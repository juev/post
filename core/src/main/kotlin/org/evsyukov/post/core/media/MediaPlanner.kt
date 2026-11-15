package org.evsyukov.post.core.media

import org.evsyukov.post.core.media.Conversion.AsIs
import org.evsyukov.post.core.media.Conversion.ConvertImage
import org.evsyukov.post.core.media.Conversion.GifAsVideo
import org.evsyukov.post.core.media.Conversion.TranscodeVideo

/**
 * Builds the media plan of one network (media.md A2–A13).
 *
 * The plan is pure: it reads the attachments, the network's rules and the chosen fixes, and says
 * which part each file goes to, which variant is uploaded, and which conflicts still need a fix.
 * The caller rebuilds it whenever attachments, targets or limits change (A3), and passes the
 * strictest rules of the network's target accounts ([NetworkMediaRules.strictest], A10).
 *
 * The checks run in this order, each on the files the previous ones kept:
 *
 * 1. Each file on its own. Images that the network does not take or that are over its limits are
 *    converted (A5, A6); video is transcoded when its type, size, frame rate or resolution is over
 *    the limits (A7); a Bluesky GIF goes as video (A8). A file the network cannot take
 *    ([ConflictKind.UNSUPPORTED_TYPE]), a video over the duration cap
 *    ([ConflictKind.VIDEO_TOO_LONG]) and a file no conversion fits ([ConflictKind.CANNOT_FIT]) are
 *    conflicts. A video of unknown duration that must be transcoded cannot get a bitrate, so it is
 *    a [ConflictKind.CANNOT_FIT] too. A conversion whose output type the network does not take is
 *    no conversion: the file is [ConflictKind.UNSUPPORTED_TYPE] when the network does not take its
 *    own type either, [ConflictKind.CANNOT_FIT] when it does. A network that takes no files
 *    ([NetworkMediaRules.maxAttachments] 0) supports no type.
 * 2. The video quota ([ConflictKind.VIDEO_QUOTA]) for every file that goes to the video service.
 * 3. Each piece of the text (threads.md T13–T14) on its own: a video, an audio file or a video
 *    GIF with images ([ConflictKind.MIXED_TYPES]), then more files than one part takes
 *    ([ConflictKind.TOO_MANY_FILES]). For Bluesky 5–10 images make one gallery, not a conflict (A11).
 * 4. A poll with media in the first part of the post ([ConflictKind.POLL_WITH_MEDIA]). The poll
 *    goes with the first part, so only the media placed there conflict with it.
 * 5. Alt text over the limit ([ConflictKind.ALT_TOO_LONG], A9): a mark that does not block.
 *
 * A fix chosen in `fixes` for a conflict kind resolves every conflict of that kind and changes the
 * plan; a fix the kind does not offer counts as no choice. A conflict without a fix stays in the
 * plan, and its files stay out of the parts until it has one. [FixKind.REMOVE_ACCOUNT] ends the
 * plan: nothing goes to this network.
 */
object MediaPlanner {

    fun plan(
        items: List<MediaItem>,
        rules: NetworkMediaRules,
        fixes: Map<ConflictKind, FixKind>,
        hasPoll: Boolean,
        videoQuotaExhausted: Boolean = false,
        stripMetadata: Boolean = true,
    ): MediaPlan {
        require(items.map { it.id }.toSet().size == items.size) { "attachment ids must be unique" }
        return Builder(items, rules, fixes, stripMetadata).build(hasPoll, videoQuotaExhausted)
    }

    private sealed interface Examined {
        data class Fits(val conversion: Conversion, val note: String?) : Examined
        data class Fails(val kind: ConflictKind) : Examined
    }

    private class Builder(
        private val items: List<MediaItem>,
        private val rules: NetworkMediaRules,
        private val fixes: Map<ConflictKind, FixKind>,
        private val stripMetadata: Boolean,
    ) {
        private val order = items.withIndex().associate { (index, item) -> item.id to index }
        private val pending = LinkedHashMap<ConflictKind, MutableList<Long>>()
        private val resolved = LinkedHashMap<ConflictKind, Pair<FixKind, MutableList<Long>>>()
        private val excluded = LinkedHashMap<Long, FixKind>()
        private val conversions = HashMap<Long, Conversion>()
        private val notes = HashMap<Long, String>()
        private var accountRemovedBy: ConflictKind? = null

        fun build(hasPoll: Boolean, videoQuotaExhausted: Boolean): MediaPlan {
            var kept = examineEach()
            if (accountRemovedBy != null) return removedPlan()

            kept = checkQuota(kept, videoQuotaExhausted)
            if (accountRemovedBy != null) return removedPlan()

            val partsByPiece = sortedMapOf<Int, List<PartMedia>>()
            for ((piece, pieceItems) in kept.groupBy { it.pieceIndex }.toSortedMap()) {
                val parts = placePiece(pieceItems)
                if (parts.isNotEmpty()) partsByPiece[piece] = parts
            }
            if (accountRemovedBy != null) return removedPlan()

            if (hasPoll && !rules.pollAllowedWithMedia) checkPoll(partsByPiece)

            checkAlt()

            val placed = partsByPiece.values.flatMap { parts -> parts.flatMap { it.itemIds } }
            val placedInOrder = placed.sortedBy { order.getValue(it) }
            return MediaPlan(
                network = rules.network,
                partsByPiece = partsByPiece.filterValues { it.isNotEmpty() },
                conversions = placedInOrder.associateWith { conversions.getValue(it) },
                conflicts = pending.map { (kind, ids) -> Conflict(kind, inOrder(ids)) },
                notes = placedInOrder.mapNotNull { notes[it] },
                resolved = resolved.map { (kind, entry) -> ResolvedConflict(Conflict(kind, inOrder(entry.second)), entry.first) },
                excluded = excluded.entries.sortedBy { order.getValue(it.key) }.associate { it.key to it.value },
            )
        }

        /** Step 1: every file against the type and limits of the network. */
        private fun examineEach(): List<MediaItem> {
            val kept = mutableListOf<MediaItem>()
            for (item in items) {
                when (val result = examine(item)) {
                    is Examined.Fits -> {
                        conversions[item.id] = result.conversion
                        result.note?.let { notes[item.id] = it }
                        kept += item
                    }
                    is Examined.Fails -> when (val fix = raise(result.kind, listOf(item.id))) {
                        null, FixKind.REMOVE_ACCOUNT -> Unit
                        else -> excluded[item.id] = fix
                    }
                }
            }
            return kept
        }

        private fun examine(item: MediaItem): Examined = when {
            rules.maxAttachments <= 0 -> Examined.Fails(ConflictKind.UNSUPPORTED_TYPE)
            item.kind == MediaKind.IMAGE -> examineImage(item)
            item.kind == MediaKind.GIF -> if (rules.gifIsVideo) examineVideoGif(item) else examineImageGif(item)
            item.kind == MediaKind.VIDEO -> examineVideo(item)
            else -> examineAudio(item)
        }

        private fun examineImage(item: MediaItem): Examined {
            val mime = normalizeMime(item.mime)
            val target = ImageTargets.target(
                item.width, item.height, item.sizeBytes, item.mime, rules, item.hasTransparency, stripMetadata,
            )
            if (target != null) {
                if (!rules.acceptsImage(target.targetMime)) return cannotConvert(rules.acceptsImage(mime))
                return Examined.Fits(target, "image ${item.id}: $mime → ${target.describe()}")
            }
            val unknownSize = item.width == null || item.height == null
            return when {
                !stripMetadata || mime == GIF_MIME -> Examined.Fits(AsIs, null)
                // A6: the image fits, but its metadata goes; JPEG, PNG and WebP keep their type and pixels.
                // With an unknown size the pass carries the resolution limits, so the converter checks
                // the decoded image.
                mime in REWRITABLE_IMAGE_MIMES -> Examined.Fits(
                    ConvertImage(
                        targetMime = mime,
                        maxLongSide = if (unknownSize) rules.maxImageLongSide else null,
                        maxPixels = if (unknownSize) rules.maxImagePixels else null,
                        maxBytes = rules.maxImageBytes,
                        stripMetadata = true,
                    ),
                    null,
                )
                // A type the server takes but the device cannot write back (HEIC on a fork) becomes JPEG
                // or PNG. A server that takes neither gets the file as it is: it takes the original.
                else -> {
                    val target = ConvertImage(
                        targetMime = if (item.hasTransparency) ImageTargets.PNG else ImageTargets.JPEG,
                        maxLongSide = rules.maxImageLongSide,
                        maxPixels = rules.maxImagePixels,
                        maxBytes = rules.maxImageBytes,
                        stripMetadata = true,
                    )
                    if (rules.acceptsImage(target.targetMime)) {
                        Examined.Fits(target, "image ${item.id}: $mime → ${target.describe()}")
                    } else {
                        Examined.Fits(AsIs, null)
                    }
                }
            }
        }

        /**
         * Mastodon: a GIF is an image the server converts; the app cannot convert it without losing
         * the animation. Its size, the image limits and the GIF matrix (1280×720) decide.
         */
        private fun examineImageGif(item: MediaItem): Examined {
            if (!rules.acceptsImage(item.mime)) return Examined.Fails(ConflictKind.UNSUPPORTED_TYPE)
            val pixels = if (item.width != null && item.height != null) item.width.toLong() * item.height else null
            val longSide = if (item.width != null && item.height != null) maxOf(item.width, item.height) else null
            val tooBig = item.sizeBytes > rules.maxImageBytes ||
                (pixels != null && rules.maxImagePixels != null && pixels > rules.maxImagePixels) ||
                (longSide != null && rules.maxImageLongSide != null && longSide > rules.maxImageLongSide) ||
                !ImageTargets.withinGifMatrix(item.width, item.height, item.mime, rules)
            return if (tooBig) Examined.Fails(ConflictKind.CANNOT_FIT) else Examined.Fits(AsIs, null)
        }

        /** No conversion gives a type the network takes. */
        private fun cannotConvert(networkTakesSourceType: Boolean): Examined =
            Examined.Fails(if (networkTakesSourceType) ConflictKind.CANNOT_FIT else ConflictKind.UNSUPPORTED_TYPE)

        /** Bluesky: the GIF goes to the video service as it is (A8, N21); nothing on the device converts a GIF. */
        private fun examineVideoGif(item: MediaItem): Examined = when {
            tooLong(item) -> Examined.Fails(ConflictKind.VIDEO_TOO_LONG)
            item.sizeBytes > rules.maxVideoBytes -> Examined.Fails(ConflictKind.CANNOT_FIT)
            else -> Examined.Fits(GifAsVideo, null)
        }

        private fun examineVideo(item: MediaItem): Examined {
            if (rules.videoMimes.isEmpty()) return Examined.Fails(ConflictKind.UNSUPPORTED_TYPE)
            if (tooLong(item)) return Examined.Fails(ConflictKind.VIDEO_TOO_LONG)
            val pixels = if (item.width != null && item.height != null) item.width.toLong() * item.height else null
            val needsTranscoding = !rules.acceptsVideo(item.mime) ||
                item.sizeBytes > rules.maxVideoBytes ||
                (item.frameRate != null && rules.maxVideoFrameRate != null && item.frameRate > rules.maxVideoFrameRate) ||
                (pixels != null && rules.maxVideoPixels != null && pixels > rules.maxVideoPixels)
            if (!needsTranscoding) return Examined.Fits(AsIs, null)
            if (!rules.acceptsVideo(VideoTargets.TARGET_MIME)) return cannotConvert(rules.acceptsVideo(item.mime))
            val duration = item.durationMs ?: return Examined.Fails(ConflictKind.CANNOT_FIT)
            val target = VideoTargets.target(
                durationMs = duration,
                maxBytes = rules.maxVideoBytes,
                width = item.width ?: 0,
                height = item.height ?: 0,
                maxPixels = rules.maxVideoPixels,
                sourceBytes = item.sizeBytes,
            ) ?: return Examined.Fails(ConflictKind.CANNOT_FIT)
            val conversion = target.copy(maxFrameRate = rules.maxVideoFrameRate)
            return Examined.Fits(conversion, "video ${item.id}: ${normalizeMime(item.mime)} → ${conversion.describe()}")
        }

        private fun examineAudio(item: MediaItem): Examined = when {
            !rules.acceptsAudio(item.mime) -> Examined.Fails(ConflictKind.UNSUPPORTED_TYPE)
            // The app does not transcode audio.
            item.sizeBytes > rules.maxVideoBytes -> Examined.Fails(ConflictKind.CANNOT_FIT)
            else -> Examined.Fits(AsIs, null)
        }

        private fun tooLong(item: MediaItem): Boolean {
            val cap = rules.maxVideoDurationMs ?: return false
            val duration = item.durationMs ?: return false
            return duration > cap
        }

        /** Step 2: the daily video quota covers every file that goes to the video service. */
        private fun checkQuota(kept: List<MediaItem>, exhausted: Boolean): List<MediaItem> {
            if (!exhausted) return kept
            val videos = kept.filter { goesToVideoService(it) }
            if (videos.isEmpty()) return kept
            return when (val fix = raise(ConflictKind.VIDEO_QUOTA, videos.map { it.id })) {
                FixKind.SEND_LATER -> kept
                null, FixKind.REMOVE_ACCOUNT -> kept - videos.toSet()
                else -> {
                    videos.forEach { excluded[it.id] = fix }
                    kept - videos.toSet()
                }
            }
        }

        private fun goesToVideoService(item: MediaItem): Boolean =
            item.kind == MediaKind.VIDEO || (item.kind == MediaKind.GIF && rules.gifIsVideo)

        /** A file that must be alone in its part where the network says so. */
        private fun isSolo(item: MediaItem): Boolean =
            item.kind == MediaKind.VIDEO || item.kind == MediaKind.AUDIO ||
                (item.kind == MediaKind.GIF && rules.gifIsVideo)

        /** Step 3: the parts of one piece, from its first part on. */
        private fun placePiece(pieceItems: List<MediaItem>): List<PartMedia> {
            val (solos, images) = if (rules.videoMustBeAlone) {
                pieceItems.partition { isSolo(it) }
            } else {
                emptyList<MediaItem>() to pieceItems
            }
            var imageLane = images
            var soloLane = solos
            var solosToReplies = false
            if (solos.isNotEmpty() && images.isNotEmpty()) {
                when (val fix = raise(ConflictKind.MIXED_TYPES, pieceItems.map { it.id })) {
                    FixKind.MOVE_VIDEO_TO_REPLY -> solosToReplies = true
                    FixKind.REMOVE_VIDEO -> {
                        solos.forEach { excluded[it.id] = fix }
                        soloLane = emptyList()
                    }
                    FixKind.REMOVE_IMAGES -> {
                        images.forEach { excluded[it.id] = fix }
                        imageLane = emptyList()
                    }
                    else -> return emptyList()
                }
            }
            val parts = mutableListOf<List<MediaItem>>()
            if (imageLane.isNotEmpty()) {
                // A gallery holds images only; a network that mixes video in counts every file.
                val perPart = if (imageLane.none { isSolo(it) }) rules.maxImagesPerPart else rules.maxAttachments
                parts += limit(imageLane, perPart)
            }
            if (soloLane.isNotEmpty()) {
                parts += if (solosToReplies) soloLane.map { listOf(it) } else limit(soloLane, 1)
            }
            return parts.mapIndexed { offset, part -> PartMedia(offset, part.map { it.id }) }
        }

        /** The files of one lane split into parts of [perPart]; the files over it are a conflict. */
        private fun limit(lane: List<MediaItem>, perPart: Int): List<List<MediaItem>> {
            val size = perPart.coerceAtLeast(1)
            if (lane.size <= size) return listOf(lane)
            val extras = lane.drop(size)
            return when (val fix = raise(ConflictKind.TOO_MANY_FILES, extras.map { it.id })) {
                FixKind.MOVE_TO_REPLY -> lane.chunked(size)
                FixKind.REMOVE_FILES_FOR_NETWORK -> {
                    extras.forEach { excluded[it.id] = fix }
                    listOf(lane.take(size))
                }
                else -> listOf(lane.take(size))
            }
        }

        /** Step 4: the poll goes with the first part of the post; Mastodon takes no media there. */
        private fun checkPoll(partsByPiece: MutableMap<Int, List<PartMedia>>) {
            val firstPiece = partsByPiece[FIRST_PIECE] ?: return
            val withPoll = firstPiece.firstOrNull { it.partOffset == 0 } ?: return
            when (val fix = raise(ConflictKind.POLL_WITH_MEDIA, withPoll.itemIds)) {
                FixKind.REMOVE_POLL -> Unit
                FixKind.MOVE_MEDIA_TO_REPLY ->
                    partsByPiece[FIRST_PIECE] = firstPiece.map { it.copy(partOffset = it.partOffset + 1) }
                FixKind.REMOVE_MEDIA -> {
                    withPoll.itemIds.forEach { excluded[it] = fix }
                    partsByPiece[FIRST_PIECE] = firstPiece - withPoll
                }
                else -> partsByPiece[FIRST_PIECE] = firstPiece - withPoll
            }
            if (partsByPiece[FIRST_PIECE].isNullOrEmpty()) partsByPiece.remove(FIRST_PIECE)
        }

        /** Step 5: A9 marks alt text over the limit; the user shortens it. */
        private fun checkAlt() {
            val tooLong = items.filter { it.id !in excluded && it.alt.codePointCount(0, it.alt.length) > rules.maxAltLength }
            if (tooLong.isNotEmpty()) pending.getOrPut(ConflictKind.ALT_TOO_LONG) { mutableListOf() } += tooLong.map { it.id }
        }

        /** Records a conflict and returns the chosen fix, or null when it waits for one. */
        private fun raise(kind: ConflictKind, ids: List<Long>): FixKind? {
            val fix = fixes[kind]?.takeIf { it in kind.fixes }
            if (fix == null) {
                pending.getOrPut(kind) { mutableListOf() } += ids
            } else {
                resolved.getOrPut(kind) { fix to mutableListOf() }.second += ids
                if (fix == FixKind.REMOVE_ACCOUNT && accountRemovedBy == null) accountRemovedBy = kind
            }
            return fix
        }

        /** The account leaves the targets: nothing is placed and every file is left out. */
        private fun removedPlan(): MediaPlan {
            val kind = checkNotNull(accountRemovedBy)
            val ids = resolved.getValue(kind).second
            return MediaPlan(
                network = rules.network,
                partsByPiece = emptyMap(),
                conversions = emptyMap(),
                conflicts = emptyList(),
                notes = emptyList(),
                resolved = listOf(ResolvedConflict(Conflict(kind, inOrder(ids)), FixKind.REMOVE_ACCOUNT)),
                excluded = items.associate { it.id to FixKind.REMOVE_ACCOUNT },
            )
        }

        private fun inOrder(ids: Collection<Long>): List<Long> = ids.distinct().sortedBy { order.getValue(it) }
    }

    private const val FIRST_PIECE = 0
    private const val GIF_MIME = "image/gif"

    /** Types the device encodes again when it removes metadata (`Bitmap.compress`). */
    private val REWRITABLE_IMAGE_MIMES = setOf("image/jpeg", "image/png", "image/webp")

    private fun ConvertImage.describe(): String = buildList {
        add(targetMime)
        maxLongSide?.let { add("up to $it px on the long side") }
        maxPixels?.let { add("up to $it pixels") }
        add("up to $maxBytes bytes")
    }.joinToString(", ")

    private fun TranscodeVideo.describe(): String =
        "$targetMime, ${maxShortSide}p, video ${videoBitrate / 1000} kbit/s, audio ${audioBitrate / 1000} kbit/s"
}
