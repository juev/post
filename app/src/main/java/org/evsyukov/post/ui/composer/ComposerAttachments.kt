package org.evsyukov.post.ui.composer

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.evsyukov.post.R
import org.evsyukov.post.core.media.ConflictKind
import org.evsyukov.post.core.media.FixKind
import org.evsyukov.post.core.media.MediaKind
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.posts.Attachment
import org.evsyukov.post.ui.settings.accounts.networkName
import java.io.File
import kotlin.math.roundToInt

/** Tags of the attachment strip and sheet. */
object AttachmentTags {
    const val STRIP = "composer-attachments"
    const val ADDING = "composer-attachments-adding"
    const val ATTACH_MEDIA = "composer-attach-media"
    const val ATTACH_FILES = "composer-attach-files"
    const val SHEET = "composer-attachment-sheet"
    const val ALT = "composer-attachment-alt"
    const val ALT_COUNTER = "composer-attachment-alt-counter"
    const val PREVIEW = "composer-attachment-preview"
    const val FOCUS = "composer-attachment-focus"
    const val REMOVE = "composer-attachment-remove"

    fun item(id: Long) = "composer-attachment-$id"
    fun altBadge(id: Long) = "composer-attachment-alt-badge-$id"
    fun conflictBadge(id: Long) = "composer-attachment-conflict-badge-$id"
    fun fix(network: Network, kind: ConflictKind, fix: FixKind) = "composer-fix-${network.name}-${kind.name}-${fix.name}"
    fun undo(network: Network, kind: ConflictKind) = "composer-fix-undo-${network.name}-${kind.name}"
    fun conflict(network: Network, kind: ConflictKind) = "composer-conflict-${network.name}-${kind.name}"
    fun resolved(network: Network, kind: ConflictKind) = "composer-resolved-${network.name}-${kind.name}"
}

@Composable
private fun itemName(attachment: Attachment, number: Int): String = stringResource(
    when (attachment.kind) {
        MediaKind.IMAGE -> R.string.media_item_image
        MediaKind.GIF -> R.string.media_item_gif
        MediaKind.VIDEO -> R.string.media_item_video
        MediaKind.AUDIO -> R.string.media_item_audio
    },
    number,
)

/**
 * The thumbnails of the attachments of the piece the cursor is in (screens.md, Attachment strip). A badge
 * marks missing alt text, a warning a conflict that waits for a fix (media.md); a tap opens the attachment
 * sheet. Shown while the piece has attachments or files are being added.
 */
@Composable
fun AttachmentStrip(
    attachments: List<Attachment>,
    conflicted: Set<Long>,
    adding: Boolean,
    fileOf: (Attachment) -> File,
    onOpen: (Long) -> Unit,
) {
    if (attachments.isEmpty() && !adding) return
    val stripLabel = stringResource(R.string.media_strip)
    // A post holds a handful of files, so a plain scrolling row composes them all.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { contentDescription = stripLabel }
            .testTag(AttachmentTags.STRIP),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEachIndexed { index, attachment ->
            key(attachment.id) {
                val noAlt = attachment.alt.isBlank()
                val conflict = attachment.id in conflicted
                val description = buildList {
                    add(itemName(attachment, index + 1))
                    if (noAlt) add(stringResource(R.string.media_no_alt))
                    if (conflict) add(stringResource(R.string.media_has_conflict))
                }.joinToString(", ")
                val editLabel = stringResource(R.string.media_edit)
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClickLabel = editLabel) { onOpen(attachment.id) }
                        .semantics(mergeDescendants = true) { contentDescription = description }
                        .testTag(AttachmentTags.item(attachment.id)),
                ) {
                    Thumbnail(attachment, fileOf(attachment), Modifier.fillMaxSize())
                    if (noAlt) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).testTag(AttachmentTags.altBadge(attachment.id)),
                        ) { Text("ALT") }
                    }
                    if (conflict) {
                        Icon(
                            Icons.Outlined.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onError,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(20.dp)
                                .background(MaterialTheme.colorScheme.error, RoundedCornerShape(10.dp))
                                .padding(2.dp)
                                .testTag(AttachmentTags.conflictBadge(attachment.id)),
                        )
                    }
                }
            }
        }
        if (adding) {
            val addingLabel = stringResource(R.string.media_adding)
            Box(
                Modifier.size(72.dp).semantics { contentDescription = addingLabel }.testTag(AttachmentTags.ADDING),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(Modifier.size(32.dp)) }
        }
    }
}

/** An image or GIF through Coil, a frame of a video, an icon for audio. */
@Composable
private fun Thumbnail(attachment: Attachment, file: File, modifier: Modifier, scale: ContentScale = ContentScale.Crop) {
    val background = modifier.background(MaterialTheme.colorScheme.surfaceVariant)
    when (attachment.kind) {
        MediaKind.IMAGE, MediaKind.GIF -> AsyncImage(model = file, contentDescription = null, contentScale = scale, modifier = background)
        MediaKind.VIDEO -> {
            val frame by produceState<Bitmap?>(null, file) {
                value = withContext(Dispatchers.IO) {
                    runCatching { ThumbnailUtils.createVideoThumbnail(file, Size(320, 320), null) }.getOrNull()
                }
            }
            Box(background, contentAlignment = Alignment.Center) {
                frame?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = scale, modifier = Modifier.fillMaxSize()) }
                Icon(Icons.Outlined.Movie, contentDescription = null, tint = if (frame == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White)
            }
        }
        MediaKind.AUDIO -> Box(background, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.AudioFile, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The alt text limit of one selected network: the strictest of its accounts (A9, A10). */
data class AltLimit(val network: Network, val limit: Int)

/**
 * The attachment sheet (screens.md): preview, alt text with a counter against the limit of each selected
 * network (A9), the focus point for Mastodon picked by a tap on the image, remove.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentSheet(
    attachment: Attachment,
    file: File,
    altLimits: List<AltLimit>,
    onAlt: (String) -> Unit,
    onFocus: (Float, Float) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp).testTag(AttachmentTags.SHEET),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.media_sheet_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            val focusable = attachment.kind == MediaKind.IMAGE || attachment.kind == MediaKind.GIF
            val mastodon = altLimits.any { it.network == Network.MASTODON }
            Preview(attachment, file, pickFocus = focusable && mastodon, onFocus = onFocus)
            if (focusable && mastodon) {
                Text(stringResource(R.string.media_focus_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val length = altLength(attachment.alt)
            val over = altLimits.filter { length > it.limit }
            OutlinedTextField(
                value = attachment.alt,
                onValueChange = onAlt,
                label = { Text(stringResource(R.string.media_alt_text)) },
                placeholder = { Text(stringResource(R.string.media_alt_hint)) },
                isError = over.isNotEmpty(),
                minLines = 2,
                supportingText = {
                    Column {
                        val counters = altLimits.map { stringResource(R.string.media_alt_counter, networkName(it.network), length, it.limit) }
                        Text(counters.joinToString(" · "), modifier = Modifier.testTag(AttachmentTags.ALT_COUNTER))
                        for (limit in over) Text(stringResource(R.string.media_alt_over, networkName(limit.network), limit.limit))
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag(AttachmentTags.ALT),
            )
            OutlinedButton(onClick = onRemove, modifier = Modifier.testTag(AttachmentTags.REMOVE)) {
                Icon(Icons.Outlined.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.media_remove))
            }
            Spacer(Modifier.padding(8.dp))
        }
    }
}

/**
 * The attachment at its aspect ratio, so a tap maps to the image itself; with [pickFocus] a tap sets the
 * Mastodon focus point (x and y from -1 to 1, y up) and a ring marks it.
 */
@Composable
private fun Preview(attachment: Attachment, file: File, pickFocus: Boolean, onFocus: (Float, Float) -> Unit) {
    val width = attachment.width
    val height = attachment.height
    val ratio = if (width != null && height != null && width > 0 && height > 0) width.toFloat() / height else 16f / 9
    val label = stringResource(R.string.media_preview)
    val focusX = attachment.focusX
    val focusY = attachment.focusY
    val focusLabel = if (focusX != null && focusY != null) {
        stringResource(R.string.media_focus_point, ((focusX + 1) * 50).roundToInt(), ((1 - focusY) * 50).roundToInt())
    } else {
        null
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .heightIn(max = 280.dp)
                .aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)
                .clip(RoundedCornerShape(8.dp))
                .semantics { contentDescription = listOfNotNull(label, focusLabel).joinToString(", ") }
                .then(
                    if (pickFocus) {
                        Modifier.pointerInputFocus { x, y -> onFocus(x, y) }
                    } else {
                        Modifier
                    },
                )
                .testTag(AttachmentTags.PREVIEW),
        ) {
            if (attachment.kind == MediaKind.AUDIO) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.AudioFile, contentDescription = null, modifier = Modifier.size(48.dp))
                    Text(stringResource(R.string.media_audio_file))
                }
            } else {
                Thumbnail(attachment, file, Modifier.fillMaxSize(), ContentScale.Fit)
            }
            if (pickFocus && focusX != null && focusY != null) {
                val ring = MaterialTheme.colorScheme.primary
                Canvas(Modifier.fillMaxSize().testTag(AttachmentTags.FOCUS)) {
                    val center = Offset((focusX + 1) / 2 * size.width, (1 - focusY) / 2 * size.height)
                    drawCircle(Color.White, radius = 18.dp.toPx(), center = center, style = Stroke(width = 4.dp.toPx()))
                    drawCircle(ring, radius = 18.dp.toPx(), center = center, style = Stroke(width = 2.dp.toPx()))
                }
            }
        }
    }
}

/** A tap at (x, y) of the box as the Mastodon focus point: -1 to 1 from left to right and bottom to top. */
private fun Modifier.pointerInputFocus(onFocus: (Float, Float) -> Unit): Modifier =
    this.then(
        Modifier.pointerInput(Unit) {
            detectTapGestures { offset ->
                val x = offset.x / size.width * 2 - 1
                val y = 1 - offset.y / size.height * 2
                onFocus(x, y)
            }
        },
    )

@Composable
private fun conflictName(kind: ConflictKind): String = stringResource(
    when (kind) {
        ConflictKind.TOO_MANY_FILES -> R.string.media_conflict_too_many_files
        ConflictKind.UNSUPPORTED_TYPE -> R.string.media_conflict_unsupported_type
        ConflictKind.MIXED_TYPES -> R.string.media_conflict_mixed_types
        ConflictKind.VIDEO_TOO_LONG -> R.string.media_conflict_video_too_long
        ConflictKind.VIDEO_QUOTA -> R.string.media_conflict_video_quota
        ConflictKind.POLL_WITH_MEDIA -> R.string.media_conflict_poll_with_media
        ConflictKind.CANNOT_FIT -> R.string.media_conflict_cannot_fit
        ConflictKind.ALT_TOO_LONG -> R.string.media_conflict_alt_too_long
    },
)

@Composable
fun fixName(fix: FixKind): String = stringResource(
    when (fix) {
        FixKind.MOVE_TO_REPLY -> R.string.media_fix_move_to_reply
        FixKind.REMOVE_FILES_FOR_NETWORK -> R.string.media_fix_remove_files
        FixKind.REMOVE_ACCOUNT -> R.string.media_fix_remove_account
        FixKind.SEND_WITHOUT_FILE -> R.string.media_fix_send_without_file
        FixKind.MOVE_VIDEO_TO_REPLY -> R.string.media_fix_move_video_to_reply
        FixKind.REMOVE_VIDEO -> R.string.media_fix_remove_video
        FixKind.REMOVE_IMAGES -> R.string.media_fix_remove_images
        FixKind.REMOVE_POLL -> R.string.media_fix_remove_poll
        FixKind.REMOVE_MEDIA -> R.string.media_fix_remove_media
        FixKind.MOVE_MEDIA_TO_REPLY -> R.string.media_fix_move_media_to_reply
        FixKind.SEND_LATER -> R.string.media_fix_send_later
    },
)

/**
 * The media of one network in the details sheet (screens.md; media.md A3, A12): its conflicts with a button
 * per fix, the conflicts a fix resolved with what changes and Undo for a fix the user chose, and how many
 * files are converted.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaSection(media: NetworkMedia, chosen: ChosenFixes, onFix: (ConflictKind, FixKind) -> Unit, onUndo: (ConflictKind) -> Unit) {
    val plan = media.plan
    if (plan.conflicts.isEmpty() && plan.resolved.isEmpty() && plan.notes.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.media_section, networkName(media.network)), style = MaterialTheme.typography.titleSmall)
        for (conflict in plan.conflicts) {
            if (conflict.kind == ConflictKind.ALT_TOO_LONG) continue // shown as a blocker or on the attachment
            Text(
                pluralStringResource(R.plurals.media_conflict_files, conflict.itemIds.size, conflictName(conflict.kind), conflict.itemIds.size),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(AttachmentTags.conflict(media.network, conflict.kind)),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (fix in conflict.fixes) {
                    OutlinedButton(
                        onClick = { onFix(conflict.kind, fix) },
                        modifier = Modifier.testTag(AttachmentTags.fix(media.network, conflict.kind, fix)),
                    ) { Text(fixName(fix)) }
                }
            }
        }
        for (resolved in plan.resolved) {
            val kind = resolved.conflict.kind
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.media_resolved, conflictName(kind), fixName(resolved.fix)),
                    modifier = Modifier.weight(1f).testTag(AttachmentTags.resolved(media.network, kind)),
                )
                if (chosen[media.network]?.containsKey(kind) == true) {
                    TextButton(onClick = { onUndo(kind) }, modifier = Modifier.testTag(AttachmentTags.undo(media.network, kind))) {
                        Text(stringResource(R.string.media_fix_undo))
                    }
                }
            }
        }
        if (plan.notes.isNotEmpty()) {
            Text(
                pluralStringResource(R.plurals.media_converted, plan.notes.size, plan.notes.size),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
