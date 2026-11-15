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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.PostApplication
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.schedule.WeeklySchedule
import org.evsyukov.post.core.schedule.upcomingSlots
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.posts.DeliveryPlan
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.data.secrets.BlueskySecret
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.schedule.ScheduleScreen
import org.evsyukov.post.ui.schedule.ScheduleTags
import org.evsyukov.post.ui.schedule.SendLaterSheet
import org.evsyukov.post.ui.schedule.SendLaterTags
import org.evsyukov.post.ui.schedule.rememberSendLaterOptions
import org.evsyukov.post.ui.theme.PostTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/**
 * Screenshots of scheduling for a person to look at: the Send later sheet, the Schedule screen and the Queue
 * tab with its Late, Failed and waiting groups and the drag handle, in light and dark, English and Russian.
 * The posts, accounts and schedule made here are removed at the end.
 */
@RunWith(AndroidJUnit4::class)
class SchedulingScreenshots {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val container = ApplicationProvider.getApplicationContext<PostApplication>().container
    private val accounts = mutableListOf<Long>()
    private val posts = mutableListOf<Long>()

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
            NewAccount(Network.MASTODON, "https://mastodon.social", "denis@mastodon.social", "shot-1", "Denis", null),
            MastodonSecret("screenshot"),
        )
        val sky = container.accounts.add(
            NewAccount(Network.BLUESKY, "https://bsky.social", "denis.bsky.social", "did:plc:shot", "Denis", null),
            BlueskySecret("a", "r"),
        )
        accounts += listOf(denis, sky)
        val weekday = listOf(LocalTime.of(9, 0), LocalTime.of(13, 0), LocalTime.of(18, 0))
        val schedule = WeeklySchedule(
            mapOf(
                DayOfWeek.MONDAY to weekday, DayOfWeek.TUESDAY to weekday, DayOfWeek.WEDNESDAY to weekday,
                DayOfWeek.THURSDAY to weekday, DayOfWeek.FRIDAY to weekday, DayOfWeek.SATURDAY to listOf(LocalTime.of(11, 0)),
            ),
        )
        container.schedule.save(schedule)
        val slots = upcomingSlots(schedule, ZoneId.systemDefault(), Instant.now(), 4)

        suspend fun queue(text: String, at: Schedule, targets: List<Long> = listOf(denis, sky)) =
            container.posts.saveAndQueue(DraftInput(text = text, targets = targets), at).also { posts += it }

        val late = queue("Photo walk in the old town, part two", Schedule.Explicit(Instant.now().minusSeconds(3 * 3600)))
        container.posts.hold(listOf(late))
        val failed = queue("Release notes for version 0.1", Schedule.Explicit(Instant.now().minusSeconds(600)), listOf(denis))
        val delivery = container.posts.get(failed)!!.deliveries.single()
        container.posts.startDelivery(delivery.id, DeliveryPlan(emptyList()))
        container.posts.deliveryFailed(delivery.id, "422 Validation failed: Text character limit of 500 exceeded", null)
        queue("Morning links: Kotlin, Compose and a long read on scheduling", Schedule.Slot(slots[0]))
        queue("A pinned announcement at a fixed time", Schedule.Explicit(slots[1].atZone(ZoneId.systemDefault()).toInstant().plusSeconds(1800)))
        queue("Weekly digest", Schedule.Slot(slots[2]))
    }

    @After
    fun cleanUp() = runBlocking<Unit> {
        for (id in posts) container.posts.delete(id)
        for (id in accounts) container.accounts.remove(id)
        container.schedule.save(WeeklySchedule.EMPTY)
        container.lateNotifier.cancelLate()
    }

    @Test
    fun captureSendLaterScheduleAndQueueInLightAndDarkInEnglishAndRussian() {
        var variant by mutableStateOf(variants.first())
        var screen by mutableStateOf("queue")
        compose.setContent {
            val (locale, dark) = variant
            key(variant, screen) {
                Localized(locale, dark) {
                    CompositionLocalProvider(
                        LocalAppContainer provides container,
                        LocalActivityResultRegistryOwner provides compose.activity,
                        LocalOnBackPressedDispatcherOwner provides compose.activity,
                    ) {
                        when (screen) {
                            "queue" -> PostsScreen(onBack = {})
                            "schedule" -> ScheduleScreen(onBack = {})
                            else -> {
                                ScheduleScreen(onBack = {})
                                val options = rememberSendLaterOptions()
                                if (options != null) SendLaterSheet(options, onSlot = {}, onTime = {}, onOpenSchedule = {}, onDismiss = {})
                            }
                        }
                    }
                }
            }
        }
        each("queue") {
            variant = it
            screen = "queue"
            waitFor(QueueTags.LATE)
        }
        each("schedule") {
            variant = it
            screen = "schedule"
            waitFor(ScheduleTags.day(DayOfWeek.MONDAY))
        }
        each("send-later") {
            variant = it
            screen = "send-later"
            waitFor(SendLaterTags.NEXT_FREE)
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
