package org.evsyukov.post.ui.posts

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.PostApplication
import org.evsyukov.post.core.markdown.MANUAL_BREAK
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.history.HistoryItem
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.PollDraft
import org.evsyukov.post.data.posts.PostOptions
import org.evsyukov.post.data.posts.PublishedMedia
import org.evsyukov.post.data.posts.PublishedPart
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.data.secrets.BlueskySecret
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.composer.ComposerScreen
import org.evsyukov.post.ui.composer.ComposerTags
import org.evsyukov.post.ui.theme.PostTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.Locale

/**
 * Screenshots for a person to look at (screens.md, Posts, Post details, Editing a published post): the Drafts
 * and Sent tabs, Post details of a queued and of a sent post, and the composer editing a published post, in
 * light and dark, English and Russian. Everything made here is removed at the end.
 */
@RunWith(AndroidJUnit4::class)
class PublishedScreenshots {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val container = ApplicationProvider.getApplicationContext<PostApplication>().container
    private val accounts = mutableListOf<Long>()
    private val posts = mutableListOf<Long>()
    private var record = 0L
    private var queued = 0L

    private val outDir: File by lazy {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots")
        dir.also { it.mkdirs() }
    }

    private val variants = listOf(
        Locale.ENGLISH to false, Locale.ENGLISH to true, Locale.forLanguageTag("ru") to false, Locale.forLanguageTag("ru") to true,
    )

    @Before
    fun seed() = runBlocking<Unit> {
        val denis = container.accounts.add(
            NewAccount(Network.MASTODON, "https://mastodon.social", "denis@mastodon.social", "shot-11", "Denis", null),
            MastodonSecret("screenshot"),
        )
        val sky = container.accounts.add(
            NewAccount(Network.BLUESKY, "https://bsky.social", "denis.bsky.social", "did:plc:shot11", "Denis", null),
            BlueskySecret("a", "r"),
        )
        accounts += listOf(denis, sky)
        posts += container.posts.saveDraft(DraftInput(text = "Notes from the conference, day two: talks on Compose and offline sync", targets = listOf(denis, sky)))
        posts += container.posts.saveDraft(DraftInput(text = "Half-written thought about release trains", targets = listOf(denis)))
        queued = container.posts.saveAndQueue(
            DraftInput(text = "Morning links: Kotlin and Compose\n$MANUAL_BREAK\nAnd a long read on scheduling", targets = listOf(denis, sky)),
            Schedule.Explicit(Instant.now().plusSeconds(3 * 3600)),
        ).also { posts += it }
        val text = "Photo walk in the old town\n$MANUAL_BREAK\nThe second half of the walk, by the river"
        record = container.history.insert(
            null,
            Instant.now().minusSeconds(1800),
            text,
            PostOptions(poll = PollDraft(listOf("Old town", "River"), 86_400)),
            listOf(
                HistoryItem(
                    denis, Network.MASTODON, "https://mastodon.social", "denis@mastodon.social",
                    listOf(
                        PublishedPart(
                            id = "1", url = "https://mastodon.social/@denis/1", text = "Photo walk in the old town",
                            media = listOf(PublishedMedia("m1", "A narrow street with lanterns")),
                        ),
                        PublishedPart(id = "2", url = "https://mastodon.social/@denis/2", text = "The second half of the walk, by the river"),
                    ),
                ),
                HistoryItem(
                    sky, Network.BLUESKY, "https://bsky.social", "denis.bsky.social",
                    listOf(PublishedPart(uri = "at://did:plc:shot11/app.bsky.feed.post/3k", url = "https://bsky.app/profile/did:plc:shot11/post/3k", text = text)),
                ),
            ),
        )!!
    }

    @After
    fun cleanUp() = runBlocking<Unit> {
        container.history.delete(record)
        for (id in posts) container.posts.delete(id)
        for (id in accounts) container.accounts.remove(id)
    }

    @Test
    fun captureDraftsSentDetailsAndEditingInLightAndDarkInEnglishAndRussian() {
        var variant by mutableStateOf(variants.first())
        var screen by mutableStateOf("drafts")
        compose.setContent {
            val (locale, dark) = variant
            key(variant, screen) {
                Localized(locale, dark) {
                    CompositionLocalProvider(
                        LocalAppContainer provides container,
                        LocalActivityResultRegistryOwner provides compose.activity,
                        LocalOnBackPressedDispatcherOwner provides compose.activity,
                    ) {
                        val nav = SentNavigation({}, {}, {})
                        when (screen) {
                            "drafts", "sent" -> PostsScreen(onBack = {})
                            "details-queued" -> PostDetailsScreen(queued, null, onBack = {}, onEdit = {}, onOpenSchedule = {}, navigation = nav)
                            "details-sent" -> PostDetailsScreen(null, record, onBack = {}, onEdit = {}, onOpenSchedule = {}, navigation = nav)
                            else -> ComposerScreen(onOpenPosts = {}, onOpenSchedule = {}, onOpenSettings = {}, editHistoryId = record)
                        }
                    }
                }
            }
        }
        each("drafts") {
            variant = it
            screen = "drafts"
            waitFor(PostsTags.TAB_DRAFTS)
            compose.onNodeWithTag(PostsTags.TAB_DRAFTS).performClick()
            waitFor(DraftsTags.LIST)
        }
        each("sent") {
            variant = it
            screen = "sent"
            waitFor(PostsTags.TAB_SENT)
            compose.onNodeWithTag(PostsTags.TAB_SENT).performClick()
            waitFor(SentTags.row(record))
        }
        each("details-queued") {
            variant = it
            screen = "details-queued"
            waitFor(DetailsTags.part(accounts[0], 1))
        }
        each("details-sent") {
            variant = it
            screen = "details-sent"
            waitFor(DetailsTags.EDIT_PUBLISHED)
        }
        each("edit-published") {
            variant = it
            screen = "edit"
            waitFor(ComposerTags.blueskyLeave(accounts[1]))
        }
    }

    private fun each(name: String, show: (Pair<Locale, Boolean>) -> Unit) {
        for (v in variants) {
            show(v)
            compose.waitForIdle()
            Thread.sleep(900)
            compose.waitForIdle()
            capture("$name-${v.first.language}-${if (v.second) "dark" else "light"}.png")
        }
    }

    private fun waitFor(tag: String) =
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty() }

    private fun capture(file: String) {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(outDir, file).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

/** [content] with the strings of [locale] and the [dark] theme, leaving the device settings alone. */
@Composable
private fun Localized(locale: Locale, dark: Boolean, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val config = Configuration(LocalConfiguration.current).apply {
        setLocale(locale)
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
    }
    val context = base.createConfigurationContext(config)
    CompositionLocalProvider(
        LocalContext provides context,
        LocalConfiguration provides config,
        LocalResources provides context.resources,
    ) {
        PostTheme(darkTheme = dark) { Surface { content() } }
    }
}
