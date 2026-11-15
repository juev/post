package org.evsyukov.post.ui.posts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.evsyukov.post.R
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.db.PostKind
import org.evsyukov.post.data.posts.Post
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.schedule.firstLine
import org.evsyukov.post.ui.schedule.formatInstant

object DraftsTags {
    const val LIST = "drafts-list"
    const val EMPTY = "drafts-empty"
    const val MENU_OPEN = "drafts-menu-open"
    const val MENU_DELETE = "drafts-menu-delete"

    fun row(id: Long) = "drafts-row-$id"
    fun menu(id: Long) = "drafts-menu-$id"
}

/**
 * The Drafts tab (R15; screens.md, Posts): saved drafts, the last saved first, each with the first line of its
 * text, the number of attachments, its targets and when it was saved. A tap opens the draft in the composer;
 * a swipe or the menu deletes it, with Undo: the draft goes only once the snackbar is gone.
 */
@Composable
fun DraftsTab(snackbar: SnackbarHostState, onOpen: (Long) -> Unit) {
    val container = LocalAppContainer.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val drafts by container.posts.observeDrafts().collectAsStateWithLifecycle(initialValue = null)
    val accounts by container.accounts.observe().collectAsStateWithLifecycle(initialValue = emptyList())
    var hidden by remember { mutableStateOf(setOf<Long>()) }
    val list = drafts?.filter { it.id !in hidden } ?: return
    val byId = accounts.associateBy { it.id }

    fun delete(id: Long) {
        hidden = hidden + id
        scope.launch {
            var undone = false
            try {
                val result = snackbar.showSnackbar(
                    resources.getString(R.string.drafts_deleted),
                    actionLabel = resources.getString(R.string.posts_undo),
                    duration = SnackbarDuration.Short,
                )
                undone = result == SnackbarResult.ActionPerformed
            } finally {
                // Leaving the screen while the snackbar shows is not an Undo: the draft still goes.
                if (undone) {
                    hidden = hidden - id
                } else {
                    container.scope.launch {
                        container.posts.deleteDraft(id)
                        hidden = hidden - id
                    }
                }
            }
        }
    }

    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp).testTag(DraftsTags.EMPTY), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.drafts_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag(DraftsTags.LIST)) {
        items(list, key = { it.id }) { draft ->
            val state = rememberSwipeToDismissBoxState()
            SwipeToDismissBox(
                state = state,
                backgroundContent = {},
                onDismiss = { delete(draft.id) },
            ) {
                DraftRow(draft, byId, onOpen = { onOpen(draft.id) }, onDelete = { delete(draft.id) })
            }
        }
    }
}

@Composable
private fun DraftRow(draft: Post, accounts: Map<Long, Account>, onOpen: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val deleteLabel = stringResource(R.string.posts_delete)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .semantics { customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(); true }) }
            .testTag(DraftsTags.row(draft.id)),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.drafts_saved_at, formatInstant(draft.updatedAt)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (draft.kind == PostKind.EDIT) {
                    Text(stringResource(R.string.details_kind_edit), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                }
                Text(
                    firstLine(draft.text).ifEmpty { stringResource(R.string.posts_no_text) },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    val targets = draft.targets.mapNotNull { accounts[it]?.handle }
                    Text(
                        targets.joinToString(", ").ifEmpty { stringResource(R.string.drafts_no_targets) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (draft.attachments.isNotEmpty()) {
                        val count = draft.attachments.size
                        val description = pluralStringResource(R.plurals.posts_attachments, count, count)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(count.toString(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.semantics { contentDescription = description })
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag(DraftsTags.menu(draft.id))) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.drafts_menu))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.drafts_open)) },
                        onClick = {
                            menu = false
                            onOpen()
                        },
                        modifier = Modifier.testTag(DraftsTags.MENU_OPEN),
                    )
                    DropdownMenuItem(
                        text = { Text(deleteLabel) },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                        modifier = Modifier.testTag(DraftsTags.MENU_DELETE),
                    )
                }
            }
        }
        HorizontalDivider()
    }
}
