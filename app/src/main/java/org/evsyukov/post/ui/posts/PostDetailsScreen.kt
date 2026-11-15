package org.evsyukov.post.ui.posts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.evsyukov.post.R
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.db.PostKind
import org.evsyukov.post.data.history.HistoryRecord
import org.evsyukov.post.data.posts.Post
import org.evsyukov.post.data.posts.PostNotFound
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.send.FreezeResult
import org.evsyukov.post.send.PlanFreezer
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.common.BackTopBar
import org.evsyukov.post.ui.schedule.SendLaterSheet
import org.evsyukov.post.ui.schedule.formatInstant
import org.evsyukov.post.ui.schedule.rememberSendLaterOptions
import java.time.Instant

object DetailsTags {
    const val SCREEN = "details"
    const val GONE = "details-gone"
    const val RETRY = "details-retry"
    const val SEND_NOW = "details-send-now"
    const val CHANGE_TIME = "details-change-time"
    const val EDIT = "details-edit"
    const val DELETE = "details-delete"
    const val DELETE_CONFIRM = "details-delete-confirm"
    const val EDIT_PUBLISHED = "details-edit-published"
    const val DELETE_PUBLISHED = "details-delete-published"
    const val NEW_FROM = "details-new-from"

    fun account(id: Long) = "details-account-$id"
    fun part(accountId: Long, index: Int) = "details-part-$accountId-$index"
    fun link(accountId: Long) = "details-link-$accountId"
}

/**
 * Post details (screens.md): a queued post by [postId] or a sent one by [historyId]. It shows the full text by
 * thread part for each account, the attachments while they are kept, and per account the state, the time, the
 * server's error for a failure and the link for a success. A queued post offers Retry, Send now, Change time,
 * Edit (before the first delivery starts) and Delete; a sent one Edit published, Delete published and New post
 * from this.
 */
@Composable
fun PostDetailsScreen(
    postId: Long?,
    historyId: Long?,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onOpenSchedule: () -> Unit,
    navigation: SentNavigation,
) {
    val snackbar = remember { SnackbarHostState() }
    Scaffold(
        modifier = Modifier.testTag(DetailsTags.SCREEN),
        topBar = { BackTopBar(title = stringResource(R.string.details_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                postId != null -> QueuedDetails(postId, snackbar, onBack, onEdit, onOpenSchedule)
                historyId != null -> SentDetails(historyId, snackbar, onBack, navigation)
                else -> Gone()
            }
        }
    }
}

@Composable
private fun Gone() {
    Box(Modifier.fillMaxSize().padding(24.dp).testTag(DetailsTags.GONE), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.details_gone), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The parts each account gets: the frozen plan once sending started, else the parts as they would be frozen now. */
private class AccountParts(val parts: List<String>?, val blocked: String?)

@Composable
private fun QueuedDetails(postId: Long, snackbar: SnackbarHostState, onBack: () -> Unit, onEdit: (Long) -> Unit, onOpenSchedule: () -> Unit) {
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()
    val loaded by produceState<Post?>(null, postId) { container.posts.observe(postId).collect { value = it } }
    val accounts by container.accounts.observe().collectAsStateWithLifecycle(initialValue = emptyList())
    var seen by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val post = loaded
    if (post == null) {
        // Sent or deleted while open: the screen has nothing more to show.
        if (seen) Gone()
        return
    }
    seen = true
    val byId = accounts.associateBy { it.id }
    val parts by produceState<Map<Long, AccountParts>>(emptyMap(), post, accounts) {
        value = withContext(Dispatchers.Default) { partsOf(post, byId, container.settings.settings.first(), PlanFreezer(container.textSegmenter)) }
    }

    fun act(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: IllegalStateException) {
                // Started sending meanwhile; the screen shows how it is now.
            } catch (_: PostNotFound) {
                // Gone meanwhile.
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column {
                kindLabel(post.kind)?.let { Text(stringResource(it), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary) }
                post.sendAt?.let { Text(stringResource(R.string.details_due_at, formatInstant(it)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val waiting = !post.started
                if (post.deliveries.any { it.state == DeliveryState.FAILED }) {
                    FilledTonalButton(onClick = { act { container.publisher.retryPost(post.id) } }, modifier = Modifier.testTag(DetailsTags.RETRY)) {
                        Text(stringResource(R.string.posts_retry))
                    }
                }
                if (waiting) {
                    FilledTonalButton(onClick = { act { container.queueActions.sendNow(post.id) } }, modifier = Modifier.testTag(DetailsTags.SEND_NOW)) {
                        Text(stringResource(R.string.posts_send_now))
                    }
                    OutlinedButton(onClick = { changing = true }, modifier = Modifier.testTag(DetailsTags.CHANGE_TIME)) {
                        Text(stringResource(R.string.posts_change_time))
                    }
                    // A deletion has nothing to edit.
                    if (post.kind != PostKind.DELETE) {
                        OutlinedButton(onClick = { onEdit(post.id) }, modifier = Modifier.testTag(DetailsTags.EDIT)) { Text(stringResource(R.string.posts_edit)) }
                    }
                }
                OutlinedButton(onClick = { deleting = true }, modifier = Modifier.testTag(DetailsTags.DELETE)) { Text(stringResource(R.string.posts_delete)) }
            }
        }
        if (post.attachments.isNotEmpty()) {
            item {
                Column {
                    Text(stringResource(R.string.details_attachments), style = MaterialTheme.typography.titleSmall)
                    for (attachment in post.attachments) {
                        Text(
                            stringResource(R.string.details_media_alt, attachment.pieceIndex + 1, attachment.alt.ifBlank { attachment.mime }),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        for (delivery in post.deliveries) {
            val account = byId[delivery.accountId]
            val handle = account?.handle ?: "?"
            item(key = "a-${delivery.accountId}") {
                Column(Modifier.testTag(DetailsTags.account(delivery.accountId))) {
                    Text(
                        if (account == null) stringResource(R.string.details_account_gone, handle) else handle,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(stringResource(stateText(delivery.state), handle), style = MaterialTheme.typography.bodyMedium)
                    delivery.lastAttemptAt?.let { Text(formatInstant(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (delivery.state == DeliveryState.FAILED) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(delivery.error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { act { container.publisher.retryDelivery(delivery.id) } }, modifier = Modifier.testTag(QueueTags.retryDelivery(delivery.id))) {
                                Text(stringResource(R.string.posts_retry))
                            }
                        }
                    }
                    delivery.progress.parts.firstNotNullOfOrNull { it.published?.url }?.let { url -> LinkButton(url, handle, delivery.accountId, snackbar) }
                }
            }
            val accountParts = parts[delivery.accountId]
            partCards(delivery.accountId, accountParts?.parts, accountParts?.blocked, delivery.plan == null && post.kind != PostKind.DELETE)
        }
    }

    if (changing) {
        val options = rememberSendLaterOptions(except = post.id)
        if (options != null) {
            SendLaterSheet(
                options = options,
                onSlot = { local ->
                    changing = false
                    act { container.queueActions.changeTime(post.id, Schedule.Slot(local)) }
                },
                onTime = { at ->
                    changing = false
                    if (at.isAfter(Instant.now())) act { container.queueActions.changeTime(post.id, Schedule.Explicit(at)) }
                },
                onOpenSchedule = {
                    changing = false
                    onOpenSchedule()
                },
                onDismiss = { changing = false },
            )
        }
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(stringResource(R.string.posts_delete_title)) },
            text = { Text(stringResource(R.string.posts_delete_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = false
                        act {
                            if (container.queueActions.delete(post.id)) onBack() else snackbar.showSnackbar(container.context.getString(R.string.posts_cannot_delete))
                        }
                    },
                    modifier = Modifier.testTag(DetailsTags.DELETE_CONFIRM),
                ) { Text(stringResource(R.string.posts_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun partsOf(post: Post, accounts: Map<Long, Account>, settings: org.evsyukov.post.data.settings.Settings, freezer: PlanFreezer): Map<Long, AccountParts> {
    val targets = post.targets.mapNotNull { accounts[it] }
    return post.deliveries.associate { delivery ->
        val plan = delivery.plan
        delivery.accountId to when {
            plan != null -> AccountParts(plan.parts.map { it.text }, null)
            post.kind == PostKind.DELETE -> AccountParts(null, null)
            else -> {
                val account = accounts[delivery.accountId]
                if (account == null) {
                    AccountParts(null, null)
                } else {
                    when (val frozen = freezer.freeze(post, account, targets.filter { it.network == account.network }, settings)) {
                        is FreezeResult.Ready -> AccountParts(frozen.plan.parts.map { it.text }, null)
                        is FreezeResult.Blocked -> AccountParts(null, frozen.message)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.partCards(accountId: Long, parts: List<String>?, blocked: String?, preview: Boolean) {
    if (blocked != null) {
        item(key = "b-$accountId") { Text(blocked, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    val list = parts ?: return
    list.forEachIndexed { index, text ->
        item(key = "p-$accountId-$index") {
            OutlinedCard(Modifier.fillMaxWidth().testTag(DetailsTags.part(accountId, index))) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (list.size > 1) {
                        Text(stringResource(R.string.composer_part_number, index + 1, list.size), style = MaterialTheme.typography.labelMedium)
                    }
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    if (preview) {
        item(key = "n-$accountId") {
            Text(stringResource(R.string.details_parts_unknown), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LinkButton(url: String, handle: String, accountId: Long, snackbar: SnackbarHostState) {
    val actions = rememberPublishedActions(snackbar, SentNavigation({}, {}, {}))
    TextButton(onClick = { actions.open(url) }, modifier = Modifier.testTag(DetailsTags.link(accountId))) {
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
        Text(stringResource(R.string.sent_open_on, handle), modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun SentDetails(historyId: Long, snackbar: SnackbarHostState, onBack: () -> Unit, navigation: SentNavigation) {
    val container = LocalAppContainer.current
    val records by container.history.observe().collectAsStateWithLifecycle(initialValue = null)
    val accounts by container.accounts.observe().collectAsStateWithLifecycle(initialValue = emptyList())
    var deleting by remember { mutableStateOf(false) }
    val actions = rememberPublishedActions(snackbar, navigation, afterDelete = onBack)
    val list = records ?: return
    val record: HistoryRecord = list.firstOrNull { it.id == historyId } ?: return Gone()
    val existing = accounts.map { it.id }.toSet()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column {
                Text(stringResource(R.string.details_sent_at, formatInstant(record.sentAt)), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(
                    stringResource(R.string.sent_kept_until, formatInstant(record.expiresAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { actions.edit(record.id) }, modifier = Modifier.testTag(DetailsTags.EDIT_PUBLISHED)) {
                    Text(stringResource(R.string.details_edit_published))
                }
                OutlinedButton(onClick = { deleting = true }, modifier = Modifier.testTag(DetailsTags.DELETE_PUBLISHED)) {
                    Text(stringResource(R.string.details_delete_published))
                }
                OutlinedButton(onClick = { actions.newFrom(record.id) }, modifier = Modifier.testTag(DetailsTags.NEW_FROM)) {
                    Text(stringResource(R.string.sent_new_from))
                }
            }
        }
        if (record.items.any { item -> item.parts.any { it.media.isNotEmpty() } }) {
            item { Text(stringResource(R.string.details_media_not_kept), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        for (item in record.items) {
            item(key = "a-${item.accountId}") {
                Column(Modifier.testTag(DetailsTags.account(item.accountId))) {
                    Text(
                        if (item.accountId in existing) item.handle else stringResource(R.string.details_account_gone, item.handle),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(stringResource(R.string.posts_state_sent, item.handle), style = MaterialTheme.typography.bodyMedium)
                    item.link()?.let { url ->
                        TextButton(onClick = { actions.open(url) }, modifier = Modifier.testTag(DetailsTags.link(item.accountId))) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                            Text(stringResource(R.string.sent_open_on, item.handle), modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
            val texts = item.parts.map { it.text ?: if (item.parts.size == 1) record.text else "" }
            partCards(item.accountId, texts, null, preview = false)
            val alts = item.parts.flatMapIndexed { index, part -> part.media.map { index to it.alt } }
            if (alts.isNotEmpty()) {
                item(key = "m-${item.accountId}") {
                    Column {
                        for ((index, alt) in alts) {
                            Text(stringResource(R.string.details_media_alt, index + 1, alt.ifBlank { "—" }), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
    if (deleting) {
        DeletePublishedDialog(
            record = record,
            existing = existing,
            onConfirm = { ids ->
                deleting = false
                actions.delete(record.id, ids)
            },
            onDismiss = { deleting = false },
        )
    }
}

private fun kindLabel(kind: PostKind): Int? = when (kind) {
    PostKind.NEW -> null
    PostKind.EDIT -> R.string.details_kind_edit
    PostKind.DELETE -> R.string.details_kind_delete
}

private fun stateText(state: DeliveryState): Int = when (state) {
    DeliveryState.WAITING -> R.string.posts_state_waiting
    DeliveryState.SENDING -> R.string.posts_state_sending
    DeliveryState.SENT -> R.string.posts_state_sent
    DeliveryState.FAILED -> R.string.posts_state_failed
}
