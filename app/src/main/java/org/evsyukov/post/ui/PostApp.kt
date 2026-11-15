package org.evsyukov.post.ui

import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import org.evsyukov.post.ui.composer.ComposerScreen
import org.evsyukov.post.ui.posts.PostsScreen
import org.evsyukov.post.ui.schedule.ScheduleScreen
import org.evsyukov.post.ui.settings.SettingsScreen

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
            entry<SettingsKey> { SettingsScreen(onBack = back) }
        },
    )
}
