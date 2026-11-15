package org.evsyukov.post.ui.posts

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.evsyukov.post.R
import org.evsyukov.post.data.history.HistoryItem
import org.evsyukov.post.data.history.HistoryRecord

object PublishedTags {
    const val DELETE_CONFIRM = "published-delete-confirm"

    fun deleteAccount(id: Long) = "published-delete-account-$id"
}

/** The link of the published copy on one account: its first part, which opens the thread (R31). */
fun HistoryItem.link(): String? = parts.firstNotNullOfOrNull { it.url }

/** Opens [url] in the browser or the app that handles it; false when nothing can (R37). */
fun openLink(context: Context, url: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: ActivityNotFoundException) {
    false
}

fun copyLink(context: Context, url: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(url, url))
}

/**
 * Delete published (R39): which accounts to delete the post from, every one holding it by default. Only
 * accounts that still exist can delete; [existing] names them.
 */
@Composable
fun DeletePublishedDialog(record: HistoryRecord, existing: Set<Long>, onConfirm: (List<Long>) -> Unit, onDismiss: () -> Unit) {
    val holders = record.items.filter { it.accountId in existing }
    var chosen by remember(record.id) { mutableStateOf(holders.map { it.accountId }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_published_title)) },
        text = {
            Column {
                Text(stringResource(R.string.delete_published_text))
                for (item in holders) {
                    val checked = item.accountId in chosen
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(checked, role = Role.Checkbox) { on ->
                                chosen = if (on) chosen + item.accountId else chosen - item.accountId
                            }
                            .padding(vertical = 4.dp)
                            .testTag(PublishedTags.deleteAccount(item.accountId)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Text(item.handle, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(holders.map { it.accountId }.filter { it in chosen }) },
                enabled = chosen.isNotEmpty(),
                modifier = Modifier.testTag(PublishedTags.DELETE_CONFIRM),
            ) { Text(stringResource(R.string.posts_delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
