package org.evsyukov.post.ui.composer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.evsyukov.post.R
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.history.HistoryRecord
import org.evsyukov.post.data.posts.KeptMedia

/** What saving an edit does to one part of a published thread (screens.md, Editing a published post). */
enum class EditMark { UNCHANGED, EDITED, ADDED }

/** The marks of one network's parts, and how many published parts the edit deletes. */
data class EditMarks(val parts: List<EditMark>, val deleted: Int)

/**
 * The marks of each render of the edit preview (N29, N31, N32): for a Mastodon server that edits in place a
 * part is unchanged when its text, content warning and media stay, edited otherwise, added past the published
 * thread, and the published parts past the new thread are deleted. A copy whose server cannot edit, Bluesky or
 * an old Mastodon, is deleted and posted again, so every part is added. Null for a render no account of which
 * holds the post.
 */
fun editMarks(
    renders: List<ProfileRender>,
    record: HistoryRecord,
    kept: List<KeptMedia>,
    reposted: Set<Long> = emptySet(),
): List<EditMarks?> = renders.map { render ->
    val item = record.items.firstOrNull { item -> render.accounts.any { it.id == item.accountId } } ?: return@map null
    val parts = render.render.parts
    // N31, N32: a Bluesky copy, and a Mastodon copy the user chose to post again, is deleted and reposted whole.
    if (render.accounts.all { it.network == Network.BLUESKY || it.id in reposted }) {
        return@map EditMarks(parts.map { EditMark.ADDED }, item.parts.size)
    }
    val marks = parts.mapIndexed { index, part ->
        val old = item.parts.getOrNull(index) ?: return@mapIndexed EditMark.ADDED
        val mediaChanged = old.media.withIndex().any { (position, media) ->
            val edit = kept.firstOrNull { it.part == index && it.index == position }
            edit != null && (edit.removed || edit.alt != media.alt)
        }
        val newMedia = render.assembled?.parts?.getOrNull(index)?.mediaItemIds.orEmpty().isNotEmpty()
        val sameText = old.text == part.text && old.spoilerText.orEmpty() == render.render.spoilerText
        if (sameText && !mediaChanged && !newMedia) EditMark.UNCHANGED else EditMark.EDITED
    }
    EditMarks(marks, (item.parts.size - parts.size).coerceAtLeast(0))
}

@Composable
fun EditMarkLabel(mark: EditMark, tag: String) {
    val (text, color) = when (mark) {
        EditMark.UNCHANGED -> R.string.edit_mark_unchanged to MaterialTheme.colorScheme.onSurfaceVariant
        EditMark.EDITED -> R.string.edit_mark_edited to MaterialTheme.colorScheme.tertiary
        EditMark.ADDED -> R.string.edit_mark_added to MaterialTheme.colorScheme.primary
    }
    Text(stringResource(text), color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag(tag))
}

/**
 * The Bluesky accounts whose copy of [record] has files recorded without their blob reference, by a version
 * before history kept it: "Delete and post again" cannot embed those files (N32).
 */
fun filesNotPostedAgain(record: HistoryRecord): Set<Long> = record.items
    .filter { item -> item.network == Network.BLUESKY && item.parts.any { part -> part.media.any { it.blob == null } } }
    .map { it.accountId }
    .toSet()

/**
 * N31, N32: for each account whose copy of the post cannot be edited in place, "Leave unchanged" (the default)
 * or "Delete and post again", with the warning that likes, reposts and replies are lost, and for a Bluesky
 * account in [filesLost] that its files are not posted again.
 */
@Composable
fun RepostEditChoices(
    accounts: List<Account>,
    targets: List<Long>,
    reposted: Set<Long> = emptySet(),
    onToggle: (Long) -> Unit,
    filesLost: Set<Long> = emptySet(),
) {
    if (accounts.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (account in accounts) {
            val bluesky = account.network == Network.BLUESKY
            // A Bluesky copy is a target only for a repost (N32); a Mastodon one only through this choice (N31).
            val repost = if (bluesky) account.id in targets else account.id in reposted
            val label = if (bluesky) R.string.edit_bluesky_account else R.string.edit_mastodon_account
            Text(stringResource(label, account.handle), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !repost,
                    onClick = { if (repost) onToggle(account.id) },
                    label = { Text(stringResource(R.string.edit_bluesky_leave)) },
                    modifier = Modifier.semantics { role = Role.RadioButton }
                        .testTag(if (bluesky) ComposerTags.blueskyLeave(account.id) else ComposerTags.mastodonLeave(account.id)),
                )
                FilterChip(
                    selected = repost,
                    onClick = { if (!repost) onToggle(account.id) },
                    label = { Text(stringResource(R.string.edit_bluesky_repost)) },
                    modifier = Modifier.semantics { role = Role.RadioButton }
                        .testTag(if (bluesky) ComposerTags.blueskyRepost(account.id) else ComposerTags.mastodonRepost(account.id)),
                )
            }
            if (repost) {
                val warning = if (bluesky) R.string.edit_bluesky_warning else R.string.edit_mastodon_warning
                Text(stringResource(warning), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (account.id in filesLost) {
                    Text(
                        stringResource(R.string.edit_bluesky_files_lost),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag(ComposerTags.blueskyFilesLost(account.id)),
                    )
                }
            }
        }
    }
}

/** N30: the files of the published post; each keeps its place, takes a new alt text, or is removed. */
@Composable
fun KeptMediaList(kept: List<KeptMedia>, onAlt: (Int, Int, String) -> Unit, onToggle: (Int, Int) -> Unit, modifier: Modifier = Modifier) {
    if (kept.isEmpty()) return
    Column(modifier.fillMaxWidth().testTag(ComposerTags.KEPT_MEDIA), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.edit_kept_media), style = MaterialTheme.typography.titleSmall)
        for (media in kept) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(ComposerTags.keptMedia(media.part, media.index))) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.edit_kept_media_item, media.part + 1, media.index + 1),
                        style = MaterialTheme.typography.labelMedium,
                        textDecoration = if (media.removed) TextDecoration.LineThrough else null,
                    )
                    if (media.removed) {
                        Text(stringResource(R.string.edit_kept_media_removed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    } else {
                        OutlinedTextField(
                            value = media.alt,
                            onValueChange = { onAlt(media.part, media.index, it) },
                            label = { Text(stringResource(R.string.edit_kept_media_alt)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                IconButton(onClick = { onToggle(media.part, media.index) }, modifier = Modifier.testTag(ComposerTags.keptMediaToggle(media.part, media.index))) {
                    if (media.removed) {
                        Icon(Icons.Outlined.Restore, contentDescription = stringResource(R.string.edit_kept_media_keep))
                    } else {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.edit_kept_media_remove))
                    }
                }
            }
        }
    }
}
