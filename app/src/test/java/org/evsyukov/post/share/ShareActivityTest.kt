package org.evsyukov.post.share

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.MainActivity
import org.evsyukov.post.R
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.media.MediaKind
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.schedule.WeeklySchedule
import org.evsyukov.post.data.accounts.AccountLimits
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.posts.Post
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.data.secrets.BlueskySecret
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.data.settings.ShareAction
import org.evsyukov.post.data.settings.TooManyFilesDefault
import org.evsyukov.post.data.settings.UnsupportedTypeDefault
import org.evsyukov.post.media.importer.AttachmentImporter
import org.evsyukov.post.ui.composer.ComposerTags
import org.evsyukov.post.ui.schedule.SendLaterTags
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime

/** The Share activity end to end on the JVM: intents, the copy of the files, the sheet and its actions (R16, R18–R22, A12, A13). */
@RunWith(AndroidJUnit4::class)
@Config(application = ShareTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareActivityTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private val app: ShareTestApplication = ApplicationProvider.getApplicationContext()
    private val container get() = app.container
    private var scenario: ActivityScenario<ShareActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
        if (container.database.isOpen) container.database.close()
    }

    // Setup

    private fun mastodon(name: String): Long = runBlocking {
        val id = container.accounts.add(
            NewAccount(Network.MASTODON, "https://$name.example", "$name@$name.example", "r-$name", name, null),
            MastodonSecret("t-$name"),
        )
        container.accounts.updateLimits(id, AccountLimits.Mastodon(MastodonCapabilities()), Instant.EPOCH)
        id
    }

    private fun bluesky(name: String): Long = runBlocking {
        container.accounts.add(NewAccount(Network.BLUESKY, "https://pds.example", "$name.bsky.social", "did:plc:$name", name, null), BlueskySecret("a", "r"))
    }

    private fun defaults(vararg ids: Long) = runBlocking { container.settings.setDefaultTargets(ids.toList()) }

    /** A copy of the fixture [name] outside app storage, as the sending app would hold it. */
    private fun source(name: String, bytes: ByteArray? = null): Uri {
        val file = File(temp.newFolder(), name)
        if (bytes != null) {
            file.writeBytes(bytes)
        } else {
            requireNotNull(javaClass.getResourceAsStream("/media/$name")).use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        return Uri.fromFile(file)
    }

    private fun sendIntent(type: String, text: String? = null, stream: Uri? = null) = Intent(Intent.ACTION_SEND).setType(type).apply {
        text?.let { putExtra(Intent.EXTRA_TEXT, it) }
        stream?.let { putExtra(Intent.EXTRA_STREAM, it) }
    }

    private fun sendMultiple(type: String, uris: List<Uri>, text: String? = null) = Intent(Intent.ACTION_SEND_MULTIPLE).setType(type)
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)).apply { text?.let { putExtra(Intent.EXTRA_TEXT, it) } }

    /** Opens the sheet for [intent] and waits until the shared files are copied and the targets are read. */
    private fun share(intent: Intent) {
        scenario = ActivityScenario.launch(intent.setClass(app, ShareActivity::class.java))
        compose.waitUntil(TIMEOUT) {
            compose.onAllNodes(hasTestTag(ShareTags.SHEET)).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasTestTag(ShareTags.COPYING)).fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
    }

    private fun click(tag: String) {
        runCatching { compose.onNodeWithTag(tag).performScrollTo() }
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun waitFinished() = compose.waitUntil(TIMEOUT) { isFinished() }

    /** The activity called finish(); Robolectric leaves destroying it to the scenario. */
    private fun isFinished(): Boolean {
        val current = scenario!!
        if (current.state == Lifecycle.State.DESTROYED) return true
        var finishing = false
        current.onActivity { finishing = it.isFinishing }
        return finishing
    }

    private fun queue(): List<Post> = runBlocking { container.posts.observeQueue().first() }

    private fun drafts(): List<Post> = runBlocking { container.posts.observeDrafts().first() }

    private fun copies(): List<File> = File(app.filesDir, AttachmentImporter.DIR).listFiles().orEmpty().toList()

    private fun string(id: Int, vararg args: Any) = app.getString(id, *args)

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    // R19, R20: intents fill the sheet

    @Test
    fun sharedTextFillsTheSheetWithTheDefaultTargetsSelected() {
        val me = mastodon("me")
        val sky = bluesky("sky")
        mastodon("work")
        defaults(me, sky)
        share(sendIntent("text/plain", "Hello from the browser").putExtra(Intent.EXTRA_SUBJECT, "Title"))

        compose.onNodeWithTag(ShareTags.TEXT).assert(hasText("Title\n\nHello from the browser"))
        compose.onNodeWithTag(ComposerTags.target(me)).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onNodeWithTag(ComposerTags.target(sky)).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onAllNodes(hasTestTag(ShareTags.STATUS)).fetchSemanticsNodes().single()
        compose.onNodeWithTag(ShareTags.STATUS).assert(hasText("Mastodon 29/500", substring = true))
        compose.onNodeWithTag(ShareTags.STATUS).assert(hasText("Bluesky 29/300", substring = true))
    }

    /** R21: Send now saves the post as queued and due now, wakes the sender, closes and says how many accounts. */
    @Test
    fun sendNowQueuesThePostTriggersPublishingAndCloses() {
        val me = mastodon("me")
        val sky = bluesky("sky")
        val work = mastodon("work")
        defaults(me, sky)
        share(sendIntent("text/plain", "Hello"))
        // A tap on a chip changes the targets of this post only.
        click(ComposerTags.target(work))
        click(ComposerTags.target(sky))
        click(ShareTags.SEND)
        waitFinished()

        val post = queue().single()
        assertThat(post.text).isEqualTo("Hello")
        assertThat(post.state).isEqualTo(PostState.QUEUED)
        assertThat(post.targets).containsExactly(me, work)
        assertThat(post.schedule).isInstanceOf(Schedule.Now::class.java)
        assertThat(app.triggers.get()).isEqualTo(1)
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(app.resources.getQuantityString(R.plurals.composer_sending_to, 2, 2))
        runBlocking { assertThat(container.settings.current().defaultTargets).containsExactly(me, sky) }
    }

    /** R9, R19: the file is copied while the activity lives; the post keeps the copy after the source is gone. */
    @Test
    fun oneImageIsCopiedBeforeTheActivityFinishesAndOutlivesItsSource() {
        val me = mastodon("me")
        defaults(me)
        val uri = source("photo_exif_rot90.jpg")
        val bytes = File(uri.path!!).readBytes()
        share(sendIntent("image/jpeg", "A photo", uri))

        // The copy is there while the sheet is still open, before any action.
        assertThat(isFinished()).isFalse()
        val copy = copies().single()
        assertThat(copy.readBytes()).isEqualTo(bytes)
        // The sender's grant ends with its file; the copy does not depend on it.
        assertThat(File(uri.path!!).delete()).isTrue()

        click(ShareTags.SEND)
        waitFinished()
        val attachment = queue().single().attachments.single()
        assertThat(attachment.kind).isEqualTo(MediaKind.IMAGE)
        assertThat(container.attachmentImporter.file(attachment.path).readBytes()).isEqualTo(bytes)
    }

    @Test
    fun severalImagesComeInTheirOrder() {
        val me = mastodon("me")
        defaults(me)
        share(sendMultiple("image/*", listOf(source("alpha.png"), source("photo_exif_rot90.jpg"), source("still.gif")), "Three"))
        click(ShareTags.SEND)
        waitFinished()

        val post = queue().single()
        assertThat(post.text).isEqualTo("Three")
        assertThat(post.attachments.map { it.mime }).containsExactly("image/png", "image/jpeg", "image/gif").inOrder()
        assertThat(post.attachments.map { container.attachmentImporter.file(it.path).exists() }).containsExactly(true, true, true)
    }

    @Test
    fun aVideoIsSharedAsAVideo() {
        val me = mastodon("me")
        defaults(me)
        share(sendIntent("video/mp4", stream = source("clip.mp4")))
        click(ShareTags.SEND)
        waitFinished()
        assertThat(queue().single().attachments.single().kind).isEqualTo(MediaKind.VIDEO)
    }

    @Test
    fun audioGoesToMastodon() {
        val me = mastodon("me")
        defaults(me)
        share(sendIntent("audio/mpeg", "Listen", source("tone.mp3")))
        click(ShareTags.SEND)
        waitFinished()
        assertThat(queue().single().attachments.single().kind).isEqualTo(MediaKind.AUDIO)
    }

    /** A file that is no image, video or audio is not added; the sheet says so and the text can still go. */
    @Test
    fun anUnsupportedFileIsLeftOutAndNamed() {
        val me = mastodon("me")
        defaults(me)
        share(sendIntent("application/pdf", "A document", source("doc.pdf", "%PDF-1.4\n%âãÏÓ\n1 0 obj\n".toByteArray())))

        compose.onNodeWithTag(ShareTags.FAILED).assert(hasText(app.resources.getQuantityString(R.plurals.media_import_failed, 1, 1)))
        assertThat(copies()).isEmpty()
        click(ShareTags.SEND)
        waitFinished()
        assertThat(queue().single().attachments).isEmpty()
    }

    // R18: the default action

    @Test
    fun theDefaultActionOfTheSettingsIsTheFilledButton() {
        val me = mastodon("me")
        defaults(me)
        val isDefault = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, string(R.string.share_default_action))
        val notDefault = SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription)
        val tags = mapOf(ShareAction.COMPOSER to ShareTags.OPEN_COMPOSER, ShareAction.SEND_LATER to ShareTags.SEND_LATER, ShareAction.SEND_NOW to ShareTags.SEND)
        for ((action, tag) in tags) {
            runBlocking { container.settings.setShareAction(action) }
            share(sendIntent("text/plain", "Hi"))
            compose.onNodeWithTag(tag).assert(isDefault)
            (tags.values - tag).forEach { compose.onNodeWithTag(it).assert(notDefault) }
            scenario!!.close()
        }
    }

    // R21, R24: Send later

    /** A tap on Send later queues the post in the next free slot at once; a slot taken by another post is skipped. */
    @Test
    fun sendLaterTakesTheNextFreeSlot() {
        val me = mastodon("me")
        defaults(me)
        val time = LocalTime.of(12, 0)
        runBlocking { container.schedule.save(WeeklySchedule(DayOfWeek.entries.associateWith { listOf(time) })) }
        val now = LocalDateTime.now()
        val slots = generateSequence(now.toLocalDate()) { it.plusDays(1) }.map { it.atTime(time) }.filter { it.isAfter(now) }.take(2).toList()
        val taken = runBlocking {
            container.posts.saveAndQueue(org.evsyukov.post.data.posts.DraftInput(text = "earlier", targets = listOf(me)), Schedule.Slot(slots[0]))
        }

        share(sendIntent("text/plain", "Later"))
        click(ShareTags.SEND_LATER)
        waitFinished()

        val post = queue().single { it.id != taken }
        assertThat(post.text).isEqualTo("Later")
        assertThat(post.schedule).isEqualTo(Schedule.Slot(slots[1]))
        assertThat(ShadowToast.getTextOfLatestToast()).startsWith(string(R.string.composer_queued_for, "").trimEnd())
    }

    /** Without a schedule there is no slot: Send later asks for a date and time. */
    @Test
    fun sendLaterWithoutAScheduleOpensTheDateAndTimePicker() {
        val me = mastodon("me")
        defaults(me)
        share(sendIntent("text/plain", "Later"))
        click(ShareTags.SEND_LATER)
        compose.waitUntil(TIMEOUT) { exists(ComposerTags.DATE_CONFIRM) }
        assertThat(isFinished()).isFalse()
        assertThat(queue()).isEmpty()
    }

    /** A long press opens the Send later sheet; its next free slot queues the post there. */
    @Test
    fun aLongPressOnSendLaterOpensTheSendLaterSheet() {
        val me = mastodon("me")
        defaults(me)
        val time = LocalTime.of(12, 0)
        runBlocking { container.schedule.save(WeeklySchedule(DayOfWeek.entries.associateWith { listOf(time) })) }
        val now = LocalDateTime.now()
        val next = generateSequence(now.toLocalDate()) { it.plusDays(1) }.map { it.atTime(time) }.first { it.isAfter(now) }

        share(sendIntent("text/plain", "Later"))
        compose.onNodeWithTag(ShareTags.SEND_LATER).performTouchInput { longClick() }
        compose.waitUntil(TIMEOUT) { exists(SendLaterTags.NEXT_FREE) }
        click(SendLaterTags.NEXT_FREE)
        waitFinished()
        assertThat(queue().single().schedule).isEqualTo(Schedule.Slot(next))
    }

    // R22, A12, A13: conflicts

    /** Audio for Bluesky without a default fix: both sends are off, the line names the account; Open in composer works. */
    @Test
    fun aConflictWithoutADefaultFixDisablesSendingAndNamesTheAccount() {
        val sky = bluesky("sky")
        defaults(sky)
        share(sendIntent("audio/mpeg", "Listen", source("tone.mp3")))

        compose.onNodeWithTag(ShareTags.SEND).assertIsNotEnabled()
        compose.onNodeWithTag(ShareTags.SEND_LATER).assertIsNotEnabled()
        compose.onNodeWithTag(ShareTags.problem(0)).assert(
            hasText(string(R.string.share_problem, "sky.bsky.social", "Bluesky", string(R.string.media_conflict_unsupported_type))),
        )
        compose.onNodeWithTag(ShareTags.OPEN_COMPOSER).assertIsEnabled()

        click(ShareTags.OPEN_COMPOSER)
        waitFinished()
        val draft = drafts().single()
        assertThat(draft.text).isEqualTo("Listen")
        assertThat(draft.targets).containsExactly(sky)
        assertThat(container.attachmentImporter.file(draft.attachments.single().path).exists()).isTrue()
        val started = shadowOf(app).nextStartedActivity
        assertThat(started.component?.className).isEqualTo(MainActivity::class.java.name)
        assertThat(started.getLongExtra(MainActivity.EXTRA_DRAFT_ID, -1)).isEqualTo(draft.id)
        assertThat(queue()).isEmpty()
    }

    /** A12: with "send without the file" as the default, Send stays on and the sheet lists what will change. */
    @Test
    fun aDefaultFixKeepsSendingAndIsListed() {
        val sky = bluesky("sky")
        defaults(sky)
        runBlocking { container.settings.setDefaultFixUnsupportedType(UnsupportedTypeDefault.SEND_WITHOUT_FILE) }
        share(sendIntent("audio/mpeg", "Listen", source("tone.mp3")))

        compose.onNodeWithTag(ShareTags.change(0)).assert(
            hasText(string(R.string.share_change, "Bluesky", string(R.string.media_conflict_unsupported_type), string(R.string.media_fix_send_without_file))),
        )
        assertThat(exists(ShareTags.PROBLEMS)).isFalse()
        compose.onNodeWithTag(ShareTags.SEND).assertIsEnabled()
        click(ShareTags.SEND)
        waitFinished()
        assertThat(queue().single().targets).containsExactly(sky)
    }

    /** Five images for Mastodon, which takes four: blocked without a default; moved to a reply with "move to a reply" as the default. */
    @Test
    fun tooManyFilesFollowTheDefaultFix() {
        val me = mastodon("me")
        defaults(me)
        val images = List(5) { source("photo_exif_rot90.jpg").let { uri -> File(uri.path!!).let { f -> Uri.fromFile(f.copyTo(File(f.parentFile, "p$it.jpg"))) } } }
        share(sendMultiple("image/*", images, "Five"))
        compose.onNodeWithTag(ShareTags.SEND).assertIsNotEnabled()
        compose.onNodeWithTag(ShareTags.problem(0)).assert(
            hasText(string(R.string.share_problem, "me@me.example", "Mastodon", string(R.string.media_conflict_too_many_files))),
        )

        runBlocking { container.settings.setDefaultFixTooManyFiles(TooManyFilesDefault.MOVE_TO_REPLY) }
        compose.waitUntil(TIMEOUT) { exists(ShareTags.CHANGES) }
        compose.onNodeWithTag(ShareTags.change(0)).assert(
            hasText(string(R.string.share_change, "Mastodon", string(R.string.media_conflict_too_many_files), string(R.string.media_fix_move_to_reply))),
        )
        compose.onNodeWithTag(ShareTags.SEND).assertIsEnabled()
    }

    /** T9, R22: a word longer than Bluesky's limit cannot be split; the sheet names the account and the limit. */
    @Test
    fun anUnsplittableTextDisablesSending() {
        val me = mastodon("me")
        val sky = bluesky("sky")
        defaults(me, sky)
        share(sendIntent("text/plain", "x".repeat(320)))

        compose.onNodeWithTag(ShareTags.SEND).assertIsNotEnabled()
        compose.onNodeWithTag(ShareTags.SEND_LATER).assertIsNotEnabled()
        compose.onNodeWithTag(ShareTags.problem(0)).assert(
            hasText(string(R.string.share_problem, "sky.bsky.social", "Bluesky", string(R.string.share_problem_too_long, 300))),
        )
        compose.onNodeWithTag(ShareTags.OPEN_COMPOSER).assertIsEnabled()
        // Shortening the text in the sheet lifts the block.
        compose.onNodeWithTag(ShareTags.TEXT).performTextReplacement("short")
        compose.waitForIdle()
        compose.onNodeWithTag(ShareTags.SEND).assertIsEnabled()
    }

    // Leaving

    /** Closing without posting deletes the copies, which nothing else holds. */
    @Test
    fun dismissingTheSheetDeletesTheCopies() {
        val me = mastodon("me")
        defaults(me)
        share(sendIntent("image/jpeg", stream = source("photo_exif_rot90.jpg")))
        assertThat(copies()).hasSize(1)
        // A tap above the sheet, on the app that shared.
        compose.onNodeWithTag(ShareTags.SCRIM).performTouchInput { this.click(androidx.compose.ui.geometry.Offset(centerX, 4f)) }
        waitFinished()
        // The finished activity is destroyed, and its model with it.
        scenario!!.close()
        compose.waitUntil(TIMEOUT) { copies().isEmpty() }
        assertThat(queue()).isEmpty()
        assertThat(drafts()).isEmpty()
    }

    /** A rotation keeps the edits and the copies; the files are not copied again. */
    @Test
    fun aRotationKeepsTheSheetAndItsFiles() {
        val me = mastodon("me")
        val work = mastodon("work")
        defaults(me)
        share(sendIntent("image/jpeg", "Before", source("photo_exif_rot90.jpg")))
        compose.onNodeWithTag(ShareTags.TEXT).performTextReplacement("Edited")
        click(ComposerTags.target(work))

        scenario!!.recreate()
        compose.waitUntil(TIMEOUT) { exists(ShareTags.SHEET) }
        compose.onNodeWithTag(ShareTags.TEXT).assert(hasText("Edited"))
        assertThat(copies()).hasSize(1)

        click(ShareTags.SEND)
        waitFinished()
        val post = queue().single()
        assertThat(post.text).isEqualTo("Edited")
        assertThat(post.targets).containsExactly(me, work)
        assertThat(post.attachments.single().path).isEqualTo("${AttachmentImporter.DIR}/${copies().single().name}")
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
