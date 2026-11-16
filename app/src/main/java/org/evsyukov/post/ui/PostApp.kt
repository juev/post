package org.evsyukov.post.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.ui.composer.ComposerScreen
import org.evsyukov.post.ui.posts.PostDetailsScreen
import org.evsyukov.post.ui.posts.PostsScreen
import org.evsyukov.post.ui.posts.SentNavigation
import org.evsyukov.post.ui.schedule.ScheduleScreen
import org.evsyukov.post.ui.settings.AboutScreen
import org.evsyukov.post.ui.settings.DefaultsSettingsScreen
import org.evsyukov.post.ui.settings.SendingSettingsScreen
import org.evsyukov.post.ui.settings.SettingsScreen
import org.evsyukov.post.ui.settings.accounts.AccountDetailsScreen
import org.evsyukov.post.ui.settings.accounts.AccountsScreen
import org.evsyukov.post.ui.settings.accounts.AddBlueskyScreen
import org.evsyukov.post.ui.settings.accounts.AddMastodonScreen

/**
 * The composer, the start screen (screens.md, Navigation); with [draftId] it opens that draft (R15), with
 * [editHistoryId] it edits that published post (R40), with [fromHistoryId] it starts a new post from it (R31).
 */
@Serializable
data class ComposerKey(
    val draftId: Long? = null,
    val editHistoryId: Long? = null,
    val fromHistoryId: Long? = null,
    /**
     * Tells apart two openings of the same draft, queued post, edit or new post from a record: models live as
     * long as the activity, and one that already saved, sent or cleared its post starts over as an empty draft.
     */
    val session: Long = 0,
) : NavKey

/** Post details of a queued post ([postId]) or of a sent one ([historyId]). */
@Serializable
data class PostDetailsKey(val postId: Long? = null, val historyId: Long? = null) : NavKey

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

/** [a] and [b] show the same screen: equal keys, or openings of the same post in the composer. */
private fun sameScreen(a: NavKey, b: NavKey): Boolean =
    a == b || (a is ComposerKey && b is ComposerKey && a.copy(session = 0) == b.copy(session = 0))

/** The widest a screen gets: on a tablet or in landscape it stays this wide, in the middle. */
val MaxContentWidth = 640.dp

/**
 * The composer, with the other screens opened on top of it by its top bar and closed by their back
 * arrow or by the system back. There is no bottom navigation: the composer keeps the whole screen.
 * [openOnStart] is a screen a notification asked for (product.md R5, R32), opened over the composer. With
 * [draftId] the composer starts with that draft, as Open in composer of the Share sheet asks (R20).
 */
@Composable
fun PostApp(
    openOnStart: NavKey? = null,
    draftId: Long? = null,
    /** A screen asked for while the app runs, such as by a tapped notification; [onRequestHandled] clears it. */
    request: NavKey? = null,
    onRequestHandled: () -> Unit = {},
) {
    val backStack = rememberNavBackStack(*listOfNotNull(ComposerKey(draftId), openOnStart).toTypedArray())
    val activity = LocalActivity.current
    // A second tap on an action while its screen slides in would stack the screen twice. Two openings of the same
    // post differ only in their session.
    val open: (NavKey) -> Unit = { key -> if (backStack.lastOrNull()?.let { sameScreen(it, key) } != true) backStack.add(key) }
    // A second tap on a back arrow while the screen slides out would pop the composer itself.
    val back: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
    // After a sign-in the add screen closes and the accounts list shows the account.
    val backToAccounts: () -> Unit = {
        while (backStack.size > 1 && backStack.last() != AccountsKey) backStack.removeAt(backStack.lastIndex)
        if (backStack.last() != AccountsKey) backStack.add(AccountsKey)
    }
    // Q6: every request for Posts (Review of the late notification) shows its Queue tab, also when Posts is
    // already open on another tab and nothing is pushed.
    var queueRequests by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(request) {
        if (request != null) {
            if (request == PostsKey) queueRequests++
            open(request)
            onRequestHandled()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        NavDisplay(
            backStack = backStack,
            modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxSize(),
            onBack = back,
            entryProvider = entryProvider {
                entry<ComposerKey> { key ->
                    ComposerScreen(
                        draftId = key.draftId,
                        editHistoryId = key.editHistoryId,
                        fromHistoryId = key.fromHistoryId,
                        session = key.session,
                        onOpenPosts = { open(PostsKey) },
                        onOpenSchedule = { open(ScheduleKey) },
                        onOpenSettings = { open(SettingsKey) },
                        // The R15 question has been answered: leave as the system back would.
                        onExit = { if (backStack.size > 1) back() else activity?.finish() },
                    )
                }
                val sent = SentNavigation(
                    onOpen = { open(PostDetailsKey(historyId = it)) },
                    onEdit = { open(ComposerKey(editHistoryId = it, session = System.nanoTime())) },
                    onNewFrom = { open(ComposerKey(fromHistoryId = it, session = System.nanoTime())) },
                )
                entry<PostsKey> {
                    PostsScreen(
                        onBack = back,
                        queueRequests = queueRequests,
                        onEdit = { open(ComposerKey(it, session = System.nanoTime())) },
                        onOpenSchedule = { open(ScheduleKey) },
                        onOpenDetails = { open(PostDetailsKey(postId = it)) },
                        sent = sent,
                    )
                }
                entry<PostDetailsKey> { key ->
                    PostDetailsScreen(
                        postId = key.postId,
                        historyId = key.historyId,
                        onBack = back,
                        onEdit = { open(ComposerKey(it, session = System.nanoTime())) },
                        onOpenSchedule = { open(ScheduleKey) },
                        navigation = sent,
                    )
                }
                entry<ScheduleKey> { ScheduleScreen(onBack = back, onOpenPost = { open(ComposerKey(it, session = System.nanoTime())) }) }
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
}
