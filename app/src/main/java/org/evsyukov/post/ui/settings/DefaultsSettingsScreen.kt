package org.evsyukov.post.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.evsyukov.post.R
import org.evsyukov.post.core.markdown.BlueskyLinkMode
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.settings.Settings
import org.evsyukov.post.data.settings.ShareAction
import org.evsyukov.post.data.settings.TooManyFilesDefault
import org.evsyukov.post.data.settings.UnsupportedTypeDefault
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.common.BackTopBar
import org.evsyukov.post.ui.settings.accounts.networkName

object DefaultsTags {
    const val SCREEN = SettingsSectionTags.DEFAULTS
    const val LIST = "defaults-list"
    const val TARGETS_EMPTY = "defaults-targets-empty"
    const val SHARE_ACTION = "defaults-share-action"
    const val REQUIRE_ALT = "defaults-require-alt"
    const val TOO_MANY_FILES = "defaults-too-many-files"
    const val UNSUPPORTED_TYPE = "defaults-unsupported-type"
    const val BLUESKY_LINKS = "defaults-bluesky-links"

    fun target(id: Long) = "defaults-target-$id"
    fun moveUp(id: Long) = "defaults-target-up-$id"
    fun moveDown(id: Long) = "defaults-target-down-$id"
}

/**
 * Settings → Defaults (screens.md): default targets and their order (R16), the Share action (R18),
 * "Require alt text" (R10), the default media fixes (A12) and "Bluesky: Markdown links" (F11).
 */
@Composable
fun DefaultsSettingsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val settings by rememberSettings(container)
    val source = remember(container) { container.accounts.observe() }
    val accounts by source.collectAsStateWithLifecycle(initialValue = null)
    DefaultsSettingsContent(settings, accounts, onBack, container::editSettings)
}

/** Defaults without their data source; nothing shows until [settings] and [accounts] are read. */
@Composable
fun DefaultsSettingsContent(settings: Settings?, accounts: List<Account>?, onBack: () -> Unit, onEdit: (SettingsEdit) -> Unit) {
    Scaffold(
        modifier = Modifier.testTag(DefaultsTags.SCREEN),
        topBar = { BackTopBar(title = stringResource(R.string.settings_defaults), onBack = onBack) },
    ) { padding ->
        if (settings == null || accounts == null) return@Scaffold
        val existing = accounts.map { it.id }.toSet()
        // A removed account stays in the stored list until the next change; it is not shown.
        val selected = settings.defaultTargets.distinct().mapNotNull { id -> accounts.firstOrNull { it.id == id } }
        val others = accounts.filter { it.id !in settings.defaultTargets }
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag(DefaultsTags.LIST)) {
            item { SettingsHeader(stringResource(R.string.defaults_targets)) }
            item { SettingsNote(stringResource(R.string.defaults_targets_summary)) }
            if (accounts.isEmpty()) {
                item { SettingsNote(stringResource(R.string.defaults_targets_empty), Modifier.testTag(DefaultsTags.TARGETS_EMPTY)) }
            }
            items(selected.size, key = { selected[it].id }) { index ->
                val account = selected[index]
                TargetRow(
                    account = account,
                    checked = true,
                    canMoveUp = index > 0,
                    canMoveDown = index < selected.lastIndex,
                    onToggle = { onEdit { updateDefaultTargets { toggledTarget(it, existing, account.id) } } },
                    onMove = { up -> onEdit { updateDefaultTargets { movedTarget(it, existing, account.id, up) } } },
                )
            }
            items(others.size, key = { others[it].id }) { index ->
                val account = others[index]
                TargetRow(
                    account = account,
                    checked = false,
                    canMoveUp = false,
                    canMoveDown = false,
                    onToggle = { onEdit { updateDefaultTargets { toggledTarget(it, existing, account.id) } } },
                    onMove = {},
                )
            }

            item { SettingsHeader(stringResource(R.string.defaults_share_action)) }
            item { SettingsNote(stringResource(R.string.defaults_share_action_summary)) }
            item {
                ChoiceGroup(
                    listOf(
                        Choice(ShareAction.COMPOSER, stringResource(R.string.share_action_composer)),
                        Choice(ShareAction.SEND_NOW, stringResource(R.string.share_action_send_now)),
                        Choice(ShareAction.SEND_LATER, stringResource(R.string.share_action_send_later)),
                    ),
                    settings.shareAction,
                    DefaultsTags.SHARE_ACTION,
                ) { value -> onEdit { setShareAction(value) } }
            }

            item { SettingsHeader(stringResource(R.string.defaults_media)) }
            item {
                SwitchRow(
                    stringResource(R.string.defaults_require_alt),
                    stringResource(R.string.defaults_require_alt_summary),
                    settings.requireAltText,
                    DefaultsTags.REQUIRE_ALT,
                ) { value -> onEdit { setRequireAltText(value) } }
            }
            item { SettingsNote(stringResource(R.string.defaults_too_many_files)) }
            item {
                ChoiceGroup(
                    listOf(
                        Choice(TooManyFilesDefault.ASK, stringResource(R.string.fix_ask)),
                        Choice(TooManyFilesDefault.MOVE_TO_REPLY, stringResource(R.string.fix_move_to_reply)),
                    ),
                    settings.defaultFixTooManyFiles,
                    DefaultsTags.TOO_MANY_FILES,
                ) { value -> onEdit { setDefaultFixTooManyFiles(value) } }
            }
            item { SettingsNote(stringResource(R.string.defaults_unsupported_type)) }
            item {
                ChoiceGroup(
                    listOf(
                        Choice(UnsupportedTypeDefault.ASK, stringResource(R.string.fix_ask)),
                        Choice(UnsupportedTypeDefault.SEND_WITHOUT_FILE, stringResource(R.string.fix_send_without_file)),
                    ),
                    settings.defaultFixUnsupportedType,
                    DefaultsTags.UNSUPPORTED_TYPE,
                ) { value -> onEdit { setDefaultFixUnsupportedType(value) } }
            }

            item { SettingsHeader(stringResource(R.string.defaults_bluesky_links)) }
            item { SettingsNote(stringResource(R.string.defaults_bluesky_links_summary)) }
            item {
                ChoiceGroup(
                    listOf(
                        Choice(BlueskyLinkMode.FACET_ON_TEXT, stringResource(R.string.bluesky_links_facet)),
                        Choice(
                            BlueskyLinkMode.TEXT_AND_SHORT_URL,
                            stringResource(R.string.bluesky_links_short_url),
                            stringResource(R.string.bluesky_links_short_url_example),
                        ),
                    ),
                    settings.blueskyLinkMode,
                    DefaultsTags.BLUESKY_LINKS,
                ) { value -> onEdit { setBlueskyLinkMode(value) } }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.padding(12.dp)) }
        }
    }
}

/** An account as a default target: the row toggles it; a selected one also moves up and down (R16). */
@Composable
private fun TargetRow(
    account: Account,
    checked: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onMove: (up: Boolean) -> Unit,
) {
    val name = account.displayName.ifBlank { account.handle }
    ListItem(
        modifier = Modifier
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .testTag(DefaultsTags.target(account.id)),
        leadingContent = { Checkbox(checked = checked, onCheckedChange = null) },
        headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text("@${account.handle} · ${networkName(account.network)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = if (!checked) {
            null
        } else {
            {
                Row {
                    IconButton(onClick = { onMove(true) }, enabled = canMoveUp, modifier = Modifier.testTag(DefaultsTags.moveUp(account.id))) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.defaults_target_move_up, name))
                    }
                    IconButton(onClick = { onMove(false) }, enabled = canMoveDown, modifier = Modifier.testTag(DefaultsTags.moveDown(account.id))) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.defaults_target_move_down, name))
                    }
                }
            }
        },
    )
}

/**
 * The default targets after a tap on [id]: a selected account leaves the list, another one joins at its
 * end. Ids of accounts that are no longer in [existing] (removed accounts) are dropped.
 */
internal fun toggledTarget(targets: List<Long>, existing: Set<Long>, id: Long): List<Long> {
    val kept = targets.filter { it in existing }.distinct()
    return if (id in kept) kept - id else kept + id
}

/** The default targets with [id] moved one place up or down; ids of removed accounts are dropped. */
internal fun movedTarget(targets: List<Long>, existing: Set<Long>, id: Long, up: Boolean): List<Long> {
    val kept = targets.filter { it in existing }.distinct().toMutableList()
    val from = kept.indexOf(id)
    val to = if (up) from - 1 else from + 1
    if (from < 0 || to !in kept.indices) return kept
    kept[from] = kept[to].also { kept[to] = kept[from] }
    return kept
}
