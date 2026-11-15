package org.evsyukov.post.ui

import androidx.activity.compose.LocalActivity
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

/** The composer, the start screen (screens.md, Navigation); with [draftId] it opens that draft (R15). */
@Serializable
data class ComposerKey(val draftId: Long? = null) : NavKey

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

/** Settings → Defaults. */
@Serializable
data object DefaultsSettingsKey : NavKey

/** Settings → Sending. */
@Serializable
data object SendingSettingsKey : NavKey

/** Settings → About. */
@Serializable
data object AboutKey : NavKey

/**
 * The composer, with the other screens opened on top of it by its top bar and closed by their back
 * arrow or by the system back. There is no bottom navigation: the composer keeps the whole screen.
 * [openOnStart] is a screen a notification asked for (product.md R5, R32), opened over the composer.
 */
@Composable
fun PostApp(openOnStart: NavKey? = null) {
    val backStack = rememberNavBackStack(*listOfNotNull(ComposerKey(), openOnStart).toTypedArray())
    val activity = LocalActivity.current
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
            entry<ComposerKey> { key ->
                ComposerScreen(
                    draftId = key.draftId,
                    onOpenPosts = { open(PostsKey) },
                    onOpenSchedule = { open(ScheduleKey) },
                    onOpenSettings = { open(SettingsKey) },
                    // The R15 question has been answered: leave as the system back would.
                    onExit = { if (backStack.size > 1) back() else activity?.finish() },
                )
            }
            entry<PostsKey> { PostsScreen(onBack = back, onEdit = { open(ComposerKey(it)) }, onOpenSchedule = { open(ScheduleKey) }) }
            entry<ScheduleKey> { ScheduleScreen(onBack = back, onOpenPost = { open(ComposerKey(it)) }) }
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
