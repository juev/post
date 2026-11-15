package org.evsyukov.post.ui

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.ui.composer.ComposerScreen
import org.evsyukov.post.ui.posts.PostsScreen
import org.evsyukov.post.ui.schedule.ScheduleScreen
import org.evsyukov.post.ui.settings.AboutScreen
import org.evsyukov.post.ui.settings.DefaultsSettingsScreen
import org.evsyukov.post.ui.settings.SendingSettingsScreen
import org.evsyukov.post.ui.settings.SettingsScreen
import org.evsyukov.post.ui.settings.accounts.AccountDetailsScreen
import org.evsyukov.post.ui.settings.accounts.AccountsScreen
import org.evsyukov.post.ui.settings.accounts.AddBlueskyScreen
import org.evsyukov.post.ui.settings.accounts.AddMastodonScreen

/** The composer, the start screen (screens.md, Navigation). */
@Serializable
data object ComposerKey : NavKey

/** Queue, drafts and sent posts. */
@Serializable
data object PostsKey : NavKey

@Serializable
data object ScheduleKey : NavKey

@Serializable
data object SettingsKey : NavKey

/** Settings → Accounts. */
@Serializable
data object AccountsKey : NavKey

/** Add Mastodon account, or sign [accountId] in again at [address]. */
@Serializable
data class AddMastodonKey(val accountId: Long? = null, val address: String = "") : NavKey

/** Add Bluesky account, or sign [accountId] in again as [handle]. */
@Serializable
data class AddBlueskyKey(val accountId: Long? = null, val handle: String = "") : NavKey

@Serializable
data class AccountDetailsKey(val accountId: Long) : NavKey

/** Settings → Defaults (completed in step 12). */
@Serializable
data object DefaultsSettingsKey : NavKey

/** Settings → Sending (completed in step 12). */
@Serializable
data object SendingSettingsKey : NavKey

/** Settings → About (completed in step 12). */
@Serializable
data object AboutKey : NavKey

/**
 * The composer, with the other screens opened on top of it by its top bar and closed by their back
 * arrow or by the system back. There is no bottom navigation: the composer keeps the whole screen.
 */
@Composable
fun PostApp() {
    val backStack = rememberNavBackStack(ComposerKey)
    // A second tap on an action while its screen slides in would stack the screen twice.
    val open: (NavKey) -> Unit = { key -> if (backStack.lastOrNull() != key) backStack.add(key) }
    // A second tap on a back arrow while the screen slides out would pop the composer itself.
    val back: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
    // After a sign-in the add screen closes and the accounts list shows the account.
    val backToAccounts: () -> Unit = {
        while (backStack.size > 1 && backStack.last() != AccountsKey) backStack.removeAt(backStack.lastIndex)
        if (backStack.last() != AccountsKey) backStack.add(AccountsKey)
    }
    NavDisplay(
        backStack = backStack,
        onBack = back,
        entryProvider = entryProvider {
            entry<ComposerKey> {
                ComposerScreen(
                    onOpenPosts = { open(PostsKey) },
                    onOpenSchedule = { open(ScheduleKey) },
                    onOpenSettings = { open(SettingsKey) },
                )
            }
            entry<PostsKey> { PostsScreen(onBack = back) }
            entry<ScheduleKey> { ScheduleScreen(onBack = back) }
            entry<SettingsKey> {
                SettingsScreen(
                    onBack = back,
                    onOpenAccounts = { open(AccountsKey) },
                    onOpenDefaults = { open(DefaultsSettingsKey) },
                    onOpenSending = { open(SendingSettingsKey) },
                    onOpenSchedule = { open(ScheduleKey) },
                    onOpenAbout = { open(AboutKey) },
                )
            }
            entry<DefaultsSettingsKey> { DefaultsSettingsScreen(onBack = back) }
            entry<SendingSettingsKey> { SendingSettingsScreen(onBack = back) }
            entry<AboutKey> { AboutScreen(onBack = back) }
            entry<AccountsKey> {
                AccountsScreen(
                    onBack = back,
                    onOpenAccount = { open(AccountDetailsKey(it)) },
                    onAdd = { network ->
                        open(if (network == Network.MASTODON) AddMastodonKey() else AddBlueskyKey())
                    },
                )
            }
            entry<AddMastodonKey> { key ->
                AddMastodonScreen(accountId = key.accountId, initialAddress = key.address, onBack = back, onDone = backToAccounts)
            }
            entry<AddBlueskyKey> { key ->
                AddBlueskyScreen(accountId = key.accountId, initialHandle = key.handle, onBack = back, onDone = backToAccounts)
            }
            entry<AccountDetailsKey> { key ->
                AccountDetailsScreen(
                    accountId = key.accountId,
                    onBack = back,
                    onSignInAgain = { account ->
                        open(
                            if (account.network == Network.MASTODON) {
                                AddMastodonKey(account.id, account.server)
                            } else {
                                AddBlueskyKey(account.id, account.handle)
                            },
                        )
                    },
                    onRemoved = back,
                )
            }
        },
    )
}
