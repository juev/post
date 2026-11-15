package org.evsyukov.post.share

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.evsyukov.post.AppContainer
import org.evsyukov.post.MainActivity
import org.evsyukov.post.R
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.ui.composer.chipLabel

/**
 * The Direct Share target (product.md R23): one long-lived sharing shortcut whose category the share target of
 * `res/xml/shortcuts.xml` names, so Post shows in the Direct Share row for text, images, video and audio. Its
 * labels name the default targets, so it is published again whenever they change.
 */
object DirectShare {
    /** The category of `res/xml/shortcuts.xml`. */
    const val CATEGORY = "org.evsyukov.post.category.SHARE_TARGET"
    const val ID = "share-default-targets"

    /**
     * Publishes the shortcut for [targets], the default targets in order. Publishing again also tells Android the
     * target was used, which keeps it ranked. The shortcut opens Post from the launcher menu too: one excluded
     * from the launcher is not kept as a dynamic shortcut, and the share sheet does not show it (ShareDing,
     * checked on Android 16).
     */
    fun publish(context: Context, targets: List<Account>) {
        val app = context.getString(R.string.app_name)
        val names = targets.joinToString(", ") { it.chipLabel() }
        val shortcut = ShortcutInfoCompat.Builder(context, ID)
            .setShortLabel(if (targets.size == 1) names else app)
            .setLongLabel(if (targets.isEmpty()) app else context.getString(R.string.share_shortcut_long, names))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_launcher))
            .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
            .setLongLived(true)
            .setCategories(setOf(CATEGORY))
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
    }

    /** Publishes the shortcut now and again whenever the default targets or their accounts change. */
    fun follow(container: AppContainer) {
        container.scope.launch {
            container.settings.settings.combine(container.accounts.observe()) { settings, accounts ->
                val byId = accounts.associateBy { it.id }
                settings.defaultTargets.mapNotNull { byId[it] }
            }
                .distinctUntilChanged { old, new -> old.map { it.id to it.chipLabel() } == new.map { it.id to it.chipLabel() } }
                .collect { targets ->
                    try {
                        publish(container.context, targets)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A rate limit or a launcher without shortcuts; the next change tries again.
                        android.util.Log.w("PostShare", "cannot publish the Direct Share shortcut", e)
                    }
                }
        }
    }
}
