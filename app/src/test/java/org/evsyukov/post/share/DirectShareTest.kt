package org.evsyukov.post.share

import android.content.Context
import android.os.Looper
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.AppContainer
import org.evsyukov.post.R
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.FakeCipher
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.secrets.BlueskySecret
import org.evsyukov.post.data.secrets.MastodonSecret
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** R23: the sharing shortcut of Direct Share, and its refresh when the default targets change. */
@RunWith(AndroidJUnit4::class)
class DirectShareTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container = AppContainer(context) { FakeCipher() }

    @After
    fun tearDown() {
        if (container.database.isOpen) container.database.close()
    }

    private fun shortcut() = ShortcutManagerCompat.getDynamicShortcuts(context).singleOrNull { it.id == DirectShare.ID }

    private fun until(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun theShortcutIsALongLivedShareTarget() {
        DirectShare.publish(context, emptyList())
        val shortcut = requireNotNull(shortcut())
        // The getter is hidden in the SDK; the platform object carries the flag the builder set.
        val platform = context.getSystemService(android.content.pm.ShortcutManager::class.java).dynamicShortcuts.single { it.id == DirectShare.ID }
        assertThat(android.content.pm.ShortcutInfo::class.java.getMethod("isLongLived").invoke(platform)).isEqualTo(true)
        assertThat(shortcut.categories).containsExactly(DirectShare.CATEGORY)
        assertThat(shortcut.shortLabel.toString()).isEqualTo(context.getString(R.string.app_name))
    }

    /** The category matches the share target of res/xml/shortcuts.xml, which names the Share activity. */
    @Test
    fun theShareTargetOfTheShortcutsFileNamesTheCategoryAndTheTypes() {
        val parser = context.resources.getXml(R.xml.shortcuts)
        val types = mutableListOf<String>()
        var category: String? = null
        var target: String? = null
        while (parser.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) continue
            val android = "http://schemas.android.com/apk/res/android"
            when (parser.name) {
                "share-target" -> target = parser.getAttributeValue(android, "targetClass")
                "data" -> types += parser.getAttributeValue(android, "mimeType")
                "category" -> category = parser.getAttributeValue(android, "name")
            }
        }
        assertThat(target).isEqualTo(ShareActivity::class.java.name)
        assertThat(category).isEqualTo(DirectShare.CATEGORY)
        assertThat(types).containsExactly("text/plain", "image/*", "video/*", "audio/*")
    }

    @Test
    fun theShortcutFollowsTheDefaultTargets() {
        val me = runBlocking {
            container.accounts.add(NewAccount(Network.MASTODON, "https://me.example", "me@me.example", "r-me", "me", null), MastodonSecret("t"))
        }
        val sky = runBlocking {
            container.accounts.add(NewAccount(Network.BLUESKY, "https://pds.example", "sky.bsky.social", "did:plc:sky", "sky", null), BlueskySecret("a", "r"))
        }
        DirectShare.follow(container)
        until { shortcut() != null }
        assertThat(shortcut()!!.shortLabel.toString()).isEqualTo(context.getString(R.string.app_name))

        runBlocking { container.settings.setDefaultTargets(listOf(me)) }
        until { shortcut()?.shortLabel?.toString() == "me@me.example" }

        runBlocking { container.settings.setDefaultTargets(listOf(me, sky)) }
        until { shortcut()?.longLabel?.toString() == context.getString(R.string.share_shortcut_long, "me@me.example, sky.bsky.social") }
        assertThat(shortcut()!!.shortLabel.toString()).isEqualTo(context.getString(R.string.app_name))
    }
}
