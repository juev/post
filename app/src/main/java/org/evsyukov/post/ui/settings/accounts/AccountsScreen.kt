package org.evsyukov.post.ui.settings.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.evsyukov.post.R
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.db.AccountState
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.common.BackTopBar

object AccountsTags {
    const val SCREEN = "accounts"
    const val ADD = "accounts-add"
    const val EMPTY = "accounts-empty"
    const val CHOOSE_MASTODON = "accounts-choose-mastodon"
    const val CHOOSE_BLUESKY = "accounts-choose-bluesky"
    const val MOVE_UP = "account-move-up"
    const val MOVE_DOWN = "account-move-down"

    fun row(id: Long) = "account-$id"

    fun menu(id: Long) = "account-menu-$id"

    fun header(network: Network) = "accounts-header-$network"
}

/**
 * The ids of [network]'s accounts after [id] moved one place up or down, or null when it cannot move.
 * [accounts] are in the order of the list.
 */
fun movedOrder(accounts: List<Account>, id: Long, up: Boolean): List<Long>? {
    val account = accounts.find { it.id == id } ?: return null
    val ids = accounts.filter { it.network == account.network }.map { it.id }.toMutableList()
    val index = ids.indexOf(id)
    val target = if (up) index - 1 else index + 1
    if (target !in ids.indices) return null
    ids[index] = ids[target]
    ids[target] = id
    return ids
}

/** Settings → Accounts (screens.md): accounts grouped by network, reordered by the row menu. */
@Composable
fun AccountsScreen(onBack: () -> Unit, onOpenAccount: (Long) -> Unit, onAdd: (Network) -> Unit) {
    val container = LocalAppContainer.current
    // One flow for the screen: a new one on every recomposition would restart the query each time.
    val source = remember(container) { container.accounts.observe() }
    val accounts by source.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    AccountsContent(
        accounts = accounts,
        onBack = onBack,
        onOpenAccount = onOpenAccount,
        onMove = { id, up ->
            val order = accounts?.let { movedOrder(it, id, up) }
            if (order != null) scope.launch { container.accounts.reorder(order) }
        },
        onAdd = onAdd,
    )
}

/** The accounts list without its data source; [accounts] is null while it loads. */
@Composable
fun AccountsContent(
    accounts: List<Account>?,
    onBack: () -> Unit,
    onOpenAccount: (Long) -> Unit,
    onMove: (id: Long, up: Boolean) -> Unit,
    onAdd: (Network) -> Unit,
) {
    var choosing by remember { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.testTag(AccountsTags.SCREEN),
        topBar = { BackTopBar(title = stringResource(R.string.settings_accounts), onBack = onBack) },
        floatingActionButton = {
            // R33: the content slot, not the text/icon overload, whose animated label a screen reader never
            // reached (the button had no name in TalkBack and in uiautomator).
            ExtendedFloatingActionButton(onClick = { choosing = true }, modifier = Modifier.testTag(AccountsTags.ADD)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.accounts_add))
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (accounts != null && accounts.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.accounts_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp).testTag(AccountsTags.EMPTY),
                    )
                }
            }
            for (network in Network.entries) {
                val group = accounts.orEmpty().filter { it.network == network }
                if (group.isEmpty()) continue
                item(key = "header-$network") {
                    Text(
                        networkName(network),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp).testTag(AccountsTags.header(network)),
                    )
                }
                items(group, key = { it.id }) { account ->
                    AccountRow(
                        account = account,
                        canMoveUp = account.id != group.first().id,
                        canMoveDown = account.id != group.last().id,
                        onOpen = { onOpenAccount(account.id) },
                        onMove = { up -> onMove(account.id, up) },
                    )
                }
            }
            // Room under the floating button.
            item { Spacer(Modifier.padding(40.dp)) }
        }
    }
    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.accounts_choose_network)) },
            text = {
                androidx.compose.foundation.layout.Column {
                    for (network in Network.entries) {
                        ListItem(
                            onClick = {
                                choosing = false
                                onAdd(network)
                            },
                            modifier = Modifier.testTag(if (network == Network.MASTODON) AccountsTags.CHOOSE_MASTODON else AccountsTags.CHOOSE_BLUESKY),
                        ) { Text(networkName(network)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun AccountRow(account: Account, canMoveUp: Boolean, canMoveDown: Boolean, onOpen: () -> Unit, onMove: (Boolean) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val name = account.displayName.ifBlank { account.handle }
    ListItem(
        onClick = onOpen,
        modifier = Modifier.testTag(AccountsTags.row(account.id)),
        leadingContent = { AccountAvatar(account.avatarUrl, name) },
        supportingContent = {
            androidx.compose.foundation.layout.Column {
                Text("@${account.handle}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (account.label.isNotBlank()) Text(account.label, color = MaterialTheme.colorScheme.tertiary, maxLines = 1)
                if (account.state == AccountState.NEEDS_SIGN_IN) NeedsSignInMark()
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag(AccountsTags.menu(account.id))) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.account_menu, name))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.account_move_up)) },
                        enabled = canMoveUp,
                        onClick = {
                            menu = false
                            onMove(true)
                        },
                        modifier = Modifier.testTag(AccountsTags.MOVE_UP),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.account_move_down)) },
                        enabled = canMoveDown,
                        onClick = {
                            menu = false
                            onMove(false)
                        },
                        modifier = Modifier.testTag(AccountsTags.MOVE_DOWN),
                    )
                }
            }
        },
    ) { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** The "Needs sign-in" mark (R5). */
@Composable
fun NeedsSignInMark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.accounts_needs_sign_in), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
    }
}

/** The avatar from the account's server, or the first letter of [name] while there is none. */
@Composable
fun AccountAvatar(url: String?, name: String, size: Dp = 40.dp) {
    val modifier = Modifier.size(size).clip(CircleShape)
    if (url == null) {
        Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Text(
                name.trim().removePrefix("@").take(1).uppercase(),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    } else {
        AsyncImage(model = url, contentDescription = stringResource(R.string.account_avatar), modifier = modifier.background(MaterialTheme.colorScheme.secondaryContainer))
    }
}

@Composable
fun networkName(network: Network): String = stringResource(
    when (network) {
        Network.MASTODON -> R.string.network_mastodon
        Network.BLUESKY -> R.string.network_bluesky
    },
)
