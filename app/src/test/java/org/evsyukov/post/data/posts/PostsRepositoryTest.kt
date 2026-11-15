package org.evsyukov.post.data.posts

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.core.mastodon.Visibility
import org.evsyukov.post.core.media.ConflictKind
import org.evsyukov.post.core.media.FixKind
import org.evsyukov.post.core.media.MediaKind
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.schedule.SendTime
import org.evsyukov.post.core.schedule.reorder
import org.evsyukov.post.data.FakeCipher
import org.evsyukov.post.data.MutableClock
import org.evsyukov.post.data.RecordingFiles
import org.evsyukov.post.data.accounts.AccountOptions
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.accounts.RoomAccountsRepository
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.db.PostDatabase
import org.evsyukov.post.data.db.PostKind
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.history.HistoryItem
import org.evsyukov.post.data.history.RoomHistoryRepository
import org.evsyukov.post.data.inMemoryDatabase
import org.evsyukov.post.data.rows
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.data.secrets.SecretStore
import org.evsyukov.post.data.settings.HistoryRetention
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Drafts, the queue and deliveries in Room (R15, R24–R31, scheduling.md S2–S10, Q1–Q8). */
@RunWith(AndroidJUnit4::class)
class PostsRepositoryTest {
    // Monday, 16 November 2026, 08:00 in Berlin (UTC+1).
    private val clock = MutableClock(Instant.parse("2026-11-16T07:00:00Z"))
    private var zone: ZoneId = BERLIN
    private var retention = HistoryRetention.DAYS_30
    private val files = RecordingFiles()
    private val db: PostDatabase = inMemoryDatabase()
    private val accounts = RoomAccountsRepository(db, SecretStore(FakeCipher()), clock)
    private val repo = RoomPostsRepository(db, files, { retention }, clock, { zone })
    private val history = RoomHistoryRepository(db) { retention }

    private var alice = 0L
    private var bob = 0L
    private var carol = 0L

    @Before
    fun setUp() = runBlocking<Unit> {
        alice = accounts.add(NewAccount(Network.MASTODON, "https://mastodon.social", "alice", "1", "Alice", null), MastodonSecret("t"))
        bob = accounts.add(NewAccount(Network.MASTODON, "https://fosstodon.org", "bob", "2", "Bob", null), MastodonSecret("t"))
        carol = accounts.add(NewAccount(Network.MASTODON, "https://hachyderm.io", "carol", "3", "Carol", null), MastodonSecret("t"))
    }

    @After
    fun tearDown() = db.close()

    // Drafts (R15)

    @Test
    fun aDraftKeepsItsTextOptionsTargetsAndAttachments() = runBlocking<Unit> {
        val options = PostOptions(
            common = AccountOptions(languages = listOf("en"), contentWarning = "politics"),
            accounts = mapOf(alice to AccountOptions(visibility = Visibility.UNLISTED, replyRules = emptySet())),
            poll = PollDraft(listOf("Yes", "No"), expiresInSeconds = 86_400, multiple = true),
            linkCard = false,
            mediaFixes = mapOf(
                Network.MASTODON to mapOf(ConflictKind.TOO_MANY_FILES to FixKind.MOVE_TO_REPLY),
                Network.BLUESKY to mapOf(ConflictKind.UNSUPPORTED_TYPE to FixKind.SEND_WITHOUT_FILE),
            ),
        )
        val video = Attachment(
            kind = MediaKind.VIDEO,
            path = "attachments/v.mp4",
            mime = "video/mp4",
            sizeBytes = 50_000_000,
            width = 1920,
            height = 1080,
            durationMs = 61_000,
            frameRate = 29.97,
            videoCodec = "video/avc",
            pieceIndex = 1,
            alt = "A walk",
            focusX = -0.5f,
            focusY = 0.25f,
        )
        val gif = Attachment(kind = MediaKind.GIF, path = "attachments/g.gif", mime = "image/gif", sizeBytes = 900, animated = true, hasTransparency = true)

        val id = repo.saveDraft(
            DraftInput(text = "Hello\n\n⸻\n\nworld", options = options, targets = listOf(bob, alice), attachments = listOf(video, gif)),
        )

        val post = repo.get(id)!!
        assertThat(post.state).isEqualTo(PostState.DRAFT)
        assertThat(post.kind).isEqualTo(PostKind.NEW)
        assertThat(post.text).isEqualTo("Hello\n\n⸻\n\nworld")
        assertThat(post.options).isEqualTo(options)
        assertThat(post.options.forAccount(alice).visibility).isEqualTo(Visibility.UNLISTED)
        assertThat(post.options.forAccount(bob)).isEqualTo(options.common)
        assertThat(post.targets).containsExactly(bob, alice).inOrder()
        assertThat(post.schedule).isNull()
        assertThat(post.deliveries).isEmpty()
        assertThat(post.attachments.map { it.copy(id = 0) }).containsExactly(video, gif).inOrder()
        assertThat(post.createdAt).isEqualTo(clock.now)
    }

    @Test
    fun savingADraftAgainUpdatesItInPlaceAndDeletesTheFilesOfRemovedAttachments() = runBlocking<Unit> {
        val id = repo.saveDraft(
            DraftInput(text = "v1", attachments = listOf(image("attachments/a.jpg", "a"), image("attachments/b.jpg"), image("attachments/c.jpg"))),
        )
        val (a, b, c) = repo.get(id)!!.attachments
        repo.putVariant(Variant(attachmentId = b.id, profileKey = "bluesky", path = "attachments/b-bluesky.jpg", mime = "image/jpeg", sizeBytes = 10))
        val created = clock.now
        clock.advance(Duration.ofMinutes(1))

        val again = repo.saveDraft(
            DraftInput(
                id = id,
                text = "v2",
                targets = listOf(alice),
                attachments = listOf(
                    c.copy(alt = "now first", focusX = 0.5f, focusY = -1f, pieceIndex = 2, path = "ignored", sizeBytes = 1),
                    a,
                    image("attachments/d.jpg", "new"),
                ),
            ),
        )

        assertThat(again).isEqualTo(id)
        val post = repo.get(id)!!
        assertThat(post.text).isEqualTo("v2")
        assertThat(post.targets).containsExactly(alice)
        assertThat(post.createdAt).isEqualTo(created)
        assertThat(post.updatedAt).isEqualTo(clock.now)
        assertThat(post.attachments.map { it.path }).containsExactly("attachments/c.jpg", "attachments/a.jpg", "attachments/d.jpg").inOrder()
        assertThat(post.attachments[0].id).isEqualTo(c.id)
        assertThat(post.attachments[0].alt).isEqualTo("now first")
        assertThat(post.attachments[0].focusX).isEqualTo(0.5f)
        assertThat(post.attachments[0].focusY).isEqualTo(-1f)
        assertThat(post.attachments[0].pieceIndex).isEqualTo(2)
        // The file and its facts stay as they were copied.
        assertThat(post.attachments[0].sizeBytes).isEqualTo(c.sizeBytes)
        assertThat(post.attachments[1].id).isEqualTo(a.id)
        assertThat(files.deleted).containsExactly("attachments/b.jpg", "attachments/b-bluesky.jpg")
        assertThat(db.rows("SELECT COUNT(*) FROM variants").single().single()).isEqualTo(0L)
    }

    @Test
    fun anAttachmentOfAnotherPostIsRejectedAndNothingChanges() = runBlocking<Unit> {
        val first = repo.saveDraft(DraftInput(text = "one", attachments = listOf(image("attachments/1.jpg"))))
        val second = repo.saveDraft(DraftInput(text = "two"))
        val foreign = repo.get(first)!!.attachments.single()

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.saveDraft(DraftInput(id = second, text = "changed", attachments = listOf(foreign))) }
        }

        assertThat(repo.get(second)!!.text).isEqualTo("two")
        assertThat(repo.get(first)!!.attachments.single()).isEqualTo(foreign)
    }

    @Test
    fun draftsAreListedLastSavedFirstAndLeaveWhenQueued() = runBlocking<Unit> {
        val first = repo.saveDraft(DraftInput(text = "first"))
        clock.advance(Duration.ofMinutes(1))
        val second = repo.saveDraft(DraftInput(text = "second"))
        clock.advance(Duration.ofMinutes(1))
        val third = repo.saveDraft(DraftInput(text = "third"))
        clock.advance(Duration.ofMinutes(1))
        repo.saveDraft(DraftInput(id = first, text = "first, edited"))

        assertThat(repo.observeDrafts().first().map { it.id }).containsExactly(first, third, second).inOrder()

        repo.queue(third, Schedule.Now(clock.now), listOf(alice))
        assertThat(repo.observeDrafts().first().map { it.id }).containsExactly(first, second).inOrder()
    }

    @Test
    fun deletingADraftRemovesItWithItsFilesButNotAQueuedPost() = runBlocking<Unit> {
        val draft = repo.saveDraft(DraftInput(text = "d", attachments = listOf(image("attachments/d.jpg"))))
        val attachment = repo.get(draft)!!.attachments.single()
        repo.putVariant(Variant(attachmentId = attachment.id, profileKey = "p", path = "attachments/d-p.jpg", mime = "image/jpeg", sizeBytes = 1))
        val queued = queued("q", listOf(alice))

        assertThat(repo.deleteDraft(queued)).isFalse()
        assertThat(repo.get(queued)).isNotNull()

        assertThat(repo.deleteDraft(draft)).isTrue()
        assertThat(repo.get(draft)).isNull()
        assertThat(files.deleted).containsExactly("attachments/d.jpg", "attachments/d-p.jpg")
        assertThat(db.rows("SELECT COUNT(*) FROM attachments").single().single()).isEqualTo(0L)
        assertThat(repo.deleteDraft(draft)).isFalse()
    }

    // Queue (R27, Q1, Q8)

    @Test
    fun queueingWritesAWaitingDeliveryPerTargetAndTheSlotResolvedInTheDeviceZone() = runBlocking<Unit> {
        val id = repo.saveDraft(DraftInput(text = "hello", targets = listOf(bob)))
        val slot = LocalDateTime.parse("2026-11-16T09:00")

        repo.queue(id, Schedule.Slot(slot), listOf(carol, alice))

        val post = repo.get(id)!!
        assertThat(post.state).isEqualTo(PostState.QUEUED)
        assertThat(post.schedule).isEqualTo(Schedule.Slot(slot))
        assertThat(post.sendAt).isEqualTo(Instant.parse("2026-11-16T08:00:00Z"))
        assertThat(post.targets).containsExactly(carol, alice).inOrder()
        assertThat(post.deliveries.map { it.accountId }).containsExactly(carol, alice).inOrder()
        for (delivery in post.deliveries) {
            assertThat(delivery.state).isEqualTo(DeliveryState.WAITING)
            assertThat(delivery.attempts).isEqualTo(0)
            assertThat(delivery.plan).isNull()
            assertThat(delivery.progress).isEqualTo(DeliveryProgress())
            assertThat(delivery.error).isNull()
        }
    }

    @Test
    fun queueingToAnUnknownAccountFailsAndLeavesTheDraftAsItWas() = runBlocking<Unit> {
        val id = repo.saveDraft(DraftInput(text = "hello", targets = listOf(alice)))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.queue(id, Schedule.Now(clock.now), listOf(alice, 999)) }
        }

        // R27: one transaction; the post is not half queued.
        val post = repo.get(id)!!
        assertThat(post.state).isEqualTo(PostState.DRAFT)
        assertThat(post.schedule).isNull()
        assertThat(post.targets).containsExactly(alice)
        assertThat(post.deliveries).isEmpty()
        assertThat(db.rows("SELECT COUNT(*) FROM deliveries").single().single()).isEqualTo(0L)
    }

    @Test
    fun aPostNeedsDistinctTargetsToBeQueued() = runBlocking<Unit> {
        val id = repo.saveDraft(DraftInput(text = "hello"))

        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.queue(id, Schedule.Now(clock.now), emptyList()) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { repo.queue(id, Schedule.Now(clock.now), listOf(alice, alice)) } }
        assertThat(repo.get(id)!!.state).isEqualTo(PostState.DRAFT)
    }

    @Test
    fun aQueuedPostSurvivesTheProcessAndReopeningTheDatabase() = runBlocking<Unit> {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "reopen-test.db"
        context.deleteDatabase(name)
        try {
            val first = Room.databaseBuilder(context, PostDatabase::class.java, name).allowMainThreadQueries().build()
            val account = RoomAccountsRepository(first, SecretStore(FakeCipher()), clock)
                .add(NewAccount(Network.MASTODON, "https://m.example", "me", "1", "Me", null), MastodonSecret("t"))
            val posts = RoomPostsRepository(first, files, { retention }, clock, { zone })
            val id = posts.saveDraft(DraftInput(text = "survives", attachments = listOf(image("attachments/s.jpg"))))
            posts.queue(id, Schedule.Explicit(Instant.parse("2026-11-17T10:00:00Z")), listOf(account))
            first.close()

            val reopened = Room.databaseBuilder(context, PostDatabase::class.java, name).allowMainThreadQueries().build()
            try {
                val post = RoomPostsRepository(reopened, files, { retention }, clock, { zone }).get(id)!!
                assertThat(post.text).isEqualTo("survives")
                assertThat(post.state).isEqualTo(PostState.QUEUED)
                assertThat(post.schedule).isEqualTo(Schedule.Explicit(Instant.parse("2026-11-17T10:00:00Z")))
                assertThat(post.attachments.single().path).isEqualTo("attachments/s.jpg")
                assertThat(post.deliveries.single().state).isEqualTo(DeliveryState.WAITING)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    @Test
    fun theQueueListsWaitingHeldSendingAndFailedPostsByTimeThenQueueOrder() = runBlocking<Unit> {
        val later = queued("later", listOf(alice), Schedule.Explicit(Instant.parse("2026-11-16T10:00:00Z")))
        val firstNow = queued("first now", listOf(alice))
        val secondNow = queued("second now", listOf(alice))
        val held = queued("held", listOf(alice), Schedule.Explicit(Instant.parse("2026-11-16T06:00:00Z")))
        repo.hold(listOf(held))
        val failed = queued("failed", listOf(bob), Schedule.Explicit(Instant.parse("2026-11-16T05:00:00Z")))
        val failedDelivery = repo.get(failed)!!.deliveries.single().id
        repo.startDelivery(failedDelivery, DeliveryPlan(emptyList()))
        repo.deliveryFailed(failedDelivery, "422", retryAt = null)
        repo.saveDraft(DraftInput(text = "draft"))
        val sent = queued("sent", listOf(carol))
        deliverAll(sent)
        repo.markSent(sent, emptyList())

        val queue = repo.observeQueue().first()

        assertThat(queue.map { it.id }).containsExactly(failed, held, firstNow, secondNow, later).inOrder()
        assertThat(queue.map { it.state })
            .containsExactly(PostState.FAILED, PostState.HELD, PostState.QUEUED, PostState.QUEUED, PostState.QUEUED).inOrder()
        // Q8: a new post goes last among posts due at the same time.
        assertThat(queue.first { it.id == secondNow }.queueOrder).isGreaterThan(queue.first { it.id == firstNow }.queueOrder)
        assertThat(queue.first { it.id == held }.deliveries.single().state).isEqualTo(DeliveryState.WAITING)
    }

    @Test
    fun queueingAgainBeforeTheFirstDeliveryStartsFollowsTheNewTimeAndTargets() = runBlocking<Unit> {
        val id = queued("hello", listOf(alice, bob))
        val order = repo.get(id)!!.queueOrder
        queued("another", listOf(alice))

        repo.queue(id, Schedule.Explicit(Instant.parse("2026-11-18T12:00:00Z")), listOf(bob, carol))

        val post = repo.get(id)!!
        assertThat(post.deliveries.map { it.accountId }).containsExactly(bob, carol).inOrder()
        assertThat(post.queueOrder).isEqualTo(order)
        assertThat(post.schedule).isEqualTo(Schedule.Explicit(Instant.parse("2026-11-18T12:00:00Z")))
    }

    @Test
    fun aQueuedPostCanBeEditedUntilItsFirstDeliveryStarts() = runBlocking<Unit> {
        val id = queued("hello", listOf(alice, bob))

        // Q2: editing a queued post updates it and its deliveries follow the targets.
        repo.saveDraft(DraftInput(id = id, text = "hello again", targets = listOf(bob, carol)))
        val edited = repo.get(id)!!
        assertThat(edited.state).isEqualTo(PostState.QUEUED)
        assertThat(edited.text).isEqualTo("hello again")
        assertThat(edited.deliveries.map { it.accountId }).containsExactly(bob, carol).inOrder()

        repo.startDelivery(edited.deliveries.first().id, DeliveryPlan(listOf(PlannedPart("hello again"))))

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.saveDraft(DraftInput(id = id, text = "too late", targets = listOf(bob))) }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repo.queue(id, Schedule.Now(clock.now), listOf(bob)) }
        }
        assertThat(repo.get(id)!!.text).isEqualTo("hello again")
    }

    // Schedule changes (S2, S6, S8, Q3, Q5, Q6)

    @Test
    fun aDragOfSlotPostsTakesTheirSlotTimesInTheNewOrderAndExplicitTimesStay() = runBlocking<Unit> {
        // scheduling.md: A 09:00, B 13:00 (slots), X 11:00 (explicit). Drag B above A.
        val a = queued("A", listOf(alice), Schedule.Slot(LocalDateTime.parse("2026-11-16T09:00")))
        val b = queued("B", listOf(alice), Schedule.Slot(LocalDateTime.parse("2026-11-16T13:00")))
        val x = queued("X", listOf(alice), Schedule.Explicit(Instant.parse("2026-11-16T10:00:00Z")))
        val slotPosts = repo.observeQueue().first().mapNotNull { it.toQueuedRef() }.filter { it.time is SendTime.Slot }

        val moved = reorder(slotPosts, fromIndex = 1, toIndex = 0)
        repo.updateSchedules(moved.mapValues { Schedule.Slot(it.value) })

        val queue = repo.observeQueue().first()
        assertThat(queue.map { it.id }).containsExactly(b, x, a).inOrder()
        assertThat(repo.get(b)!!.schedule).isEqualTo(Schedule.Slot(LocalDateTime.parse("2026-11-16T09:00")))
        assertThat(repo.get(a)!!.schedule).isEqualTo(Schedule.Slot(LocalDateTime.parse("2026-11-16T13:00")))
        assertThat(repo.get(x)!!.schedule).isEqualTo(Schedule.Explicit(Instant.parse("2026-11-16T10:00:00Z")))
    }

    @Test
    fun aScheduleChangeThatTouchesAStartedPostChangesNothing() = runBlocking<Unit> {
        val waiting = queued("waiting", listOf(alice), Schedule.Slot(LocalDateTime.parse("2026-11-16T09:00")))
        val started = queued("started", listOf(alice))
        repo.startDelivery(repo.get(started)!!.deliveries.single().id, DeliveryPlan(emptyList()))

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                repo.updateSchedules(
                    linkedMapOf(
                        waiting to Schedule.Slot(LocalDateTime.parse("2026-11-16T18:00")),
                        started to Schedule.Slot(LocalDateTime.parse("2026-11-16T09:00")),
                    ),
                )
            }
        }

        assertThat(repo.get(waiting)!!.schedule).isEqualTo(Schedule.Slot(LocalDateTime.parse("2026-11-16T09:00")))
        assertThat(repo.get(started)!!.schedule).isEqualTo(Schedule.Now(Instant.parse("2026-11-16T07:00:00Z")))
    }

    @Test
    fun onlyQueuedPostsThatHaveNotStartedGoOnHoldAndAHeldPostReturnsWithANewTime() = runBlocking<Unit> {
        val late = queued("late", listOf(alice), Schedule.Explicit(Instant.parse("2026-11-16T05:00:00Z")))
        val alsoLate = queued("also late", listOf(alice), Schedule.Slot(LocalDateTime.parse("2026-11-16T06:00")))
        val started = queued("started", listOf(alice))
        repo.startDelivery(repo.get(started)!!.deliveries.single().id, DeliveryPlan(emptyList()))
        val draft = repo.saveDraft(DraftInput(text = "draft"))

        val heldIds = repo.hold(listOf(late, alsoLate, started, draft, 999))

        assertThat(heldIds).containsExactly(late, alsoLate)
        assertThat(repo.get(late)!!.state).isEqualTo(PostState.HELD)
        assertThat(repo.get(started)!!.state).isEqualTo(PostState.SENDING)
        assertThat(repo.get(draft)!!.state).isEqualTo(PostState.DRAFT)

        // Q6 Send now: held posts are queued again, due now.
        clock.advance(Duration.ofHours(2))
        repo.release(listOf(late), clock.now)
        assertThat(repo.get(late)!!.state).isEqualTo(PostState.QUEUED)
        assertThat(repo.get(late)!!.schedule).isEqualTo(Schedule.Now(clock.now))

        // Q6 Reschedule: a held post given a slot is queued again.
        repo.updateSchedules(mapOf(alsoLate to Schedule.Slot(LocalDateTime.parse("2026-11-17T09:00"))))
        assertThat(repo.get(alsoLate)!!.state).isEqualTo(PostState.QUEUED)
        assertThat(repo.get(alsoLate)!!.sendAt).isEqualTo(Instant.parse("2026-11-17T08:00:00Z"))
    }

    @Test
    fun slotsFollowTheDeviceToANewZoneAndExplicitTimesDoNot() = runBlocking<Unit> {
        val slot = queued("slot", listOf(alice), Schedule.Slot(LocalDateTime.parse("2026-11-16T09:00")))
        val explicit = queued("explicit", listOf(alice), Schedule.Explicit(Instant.parse("2026-11-16T09:00:00Z")))

        repo.refreshSlotTimes(ZoneId.of("Asia/Tokyo"))

        assertThat(repo.get(slot)!!.sendAt).isEqualTo(Instant.parse("2026-11-16T00:00:00Z"))
        assertThat(repo.get(slot)!!.schedule).isEqualTo(Schedule.Slot(LocalDateTime.parse("2026-11-16T09:00")))
        assertThat(repo.get(explicit)!!.sendAt).isEqualTo(Instant.parse("2026-11-16T09:00:00Z"))
    }

    @Test
    fun aSlotInADaylightSavingGapIsDueAtTheEndOfTheGap() = runBlocking<Unit> {
        // S2: 02:30 does not exist in Berlin on 29 March 2026; the slot is due at 03:00 CEST.
        val id = queued("gap", listOf(alice), Schedule.Slot(LocalDateTime.parse("2026-03-29T02:30")))

        assertThat(repo.get(id)!!.sendAt).isEqualTo(Instant.parse("2026-03-29T01:00:00Z"))
    }

    // Deliveries (R28–R30)

    @Test
    fun aDeliveryFreezesItsPlanAtTheFirstAttemptAndRetriesKeepIt() = runBlocking<Unit> {
        val id = queued("hello", listOf(alice, bob))
        val (toAlice, toBob) = repo.get(id)!!.deliveries.map { it.id }
        val plan = DeliveryPlan(listOf(PlannedPart("part 1", listOf(1L)), PlannedPart("part 2")))

        val started = repo.startDelivery(toAlice, plan)!!

        assertThat(started.state).isEqualTo(DeliveryState.SENDING)
        assertThat(started.attempts).isEqualTo(1)
        assertThat(started.plan).isEqualTo(plan)
        assertThat(started.lastAttemptAt).isEqualTo(clock.now)
        assertThat(repo.get(id)!!.state).isEqualTo(PostState.SENDING)
        // A delivery that is already sending cannot be claimed twice.
        assertThat(repo.startDelivery(toAlice, plan)).isNull()

        val progress = DeliveryProgress(
            listOf(
                PartProgress(0, key = "idem-0", uploaded = mapOf(1L to "media-9"), published = PublishedPart(id = "111", url = "https://m/111")),
                PartProgress(1, key = "idem-1"),
            ),
        )
        repo.saveProgress(toAlice, progress)
        val retryAt = clock.now.plusSeconds(30)
        assertThat(repo.deliveryFailed(toAlice, "timeout", retryAt)).isTrue()

        val waiting = repo.get(id)!!.deliveries.first { it.id == toAlice }
        assertThat(waiting.state).isEqualTo(DeliveryState.WAITING)
        assertThat(waiting.error).isEqualTo("timeout")
        assertThat(waiting.nextAttemptAt).isEqualTo(retryAt)
        assertThat(waiting.progress).isEqualTo(progress)
        assertThat(waiting.progress.firstUnpublished(partCount = 2)).isEqualTo(1)
        assertThat(repo.get(id)!!.state).isEqualTo(PostState.SENDING)

        // R30: the retry keeps the frozen plan and the progress.
        clock.advance(Duration.ofSeconds(30))
        val retried = repo.startDelivery(toAlice, DeliveryPlan(listOf(PlannedPart("text changed meanwhile"))))!!
        assertThat(retried.plan).isEqualTo(plan)
        assertThat(retried.attempts).isEqualTo(2)
        assertThat(retried.nextAttemptAt).isNull()
        assertThat(retried.progress).isEqualTo(progress)

        val done = progress.copy(parts = progress.parts.map { it.copy(published = it.published ?: PublishedPart(id = "112")) })
        assertThat(repo.deliverySucceeded(toAlice, done)).isTrue()
        assertThat(repo.get(id)!!.deliveries.first { it.id == toAlice }.state).isEqualTo(DeliveryState.SENT)
        assertThat(done.firstUnpublished(partCount = 2)).isNull()

        // R28: the other account fails on its own; the post is failed once nothing is pending.
        repo.startDelivery(toBob, plan)
        assertThat(repo.deliveryFailed(toBob, "422 Validation failed: Text too long", retryAt = null)).isTrue()
        val failed = repo.get(id)!!
        assertThat(failed.deliveries.first { it.id == toBob }.state).isEqualTo(DeliveryState.FAILED)
        assertThat(failed.deliveries.first { it.id == toBob }.error).isEqualTo("422 Validation failed: Text too long")
        assertThat(failed.deliveries.first { it.id == toAlice }.state).isEqualTo(DeliveryState.SENT)
        assertThat(failed.state).isEqualTo(PostState.FAILED)

        // Retry from Post details.
        assertThat(repo.retryFailed(id)).isEqualTo(1)
        val again = repo.get(id)!!
        assertThat(again.deliveries.first { it.id == toBob }.state).isEqualTo(DeliveryState.WAITING)
        assertThat(again.state).isEqualTo(PostState.SENDING)
    }

    @Test
    fun retryLeavesTheDeliveryOfARemovedAccountFailed() = runBlocking<Unit> {
        val id = queued("hello", listOf(alice, bob))
        val toAlice = repo.get(id)!!.deliveries.first { it.accountId == alice }.id
        repo.startDelivery(toAlice, DeliveryPlan(emptyList()))
        repo.deliveryFailed(toAlice, "422", retryAt = null)
        accounts.remove(bob)
        val onlyBob = queued("only bob", listOf(carol))
        accounts.remove(carol)

        // Alice's delivery waits again; Bob's has no account to send with and keeps its reason (R1).
        assertThat(repo.retryFailed(id)).isEqualTo(1)
        val post = repo.get(id)!!
        assertThat(post.deliveries.first { it.accountId == alice }.state).isEqualTo(DeliveryState.WAITING)
        assertThat(post.deliveries.first { it.accountId == bob }.state).isEqualTo(DeliveryState.FAILED)
        assertThat(post.deliveries.first { it.accountId == bob }.error).isEqualTo("account removed")

        assertThat(repo.retryFailed(onlyBob)).isEqualTo(0)
        assertThat(repo.get(onlyBob)!!.state).isEqualTo(PostState.FAILED)
        assertThat(repo.get(onlyBob)!!.deliveries.single().state).isEqualTo(DeliveryState.FAILED)
    }

    @Test
    fun deliveryChangesNeedASendingDelivery() = runBlocking<Unit> {
        val id = queued("hello", listOf(alice))
        val delivery = repo.get(id)!!.deliveries.single().id

        assertThat(repo.deliveryFailed(delivery, "x", null)).isFalse()
        assertThat(repo.deliverySucceeded(delivery, DeliveryProgress())).isFalse()
        assertThat(repo.get(id)!!.deliveries.single().state).isEqualTo(DeliveryState.WAITING)
        assertThrows(DeliveryNotFound::class.java) { runBlocking { repo.deliveryFailed(999, "x", null) } }
        assertThrows(DeliveryNotFound::class.java) { runBlocking { repo.saveProgress(999, DeliveryProgress()) } }
    }

    @Test
    fun deliveriesInterruptedByProcessDeathWaitAgain() = runBlocking<Unit> {
        val id = queued("hello", listOf(alice, bob))
        val toAlice = repo.get(id)!!.deliveries.first().id
        repo.startDelivery(toAlice, DeliveryPlan(emptyList()))

        assertThat(repo.recoverInterrupted()).isEqualTo(1)

        val post = repo.get(id)!!
        assertThat(post.deliveries.map { it.state }).containsExactly(DeliveryState.WAITING, DeliveryState.WAITING)
        assertThat(post.deliveries.first().attempts).isEqualTo(1)
        // It has started: it cannot be dragged or held any more (S9, Q4).
        assertThat(post.state).isEqualTo(PostState.SENDING)
        assertThat(repo.hold(listOf(id))).isEmpty()
    }

    // Sent (R28, R31, R39)

    @Test
    fun aSentPostGoesToHistoryAndItsAttachmentFilesAreDeleted() = runBlocking<Unit> {
        retention = HistoryRetention.DAYS_7
        val id = repo.saveDraft(
            DraftInput(text = "**hello**", targets = listOf(alice, bob), attachments = listOf(image("attachments/1.jpg"), image("attachments/2.jpg"))),
        )
        val first = repo.get(id)!!.attachments.first()
        repo.putVariant(Variant(attachmentId = first.id, profileKey = "mastodon", path = "attachments/1-m.jpg", mime = "image/jpeg", sizeBytes = 1))
        repo.queue(id, Schedule.Now(clock.now), listOf(alice, bob))
        val items = listOf(
            HistoryItem(alice, Network.MASTODON, "https://mastodon.social", "alice", listOf(PublishedPart(id = "1", url = "https://mastodon.social/@alice/1", media = listOf(PublishedMedia("m1", "alt"))))),
            HistoryItem(bob, Network.MASTODON, "https://fosstodon.org", "bob", listOf(PublishedPart(id = "2"))),
        )

        // R28: not sent while a delivery has not succeeded.
        assertThrows(IllegalStateException::class.java) { runBlocking { repo.markSent(id, items) } }
        assertThat(files.deleted).isEmpty()

        deliverAll(id)
        clock.advance(Duration.ofMinutes(1))
        val historyId = repo.markSent(id, items)!!

        val post = repo.get(id)!!
        assertThat(post.state).isEqualTo(PostState.SENT)
        assertThat(post.attachments).isEmpty()
        assertThat(files.deleted).containsExactly("attachments/1.jpg", "attachments/2.jpg", "attachments/1-m.jpg")
        val record = history.get(historyId)!!
        assertThat(record.postId).isEqualTo(id)
        assertThat(record.text).isEqualTo("**hello**")
        assertThat(record.items).isEqualTo(items)
        assertThat(record.sentAt).isEqualTo(clock.now)
        assertThat(record.expiresAt).isEqualTo(clock.now.plus(Duration.ofDays(7)))
    }

    @Test
    fun withHistoryOffASentPostLeavesNoRecordAndNoIds() = runBlocking<Unit> {
        retention = HistoryRetention.OFF
        val id = queued("hello", listOf(alice))
        for (delivery in repo.get(id)!!.deliveries) {
            repo.startDelivery(delivery.id, DeliveryPlan(listOf(PlannedPart("hello"))))
            repo.deliverySucceeded(delivery.id, DeliveryProgress(listOf(PartProgress(0, published = PublishedPart(id = "status-110")))))
        }

        assertThat(repo.markSent(id, listOf(HistoryItem(alice, Network.MASTODON, "https://mastodon.social", "alice", emptyList())))).isNull()

        // R37: with history off the app keeps no ids, so the post and its deliveries go as well.
        assertThat(history.observe().first()).isEmpty()
        assertThat(repo.get(id)).isNull()
        assertThat(db.rows("SELECT COUNT(*) FROM posts").single().single()).isEqualTo(0L)
        assertThat(db.rows("SELECT COUNT(*) FROM deliveries").single().single()).isEqualTo(0L)
    }

    @Test
    fun aSentPostStaysWhileItsRecordDoesAndLeavesWithIt() = runBlocking<Unit> {
        retention = HistoryRetention.DAYS_7
        val purged = queued("purged", listOf(alice))
        deliverAll(purged)
        val purgedRecord = repo.markSent(purged, emptyList())!!
        val deleted = queued("deleted", listOf(bob))
        deliverAll(deleted)
        val deletedRecord = repo.markSent(deleted, emptyList())!!

        assertThat(repo.get(purged)!!.state).isEqualTo(PostState.SENT)
        assertThat(repo.get(deleted)!!.state).isEqualTo(PostState.SENT)

        // The user deletes one record from the Sent tab; the other expires.
        assertThat(history.delete(deletedRecord)).isTrue()
        assertThat(repo.get(deleted)).isNull()
        assertThat(repo.get(purged)).isNotNull()

        assertThat(history.purgeExpired(clock.now.plus(Duration.ofDays(7)))).isEqualTo(1)
        assertThat(history.get(purgedRecord)).isNull()
        assertThat(repo.get(purged)).isNull()
        assertThat(db.rows("SELECT COUNT(*) FROM deliveries").single().single()).isEqualTo(0L)
    }

    @Test
    fun anEditReplacesItsRecordAndADeletionRemovesItOrKeepsWhatIsLeft() = runBlocking<Unit> {
        val options = PostOptions(common = AccountOptions(languages = listOf("en")))
        val aliceCopy = HistoryItem(alice, Network.MASTODON, "https://mastodon.social", "alice", listOf(PublishedPart(id = "1")))
        val bobCopy = HistoryItem(bob, Network.MASTODON, "https://fosstodon.org", "bob", listOf(PublishedPart(id = "2")))
        val record = history.insert(null, clock.now, "original", options, listOf(aliceCopy, bobCopy))!!

        // R40: an edit replaces text, options and the published copies.
        val edit = repo.saveDraft(DraftInput(text = "edited", kind = PostKind.EDIT, historyId = record, targets = listOf(alice)))
        repo.queue(edit, Schedule.Now(clock.now), listOf(alice))
        deliverAll(edit)
        val edited = listOf(aliceCopy.copy(parts = listOf(PublishedPart(id = "1", url = "u"))), bobCopy)
        assertThat(repo.markSent(edit, edited)).isEqualTo(record)
        assertThat(history.get(record)!!.text).isEqualTo("edited")
        assertThat(history.get(record)!!.items).isEqualTo(edited)

        // R39: deleting from one account keeps the record with what is still published.
        val partial = repo.saveDraft(DraftInput(text = "", kind = PostKind.DELETE, historyId = record, targets = listOf(alice)))
        repo.queue(partial, Schedule.Now(clock.now), listOf(alice))
        deliverAll(partial)
        assertThat(repo.markSent(partial, listOf(bobCopy))).isEqualTo(record)
        assertThat(history.get(record)!!.items).containsExactly(bobCopy)
        assertThat(history.get(record)!!.text).isEqualTo("edited")

        // R39: the record leaves history when every copy is deleted.
        val rest = repo.saveDraft(DraftInput(text = "", kind = PostKind.DELETE, historyId = record, targets = listOf(bob)))
        repo.queue(rest, Schedule.Now(clock.now), listOf(bob))
        deliverAll(rest)
        assertThat(repo.markSent(rest, emptyList())).isNull()
        assertThat(history.get(record)).isNull()

        // No record points to an edit or a deletion once it is sent: they leave with their deliveries.
        assertThat(listOf(edit, partial, rest).map { repo.get(it) }).containsExactly(null, null, null)
        assertThat(db.rows("SELECT COUNT(*) FROM deliveries").single().single()).isEqualTo(0L)
    }

    @Test
    fun deletingAPostRemovesItsRowsAndFilesWhileItsHistoryRecordStays() = runBlocking<Unit> {
        val id = repo.saveDraft(DraftInput(text = "hello", targets = listOf(alice), attachments = listOf(image("attachments/x.jpg"))))
        repo.queue(id, Schedule.Now(clock.now), listOf(alice))
        val record = history.insert(id, clock.now, "hello", PostOptions(), emptyList())!!

        assertThat(repo.delete(id)).isTrue()

        assertThat(repo.get(id)).isNull()
        assertThat(files.deleted).containsExactly("attachments/x.jpg")
        assertThat(db.rows("SELECT COUNT(*) FROM deliveries").single().single()).isEqualTo(0L)
        assertThat(db.rows("SELECT COUNT(*) FROM attachments").single().single()).isEqualTo(0L)
        assertThat(history.get(record)!!.postId).isNull()
        assertThat(repo.delete(id)).isFalse()
    }

    @Test
    fun aNewVariantOfTheSameProfileReplacesTheOldOneAndItsFile() = runBlocking<Unit> {
        val id = repo.saveDraft(DraftInput(text = "v", attachments = listOf(image("attachments/o.jpg"))))
        val attachment = repo.get(id)!!.attachments.single()

        repo.putVariant(Variant(attachmentId = attachment.id, profileKey = "bluesky", path = "attachments/o-1.jpg", mime = "image/jpeg", sizeBytes = 5, width = 4, height = 3))
        repo.putVariant(Variant(attachmentId = attachment.id, profileKey = "mastodon", path = "attachments/o-m.jpg", mime = "image/png", sizeBytes = 6))
        repo.putVariant(Variant(attachmentId = attachment.id, profileKey = "bluesky", path = "attachments/o-2.jpg", mime = "image/jpeg", sizeBytes = 7))

        val variants = repo.get(id)!!.attachments.single().variants
        assertThat(variants.map { it.profileKey to it.path })
            .containsExactly("mastodon" to "attachments/o-m.jpg", "bluesky" to "attachments/o-2.jpg")
        assertThat(files.deleted).containsExactly("attachments/o-1.jpg")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.putVariant(Variant(attachmentId = 999, profileKey = "p", path = "p", mime = "m", sizeBytes = 1)) }
        }
    }

    private suspend fun queued(text: String, targets: List<Long>, schedule: Schedule = Schedule.Now(clock.now)): Long {
        val id = repo.saveDraft(DraftInput(text = text, targets = targets))
        repo.queue(id, schedule, targets)
        return id
    }

    private suspend fun deliverAll(postId: Long) {
        for (delivery in repo.get(postId)!!.deliveries) {
            repo.startDelivery(delivery.id, DeliveryPlan(emptyList()))
            repo.deliverySucceeded(delivery.id, DeliveryProgress())
        }
    }

    private fun image(path: String, alt: String = "") =
        Attachment(kind = MediaKind.IMAGE, path = path, mime = "image/jpeg", sizeBytes = 1000, width = 800, height = 600, alt = alt)

    private companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
    }
}
