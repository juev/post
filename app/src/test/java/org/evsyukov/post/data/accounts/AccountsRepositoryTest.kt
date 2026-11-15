package org.evsyukov.post.data.accounts

import android.database.sqlite.SQLiteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.core.bluesky.ContentLabel
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.mastodon.Visibility
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.FakeCipher
import org.evsyukov.post.data.MutableClock
import org.evsyukov.post.data.RecordingFiles
import org.evsyukov.post.data.ThreadRecordingCipher
import org.evsyukov.post.data.containsBytes
import org.evsyukov.post.data.db.AccountState
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.db.PostDatabase
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.inMemoryDatabase
import org.evsyukov.post.data.posts.DeliveryPlan
import org.evsyukov.post.data.posts.DeliveryProgress
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.RoomPostsRepository
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.data.rows
import org.evsyukov.post.data.secrets.BlueskySecret
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.data.secrets.SecretStore
import org.evsyukov.post.data.secrets.SecretUnreadable
import org.evsyukov.post.data.settings.HistoryRetention
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Accounts in Room: order, signing in again, JSON columns, sealed tokens (R4) and removal (R1). */
@RunWith(AndroidJUnit4::class)
class AccountsRepositoryTest {
    private val clock = MutableClock(Instant.parse("2026-11-16T08:00:00Z"))
    private val db: PostDatabase = inMemoryDatabase()
    private val repo = RoomAccountsRepository(db, SecretStore(FakeCipher()), clock)
    private val posts = RoomPostsRepository(db, RecordingFiles(), { HistoryRetention.DAYS_30 }, clock)

    @After
    fun tearDown() = db.close()

    @Test
    fun accountsAreGroupedByNetworkAndKeepTheOrderTheyWereAddedIn() = runBlocking<Unit> {
        val me = repo.add(mastodon("me", "1"), MastodonSecret("t1"))
        val sky = repo.add(bluesky("me.bsky.social", "did:plc:me"), BlueskySecret("a", "r"))
        val work = repo.add(mastodon("work", "2", server = "https://fosstodon.org"), MastodonSecret("t2"))

        val accounts = repo.observe().first()

        assertThat(accounts.map { it.id }).containsExactly(sky, me, work).inOrder()
        assertThat(accounts.map { it.position }).containsExactly(0, 0, 1).inOrder()
        assertThat(accounts.all { it.state == AccountState.OK }).isTrue()
        assertThat(accounts.first { it.id == me }.createdAt).isEqualTo(clock.now)
    }

    @Test
    fun reorderPutsTheAccountsOfANetworkInTheGivenOrder() = runBlocking<Unit> {
        val a = repo.add(mastodon("a", "1"), MastodonSecret("t"))
        val b = repo.add(mastodon("b", "2"), MastodonSecret("t"))
        val c = repo.add(mastodon("c", "3"), MastodonSecret("t"))

        repo.reorder(listOf(c, a, b))

        assertThat(repo.observe().first().map { it.id }).containsExactly(c, a, b).inOrder()
        assertThrows(AccountNotFound::class.java) { runBlocking { repo.reorder(listOf(a, 999)) } }
        // The failed reorder changed nothing.
        assertThat(repo.observe().first().map { it.id }).containsExactly(c, a, b).inOrder()
    }

    @Test
    fun aPartialReorderPutsTheOtherAccountsOfTheNetworkAfterItAndNoTwoShareAPlace() = runBlocking<Unit> {
        val a = repo.add(mastodon("a", "1"), MastodonSecret("t"))
        val b = repo.add(mastodon("b", "2"), MastodonSecret("t"))
        val c = repo.add(mastodon("c", "3"), MastodonSecret("t"))
        val sky = repo.add(bluesky("me.bsky.social", "did:plc:me"), BlueskySecret("a", "r"))

        repo.reorder(listOf(c, b))

        val accounts = repo.observe().first()
        assertThat(accounts.map { it.id }).containsExactly(sky, c, b, a).inOrder()
        assertThat(accounts.map { it.position }).containsExactly(0, 0, 1, 2).inOrder()
    }

    @Test
    fun signingInAgainKeepsTheAccountItsLabelPlaceAndDefaults() = runBlocking<Unit> {
        val first = repo.add(mastodon("me", "1"), MastodonSecret("old-token"))
        repo.add(mastodon("other", "2"), MastodonSecret("t"))
        repo.setLabel(first, "Personal")
        repo.updateDefaults(first, AccountOptions(visibility = Visibility.UNLISTED))
        repo.setState(first, AccountState.NEEDS_SIGN_IN)

        // R5: signing in again resumes the account.
        val again = repo.add(mastodon("me-renamed", "1", displayName = "New name"), MastodonSecret("new-token"))

        assertThat(again).isEqualTo(first)
        val account = repo.get(first)!!
        assertThat(account.state).isEqualTo(AccountState.OK)
        assertThat(account.handle).isEqualTo("me-renamed")
        assertThat(account.displayName).isEqualTo("New name")
        assertThat(account.label).isEqualTo("Personal")
        assertThat(account.position).isEqualTo(0)
        assertThat(account.defaults.visibility).isEqualTo(Visibility.UNLISTED)
        assertThat(repo.readSecret(first)).isEqualTo(MastodonSecret("new-token"))
        assertThat(repo.observe().first()).hasSize(2)
    }

    @Test
    fun theSameHandleOnAnotherServerIsAnotherAccount() = runBlocking<Unit> {
        val one = repo.add(mastodon("me", "1", server = "https://one.example"), MastodonSecret("t"))
        val two = repo.add(mastodon("me", "1", server = "https://two.example"), MastodonSecret("t"))

        assertThat(two).isNotEqualTo(one)
    }

    @Test
    fun profileLimitsAndDefaultsSurviveTheirJsonColumns() = runBlocking<Unit> {
        val id = repo.add(mastodon("me", "1"), MastodonSecret("t"))
        val capabilities = MastodonCapabilities(
            maxCharacters = 5000,
            supportedMediaMimeTypes = setOf("image/png", "video/mp4"),
            postFormats = setOf("text/plain", "text/markdown"),
            postFormatsDeclared = true,
            mastodonApiVersion = 7,
            supportsQuotePolicy = true,
            softwareName = "glitch-soc",
        )
        val defaults = AccountOptions(
            visibility = Visibility.PRIVATE,
            languages = listOf("ru", "en"),
            contentWarning = "spoilers",
            sensitive = true,
            contentLabels = setOf(ContentLabel.GRAPHIC_MEDIA),
            replyRules = setOf(ReplyRule.FOLLOWERS, ReplyRule.MENTIONED),
            quotePolicy = QuotePolicy.NOBODY,
            allowQuotes = false,
        )
        val fetchedAt = Instant.parse("2026-11-16T07:00:00Z")

        repo.updateProfile(id, "me2", "Me Two", "https://example.com/a.png")
        repo.updateLimits(id, AccountLimits.Mastodon(capabilities), fetchedAt)
        repo.updateDefaults(id, defaults)

        val account = repo.get(id)!!
        assertThat(account.handle).isEqualTo("me2")
        assertThat(account.displayName).isEqualTo("Me Two")
        assertThat(account.avatarUrl).isEqualTo("https://example.com/a.png")
        assertThat(account.limits).isEqualTo(AccountLimits.Mastodon(capabilities))
        assertThat(account.limitsFetchedAt).isEqualTo(fetchedAt)
        assertThat(account.defaults).isEqualTo(defaults)
    }

    @Test
    fun aNewAccountHasNoLimitsYetAndTheDefaultOptions() = runBlocking<Unit> {
        val id = repo.add(bluesky("me.bsky.social", "did:plc:me"), BlueskySecret("a", "r"))

        val account = repo.get(id)!!
        assertThat(account.limits).isNull()
        assertThat(account.limitsFetchedAt).isNull()
        assertThat(account.defaults).isEqualTo(AccountOptions())
        // Anyone may reply and quote unless the user says otherwise.
        assertThat(account.defaults.replyRules).isNull()
        assertThat(account.defaults.allowQuotes).isTrue()
    }

    @Test
    fun tokensAreSealedAtRestAndOpenAgain() = runBlocking<Unit> {
        val mastodon = repo.add(mastodon("me", "1"), MastodonSecret("mastodon-access-token-123"))
        val bluesky = repo.add(bluesky("me.bsky.social", "did:plc:me"), BlueskySecret("access-jwt-456", "refresh-jwt-789"))
        repo.updateSecret(bluesky, BlueskySecret("access-jwt-new", "refresh-jwt-new"))

        assertThat(repo.readSecret(mastodon)).isEqualTo(MastodonSecret("mastodon-access-token-123"))
        assertThat(repo.readSecret(bluesky)).isEqualTo(BlueskySecret("access-jwt-new", "refresh-jwt-new"))

        // R4: the BLOB column holds no token in the clear, in UTF-8 or UTF-16.
        val blobs = db.rows("SELECT secret FROM accounts").map { it.single() as ByteArray }
        assertThat(blobs).hasSize(2)
        for (token in listOf("mastodon-access-token-123", "access-jwt-new", "refresh-jwt-new", "access-jwt-456")) {
            for (blob in blobs) {
                assertThat(blob.containsBytes(token.toByteArray(Charsets.UTF_8))).isFalse()
                assertThat(blob.containsBytes(token.toByteArray(Charsets.UTF_16LE))).isFalse()
            }
        }
        // And the account seen by the rest of the app carries no token at all.
        assertThat(repo.observe().first().toString()).doesNotContain("token")
    }

    @Test
    fun anUnreadableSecretIsReportedAsSecretUnreadable() = runBlocking<Unit> {
        val id = repo.add(mastodon("me", "1"), MastodonSecret("t"))
        // The key changed or the bytes were damaged.
        db.openHelper.writableDatabase.execSQL("UPDATE accounts SET secret = X'00112233445566778899' WHERE id = $id")

        assertThrows(SecretUnreadable::class.java) { runBlocking { repo.readSecret(id) } }
        // The rest of the account still reads, so the caller can mark it as needing sign-in (R5).
        repo.setState(id, AccountState.NEEDS_SIGN_IN)
        assertThat(repo.get(id)!!.state).isEqualTo(AccountState.NEEDS_SIGN_IN)
    }

    @Test
    fun aSecretOfAnotherNetworkIsRejected() = runBlocking<Unit> {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.add(mastodon("me", "1"), BlueskySecret("a", "r")) }
        }
        val id = repo.add(mastodon("me", "1"), MastodonSecret("t"))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repo.updateSecret(id, BlueskySecret("a", "r")) }
        }
        assertThat(repo.readSecret(id)).isEqualTo(MastodonSecret("t"))
    }

    @Test
    fun removingAnAccountFailsItsPendingDeliveriesAndErasesItsSecret() = runBlocking<Unit> {
        val removed = repo.add(mastodon("me", "1"), MastodonSecret("secret-of-the-removed-account"))
        val kept = repo.add(bluesky("me.bsky.social", "did:plc:me"), BlueskySecret("a", "r"))

        // One post sending to both, one already sent to the removed account, one only for it.
        val sending = queued(listOf(removed, kept))
        val sendingDelivery = deliveryOf(sending, removed)
        posts.startDelivery(sendingDelivery, DeliveryPlan(emptyList()))
        val partlySent = queued(listOf(removed, kept))
        val sentDelivery = deliveryOf(partlySent, removed)
        posts.startDelivery(sentDelivery, DeliveryPlan(emptyList()))
        posts.deliverySucceeded(sentDelivery, DeliveryProgress())
        val onlyRemoved = queued(listOf(removed))

        assertThat(repo.remove(removed)).isTrue()

        // R1: pending deliveries of the removed account failed with the reason, in the same transaction.
        val first = posts.get(sending)!!
        assertThat(first.deliveries.first { it.accountId == removed }.state).isEqualTo(DeliveryState.FAILED)
        assertThat(first.deliveries.first { it.accountId == removed }.error).isEqualTo("account removed")
        assertThat(first.deliveries.first { it.accountId == kept }.state).isEqualTo(DeliveryState.WAITING)
        assertThat(first.state).isEqualTo(PostState.SENDING)

        val second = posts.get(partlySent)!!
        assertThat(second.deliveries.first { it.accountId == removed }.state).isEqualTo(DeliveryState.SENT)
        assertThat(second.deliveries.first { it.accountId == removed }.error).isNull()

        val third = posts.get(onlyRemoved)!!
        assertThat(third.deliveries.single().state).isEqualTo(DeliveryState.FAILED)
        assertThat(third.deliveries.single().error).isEqualTo("account removed")
        assertThat(third.state).isEqualTo(PostState.FAILED)

        // The account and its sealed tokens are gone; the other account is untouched.
        assertThat(repo.get(removed)).isNull()
        assertThat(db.rows("SELECT COUNT(*) FROM accounts WHERE id = $removed").single().single()).isEqualTo(0L)
        assertThrows(AccountNotFound::class.java) { runBlocking { repo.readSecret(removed) } }
        assertThat(repo.readSecret(kept)).isEqualTo(BlueskySecret("a", "r"))
        assertThat(repo.observe().first().map { it.id }).containsExactly(kept)
        assertThat(repo.remove(removed)).isFalse()
    }

    @Test
    fun aRemovalThatFailsHalfwayChangesNothing() = runBlocking<Unit> {
        val removed = repo.add(mastodon("me", "1"), MastodonSecret("kept-token"))
        val kept = repo.add(bluesky("me.bsky.social", "did:plc:me"), BlueskySecret("a", "r"))
        val post = queued(listOf(removed, kept))
        // The account row refuses to go, after the deliveries have already been failed.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TEMP TRIGGER refuse_removal BEFORE DELETE ON accounts BEGIN SELECT RAISE(ABORT, 'refused'); END",
        )

        assertThrows(SQLiteException::class.java) { runBlocking { repo.remove(removed) } }

        // R1 runs in one transaction: the deliveries and the post are as they were, the account stays.
        val after = posts.get(post)!!
        assertThat(after.deliveries.map { it.state }).containsExactly(DeliveryState.WAITING, DeliveryState.WAITING)
        assertThat(after.deliveries.map { it.error }).containsExactly(null, null)
        assertThat(after.state).isEqualTo(PostState.QUEUED)
        assertThat(repo.readSecret(removed)).isEqualTo(MastodonSecret("kept-token"))
    }

    @Test
    fun theCipherNeverRunsOnTheCallersThread() = runBlocking<Unit> {
        val cipher = ThreadRecordingCipher()
        val accounts = RoomAccountsRepository(db, SecretStore(cipher), clock)
        val apps = RoomMastodonAppsRepository(db, SecretStore(cipher))
        val caller = Thread.currentThread()

        val id = accounts.add(mastodon("me", "1"), MastodonSecret("t"))
        accounts.updateSecret(id, MastodonSecret("t2"))
        accounts.readSecret(id)
        apps.put(MastodonApp("https://mastodon.social", "client", "secret", "org.evsyukov.post://oauth/mastodon", "profile", clock.now))
        apps.get("https://mastodon.social")

        // The Keystore goes over binder: a call from the main thread must not block it.
        assertThat(cipher.threads).hasSize(5)
        assertThat(cipher.threads).doesNotContain(caller)
    }

    private suspend fun queued(targets: List<Long>): Long {
        val id = posts.saveDraft(DraftInput(text = "hello", targets = targets))
        posts.queue(id, Schedule.Now(clock.now), targets)
        return id
    }

    private suspend fun deliveryOf(postId: Long, accountId: Long): Long =
        posts.get(postId)!!.deliveries.first { it.accountId == accountId }.id

    private fun mastodon(
        handle: String,
        remoteId: String,
        server: String = "https://mastodon.social",
        displayName: String = handle,
    ) = NewAccount(Network.MASTODON, server, handle, remoteId, displayName, avatarUrl = null)

    private fun bluesky(handle: String, did: String) =
        NewAccount(Network.BLUESKY, "https://pds.example", handle, did, handle, avatarUrl = null)
}
