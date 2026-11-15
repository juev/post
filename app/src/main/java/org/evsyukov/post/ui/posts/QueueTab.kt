package org.evsyukov.post.ui.posts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.posts.Post
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.send.schedule.movableSlotPosts
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.schedule.SendLaterSheet
import org.evsyukov.post.ui.schedule.firstLine
import org.evsyukov.post.ui.schedule.formatInstant
import org.evsyukov.post.ui.schedule.formatSlot
import org.evsyukov.post.ui.schedule.rememberSendLaterOptions
import org.evsyukov.post.ui.settings.accounts.AccountAvatar
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.time.Instant
import java.time.ZoneId

object QueueTags {
    const val LIST = "queue-list"
    const val EMPTY = "queue-empty"
    const val LATE = "queue-group-late"
    const val FAILED = "queue-group-failed"
    const val WAITING = "queue-group-waiting"
    const val LATE_SEND_NOW = "queue-late-send-now"
    const val LATE_RESCHEDULE = "queue-late-reschedule"
    const val MENU_SEND_NOW = "queue-menu-send-now"
    const val MENU_CHANGE_TIME = "queue-menu-change-time"
    const val MENU_EDIT = "queue-menu-edit"
    const val MENU_DELETE = "queue-menu-delete"
    const val MENU_MOVE_UP = "queue-menu-move-up"
    const val MENU_MOVE_DOWN = "queue-menu-move-down"
    const val MENU_RETRY = "queue-menu-retry"
    const val DELETE_CONFIRM = "queue-delete-confirm"

    fun row(id: Long) = "queue-row-$id"
    fun menu(id: Long) = "queue-menu-$id"
    fun handle(id: Long) = "queue-handle-$id"
    fun pin(id: Long) = "queue-pin-$id"
    fun retryPost(id: Long) = "queue-retry-post-$id"
    fun retryDelivery(id: Long) = "queue-retry-delivery-$id"
}

private const val WAITING_KEY = "w-"

/**
 * The Queue tab (screens.md, Posts; R25; scheduling.md S8–S10, Q3, Q7): Late with its group actions, Failed
 * with Retry per delivery and per post, then the waiting posts by time. Slot-based waiting posts carry a drag
 * handle (long press), explicit ones a pin; every row has a menu with Send now, Change time, Edit, Delete,
 * Move up and Move down. A reorder offers Undo.
 */
@Composable
fun QueueTab(snackbar: SnackbarHostState, onEdit: (Long) -> Unit, onOpenSchedule: () -> Unit) {
    val container = LocalAppContainer.current
    val actions = container.queueActions
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val queue by container.posts.observeQueue().collectAsStateWithLifecycle(initialValue = null)
    val accounts by container.accounts.observe().collectAsStateWithLifecycle(initialValue = emptyList())
    val schedule by container.schedule.observe().collectAsStateWithLifecycle(initialValue = null)
    val list = queue ?: return
    val zone = ZoneId.systemDefault()
    val groups = queueGroups(list, zone)
    val movable = movableSlotPosts(list, zone).map { it.id }
    val accountsById = accounts.associateBy { it.id }

    var dragOrder by remember { mutableStateOf<List<Long>?>(null) }
    var changing by remember { mutableStateOf<Long?>(null) }
    var deleting by remember { mutableStateOf<Long?>(null) }

    fun act(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalStateException) {
                // The post started sending or left the queue meanwhile; the list shows how it is now.
            } catch (_: org.evsyukov.post.data.posts.PostNotFound) {
                // Gone meanwhile.
            }
        }
    }

    suspend fun offerUndo(undo: Map<Long, Schedule>?) {
        if (undo == null) return
        val result = snackbar.showSnackbar(
            resources.getString(R.string.posts_order_changed),
            actionLabel = resources.getString(R.string.posts_undo),
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) actions.undo(undo)
    }

    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val moved = (from.key as? String)?.removePrefix(WAITING_KEY)?.toLongOrNull()
        val target = (to.key as? String)?.removePrefix(WAITING_KEY)?.toLongOrNull()
        if (moved != null && target != null && moved in movable && target in movable) {
            dragOrder = moveOver(dragOrder ?: groups.waiting.map { it.id }, moved, target)
        }
    }

    fun drop(id: Long) {
        val order = dragOrder ?: return
        val index = dropIndex(order, movable, id)
        if (index == null) {
            dragOrder = null
            return
        }
        act {
            val undo = actions.reorder(id, index)
            dragOrder = null
            offerUndo(undo)
        }
    }

    if (groups.isEmpty) {
        Box(Modifier.fillMaxSize().padding(24.dp).testTag(QueueTags.EMPTY), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.posts_queue_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        val waitingById = groups.waiting.associateBy { it.id }
        val waiting = (dragOrder ?: groups.waiting.map { it.id }).mapNotNull(waitingById::get)
        val canReschedule = schedule?.isEmpty() == false
        LazyColumn(Modifier.fillMaxSize().testTag(QueueTags.LIST), state = listState) {
            if (groups.late.isNotEmpty()) {
                item(key = "late-header") {
                    GroupHeader(stringResource(R.string.posts_group_late), QueueTags.LATE, MaterialTheme.colorScheme.error) {
                        Text(
                            stringResource(R.string.posts_group_late_text),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                            FilledTonalButton(onClick = { act { actions.sendHeldNow() } }, modifier = Modifier.testTag(QueueTags.LATE_SEND_NOW)) {
                                Text(stringResource(R.string.posts_send_now))
                            }
                            if (canReschedule) {
                                OutlinedButton(
                                    onClick = {
                                        act {
                                            val moved = actions.rescheduleHeld()
                                            if (moved > 0) snackbar.showSnackbar(resources.getQuantityString(R.plurals.posts_rescheduled, moved, moved))
                                        }
                                    },
                                    modifier = Modifier.testTag(QueueTags.LATE_RESCHEDULE),
                                ) { Text(stringResource(R.string.posts_reschedule)) }
                            }
                        }
                    }
                }
                items(groups.late, key = { "l-${it.id}" }) { post ->
                    QueueRow(post, accountsById, handle = null, menu = { dismiss -> RowMenu(post, null, null, dismiss, ::act, onEdit, { changing = it }, { deleting = it }) })
                }
            }
            if (groups.failed.isNotEmpty()) {
                item(key = "failed-header") {
                    GroupHeader(stringResource(R.string.posts_group_failed), QueueTags.FAILED, MaterialTheme.colorScheme.error) {}
                }
                items(groups.failed, key = { "f-${it.id}" }) { post ->
                    QueueRow(
                        post,
                        accountsById,
                        handle = null,
                        menu = { dismiss -> RowMenu(post, null, null, dismiss, ::act, onEdit, { changing = it }, { deleting = it }) },
                        failures = {
                            FailedDeliveries(post, accountsById, onRetry = { id -> act { container.publisher.retryDelivery(id) } })
                            TextButton(onClick = { act { container.publisher.retryPost(post.id) } }, modifier = Modifier.testTag(QueueTags.retryPost(post.id))) {
                                Text(stringResource(R.string.posts_retry_all))
                            }
                        },
                    )
                }
            }
            if (waiting.isNotEmpty()) {
                item(key = "waiting-header") {
                    GroupHeader(stringResource(R.string.posts_group_waiting), QueueTags.WAITING, MaterialTheme.colorScheme.primary) {}
                }
                items(waiting, key = { WAITING_KEY + it.id }) { post ->
                    val canMove = post.id in movable
                    val position = movable.indexOf(post.id)
                    ReorderableItem(reorder, key = WAITING_KEY + post.id, enabled = canMove) { dragging ->
                        val handle = if (canMove) {
                            Modifier.longPressDraggableHandle(onDragStopped = { drop(post.id) })
                        } else {
                            null
                        }
                        Surface(tonalElevation = if (dragging) 6.dp else 0.dp) {
                            QueueRow(
                                post,
                                accountsById,
                                handle = handle,
                                menu = { dismiss ->
                                    RowMenu(
                                        post,
                                        moveUp = if (canMove && position > 0) ({ act { offerUndo(actions.moveUp(post.id)) } }) else null,
                                        moveDown = if (canMove && position < movable.lastIndex) ({ act { offerUndo(actions.moveDown(post.id)) } }) else null,
                                        dismiss = dismiss,
                                        act = ::act,
                                        onEdit = onEdit,
                                        onChangeTime = { changing = it },
                                        onDelete = { deleting = it },
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    changing?.let { id ->
        val options = rememberSendLaterOptions(except = id)
        if (options != null) {
            SendLaterSheet(
                options = options,
                onSlot = { local ->
                    changing = null
                    act { actions.changeTime(id, Schedule.Slot(local)) }
                },
                onTime = { at ->
                    changing = null
                    if (at.isAfter(Instant.now())) act { actions.changeTime(id, Schedule.Explicit(at)) }
                },
                onOpenSchedule = {
                    changing = null
                    onOpenSchedule()
                },
                onDismiss = { changing = null },
            )
        }
    }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.posts_delete_title)) },
            text = { Text(stringResource(R.string.posts_delete_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        act { if (!actions.delete(id)) snackbar.showSnackbar(resources.getString(R.string.posts_cannot_delete)) }
                    },
                    modifier = Modifier.testTag(QueueTags.DELETE_CONFIRM),
                ) { Text(stringResource(R.string.posts_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun GroupHeader(title: String, tag: String, color: Color, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).testTag(tag)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = color)
        content()
    }
}

/** The row menu (R25, S10): Send now, Change time, Edit before the first delivery, Delete, Move up and down. */
@Composable
private fun RowMenu(
    post: Post,
    moveUp: (() -> Unit)?,
    moveDown: (() -> Unit)?,
    dismiss: () -> Unit,
    act: (suspend () -> Unit) -> Unit,
    onEdit: (Long) -> Unit,
    onChangeTime: (Long) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val container = LocalAppContainer.current
    val waiting = !post.started
    if (waiting) MenuEntry(R.string.posts_send_now, QueueTags.MENU_SEND_NOW, dismiss) { act { container.queueActions.sendNow(post.id) } }
    if (waiting) MenuEntry(R.string.posts_change_time, QueueTags.MENU_CHANGE_TIME, dismiss) { onChangeTime(post.id) }
    if (waiting) MenuEntry(R.string.posts_edit, QueueTags.MENU_EDIT, dismiss) { onEdit(post.id) }
    if (post.deliveries.any { it.state == DeliveryState.FAILED }) {
        MenuEntry(R.string.posts_retry, QueueTags.MENU_RETRY, dismiss) { act { container.publisher.retryPost(post.id) } }
    }
    MenuEntry(R.string.posts_delete, QueueTags.MENU_DELETE, dismiss) { onDelete(post.id) }
    moveUp?.let { MenuEntry(R.string.posts_move_up, QueueTags.MENU_MOVE_UP, dismiss, it) }
    moveDown?.let { MenuEntry(R.string.posts_move_down, QueueTags.MENU_MOVE_DOWN, dismiss, it) }
}

@Composable
private fun MenuEntry(text: Int, tag: String, dismiss: () -> Unit, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(text)) },
        onClick = {
            dismiss()
            onClick()
        },
        modifier = Modifier.testTag(tag),
    )
}

/**
 * One queued post (screens.md, Posts): its send time (a slot, or an explicit time with a pin), the first line,
 * the attachment count, and the target accounts with a state dot each. [handle] is the drag handle's
 * modifier for a post that can move, null otherwise.
 */
@Composable
private fun QueueRow(
    post: Post,
    accounts: Map<Long, Account>,
    handle: Modifier?,
    menu: @Composable (dismiss: () -> Unit) -> Unit,
    failures: @Composable () -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag(QueueTags.row(post.id))) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(timeLabel(post), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(
                    firstLine(post.text).ifEmpty { stringResource(R.string.posts_no_text) },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    for (delivery in post.deliveries) {
                        val account = accounts[delivery.accountId]
                        TargetMark(account, delivery.state)
                        Spacer(Modifier.width(6.dp))
                    }
                    if (post.attachments.isNotEmpty()) {
                        val count = post.attachments.size
                        val description = pluralStringResource(R.plurals.posts_attachments, count, count)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Outlined.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(
                            count.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.semantics { contentDescription = description },
                        )
                    }
                }
            }
            when {
                handle != null -> Box(
                    handle.size(48.dp).testTag(QueueTags.handle(post.id)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.DragHandle, contentDescription = stringResource(R.string.posts_drag_handle))
                }
                post.schedule is Schedule.Explicit -> Box(Modifier.size(48.dp).testTag(QueueTags.pin(post.id)), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = stringResource(R.string.posts_explicit_time),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Box {
                IconButton(onClick = { open = true }, modifier = Modifier.testTag(QueueTags.menu(post.id))) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.posts_row_menu))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    menu { open = false }
                }
            }
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp)) { failures() }
        HorizontalDivider()
    }
}

/** When the post goes: the slot, or the explicit or Send now time. */
@Composable
private fun timeLabel(post: Post): String = when (val schedule = post.schedule) {
    is Schedule.Slot -> formatSlot(schedule.local)
    is Schedule.Explicit -> formatInstant(schedule.at)
    is Schedule.Now -> formatInstant(schedule.at)
    null -> ""
}

/** An account's avatar with a dot for its delivery: waiting, sending, sent or failed. */
@Composable
private fun TargetMark(account: Account?, state: DeliveryState) {
    val handle = account?.handle ?: "?"
    val description = stringResource(
        when (state) {
            DeliveryState.WAITING -> R.string.posts_state_waiting
            DeliveryState.SENDING -> R.string.posts_state_sending
            DeliveryState.SENT -> R.string.posts_state_sent
            DeliveryState.FAILED -> R.string.posts_state_failed
        },
        handle,
    )
    val color = when (state) {
        DeliveryState.WAITING -> MaterialTheme.colorScheme.outline
        DeliveryState.SENDING -> MaterialTheme.colorScheme.tertiary
        DeliveryState.SENT -> MaterialTheme.colorScheme.primary
        DeliveryState.FAILED -> MaterialTheme.colorScheme.error
    }
    Box(Modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        AccountAvatar(account?.avatarUrl, account?.displayName?.ifBlank { handle } ?: handle, size = 24.dp)
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(10.dp)
                .background(color, CircleShape)
                .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape),
        )
    }
}

/** Failed deliveries of a post, each with the server's message and Retry (R29). */
@Composable
private fun FailedDeliveries(post: Post, accounts: Map<Long, Account>, onRetry: (Long) -> Unit) {
    for (delivery in post.deliveries.filter { it.state == DeliveryState.FAILED }) {
        val handle = accounts[delivery.accountId]?.handle ?: "?"
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.posts_delivery_error, handle, delivery.error.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onRetry(delivery.id) }, modifier = Modifier.testTag(QueueTags.retryDelivery(delivery.id))) {
                Text(stringResource(R.string.posts_retry))
            }
        }
    }
}
