package org.evsyukov.post.ui.composer

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.evsyukov.post.R
import org.evsyukov.post.core.markdown.HighlightSpan
import org.evsyukov.post.core.markdown.highlight
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.ui.schedule.SendLaterSheet
import org.evsyukov.post.ui.schedule.rememberSendLaterOptions
import org.evsyukov.post.ui.LocalAppContainer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object ComposerTags {
    const val SCREEN = "composer"
    const val POSTS = "composer-posts"
    const val POSTS_BADGE = "composer-posts-badge"
    const val POSTS_LATE_BADGE = "composer-posts-late-badge"
    const val SETTINGS = "composer-settings"
    const val MORE = "composer-more"
    const val SCHEDULE = "composer-schedule"
    const val EDITOR = "composer-editor"
    const val SPOILER = "composer-spoiler"
    const val TARGETS = "composer-targets"
    const val STATUS = "composer-status"
    const val TOOLBAR = "composer-toolbar"
    const val ATTACH = "composer-attach"
    const val BOLD = "composer-bold"
    const val ITALIC = "composer-italic"
    const val STRIKE = "composer-strike"
    const val CODE = "composer-code"
    const val QUOTE = "composer-quote"
    const val LIST = "composer-list"
    const val LINK = "composer-link"
    const val CW = "composer-cw"
    const val LANGUAGE = "composer-language"
    const val OVERFLOW = "composer-overflow"
    const val MENU_POLL = "composer-menu-poll"
    const val MENU_BREAK = "composer-menu-break"
    const val MENU_PREVIEW = "composer-menu-preview"
    const val MENU_OPTIONS = "composer-menu-options"
    const val MENU_SAVE = "composer-menu-save"
    const val MENU_CLEAR = "composer-menu-clear"
    const val SEND = "composer-send"
    const val SEND_LATER = "composer-send-later"
    const val LINK_URL = "composer-link-url"
    const val LINK_CLIPBOARD = "composer-link-clipboard"
    const val LINK_INSERT = "composer-link-insert"
    const val LEAVE_KEEP = "composer-leave-keep"
    const val LEAVE_DISCARD = "composer-leave-discard"
    const val LEAVE_CANCEL = "composer-leave-cancel"
    const val CLEAR_CONFIRM = "composer-clear-confirm"
    const val DETAILS = "composer-details"
    const val DETAILS_PREVIEW = "composer-details-preview"
    const val PREVIEW = "composer-preview"
    const val ACCOUNT_OPTIONS = "composer-account-options"
    const val OPTIONS_LIST = "composer-options-list"
    const val POLL = "composer-poll"
    const val POLL_ADD = "composer-poll-add"
    const val POLL_MULTIPLE = "composer-poll-multiple"
    const val POLL_REMOVE = "composer-poll-remove"
    const val TIME_CONFIRM = "composer-time-confirm"
    const val DATE_CONFIRM = "composer-date-confirm"

    const val VISIBILITY_LOCKED = "composer-visibility-locked"
    const val EDIT_POLL_WARNING = "composer-edit-poll-warning"
    const val EDIT_UNAVAILABLE = "composer-edit-unavailable"
    const val KEPT_MEDIA = "composer-kept-media"

    fun target(id: Long) = "composer-target-$id"
    fun previewMark(profile: Int, part: Int) = "composer-preview-mark-$profile-$part"
    fun previewDeleted(profile: Int) = "composer-preview-deleted-$profile"
    fun blueskyLeave(id: Long) = "composer-bluesky-leave-$id"
    fun blueskyRepost(id: Long) = "composer-bluesky-repost-$id"
    fun keptMedia(part: Int, index: Int) = "composer-kept-media-$part-$index"
    fun keptMediaToggle(part: Int, index: Int) = "composer-kept-media-toggle-$part-$index"
    fun breakRemove(index: Int) = "composer-break-remove-$index"
    fun statusEntry(index: Int) = "composer-status-$index"
    fun previewPart(profile: Int, part: Int) = "composer-preview-$profile-$part"
    fun language(code: String) = "composer-language-$code"
    fun pollOption(index: Int) = "composer-poll-option-$index"
    fun pollOptionRemove(index: Int) = "composer-poll-option-remove-$index"
    fun pollDuration(seconds: Long) = "composer-poll-duration-$seconds"
    fun optionsAccount(id: Long) = "composer-options-account-$id"
}

/** The key of the composer's model: one per draft, published post being edited, or sent post started from. */
fun composerModelKey(draftId: Long?, editHistoryId: Long? = null, fromHistoryId: Long? = null, session: Long = 0): String = when {
    editHistoryId != null -> "composer-edit-$editHistoryId-$session"
    fromHistoryId != null -> "composer-from-$fromHistoryId-$session"
    session != 0L -> "composer-${draftId ?: "new"}-$session"
    else -> "composer-${draftId ?: "new"}"
}

/** Where the composer goes when the user leaves it. */
enum class LeaveAction { EXIT, POSTS, SETTINGS }

/** The sheets of the composer; one is open at a time. */
private sealed interface Sheet {
    data object Details : Sheet
    data object Preview : Sheet
    data object OptionsList : Sheet
    data class AccountOptions(val accountId: Long) : Sheet
    data class Attachment(val id: Long) : Sheet
}

/**
 * The start screen (screens.md, Composer): targets, the Markdown editor, the status line of counters and
 * the toolbar above the keyboard. [draftId] opens a saved draft; null starts a new one with the default
 * targets. [onExit] leaves the composer by the system back, after the R15 question when something is
 * unsaved.
 */
@Composable
fun ComposerScreen(
    draftId: Long? = null,
    onOpenPosts: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenSettings: () -> Unit,
    onExit: () -> Unit = {},
    /** Edit the published post of this history record (R40). */
    editHistoryId: Long? = null,
    /** A new post from this history record (R31). */
    fromHistoryId: Long? = null,
    /** Tells apart two openings of the same edit (see [org.evsyukov.post.ui.ComposerKey.session]). */
    session: Long = 0,
) {
    val container = LocalAppContainer.current
    val model: ComposerModel = viewModel(key = composerModelKey(draftId, editHistoryId, fromHistoryId, session)) {
        ComposerModel(container, draftId, editHistoryId, fromHistoryId, createSavedStateHandle())
    }
    ComposerContent(model, onOpenPosts, onOpenSchedule, onOpenSettings, onExit)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerContent(
    model: ComposerModel,
    onOpenPosts: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenSettings: () -> Unit,
    onExit: () -> Unit,
) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val accounts by model.accounts.collectAsStateWithLifecycle()
    val settings by model.settings.collectAsStateWithLifecycle()
    val queueCount by model.queueCount.collectAsStateWithLifecycle()
    val lateCount by model.lateCount.collectAsStateWithLifecycle()

    var topMenu by rememberSaveable { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var leaving by remember { mutableStateOf<LeaveAction?>(null) }
    var linkDialog by remember { mutableStateOf(false) }
    var languageDialog by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    var sendLater by remember { mutableStateOf(false) }

    val unsaved by remember { derivedStateOf { model.hasUnsavedChanges() } }
    // The accounts and settings come from flows, which a derived state does not observe; they key it instead.
    val blockers by remember(accounts, settings) { derivedStateOf { model.currentBlockers() } }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    // R9: picked files are copied into app storage right away, while the grant of the picker lasts.
    fun attach(uris: List<android.net.Uri>) = model.attach(uris) { failed ->
        if (failed > 0) scope.launch { snackbar.showSnackbar(resources.getQuantityString(R.plurals.media_import_failed, failed, failed)) }
    }
    // media.md A1: unsaved files whose copies were deleted while the process was gone are dropped, and the screen says so.
    val missing = model.missingFiles
    LaunchedEffect(missing) {
        if (missing > 0) {
            // The count goes back to 0 and restarts this effect, so the snackbar runs on the screen's scope.
            model.missingFilesShown()
            scope.launch { snackbar.showSnackbar(resources.getQuantityString(R.plurals.media_files_missing, missing, missing)) }
        }
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { attach(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { attach(it) }
    val media by remember(accounts, settings) { derivedStateOf { model.currentMedia() } }
    val currentPiece by remember { derivedStateOf { model.currentPiece() } }

    // R32: the first send asks for notifications once; the send does not wait for the answer.
    fun askNotificationsOnce() {
        val current = settings ?: return
        if (current.notificationPermissionAsked) return
        container.scope.launch { container.settings.setNotificationPermissionAsked(true) }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun send(schedule: Schedule?) {
        // A queued post opened from the queue goes back there once it is saved or given a new time.
        val editing = model.editingQueued
        scope.launch {
            when (val result = model.send(schedule)) {
                is ComposerModel.SendResult.Blocked -> sheet = Sheet.Details
                ComposerModel.SendResult.AlreadySending -> Unit
                ComposerModel.SendResult.Saved -> onExit()
                ComposerModel.SendResult.NoLongerEditable -> snackbar.showSnackbar(resources.getString(R.string.schedule_edit_not_saved))
                is ComposerModel.SendResult.Queued -> {
                    if (editing) {
                        onExit()
                        return@launch
                    }
                    askNotificationsOnce()
                    val message = when (val time = result.schedule) {
                        is Schedule.Slot -> resources.getString(
                            R.string.composer_queued_for,
                            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(time.local),
                        )
                        is Schedule.Explicit -> resources.getString(
                            R.string.composer_queued_for,
                            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                                .withZone(ZoneId.systemDefault()).format(time.at),
                        )
                        else -> resources.getQuantityString(R.plurals.composer_sending_to, result.accounts, result.accounts)
                    }
                    snackbar.showSnackbar(message)
                }
            }
        }
    }

    fun perform(action: LeaveAction) = when (action) {
        LeaveAction.EXIT -> onExit()
        LeaveAction.POSTS -> onOpenPosts()
        LeaveAction.SETTINGS -> onOpenSettings()
    }

    // R15: leaving with unsaved changes asks first.
    fun guard(action: LeaveAction) {
        if (model.hasUnsavedChanges()) leaving = action else perform(action)
    }

    BackHandler(enabled = unsaved) { leaving = LeaveAction.EXIT }

    Scaffold(
        modifier = Modifier.testTag(ComposerTags.SCREEN),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (model.editing != null || model.editUnavailable) R.string.edit_title else R.string.app_name)) },
                actions = {
                    val postsLabel = if (lateCount > 0) {
                        pluralStringResource(R.plurals.schedule_late_title, lateCount, lateCount)
                    } else if (queueCount > 0) {
                        pluralStringResource(R.plurals.composer_posts_queued, queueCount, queueCount)
                    } else {
                        stringResource(R.string.posts)
                    }
                    IconButton(onClick = { guard(LeaveAction.POSTS) }, modifier = Modifier.testTag(ComposerTags.POSTS)) {
                        BadgedBox(badge = {
                            // Q7: a warning while the Late group is not empty, else the number of queued posts.
                            if (lateCount > 0) {
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.testTag(ComposerTags.POSTS_LATE_BADGE),
                                ) { Icon(Icons.Filled.PriorityHigh, contentDescription = null, modifier = Modifier.size(10.dp)) }
                            } else if (queueCount > 0) {
                                Badge(Modifier.testTag(ComposerTags.POSTS_BADGE)) { Text(queueCount.toString()) }
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Outlined.List, contentDescription = postsLabel)
                        }
                    }
                    IconButton(onClick = { guard(LeaveAction.SETTINGS) }, modifier = Modifier.testTag(ComposerTags.SETTINGS)) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
                    }
                    Box {
                        IconButton(onClick = { topMenu = true }, modifier = Modifier.testTag(ComposerTags.MORE)) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
                        }
                        DropdownMenu(expanded = topMenu, onDismissRequest = { topMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.schedule)) },
                                leadingIcon = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
                                onClick = {
                                    topMenu = false
                                    onOpenSchedule()
                                },
                                modifier = Modifier.testTag(ComposerTags.SCHEDULE),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            val editing = model.editing
            // R40: an edit lists only the accounts that hold the post; Bluesky ones choose below (N32).
            val holders = model.holders.toSet()
            TargetsRow(
                accounts = if (editing == null) accounts.orEmpty() else accounts.orEmpty().filter { it.id in holders && it.network == Network.MASTODON },
                selected = model.targets,
                onToggle = model::toggleTarget,
                onOptions = { sheet = Sheet.AccountOptions(it) },
            )
            if (editing != null) {
                BlueskyEditChoices(
                    accounts = accounts.orEmpty().filter { it.id in holders && it.network == Network.BLUESKY },
                    targets = model.targets,
                    onToggle = model::toggleTarget,
                )
            }
            if (model.editUnavailable) {
                Text(
                    stringResource(R.string.edit_not_available),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(ComposerTags.EDIT_UNAVAILABLE),
                )
            }
            HorizontalDivider()
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (model.spoilerShown) {
                    OutlinedTextField(
                        state = model.spoiler,
                        label = { Text(stringResource(R.string.content_warning)) },
                        lineLimits = TextFieldLineLimits.SingleLine,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag(ComposerTags.SPOILER),
                    )
                }
                // The field appears once the draft or the defaults are in place, so nothing typed is overwritten.
                if (model.loaded) MarkdownEditor(
                    state = model.text,
                    onRemoveBreak = { range ->
                        model.apply(MarkdownEdits.removeBreak(model.text.text.toString(), range.start, range.end))
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                model.poll?.let { poll ->
                    PollEditor(
                        poll = poll,
                        limits = model.pollLimits(),
                        onChange = model::changePoll,
                        onRemove = { model.changePoll(null) },
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    if (model.editing?.options?.poll != null) {
                        // N28: changing the options of a published poll resets its votes.
                        Text(
                            stringResource(R.string.edit_poll_warning),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp).testTag(ComposerTags.EDIT_POLL_WARNING),
                        )
                    }
                }
                if (model.editing != null) {
                    KeptMediaList(
                        kept = model.keptMedia,
                        onAlt = model::setKeptMediaAlt,
                        onToggle = model::toggleKeptMedia,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
            AttachmentStrip(
                attachments = model.attachmentsOf(currentPiece),
                conflicted = conflictedIds(media),
                adding = model.importing > 0,
                fileOf = { container.attachmentImporter.file(it.path) },
                onOpen = { sheet = Sheet.Attachment(it) },
            )
            StatusLine(
                renders = model.renders?.second,
                hasTargets = model.targets.isNotEmpty(),
                onClick = { sheet = Sheet.Details },
            )
            ComposerToolbar(
                canSend = blockers.isEmpty(),
                spoilerShown = model.spoilerShown,
                language = model.language,
                hasPoll = model.poll != null,
                onPickMedia = {
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                onPickFiles = { pickFiles.launch(arrayOf("image/*", "video/*", "audio/*")) },
                onWrap = { marker ->
                    val sel = model.selection()
                    model.apply(MarkdownEdits.wrap(model.text.text.toString(), sel.start, sel.end, marker))
                },
                onPrefix = { prefix ->
                    val sel = model.selection()
                    model.apply(MarkdownEdits.toggleLinePrefix(model.text.text.toString(), sel.start, sel.end, prefix))
                },
                onLink = { linkDialog = true },
                onSpoiler = { model.showSpoiler(!model.spoilerShown) },
                onLanguage = { languageDialog = true },
                onPoll = { if (model.poll == null) model.addPoll() else model.changePoll(null) },
                onBreak = { model.apply(MarkdownEdits.insertBreak(model.text.text.toString(), model.selection().max)) },
                onPreview = { sheet = Sheet.Preview },
                onOptions = { sheet = Sheet.OptionsList },
                onSaveDraft = {
                    scope.launch {
                        val save = model.keepDraft()
                        if (save.blocked.isNotEmpty()) {
                            sheet = Sheet.Details
                            return@launch
                        }
                        snackbar.showSnackbar(
                            resources.getString(if (save.keptAsNew) R.string.schedule_edit_kept_as_draft else R.string.composer_draft_saved),
                        )
                    }
                },
                onClear = { clearDialog = true },
                // A tap while a send runs is a double tap: it does nothing.
                onSendLater = {
                    when {
                        model.sending -> Unit
                        blockers.isEmpty() -> sendLater = true
                        else -> sheet = Sheet.Details
                    }
                },
                onSend = {
                    when {
                        model.sending -> Unit
                        blockers.isEmpty() -> send(null)
                        else -> sheet = Sheet.Details
                    }
                },
                savesQueued = model.editingQueued,
                savesPublished = model.editing != null || model.editUnavailable,
            )
        }
    }

    when (val open = sheet) {
        null -> Unit
        Sheet.Details -> DetailsSheet(
            source = model.text.text.toString(),
            renders = model.renders?.second.orEmpty(),
            blockers = blockers,
            formattingRemoved = model.targets.isNotEmpty() && model.renders?.second.orEmpty().let { list ->
                list.isNotEmpty() && list.none { it.render.profile.keepsFormatting }
            } && hasFormatting(model.text.text.toString()),
            media = media,
            chosenFixes = model.chosenFixes,
            onFix = model::chooseFix,
            onUndoFix = model::clearFix,
            onPreview = { sheet = Sheet.Preview },
            onDismiss = { sheet = null },
        )
        Sheet.Preview -> {
            val renders = model.renders?.second.orEmpty()
            val record = model.editing
            PreviewSheet(
                renders = renders,
                onDismiss = { sheet = null },
                marks = if (record == null) emptyList() else editMarks(renders, record, model.keptMedia),
            )
        }
        Sheet.OptionsList -> OptionsListSheet(
            accounts = model.selectedAccounts(),
            onPick = { sheet = Sheet.AccountOptions(it) },
            onDismiss = { sheet = null },
        )
        is Sheet.Attachment -> {
            val attachment = model.attachments.firstOrNull { it.id == open.id }
            if (attachment == null) {
                sheet = null
            } else {
                AttachmentSheet(
                    attachment = attachment,
                    file = container.attachmentImporter.file(attachment.path),
                    altLimits = media.map { AltLimit(it.network, it.rules.maxAltLength) },
                    onAlt = { model.setAlt(attachment.id, it) },
                    onFocus = { x, y -> model.setFocus(attachment.id, x, y) },
                    onRemove = {
                        sheet = null
                        scope.launch { model.removeAttachment(attachment.id) }
                    },
                    onDismiss = { sheet = null },
                )
            }
        }
        is Sheet.AccountOptions -> {
            val account = accounts.orEmpty().firstOrNull { it.id == open.accountId }
            if (account == null) {
                sheet = null
            } else {
                AccountOptionsSheet(
                    account = account,
                    options = model.optionsFor(account),
                    onChange = { model.setOptions(account.id, it) },
                    onDismiss = { sheet = null },
                    visibilityLocked = model.editing != null,
                )
            }
        }
    }

    if (linkDialog) {
        LinkDialog(
            onInsert = { url ->
                linkDialog = false
                val sel = model.selection()
                model.apply(MarkdownEdits.link(model.text.text.toString(), sel.start, sel.end, url))
            },
            onDismiss = { linkDialog = false },
        )
    }
    if (languageDialog) {
        LanguageDialog(
            current = model.language,
            onPick = {
                languageDialog = false
                model.chooseLanguage(it)
            },
            onDismiss = { languageDialog = false },
        )
    }
    if (sendLater) {
        val options = rememberSendLaterOptions(except = model.draftId)
        if (options != null) {
            SendLaterSheet(
                options = options,
                onSlot = { local ->
                    sendLater = false
                    send(Schedule.Slot(local))
                },
                onTime = { at ->
                    sendLater = false
                    if (at.isAfter(Instant.now())) {
                        send(Schedule.Explicit(at))
                    } else {
                        scope.launch { snackbar.showSnackbar(resources.getString(R.string.composer_time_in_past)) }
                    }
                },
                onOpenSchedule = {
                    sendLater = false
                    onOpenSchedule()
                },
                onDismiss = { sendLater = false },
            )
        }
    }
    if (clearDialog) {
        AlertDialog(
            onDismissRequest = { clearDialog = false },
            title = { Text(stringResource(R.string.composer_clear_title)) },
            text = { Text(stringResource(R.string.composer_clear_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearDialog = false
                        scope.launch { model.clearDraft() }
                    },
                    modifier = Modifier.testTag(ComposerTags.CLEAR_CONFIRM),
                ) { Text(stringResource(R.string.composer_clear)) }
            },
            dismissButton = { TextButton(onClick = { clearDialog = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    leaving?.let { action ->
        AlertDialog(
            onDismissRequest = { leaving = null },
            title = { Text(stringResource(R.string.composer_leave_title)) },
            text = { Text(stringResource(R.string.composer_leave_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        leaving = null
                        scope.launch {
                            // An edited post that started sending meanwhile is kept as a new draft (Q2). A queued
                            // post that cannot be sent as it is stays open with the reasons shown.
                            if (model.keepDraft().blocked.isNotEmpty()) {
                                sheet = Sheet.Details
                                return@launch
                            }
                            perform(action)
                        }
                    },
                    modifier = Modifier.testTag(ComposerTags.LEAVE_KEEP),
                ) { Text(stringResource(R.string.composer_keep_draft)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        leaving = null
                        scope.launch {
                            model.discardChanges()
                            perform(action)
                        }
                    },
                    modifier = Modifier.testTag(ComposerTags.LEAVE_DISCARD),
                ) { Text(stringResource(R.string.composer_discard)) }
                TextButton(onClick = { leaving = null }, modifier = Modifier.testTag(ComposerTags.LEAVE_CANCEL)) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** Whether [text] has markup that a plain-text account loses (F6); a manual break is not formatting. */
fun hasFormatting(text: String): Boolean = highlight(text).any { it.kind != HighlightSpan.Kind.BREAK_MARKER }
