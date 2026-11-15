package org.evsyukov.post.ui.settings.accounts

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.evsyukov.post.R
import org.evsyukov.post.accounts.mastodonCapabilities
import org.evsyukov.post.core.mastodon.Visibility
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.media.NetworkMediaRules
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.accounts.AccountOptions
import org.evsyukov.post.data.accounts.ReplyRule
import org.evsyukov.post.data.db.AccountState
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.common.BackTopBar
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

object AccountDetailsTags {
    const val SCREEN = "account-details"
    const val LABEL = "account-details-label"
    const val LANGUAGE = "account-details-language"
    const val CONTENT_WARNING = "account-details-cw"
    const val SENSITIVE = "account-details-sensitive"
    const val ALLOW_QUOTES = "account-details-allow-quotes"
    const val LIMITS = "account-details-limits"
    const val SIGN_IN_AGAIN = "account-details-sign-in-again"
    const val REMOVE = "account-details-remove"
    const val CONFIRM_REMOVE = "account-details-confirm-remove"

    fun visibility(value: Visibility) = "account-details-visibility-$value"

    fun replyMode(mode: String) = "account-details-reply-$mode"

    fun replyRule(rule: ReplyRule) = "account-details-reply-rule-$rule"
}

/** The Bluesky threadgate choice: everyone (null rules), nobody (empty), or the people of some rules. */
private enum class ReplyMode { EVERYONE, SOME, NOBODY }

/**
 * Account details (screens.md): label (R2), default options (R17), the limits read from the server and
 * when (N1, F2), Sign in again and Remove (R1, N5).
 */
@Composable
fun AccountDetailsScreen(accountId: Long, onBack: () -> Unit, onSignInAgain: (Account) -> Unit, onRemoved: () -> Unit) {
    val container = LocalAppContainer.current
    val flow = remember(accountId) { container.accounts.observe().map { list -> list.find { it.id == accountId } } }
    val account by flow.collectAsStateWithLifecycle(initialValue = null)
    val current = account
    if (current == null) {
        Scaffold(topBar = { BackTopBar(title = stringResource(R.string.account_details_title), onBack = onBack) }) { padding ->
            Spacer(Modifier.padding(padding))
        }
        return
    }
    AccountDetailsContent(
        account = current,
        onBack = onBack,
        onLabelChange = { label -> container.scope.launch { container.accounts.setLabel(accountId, label) } },
        onDefaultsChange = { defaults -> container.scope.launch { container.accounts.updateDefaults(accountId, defaults) } },
        onSignInAgain = { onSignInAgain(current) },
        onRemove = {
            // The application scope: leaving the screen must not stop the revoke and the removal.
            container.scope.launch { container.accountRemover.remove(accountId) }
            onRemoved()
        },
    )
}

/** The details without the data source; edits are written through the callbacks as they happen. */
@Composable
fun AccountDetailsContent(
    account: Account,
    onBack: () -> Unit,
    onLabelChange: (String) -> Unit,
    onDefaultsChange: (AccountOptions) -> Unit,
    onSignInAgain: () -> Unit,
    onRemove: () -> Unit,
) {
    // Edited locally and written on each change: reading the field back from the database would move the cursor.
    var label by rememberSaveable(account.id) { mutableStateOf(account.label) }
    var defaults by remember(account.id) { mutableStateOf(account.defaults) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val update: (AccountOptions) -> Unit = {
        defaults = it
        onDefaultsChange(it)
    }
    val name = account.displayName.ifBlank { account.handle }
    Scaffold(
        modifier = Modifier.testTag(AccountDetailsTags.SCREEN),
        topBar = { BackTopBar(title = stringResource(R.string.account_details_title), onBack = onBack) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AccountAvatar(account.avatarUrl, name, size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(name, style = MaterialTheme.typography.titleLarge)
                    Text("@${account.handle}", style = MaterialTheme.typography.bodyMedium)
                    Text(networkName(account.network), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if (account.state == AccountState.NEEDS_SIGN_IN) NeedsSignInMark()
                }
            }
            OutlinedTextField(
                value = label,
                onValueChange = {
                    label = it.take(MAX_LABEL)
                    onLabelChange(label)
                },
                label = { Text(stringResource(R.string.account_label)) },
                supportingText = { Text(stringResource(R.string.account_label_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(AccountDetailsTags.LABEL),
            )
            HorizontalDivider()
            Text(stringResource(R.string.account_defaults), style = MaterialTheme.typography.titleMedium)
            when (account.network) {
                Network.MASTODON -> MastodonDefaults(defaults, update)
                Network.BLUESKY -> BlueskyDefaults(defaults, update)
            }
            HorizontalDivider()
            Limits(account)
            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSignInAgain, modifier = Modifier.testTag(AccountDetailsTags.SIGN_IN_AGAIN)) {
                    Text(stringResource(R.string.sign_in_again))
                }
                Button(
                    onClick = { confirmRemove = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.testTag(AccountDetailsTags.REMOVE),
                ) {
                    Text(stringResource(R.string.remove_account))
                }
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.remove_account_title)) },
            text = { Text(stringResource(R.string.remove_account_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        onRemove()
                    },
                    modifier = Modifier.testTag(AccountDetailsTags.CONFIRM_REMOVE),
                ) { Text(stringResource(R.string.remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private const val MAX_LABEL = 40

@Composable
private fun MastodonDefaults(defaults: AccountOptions, update: (AccountOptions) -> Unit) {
    Text(stringResource(R.string.visibility), style = MaterialTheme.typography.labelLarge)
    Column(Modifier.selectableGroup()) {
        for (value in Visibility.entries) {
            ChoiceRow(
                text = stringResource(visibilityName(value)),
                selected = defaults.visibility == value,
                tag = AccountDetailsTags.visibility(value),
                onClick = { update(defaults.copy(visibility = value)) },
            )
        }
    }
    var language by rememberSaveable { mutableStateOf(defaults.languages.firstOrNull().orEmpty()) }
    OutlinedTextField(
        value = language,
        onValueChange = {
            language = it.trim().take(MAX_LANGUAGE)
            update(defaults.copy(languages = listOfNotNull(language.takeIf { code -> code.isNotEmpty() })))
        },
        label = { Text(stringResource(R.string.language)) },
        supportingText = { Text(stringResource(R.string.language_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().testTag(AccountDetailsTags.LANGUAGE),
    )
    OutlinedTextField(
        value = defaults.contentWarning,
        onValueChange = { update(defaults.copy(contentWarning = it)) },
        label = { Text(stringResource(R.string.content_warning)) },
        modifier = Modifier.fillMaxWidth().testTag(AccountDetailsTags.CONTENT_WARNING),
    )
    SwitchRow(stringResource(R.string.sensitive_media), defaults.sensitive, AccountDetailsTags.SENSITIVE) {
        update(defaults.copy(sensitive = it))
    }
}

@Composable
private fun BlueskyDefaults(defaults: AccountOptions, update: (AccountOptions) -> Unit) {
    Text(stringResource(R.string.who_can_reply), style = MaterialTheme.typography.labelLarge)
    val rules = defaults.replyRules
    val mode = when {
        rules == null -> ReplyMode.EVERYONE
        rules.isEmpty() -> ReplyMode.NOBODY
        else -> ReplyMode.SOME
    }
    Column(Modifier.selectableGroup()) {
        ChoiceRow(stringResource(R.string.reply_everyone), mode == ReplyMode.EVERYONE, AccountDetailsTags.replyMode("everyone")) {
            update(defaults.copy(replyRules = null))
        }
        ChoiceRow(stringResource(R.string.reply_some), mode == ReplyMode.SOME, AccountDetailsTags.replyMode("some")) {
            if (mode != ReplyMode.SOME) update(defaults.copy(replyRules = setOf(ReplyRule.FOLLOWING)))
        }
        if (mode == ReplyMode.SOME) {
            for (rule in ReplyRule.entries) {
                val checked = rule in rules.orEmpty()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 32.dp)
                        .toggleable(value = checked, role = Role.Checkbox) { on ->
                            val next = if (on) rules.orEmpty() + rule else rules.orEmpty() - rule
                            // No rule left would mean nobody; that is the Nobody choice, so one rule stays.
                            if (next.isNotEmpty()) update(defaults.copy(replyRules = next))
                        }
                        .testTag(AccountDetailsTags.replyRule(rule)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(replyRuleName(rule)))
                }
            }
        }
        ChoiceRow(stringResource(R.string.reply_nobody), mode == ReplyMode.NOBODY, AccountDetailsTags.replyMode("nobody")) {
            update(defaults.copy(replyRules = emptySet()))
        }
    }
    SwitchRow(stringResource(R.string.allow_quotes), defaults.allowQuotes, AccountDetailsTags.ALLOW_QUOTES) {
        update(defaults.copy(allowQuotes = it))
    }
    var languages by rememberSaveable { mutableStateOf(defaults.languages.joinToString(", ")) }
    OutlinedTextField(
        value = languages,
        onValueChange = {
            languages = it
            val codes = it.split(',').map { code -> code.trim() }.filter { code -> code.isNotEmpty() }.take(MAX_BLUESKY_LANGUAGES)
            update(defaults.copy(languages = codes))
        },
        label = { Text(stringResource(R.string.languages)) },
        supportingText = { Text(stringResource(R.string.languages_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().testTag(AccountDetailsTags.LANGUAGE),
    )
}

private const val MAX_LANGUAGE = 16
private const val MAX_BLUESKY_LANGUAGES = 3
private const val BLUESKY_MAX_GRAPHEMES = 300

@Composable
private fun Limits(account: Account) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    Column(Modifier.testTag(AccountDetailsTags.LIMITS), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.limits), style = MaterialTheme.typography.titleMedium)
        when (account.network) {
            Network.MASTODON -> {
                val caps = account.limits.mastodonCapabilities()
                Text(stringResource(R.string.limits_characters, caps.maxCharacters))
                Text(stringResource(R.string.limits_media, caps.maxMediaAttachments))
                Text(stringResource(R.string.limits_image, size(caps.imageSizeLimit)))
                Text(stringResource(R.string.limits_video, size(caps.videoSizeLimit)))
                val images = caps.supportedMediaMimeTypes.filter { it.startsWith("image/") }.map { it.removePrefix("image/").uppercase() }
                Text(stringResource(R.string.limits_image_formats, images.joinToString(", ")))
                Text(stringResource(R.string.limits_post_formats, caps.postFormats.joinToString(", ")))
                val fetched = account.limitsFetchedAt
                Text(
                    if (fetched == null) {
                        stringResource(R.string.limits_never)
                    } else {
                        stringResource(
                            R.string.limits_read_at,
                            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(fetched),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Network.BLUESKY -> {
                val rules = NetworkMediaRules.bluesky()
                Text(stringResource(R.string.limits_characters, BLUESKY_MAX_GRAPHEMES))
                Text(stringResource(R.string.limits_media, rules.maxAttachments))
                Text(stringResource(R.string.limits_gallery, rules.galleryMax ?: rules.maxAttachments))
                Text(stringResource(R.string.limits_image, size(rules.maxImageBytes)))
                Text(stringResource(R.string.limits_video, size(rules.maxVideoBytes)))
                Text(stringResource(R.string.limits_bluesky_fixed), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ChoiceRow(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

private fun visibilityName(value: Visibility): Int = when (value) {
    Visibility.PUBLIC -> R.string.visibility_public
    Visibility.UNLISTED -> R.string.visibility_unlisted
    Visibility.PRIVATE -> R.string.visibility_private
    Visibility.DIRECT -> R.string.visibility_direct
}

private fun replyRuleName(rule: ReplyRule): Int = when (rule) {
    ReplyRule.MENTIONED -> R.string.reply_mentioned
    ReplyRule.FOLLOWERS -> R.string.reply_followers
    ReplyRule.FOLLOWING -> R.string.reply_following
}
