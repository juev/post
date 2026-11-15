package org.evsyukov.post.ui.composer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.evsyukov.post.R
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.posts.PollDraft
import org.evsyukov.post.ui.settings.accounts.AccountAvatar
import org.evsyukov.post.ui.settings.accounts.networkName
import java.util.Locale

/** The name of an account on its chip: its label, or its handle. */
fun Account.chipLabel(): String = label.ifBlank { handle }

/**
 * One chip per account, grouped by network (R7): a tap selects or clears it, a long press opens its options
 * for this post (R17). The row scrolls sideways.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TargetsRow(accounts: List<Account>, selected: List<Long>, onToggle: (Long) -> Unit, onOptions: (Long) -> Unit) {
    if (accounts.isEmpty()) {
        Text(
            stringResource(R.string.composer_no_accounts),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).testTag(ComposerTags.TARGETS),
        )
        return
    }
    val groups = accounts.sortedBy { it.position }.groupBy { it.network }.toSortedMap(compareBy { it.ordinal })
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(ComposerTags.TARGETS),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        groups.entries.forEachIndexed { index, (network, list) ->
            if (index > 0) VerticalDivider(Modifier.height(24.dp))
            for (account in list) {
                TargetChip(account, network, account.id in selected, { onToggle(account.id) }, { onOptions(account.id) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TargetChip(account: Account, network: Network, isSelected: Boolean, onToggle: () -> Unit, onOptions: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val networkLabel = networkName(network)
    val description = stringResource(R.string.composer_target_description, networkLabel, account.chipLabel())
    val optionsLabel = stringResource(R.string.composer_account_options)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) scheme.secondaryContainer else scheme.surface,
        modifier = Modifier
            .border(1.dp, if (isSelected) scheme.secondaryContainer else scheme.outline, RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onToggle, onLongClick = onOptions, onLongClickLabel = optionsLabel, role = Role.Checkbox)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                selected = isSelected
                customActions = listOf(CustomAccessibilityAction(optionsLabel) { onOptions(); true })
            }
            .testTag(ComposerTags.target(account.id)),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                AccountAvatar(account.avatarUrl, account.displayName.ifBlank { account.handle }, size = 24.dp)
            }
            Spacer(Modifier.width(6.dp))
            Text(
                if (network == Network.MASTODON) "M" else "B",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.primary,
            )
            Spacer(Modifier.width(4.dp))
            Text(account.chipLabel(), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The text of one status line entry: "Mastodon 212/500", "Bluesky 4 parts", with ⚠ for an issue or a long thread (R8, T12). */
@Composable
fun statusText(render: ProfileRender): String {
    val name = networkName(render.network)
    val r = render.render
    val warn = r.issue != null || r.manyPartsWarning || !render.mediaReady
    val body = when {
        r.issue != null -> stringResource(R.string.composer_status_issue, name)
        r.parts.size <= 1 -> stringResource(R.string.composer_status_count, name, r.parts.firstOrNull()?.count ?: 0, r.limit)
        else -> pluralStringResource(R.plurals.composer_status_parts, r.parts.size, name, r.parts.size)
    }
    return if (warn && r.issue == null) "$body ⚠" else body
}

/** One entry per selected text profile; a tap opens the details sheet (screens.md, Status line). */
@Composable
fun StatusLine(renders: List<ProfileRender>?, hasTargets: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.composer_details), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(ComposerTags.STATUS),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            !hasTargets -> Text(stringResource(R.string.composer_status_no_target), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            renders == null -> Text("", style = MaterialTheme.typography.labelLarge)
            else -> renders.forEachIndexed { index, render ->
                val r = render.render
                Text(
                    statusText(render),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (r.issue != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(ComposerTags.statusEntry(index)),
                )
            }
        }
    }
}

/**
 * The toolbar above the keyboard (screens.md, Composer and Formatting): attach on the left, the formatting
 * buttons, content warning, language and the ⋯ menu in a part that scrolls sideways when narrow, and Send
 * later and Send fixed on the right. Send looks disabled while something blocks it, but a tap still opens
 * the details sheet.
 */
@Composable
fun ComposerToolbar(
    canSend: Boolean,
    spoilerShown: Boolean,
    language: String,
    hasPoll: Boolean,
    onPickMedia: () -> Unit,
    onPickFiles: () -> Unit,
    onWrap: (String) -> Unit,
    onPrefix: (String) -> Unit,
    onLink: () -> Unit,
    onSpoiler: () -> Unit,
    onLanguage: () -> Unit,
    onPoll: () -> Unit,
    onBreak: () -> Unit,
    onPreview: () -> Unit,
    onOptions: () -> Unit,
    onSaveDraft: () -> Unit,
    onClear: () -> Unit,
    onSendLater: () -> Unit,
    onSend: () -> Unit,
    /** A queued post is edited: Send saves it and keeps its time. */
    savesQueued: Boolean = false,
    /** A published post is edited (R40): Send becomes Save and there is no Send later. */
    savesPublished: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth().testTag(ComposerTags.TOOLBAR)) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            // R9: the photo picker for images and video, the document picker for any media file.
            var attachMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { attachMenu = true }, modifier = Modifier.testTag(ComposerTags.ATTACH)) {
                    Icon(Icons.Outlined.AttachFile, contentDescription = stringResource(R.string.composer_attach))
                }
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                    val close = { attachMenu = false }
                    MenuItem(R.string.media_attach_media, AttachmentTags.ATTACH_MEDIA, close, onPickMedia)
                    MenuItem(R.string.media_attach_files, AttachmentTags.ATTACH_FILES, close, onPickFiles)
                }
            }
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                ToolButton(Icons.Outlined.FormatBold, R.string.composer_bold, ComposerTags.BOLD) { onWrap("**") }
                ToolButton(Icons.Outlined.FormatItalic, R.string.composer_italic, ComposerTags.ITALIC) { onWrap("*") }
                ToolButton(Icons.Outlined.FormatStrikethrough, R.string.composer_strikethrough, ComposerTags.STRIKE) { onWrap("~~") }
                ToolButton(Icons.Outlined.Code, R.string.composer_code, ComposerTags.CODE) { onWrap("`") }
                ToolButton(Icons.Outlined.FormatQuote, R.string.composer_quote, ComposerTags.QUOTE) { onPrefix("> ") }
                @Suppress("DEPRECATION")
                ToolButton(Icons.Outlined.FormatListBulleted, R.string.composer_list, ComposerTags.LIST) { onPrefix("- ") }
                ToolButton(Icons.Outlined.Link, R.string.composer_link, ComposerTags.LINK, onLink)
                IconToggleButton(checked = spoilerShown, onCheckedChange = { onSpoiler() }, modifier = Modifier.testTag(ComposerTags.CW)) {
                    Icon(Icons.Outlined.ReportProblem, contentDescription = stringResource(R.string.content_warning))
                }
                val uiLocale = LocalConfiguration.current.locales[0]
                val languageName = Locale.forLanguageTag(language).getDisplayName(uiLocale).ifBlank { language }
                val languageLabel = stringResource(R.string.composer_language_description, languageName)
                TextButton(
                    onClick = onLanguage,
                    modifier = Modifier.semantics { contentDescription = languageLabel }.testTag(ComposerTags.LANGUAGE),
                ) {
                    Text(language.uppercase(Locale.ROOT), style = MaterialTheme.typography.labelLarge)
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag(ComposerTags.OVERFLOW)) {
                        Icon(Icons.Outlined.MoreHoriz, contentDescription = stringResource(R.string.composer_more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val close = { menu = false }
                        MenuItem(if (hasPoll) R.string.composer_remove_poll else R.string.composer_add_poll, ComposerTags.MENU_POLL, close, onPoll)
                        MenuItem(R.string.composer_break_here, ComposerTags.MENU_BREAK, close, onBreak)
                        MenuItem(R.string.composer_preview, ComposerTags.MENU_PREVIEW, close, onPreview)
                        MenuItem(R.string.composer_account_options_menu, ComposerTags.MENU_OPTIONS, close, onOptions)
                        MenuItem(R.string.composer_save_draft, ComposerTags.MENU_SAVE, close, onSaveDraft)
                        MenuItem(R.string.composer_clear, ComposerTags.MENU_CLEAR, close, onClear)
                    }
                }
            }
            if (!savesPublished) {
                IconButton(onClick = onSendLater, modifier = Modifier.testTag(ComposerTags.SEND_LATER)) {
                    Icon(Icons.Outlined.Schedule, contentDescription = stringResource(R.string.composer_send_later))
                }
            }
            val scheme = MaterialTheme.colorScheme
            FilledIconButton(
                onClick = onSend,
                colors = if (canSend) {
                    IconButtonDefaults.filledIconButtonColors()
                } else {
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = scheme.onSurface.copy(alpha = 0.12f),
                        contentColor = scheme.onSurface.copy(alpha = 0.38f),
                    )
                },
                modifier = Modifier
                    .padding(end = 4.dp)
                    .semantics { if (!canSend) disabled() }
                    .testTag(ComposerTags.SEND),
            ) {
                if (savesPublished) {
                    Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.edit_save))
                } else if (savesQueued) {
                    Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.posts_save_queued))
                } else {
                    Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = stringResource(R.string.composer_send))
                }
            }
        }
    }
}

@Composable
private fun MenuItem(text: Int, tag: String, close: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(text)) },
        onClick = {
            close()
            action()
        },
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, tag: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag(tag)) {
        Icon(icon, contentDescription = stringResource(label))
    }
}

/** The label of a poll duration such as "5 minutes" or "3 days". */
@Composable
fun durationLabel(seconds: Long): String = when {
    seconds % 86_400L == 0L -> pluralStringResource(R.plurals.composer_days, (seconds / 86_400L).toInt(), (seconds / 86_400L).toInt())
    seconds % 3600L == 0L -> pluralStringResource(R.plurals.composer_hours, (seconds / 3600L).toInt(), (seconds / 3600L).toInt())
    else -> pluralStringResource(R.plurals.composer_minutes, (seconds / 60L).toInt(), (seconds / 60L).toInt())
}

/**
 * The Mastodon poll (R13): 2 up to the server's maximum of options with its per-option limit, a duration
 * within the server's minimum and maximum, and multiple choice.
 */
@Composable
fun PollEditor(poll: PollDraft, limits: PollLimits, onChange: (PollDraft) -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth().testTag(ComposerTags.POLL)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.composer_poll_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onRemove, modifier = Modifier.testTag(ComposerTags.POLL_REMOVE)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.composer_remove_poll))
                }
            }
            poll.options.forEachIndexed { index, option ->
                OutlinedTextField(
                    value = option,
                    onValueChange = { value -> onChange(poll.copy(options = poll.options.toMutableList().also { it[index] = value })) },
                    label = { Text(stringResource(R.string.composer_poll_option, index + 1)) },
                    supportingText = { Text("${option.length}/${limits.maxCharacters}") },
                    isError = option.length > limits.maxCharacters,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    trailingIcon = if (poll.options.size > PollLimits.MIN_OPTIONS) {
                        {
                            IconButton(
                                onClick = { onChange(poll.copy(options = poll.options.filterIndexed { i, _ -> i != index })) },
                                modifier = Modifier.testTag(ComposerTags.pollOptionRemove(index)),
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.composer_poll_remove_option, index + 1))
                            }
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().testTag(ComposerTags.pollOption(index)),
                )
            }
            if (poll.options.size < limits.maxOptions) {
                TextButton(onClick = { onChange(poll.copy(options = poll.options + "")) }, modifier = Modifier.testTag(ComposerTags.POLL_ADD)) {
                    Text(stringResource(R.string.composer_poll_add_option))
                }
            }
            Text(stringResource(R.string.composer_poll_duration), style = MaterialTheme.typography.labelLarge)
            val durations = (POLL_DURATIONS.filter { it in limits.minSeconds..limits.maxSeconds } + poll.expiresInSeconds).distinct().sorted()
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (seconds in durations) {
                    FilterChip(
                        selected = poll.expiresInSeconds == seconds,
                        onClick = { onChange(poll.copy(expiresInSeconds = seconds)) },
                        label = { Text(durationLabel(seconds)) },
                        modifier = Modifier.testTag(ComposerTags.pollDuration(seconds)),
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Switch) { onChange(poll.copy(multiple = !poll.multiple)) }
                    .testTag(ComposerTags.POLL_MULTIPLE),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.composer_poll_multiple), modifier = Modifier.weight(1f))
                Switch(checked = poll.multiple, onCheckedChange = null)
            }
            Text(
                stringResource(R.string.composer_poll_mastodon_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
