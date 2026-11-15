package org.evsyukov.post.ui.posts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.evsyukov.post.R
import org.evsyukov.post.core.markdown.MANUAL_BREAK
import org.evsyukov.post.data.history.HistoryRecord
import org.evsyukov.post.data.settings.HistoryRetention
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.schedule.formatInstant

object SentTags {
    const val LIST = "sent-list"
    const val EMPTY = "sent-empty"
    const val OFF = "sent-history-off"
    const val MENU_OPEN = "sent-menu-open"
    const val MENU_COPY = "sent-menu-copy"
    const val MENU_EDIT = "sent-menu-edit"
    const val MENU_DELETE = "sent-menu-delete"
    const val MENU_NEW = "sent-menu-new"

    fun row(id: Long) = "sent-row-$id"
    fun menu(id: Long) = "sent-menu-$id"
    fun link(id: Long, accountId: Long) = "sent-link-$id-$accountId"
}

/** What a sent post leads to: its details, editing it, or a new post from it. */
class SentNavigation(
    val onOpen: (Long) -> Unit,
    val onEdit: (Long) -> Unit,
    val onNewFrom: (Long) -> Unit,
)

/**
 * The Sent tab (R31, R37–R41; screens.md, Posts): history records, newest first, with the text, the time and a
 * link per account to the published copy. The menu offers Open, Copy link, Edit, Delete and New post from this.
 * With history off nothing is kept, and the tab says so; an expired record is gone, so the empty state says
 * that an old post can only be opened in the browser (R37).
 */
@Composable
fun SentTab(snackbar: SnackbarHostState, navigation: SentNavigation) {
    val container = LocalAppContainer.current
    val records by container.history.observe().collectAsStateWithLifecycle(initialValue = null)
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val accounts by container.accounts.observe().collectAsStateWithLifecycle(initialValue = emptyList())
    val list = records ?: return
    val retention = settings?.historyRetention ?: return
    val existing = accounts.map { it.id }.toSet()
    val actions = rememberPublishedActions(snackbar, navigation)
    var deleting by remember { mutableStateOf<HistoryRecord?>(null) }

    if (list.isEmpty()) {
        val days = retention.days
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            if (days == null) {
                Text(stringResource(R.string.sent_history_off), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(SentTags.OFF))
            } else {
                Text(
                    pluralStringResource(R.plurals.sent_empty, days.toInt(), days.toInt()),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(SentTags.EMPTY),
                )
            }
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag(SentTags.LIST)) {
        if (retention == HistoryRetention.OFF) {
            item(key = "off") {
                Text(
                    stringResource(R.string.sent_history_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp).testTag(SentTags.OFF),
                )
            }
        }
        items(list, key = { it.id }) { record ->
            SentRow(record, onOpen = { navigation.onOpen(record.id) }, actions = actions, onDelete = { deleting = record })
        }
    }
    deleting?.let { record ->
        DeletePublishedDialog(
            record = record,
            existing = existing,
            onConfirm = { ids ->
                deleting = null
                actions.delete(record.id, ids)
            },
            onDismiss = { deleting = null },
        )
    }
}

/** The actions on a published post shared by the Sent tab and Post details. */
class PublishedActionsUi(
    val open: (String) -> Unit,
    val copy: (String) -> Unit,
    val edit: (Long) -> Unit,
    val newFrom: (Long) -> Unit,
    val delete: (Long, List<Long>) -> Unit,
)

@Composable
fun rememberPublishedActions(snackbar: SnackbarHostState, navigation: SentNavigation, afterDelete: () -> Unit = {}): PublishedActionsUi {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    fun say(text: Int) {
        scope.launch { snackbar.showSnackbar(resources.getString(text)) }
    }
    return PublishedActionsUi(
        open = { url -> if (!openLink(context, url)) say(R.string.sent_no_browser) },
        copy = { url ->
            copyLink(context, url)
            say(R.string.sent_link_copied)
        },
        edit = { id ->
            scope.launch {
                // Another edit or deletion of the same post would race this one.
                if (container.publishedActions.pending(id)) snackbar.showSnackbar(resources.getString(R.string.sent_pending)) else navigation.onEdit(id)
            }
        },
        newFrom = navigation.onNewFrom,
        delete = { id, accountIds ->
            scope.launch {
                try {
                    if (container.publishedActions.pending(id)) {
                        snackbar.showSnackbar(resources.getString(R.string.sent_pending))
                        return@launch
                    }
                    container.publishedActions.delete(id, accountIds)
                    afterDelete()
                    snackbar.showSnackbar(resources.getString(R.string.delete_published_queued))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: IllegalArgumentException) {
                    // The record expired meanwhile; the list shows how it is now.
                }
            }
        },
    )
}

@Composable
private fun SentRow(record: HistoryRecord, onOpen: () -> Unit, actions: PublishedActionsUi, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val firstLink = record.items.firstNotNullOfOrNull { it.link() }
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag(SentTags.row(record.id))) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(formatInstant(record.sentAt), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(
                    // Manual breaks are markers, not text (threads.md T13).
                    record.text.lines().filter { it.trim() != MANUAL_BREAK }.joinToString("\n").trim().ifBlank { stringResource(R.string.posts_no_text) },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag(SentTags.menu(record.id))) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.sent_menu))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val close = { menu = false }
                    if (firstLink != null) {
                        Entry(R.string.sent_open, SentTags.MENU_OPEN, close) { actions.open(firstLink) }
                        Entry(R.string.sent_copy_link, SentTags.MENU_COPY, close) { actions.copy(firstLink) }
                    }
                    Entry(R.string.posts_edit, SentTags.MENU_EDIT, close) { actions.edit(record.id) }
                    Entry(R.string.posts_delete, SentTags.MENU_DELETE, close, onDelete)
                    Entry(R.string.sent_new_from, SentTags.MENU_NEW, close) { actions.newFrom(record.id) }
                }
            }
        }
        FlowRow(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (item in record.items) {
                val url = item.link() ?: continue
                val description = stringResource(R.string.sent_open_on, item.handle)
                AssistChip(
                    onClick = { actions.open(url) },
                    label = { Text(item.handle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                    modifier = Modifier.semantics { contentDescription = description }.testTag(SentTags.link(record.id, item.accountId)),
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun Entry(text: Int, tag: String, close: () -> Unit, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(text)) },
        onClick = {
            close()
            onClick()
        },
        modifier = Modifier.testTag(tag),
    )
}
