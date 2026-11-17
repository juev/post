package org.evsyukov.post.share

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.evsyukov.post.R
import org.evsyukov.post.core.render.TextIssue
import org.evsyukov.post.core.render.TextRenderer
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.data.settings.ShareAction
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.composer.AttachmentStrip
import org.evsyukov.post.ui.composer.Blocker
import org.evsyukov.post.ui.composer.SendLaterPicker
import org.evsyukov.post.ui.composer.TargetsRow
import org.evsyukov.post.ui.composer.chipLabel
import org.evsyukov.post.ui.composer.conflictName
import org.evsyukov.post.ui.composer.conflictedIds
import org.evsyukov.post.ui.composer.fixName
import org.evsyukov.post.ui.composer.statusText
import org.evsyukov.post.ui.schedule.SendLaterSheet
import org.evsyukov.post.ui.schedule.rememberSendLaterOptions
import org.evsyukov.post.ui.settings.accounts.networkName
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object ShareTags {
    const val SHEET = "share-sheet"
    const val SCRIM = "share-scrim"
    const val TEXT = "share-text"
    const val STATUS = "share-status"
    const val COPYING = "share-copying"
    const val FAILED = "share-failed"
    const val MISSING = "share-missing"
    const val CHANGES = "share-changes"
    const val PROBLEMS = "share-problems"
    const val OPEN_COMPOSER = "share-open-composer"
    const val SEND_LATER = "share-send-later"
    const val SEND = "share-send"

    fun change(index: Int) = "share-change-$index"
    fun problem(index: Int) = "share-problem-$index"
}

/**
 * The Share sheet (screens.md, Share sheet; R20–R22): a sheet at the bottom of a translucent window over the app
 * that shared. [onDone] gets the message to show once the post is queued; [onOpenComposer] the draft to open,
 * with Schedule over it when [onOpenComposer]'s second argument is true; [onDismiss] closes without posting.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShareSheet(
    model: ShareModel,
    onDone: (String) -> Unit,
    onOpenComposer: (draftId: Long, openSchedule: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val container = LocalAppContainer.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    // The accounts and settings come from flows, which a derived state does not observe; they key it instead.
    val check by remember(accounts, settings) { derivedStateOf { model.currentCheck() } }
    var picking by rememberSaveable { mutableStateOf(false) }
    var moreTimes by rememberSaveable { mutableStateOf(false) }
    val defaultAction = settings?.shareAction ?: ShareAction.COMPOSER

    // R32: the first send asks for notifications once, as the composer does; the sheet closes after the answer,
    // since the request needs this activity.
    val context = LocalContext.current
    var closingWith by rememberSaveable { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        closingWith?.let { message ->
            closingWith = null
            onDone(message)
        }
    }

    fun queued(message: String) {
        val current = settings
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (current == null || current.notificationPermissionAsked || granted) {
            onDone(message)
            return
        }
        container.scope.launch { container.settings.setNotificationPermissionAsked(true) }
        closingWith = message
        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun finish(outcome: ShareModel.Outcome) {
        when (outcome) {
            is ShareModel.Outcome.Queued -> queued(queuedMessage(resources, outcome))
            is ShareModel.Outcome.Draft -> onOpenComposer(outcome.id, false)
            ShareModel.Outcome.NeedsTime -> picking = true
            is ShareModel.Outcome.Blocked, ShareModel.Outcome.Ignored -> Unit
        }
    }

    fun sendAt(at: Instant) {
        if (at.isAfter(Instant.now())) scope.launch { finish(model.send(Schedule.Explicit(at))) }
    }

    val closeLabel = stringResource(R.string.share_close)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = closeLabel, onClick = onDismiss)
                .semantics { contentDescription = closeLabel }
                .testTag(ShareTags.SCRIM),
        )
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .statusBarsPadding()
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .testTag(ShareTags.SHEET),
        ) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 20.dp, bottom = 12.dp),
            ) {
                Text(
                    stringResource(R.string.share_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                TargetsRow(accounts = accounts.orEmpty(), selected = model.targets.orEmpty(), onToggle = model::toggleTarget, onOptions = null)
                OutlinedTextField(
                    value = model.text,
                    onValueChange = model::editText,
                    label = { Text(stringResource(R.string.share_text)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(ShareTags.TEXT),
                )
                AttachmentStrip(
                    attachments = model.attachments,
                    conflicted = conflictedIds(check?.media.orEmpty()),
                    adding = model.importing,
                    fileOf = { container.attachmentImporter.file(it.path) },
                    onOpen = null,
                )
                if (model.importing) {
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 4.dp).testTag(ShareTags.COPYING), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.share_copying), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (model.failedFiles > 0) {
                    Text(
                        pluralStringResource(R.plurals.media_import_failed, model.failedFiles, model.failedFiles),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).testTag(ShareTags.FAILED),
                    )
                }
                if (model.missingFiles > 0) {
                    Text(
                        pluralStringResource(R.plurals.media_files_missing, model.missingFiles, model.missingFiles),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).testTag(ShareTags.MISSING),
                    )
                }
                StatusRow(check, hasTargets = model.targets.orEmpty().isNotEmpty())
                check?.let { current ->
                    Changes(current)
                    // R22: a media conflict names the selected accounts of its network, never the unselected ones.
                    val selected = model.targets.orEmpty().toSet()
                    Problems(current.blockers, accounts.orEmpty().filter { it.id in selected })
                }
                HorizontalDivider(Modifier.padding(top = 8.dp))
                val canSend = !model.busy && check?.blockers?.isEmpty() == true
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ActionButton(
                        text = stringResource(R.string.share_open_composer),
                        filled = defaultAction == ShareAction.COMPOSER,
                        // A13: a conflict never takes this way out away; only the copy of the files holds it.
                        enabled = !model.busy && !model.importing,
                        tag = ShareTags.OPEN_COMPOSER,
                        onClick = { scope.launch { finish(model.saveDraft()) } },
                    )
                    ActionButton(
                        text = stringResource(R.string.share_send_later),
                        filled = defaultAction == ShareAction.SEND_LATER,
                        enabled = canSend,
                        tag = ShareTags.SEND_LATER,
                        onClick = { scope.launch { finish(model.sendLater()) } },
                        onLongClick = { moreTimes = true },
                        longClickLabel = stringResource(R.string.share_more_times),
                    )
                    ActionButton(
                        text = stringResource(R.string.share_send),
                        filled = defaultAction == ShareAction.SEND_NOW,
                        enabled = canSend,
                        tag = ShareTags.SEND,
                        onClick = { scope.launch { finish(model.send(null)) } },
                    )
                }
            }
        }
    }

    if (picking) {
        SendLaterPicker(
            onPick = { at ->
                picking = false
                sendAt(at)
            },
            onDismiss = { picking = false },
        )
    }
    if (moreTimes) {
        val options = rememberSendLaterOptions()
        if (options != null) {
            SendLaterSheet(
                options = options,
                onSlot = { local ->
                    moreTimes = false
                    scope.launch { finish(model.send(Schedule.Slot(local))) }
                },
                onTime = { at ->
                    moreTimes = false
                    sendAt(at)
                },
                onOpenSchedule = {
                    moreTimes = false
                    // The share is kept as a draft, so setting up the schedule loses nothing of it.
                    scope.launch {
                        val outcome = model.saveDraft()
                        if (outcome is ShareModel.Outcome.Draft) onOpenComposer(outcome.id, true)
                    }
                },
                onDismiss = { moreTimes = false },
            )
        }
    }
}

/** "Sending to N accounts" for Send now (R21), "Queued for <time>" for a slot or a time. */
private fun queuedMessage(resources: android.content.res.Resources, outcome: ShareModel.Outcome.Queued): String {
    val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    return when (val time = outcome.schedule) {
        is Schedule.Now -> resources.getQuantityString(R.plurals.composer_sending_to, outcome.accounts, outcome.accounts)
        is Schedule.Slot -> resources.getString(R.string.composer_queued_for, format.format(time.local))
        is Schedule.Explicit -> resources.getString(R.string.composer_queued_for, format.withZone(ZoneId.systemDefault()).format(time.at))
    }
}

/** The counters and parts per network (screens.md, Share sheet). */
@Composable
private fun StatusRow(check: ShareModel.Check?, hasTargets: Boolean) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp).semantics(mergeDescendants = true) {}.testTag(ShareTags.STATUS),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!hasTargets) {
            Text(stringResource(R.string.share_no_target), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
        } else {
            check?.renders?.forEach { render ->
                Text(
                    statusText(render),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (render.render.issue != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** What the default fixes of the settings change (A12). */
@Composable
private fun Changes(check: ShareModel.Check) {
    val changes = check.media.flatMap { media -> media.plan.resolved.map { media.network to it } }
    if (changes.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp).testTag(ShareTags.CHANGES)) {
        Text(stringResource(R.string.share_will_change), style = MaterialTheme.typography.titleSmall)
        changes.forEachIndexed { index, (network, resolved) ->
            Text(
                stringResource(R.string.share_change, networkName(network), conflictName(resolved.conflict.kind), fixName(resolved.fix)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(ShareTags.change(index)),
            )
        }
    }
}

/** Why Send and Send later are disabled (R22, A13), each line naming the accounts and the problem. */
@Composable
private fun Problems(blockers: List<Blocker>, accounts: List<Account>) {
    val lines = blockers.mapNotNull { problemText(it, accounts) }
    if (lines.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp).testTag(ShareTags.PROBLEMS)) {
        lines.forEachIndexed { index, line ->
            Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(ShareTags.problem(index)))
        }
        Text(stringResource(R.string.share_resolve_in_composer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun problemText(blocker: Blocker, accounts: List<Account>): String? = when (blocker) {
    Blocker.Importing -> null
    Blocker.NoTarget -> stringResource(R.string.share_problem_no_target)
    Blocker.EmptyPost -> stringResource(R.string.share_problem_empty)
    is Blocker.Text -> stringResource(
        R.string.share_problem,
        blocker.render.accounts.joinToString(", ") { it.chipLabel() },
        networkName(blocker.render.network),
        when (val issue = blocker.issue) {
            is TextIssue.TooLongToSplit -> stringResource(R.string.share_problem_too_long, blocker.render.render.limit)
            is TextIssue.TooManyParts -> stringResource(R.string.share_problem_too_many_parts, issue.count, TextRenderer.MAX_PARTS)
            is TextIssue.SpoilerTooLong -> stringResource(R.string.share_problem_spoiler)
        },
    )
    is Blocker.Media -> stringResource(
        R.string.share_problem,
        accounts.filter { it.network == blocker.network }.joinToString(", ") { it.chipLabel() },
        networkName(blocker.network),
        conflictName(blocker.conflict.kind),
    )
    is Blocker.Poll -> stringResource(R.string.share_problem_poll)
    is Blocker.AltMissing -> stringResource(R.string.share_problem_alt_missing)
    is Blocker.AltTooLong -> stringResource(R.string.share_problem_alt_too_long)
}

/**
 * A button of the sheet: filled for the default action of the settings (R18), outlined for the others. Send later
 * also takes a long press, which a Material button does not.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionButton(
    text: String,
    filled: Boolean,
    enabled: Boolean,
    tag: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val container = when {
        !filled -> Color.Transparent
        enabled -> scheme.primary
        else -> scheme.onSurface.copy(alpha = 0.12f)
    }
    val content = when {
        !enabled -> scheme.onSurface.copy(alpha = 0.38f)
        filled -> scheme.onPrimary
        else -> scheme.primary
    }
    val defaultLabel = stringResource(R.string.share_default_action)
    Surface(
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = content,
        border = if (filled) null else BorderStroke(1.dp, if (enabled) scheme.outline else scheme.onSurface.copy(alpha = 0.12f)),
    ) {
        Box(
            Modifier
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    onLongClickLabel = longClickLabel,
                )
                .semantics { if (filled) stateDescription = defaultLabel }
                .testTag(tag)
                .heightIn(min = 48.dp)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}
