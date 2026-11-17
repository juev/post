package org.evsyukov.post.ui.composer

import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.evsyukov.post.R
import org.evsyukov.post.accounts.mastodonCapabilities
import org.evsyukov.post.core.bluesky.ContentLabel
import org.evsyukov.post.core.mastodon.Visibility
import org.evsyukov.post.core.media.ConflictKind
import org.evsyukov.post.core.media.FixKind
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.render.TextIssue
import org.evsyukov.post.core.render.TextRenderer
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.accounts.QuotePolicy
import org.evsyukov.post.data.accounts.ReplyRule
import org.evsyukov.post.data.posts.AccountPostOptions
import org.evsyukov.post.ui.common.languageDisplayName
import org.evsyukov.post.ui.common.offeredLanguages
import org.evsyukov.post.ui.settings.accounts.networkName
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The name of a profile in the sheets: its network, with the accounts when the network has several profiles. */
@Composable
private fun profileName(render: ProfileRender, all: List<ProfileRender>): String {
    val network = networkName(render.network)
    val several = all.count { it.network == render.network } > 1
    return if (several) "$network (${render.accounts.joinToString(", ") { it.chipLabel() }})" else network
}

@Composable
private fun issueText(issue: TextIssue, source: String, limit: Int): String = when (issue) {
    is TextIssue.TooLongToSplit -> stringResource(R.string.composer_issue_too_long, tooLongFragment(source, issue), limit)
    is TextIssue.TooManyParts -> stringResource(R.string.composer_issue_too_many_parts, issue.count, TextRenderer.MAX_PARTS)
    is TextIssue.SpoilerTooLong -> stringResource(R.string.composer_issue_spoiler_too_long)
}

@Composable
private fun pollProblemText(problem: PollProblem): String = stringResource(
    when (problem) {
        PollProblem.TOO_FEW_OPTIONS -> R.string.composer_poll_too_few
        PollProblem.TOO_MANY_OPTIONS -> R.string.composer_poll_too_many
        PollProblem.EMPTY_OPTION -> R.string.composer_poll_empty_option
        PollProblem.OPTION_TOO_LONG -> R.string.composer_poll_option_too_long
        PollProblem.DURATION -> R.string.composer_poll_bad_duration
    },
)

@Composable
private fun SheetTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 8.dp).semantics { heading() })
}

/**
 * The details of the status line (screens.md; R8, T9, T16, F6): what blocks sending, warnings about long
 * threads and removed formatting, and the media conflicts the attachments step adds. Preview opens from here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailsSheet(
    source: String,
    renders: List<ProfileRender>,
    blockers: List<Blocker>,
    formattingRemoved: Boolean,
    onPreview: () -> Unit,
    onDismiss: () -> Unit,
    media: List<NetworkMedia> = emptyList(),
    chosenFixes: ChosenFixes = emptyMap(),
    onFix: (Network, ConflictKind, FixKind) -> Unit = { _, _, _ -> },
    onUndoFix: (Network, ConflictKind) -> Unit = { _, _ -> },
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp).testTag(ComposerTags.DETAILS),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SheetTitle(stringResource(R.string.composer_details))
            for (render in renders) {
                // The status text names the network; the accounts are added when the network has several profiles.
                val several = renders.count { it.network == render.network } > 1
                val accounts = if (several) " (${render.accounts.joinToString(", ") { it.chipLabel() }})" else ""
                Text(statusText(render) + accounts, style = MaterialTheme.typography.titleSmall)
                render.render.issue?.let { Text(issueText(it, source, render.render.limit), color = MaterialTheme.colorScheme.error) }
                if (render.render.issue == null && render.render.manyPartsWarning) {
                    Text(stringResource(R.string.composer_many_parts, render.render.parts.size))
                }
            }
            for (blocker in blockers) {
                when (blocker) {
                    Blocker.NoTarget -> Text(stringResource(R.string.composer_blocker_no_target), color = MaterialTheme.colorScheme.error)
                    Blocker.EmptyPost -> Text(stringResource(R.string.composer_blocker_empty), color = MaterialTheme.colorScheme.error)
                    is Blocker.Poll -> Text(pollProblemText(blocker.problem), color = MaterialTheme.colorScheme.error)
                    is Blocker.AltMissing -> Text(
                        pluralStringResource(R.plurals.media_alt_missing, blocker.ids.size, blocker.ids.size),
                        color = MaterialTheme.colorScheme.error,
                    )
                    is Blocker.AltTooLong -> Text(stringResource(R.string.media_alt_too_long_blocker, blocker.limit), color = MaterialTheme.colorScheme.error)
                    Blocker.Importing -> Text(stringResource(R.string.media_importing_blocker), color = MaterialTheme.colorScheme.error)
                    is Blocker.MediaUnreadable -> Text(stringResource(R.string.composer_blocker_unreadable), color = MaterialTheme.colorScheme.error)
                    is Blocker.Text, is Blocker.Media -> Unit // shown with its profile above, or with the media below
                }
            }
            if (formattingRemoved) Text(stringResource(R.string.composer_formatting_removed), color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Media conflicts and their fixes, per network (media.md A10, A12).
            for (network in media) {
                MediaSection(network, chosenFixes, onFix = { kind, fix -> onFix(network.network, kind, fix) }, onUndo = { onUndoFix(network.network, it) })
            }
            if (renders.isNotEmpty()) {
                OutlinedButton(onClick = onPreview, modifier = Modifier.testTag(ComposerTags.DETAILS_PREVIEW)) {
                    Text(stringResource(R.string.composer_preview))
                }
            }
            Spacer(Modifier.padding(8.dp))
        }
    }
}

/** The parts of each selected profile exactly as they are published, with counters and numbers (F7, T12). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewSheet(renders: List<ProfileRender>, onDismiss: () -> Unit, marks: List<EditMarks?> = emptyList()) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(ComposerTags.PREVIEW),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SheetTitle(stringResource(R.string.composer_preview)) }
            if (renders.isEmpty()) item { Text(stringResource(R.string.composer_status_no_target)) }
            renders.forEachIndexed { profileIndex, render ->
                item {
                    Text(
                        profileName(render, renders),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                    )
                }
                render.render.issue?.let { issue ->
                    item { Text(statusText(render), color = MaterialTheme.colorScheme.error) }
                    return@forEachIndexed
                }
                val total = render.render.parts.size
                val mark = marks.getOrNull(profileIndex)
                items(render.render.parts.withIndex().toList()) { (index, part) ->
                    OutlinedCard(Modifier.fillMaxWidth().testTag(ComposerTags.previewPart(profileIndex, index))) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row {
                                Text(
                                    stringResource(R.string.composer_part_number, index + 1, total),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                val counter = buildString {
                                    append(part.count).append('/').append(render.render.limit)
                                    val bytes = part.bytes
                                    val maxBytes = render.render.profile.maxBytes
                                    if (bytes != null && maxBytes != null) append(" · ").append(bytes).append('/').append(maxBytes).append(" B")
                                }
                                Text(counter, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            mark?.parts?.getOrNull(index)?.let { EditMarkLabel(it, ComposerTags.previewMark(profileIndex, index)) }
                            val spoiler = render.render.spoilerText
                            if (spoiler.isNotEmpty() && render.network == Network.MASTODON) {
                                Text(stringResource(R.string.composer_preview_cw, spoiler), style = MaterialTheme.typography.labelLarge)
                            }
                            Text(part.text, style = MaterialTheme.typography.bodyMedium)
                            val files = render.assembled?.parts?.getOrNull(index)?.mediaItemIds.orEmpty()
                            if (files.isNotEmpty()) {
                                Text(
                                    "📎 " + pluralStringResource(R.plurals.media_preview_part, files.size, files.size),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
                if (mark != null && mark.deleted > 0) {
                    item {
                        Text(
                            pluralStringResource(R.plurals.edit_mark_deleted, mark.deleted, mark.deleted),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.testTag(ComposerTags.previewDeleted(profileIndex)),
                        )
                    }
                }
            }
            item { Spacer(Modifier.padding(16.dp)) }
        }
    }
}

/** The selected accounts, to open the options of one for this post (⋯ → options). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsListSheet(accounts: List<Account>, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag(ComposerTags.OPTIONS_LIST)) {
            Text(
                stringResource(R.string.composer_account_options_menu),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
            )
            if (accounts.isEmpty()) {
                Text(stringResource(R.string.composer_status_no_target), modifier = Modifier.padding(16.dp))
            }
            for (account in accounts) {
                ListItem(
                    headlineContent = { Text(account.chipLabel()) },
                    supportingContent = { Text(networkName(account.network)) },
                    modifier = Modifier.clickable { onPick(account.id) }.testTag(ComposerTags.optionsAccount(account.id)),
                )
            }
            Spacer(Modifier.padding(16.dp))
        }
    }
}

private fun visibilityName(value: Visibility): Int = when (value) {
    Visibility.PUBLIC -> R.string.visibility_public
    Visibility.UNLISTED -> R.string.visibility_unlisted
    Visibility.PRIVATE -> R.string.visibility_private
    Visibility.DIRECT -> R.string.visibility_direct
}

private fun quotePolicyName(value: QuotePolicy): Int = when (value) {
    QuotePolicy.PUBLIC -> R.string.composer_quote_anyone
    QuotePolicy.FOLLOWERS -> R.string.composer_quote_followers
    QuotePolicy.NOBODY -> R.string.composer_quote_nobody
}

private fun replyRuleName(rule: ReplyRule): Int = when (rule) {
    ReplyRule.MENTIONED -> R.string.reply_mentioned
    ReplyRule.FOLLOWERS -> R.string.reply_followers
    ReplyRule.FOLLOWING -> R.string.reply_following
}

private fun labelName(label: ContentLabel): Int = when (label) {
    ContentLabel.SEXUAL -> R.string.composer_label_sexual
    ContentLabel.NUDITY -> R.string.composer_label_nudity
    ContentLabel.PORN -> R.string.composer_label_porn
    ContentLabel.GRAPHIC_MEDIA -> R.string.composer_label_graphic
}

@Composable
private fun Choice(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(text)
    }
}

@Composable
private fun Toggle(text: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit, checkbox: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = if (checkbox) Role.Checkbox else Role.Switch, onValueChange = onChange)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (checkbox) {
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.width(12.dp))
            Text(text, modifier = Modifier.weight(1f))
        } else {
            Text(text, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

/** Tags of the per-account options sheet. */
object AccountOptionsTags {
    fun visibility(value: Visibility) = "composer-opt-visibility-${value.name}"
    fun quote(value: QuotePolicy) = "composer-opt-quote-${value.name}"
    fun reply(mode: String) = "composer-opt-reply-$mode"
    fun replyRule(rule: ReplyRule) = "composer-opt-reply-rule-${rule.name}"
    fun label(label: ContentLabel) = "composer-opt-label-${label.name}"
    const val SENSITIVE = "composer-opt-sensitive"
    const val ALLOW_QUOTES = "composer-opt-allow-quotes"
    const val LINK_CARD = "composer-opt-link-card"
}

/**
 * The options of one account for this post (R13, R17), opened by a long press on its chip and prefilled
 * from the account's defaults. Mastodon: visibility, quote policy (only on servers that support it),
 * sensitive media. Bluesky: who can reply, quotes, content labels (they matter only with media), link card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountOptionsSheet(
    account: Account,
    options: AccountPostOptions,
    onChange: (AccountPostOptions) -> Unit,
    onDismiss: () -> Unit,
    /** N28: a published post keeps its visibility. */
    visibilityLocked: Boolean = false,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).testTag(ComposerTags.ACCOUNT_OPTIONS),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SheetTitle(stringResource(R.string.composer_options_for, account.chipLabel()))
            Text(networkName(account.network), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            when (account.network) {
                Network.MASTODON -> {
                    Text(stringResource(R.string.visibility), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    if (visibilityLocked) {
                        Text(stringResource(visibilityName(options.visibility)), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.edit_visibility_locked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag(ComposerTags.VISIBILITY_LOCKED),
                        )
                    } else {
                        Column(Modifier.selectableGroup()) {
                            for (value in Visibility.entries) {
                                Choice(stringResource(visibilityName(value)), options.visibility == value, AccountOptionsTags.visibility(value)) {
                                    onChange(options.copy(visibility = value))
                                }
                            }
                        }
                    }
                    if (account.limits.mastodonCapabilities().supportsQuotePolicy) {
                        Text(stringResource(R.string.composer_quote_policy), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        Column(Modifier.selectableGroup()) {
                            for (value in QuotePolicy.entries) {
                                Choice(stringResource(quotePolicyName(value)), options.quotePolicy == value, AccountOptionsTags.quote(value)) {
                                    onChange(options.copy(quotePolicy = value))
                                }
                            }
                        }
                    }
                    Toggle(stringResource(R.string.sensitive_media), options.sensitive, AccountOptionsTags.SENSITIVE, { onChange(options.copy(sensitive = it)) })
                }
                Network.BLUESKY -> {
                    Text(stringResource(R.string.who_can_reply), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    val rules = options.replyRules
                    Column(Modifier.selectableGroup()) {
                        Choice(stringResource(R.string.reply_everyone), rules == null, AccountOptionsTags.reply("everyone")) {
                            onChange(options.copy(replyRules = null))
                        }
                        Choice(stringResource(R.string.reply_some), !rules.isNullOrEmpty(), AccountOptionsTags.reply("some")) {
                            if (rules.isNullOrEmpty()) onChange(options.copy(replyRules = setOf(ReplyRule.FOLLOWING)))
                        }
                        if (!rules.isNullOrEmpty()) {
                            for (rule in ReplyRule.entries) {
                                Row(Modifier.padding(start = 24.dp)) {
                                    Toggle(stringResource(replyRuleName(rule)), rule in rules, AccountOptionsTags.replyRule(rule), { on ->
                                        val next = if (on) rules + rule else rules - rule
                                        // No rule left would be Nobody, a choice of its own, so one rule stays.
                                        if (next.isNotEmpty()) onChange(options.copy(replyRules = next))
                                    }, checkbox = true)
                                }
                            }
                        }
                        Choice(stringResource(R.string.reply_nobody), rules != null && rules.isEmpty(), AccountOptionsTags.reply("nobody")) {
                            onChange(options.copy(replyRules = emptySet()))
                        }
                    }
                    Toggle(stringResource(R.string.allow_quotes), options.allowQuotes, AccountOptionsTags.ALLOW_QUOTES, { onChange(options.copy(allowQuotes = it)) })
                    Toggle(stringResource(R.string.composer_link_card), options.linkCard, AccountOptionsTags.LINK_CARD, { onChange(options.copy(linkCard = it)) })
                    Text(stringResource(R.string.composer_content_labels), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Text(
                        stringResource(R.string.composer_content_labels_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (label in ContentLabel.entries) {
                        Toggle(stringResource(labelName(label)), label in options.contentLabels, AccountOptionsTags.label(label), { on ->
                            onChange(options.copy(contentLabels = if (on) options.contentLabels + label else options.contentLabels - label))
                        }, checkbox = true)
                    }
                }
            }
            Spacer(Modifier.padding(16.dp))
        }
    }
}

/** A URL in [text] the link dialog offers: an http or https address without spaces. */
fun clipboardUrl(text: CharSequence?): String? {
    val value = text?.toString()?.trim() ?: return null
    if (value.isEmpty() || value.any { it.isWhitespace() }) return null
    return value.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
}

/** Asks for the address of a link (F5) and offers a URL found in the clipboard. */
@Composable
fun LinkDialog(onInsert: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val suggestion = remember {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboardUrl(clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text)
    }
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.composer_link_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.composer_link_address)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().testTag(ComposerTags.LINK_URL),
                )
                if (suggestion != null && suggestion != url) {
                    AssistChip(
                        onClick = { url = suggestion },
                        label = { Text(stringResource(R.string.composer_link_from_clipboard, suggestion), maxLines = 1) },
                        modifier = Modifier.testTag(ComposerTags.LINK_CLIPBOARD),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onInsert(url) }, enabled = url.isNotBlank(), modifier = Modifier.testTag(ComposerTags.LINK_INSERT)) {
                Text(stringResource(R.string.composer_link_insert))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** One language for both networks (R13). */
@Composable
fun LanguageDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val codes = offeredLanguages(current)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language)) },
        text = {
            LazyColumn(Modifier.selectableGroup()) {
                items(codes) { code ->
                    Choice("${languageDisplayName(code)} ($code)", code == current, ComposerTags.language(code)) { onPick(code) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Send later for now: a date, then a time; the post is queued for that instant (scheduling.md S5). The
 * full sheet with schedule slots replaces it later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendLaterPicker(onPick: (Instant) -> Unit, onDismiss: () -> Unit) {
    var date by remember { mutableStateOf<LocalDate?>(null) }
    val today = LocalDate.now()
    val chosen = date
    if (chosen == null) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(today)
            },
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = {
                        date = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: today
                    },
                    modifier = Modifier.testTag(ComposerTags.DATE_CONFIRM),
                ) { Text(stringResource(R.string.composer_next)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        ) {
            DatePicker(state = state)
        }
    } else {
        val now = LocalTime.now().plusHours(1)
        val state = rememberTimePickerState(initialHour = now.hour, initialMinute = 0)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.composer_pick_time)) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(
                    onClick = { onPick(chosen.atTime(state.hour, state.minute).atZone(ZoneId.systemDefault()).toInstant()) },
                    modifier = Modifier.testTag(ComposerTags.TIME_CONFIRM),
                ) { Text(stringResource(R.string.composer_send_later_confirm)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
