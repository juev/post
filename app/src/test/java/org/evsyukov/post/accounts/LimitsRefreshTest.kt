package org.evsyukov.post.accounts

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.media.NetworkMediaRules
import org.evsyukov.post.data.FakeCipher
import org.evsyukov.post.data.MutableClock
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.accounts.AccountLimits
import org.evsyukov.post.data.accounts.AccountOptions
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.accounts.RoomAccountsRepository
import org.evsyukov.post.data.db.AccountState
import org.evsyukov.post.data.inMemoryDatabase
import org.evsyukov.post.data.secrets.BlueskySecret
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.data.secrets.SecretStore
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/** The application of these tests hands the worker a refresher, as PostApplication does. */
class RefresherHostApp : Application(), LimitsRefresherHost {
    override lateinit var limitsRefresher: LimitsRefresher
}

/** networks.md N1: limits read on sign-in and once a day; a failure keeps the old values. */
@RunWith(AndroidJUnit4::class)
@Config(application = RefresherHostApp::class)
class LimitsRefreshTest {
    private val clock = MutableClock(Instant.parse("2026-11-16T08:00:00Z"))
    private val db = inMemoryDatabase()
    private val accounts = RoomAccountsRepository(db, SecretStore(FakeCipher()), clock)
    private val servers = FakeServers()
    private val refresher = LimitsRefresher(accounts, servers.http, clock)

    @After
    fun tearDown() {
        servers.close()
        db.close()
    }

    private suspend fun mastodon(host: String, limits: MastodonCapabilities?, fetchedAt: Instant?): Long {
        val id = accounts.add(NewAccount(Network.MASTODON, "https://$host", "me@$host", "1", "Me", null), MastodonSecret("t"))
        if (limits != null) accounts.updateLimits(id, AccountLimits.Mastodon(limits), fetchedAt!!)
        return id
    }

    private fun instance(host: String, maxCharacters: Int) =
        servers.json("GET", "$host/api/v2/instance", """{"domain":"$host","configuration":{"statuses":{"max_characters":$maxCharacters}}}""")

    @Test
    fun aRefreshStoresNewLimitsAndKeepsTheOldOnesOfAServerThatFails() = runBlocking<Unit> {
        val fine = mastodon("fine.example", MastodonCapabilities(maxCharacters = 500), clock.now.minus(Duration.ofDays(2)))
        val down = mastodon("down.example", MastodonCapabilities(maxCharacters = 700), clock.now.minus(Duration.ofDays(2)))
        val sky = accounts.add(NewAccount(Network.BLUESKY, "https://pds.example", "a.example.com", "did:plc:a", "A", null), BlueskySecret("a", "r"))
        instance("fine.example", 5000)
        servers.json("GET", "down.example/api/v2/instance", "{}", code = 503)
        servers.json("GET", "down.example/api/v1/instance", "{}", code = 503)
        clock.advance(Duration.ofMinutes(5))

        assertThat(refresher.refresh()).isEqualTo(1)

        assertThat((accounts.get(fine)!!.limits as AccountLimits.Mastodon).capabilities.maxCharacters).isEqualTo(5000)
        assertThat(accounts.get(fine)!!.limitsFetchedAt).isEqualTo(clock.now)
        assertThat((accounts.get(down)!!.limits as AccountLimits.Mastodon).capabilities.maxCharacters).isEqualTo(700)
        assertThat(accounts.get(down)!!.limitsFetchedAt).isEqualTo(clock.now.minus(Duration.ofDays(2)).minus(Duration.ofMinutes(5)))
        // Bluesky limits are fixed: nothing is asked of its servers.
        assertThat(accounts.get(sky)!!.limits).isNull()
        assertThat(servers.seen.map { it.host }.toSet()).containsExactly("fine.example", "down.example")
    }

    @Test
    fun theStartUpRefreshReadsOnlyLimitsOlderThanADay() = runBlocking<Unit> {
        mastodon("fresh.example", MastodonCapabilities(), clock.now.minus(Duration.ofHours(3)))
        val stale = mastodon("stale.example", MastodonCapabilities(), clock.now.minus(Duration.ofHours(25)))
        val never = mastodon("never.example", null, null)
        instance("fresh.example", 1)
        instance("stale.example", 2)
        instance("never.example", 3)

        assertThat(refresher.refresh(LimitsRefresher.MAX_AGE)).isEqualTo(2)

        assertThat(servers.seen.map { it.host }.toSet()).containsExactly("stale.example", "never.example")
        assertThat((accounts.get(stale)!!.limits as AccountLimits.Mastodon).capabilities.maxCharacters).isEqualTo(2)
        assertThat((accounts.get(never)!!.limits as AccountLimits.Mastodon).capabilities.maxCharacters).isEqualTo(3)
    }

    @Test
    fun theDailyJobIsUniquePeriodicAndWaitsForANetwork() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val workManager = WorkManager.getInstance(context)

        LimitsRefreshWorker.schedule(workManager)
        LimitsRefreshWorker.schedule(workManager)

        val daily = workManager.getWorkInfosForUniqueWork(LimitsRefreshWorker.DAILY).get().single()
        assertThat(daily.state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(daily.periodicityInfo!!.repeatIntervalMillis).isEqualTo(TimeUnit.DAYS.toMillis(1))
        assertThat(daily.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        val start = workManager.getWorkInfosForUniqueWork(LimitsRefreshWorker.ON_START).get().single()
        assertThat(start.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
        assertThat(start.periodicityInfo).isNull()
    }

    @Test
    fun theWorkerRefreshesAllForTheDailyRunAndStaleOnesOnStart() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<RefresherHostApp>()
        app.limitsRefresher = refresher
        val fresh = mastodon("fresh.example", MastodonCapabilities(maxCharacters = 10), clock.now.minus(Duration.ofHours(1)))
        instance("fresh.example", 20)

        val onStart = TestListenableWorkerBuilder<LimitsRefreshWorker>(app).setInputData(workDataOf("only-stale" to true)).build().doWork()
        assertThat(onStart).isEqualTo(ListenableWorker.Result.success())
        assertThat((accounts.get(fresh)!!.limits as AccountLimits.Mastodon).capabilities.maxCharacters).isEqualTo(10)

        val daily = TestListenableWorkerBuilder<LimitsRefreshWorker>(app).build().doWork()
        assertThat(daily).isEqualTo(ListenableWorker.Result.success())
        assertThat((accounts.get(fresh)!!.limits as AccountLimits.Mastodon).capabilities.maxCharacters).isEqualTo(20)
    }
}

/** The mapping helpers for later steps: stored limits → capabilities and media rules. */
class AccountLimitsMappingTest {
    private fun account(network: Network, limits: AccountLimits?) = Account(
        id = 1, network = network, server = "https://s.example", handle = "h", remoteId = "r", displayName = "", avatarUrl = null,
        label = "", position = 0, state = AccountState.OK, limits = limits, limitsFetchedAt = null, defaults = AccountOptions(),
        createdAt = Instant.EPOCH,
    )

    @Test
    fun serverLimitsReplaceTheVanillaOnesAndTheGifLimitStays() {
        val caps = MastodonCapabilities(
            maxMediaAttachments = 8,
            supportedMediaMimeTypes = setOf("image/jpeg", "image/avif", "video/mp4", "audio/mpeg"),
            imageSizeLimit = 20L * 1024 * 1024,
            imageMatrixLimit = 100L * 100,
            videoSizeLimit = 200L * 1024 * 1024,
            videoMatrixLimit = 1920L * 1080,
            videoFrameRateLimit = 60,
            descriptionLimit = 1500,
        )

        val rules = account(Network.MASTODON, AccountLimits.Mastodon(caps)).mediaRules()

        assertThat(rules.maxAttachments).isEqualTo(8)
        assertThat(rules.imageMimes).containsExactly("image/jpeg", "image/avif")
        assertThat(rules.videoMimes).containsExactly("video/mp4")
        assertThat(rules.audioMimes).containsExactly("audio/mpeg")
        assertThat(rules.maxImageBytes).isEqualTo(20L * 1024 * 1024)
        assertThat(rules.maxImagePixels).isEqualTo(100L * 100)
        assertThat(rules.maxVideoBytes).isEqualTo(200L * 1024 * 1024)
        assertThat(rules.maxVideoPixels).isEqualTo(1920L * 1080)
        assertThat(rules.maxVideoFrameRate).isEqualTo(60)
        assertThat(rules.maxAltLength).isEqualTo(1500)
        // Not reported by servers: the vanilla values stay.
        assertThat(rules.maxGifPixels).isEqualTo(1280L * 720)
        assertThat(rules.pollAllowedWithMedia).isFalse()
        assertThat(rules.videoMustBeAlone).isTrue()
    }

    @Test
    fun aTypeGroupTheServerDoesNotListKeepsTheVanillaTypes() {
        val rules = MastodonCapabilities(supportedMediaMimeTypes = setOf("image/png")).toMediaRules()

        assertThat(rules.imageMimes).containsExactly("image/png")
        assertThat(rules.videoMimes).isEqualTo(NetworkMediaRules.mastodonDefaults().videoMimes)
        assertThat(rules.audioMimes).isEqualTo(NetworkMediaRules.mastodonDefaults().audioMimes)
    }

    @Test
    fun anAccountWithoutLimitsUsesTheVanillaMastodonValues() {
        val account = account(Network.MASTODON, null)

        assertThat(account.limits.mastodonCapabilities()).isEqualTo(MastodonCapabilities())
        assertThat(account.mediaRules().maxAttachments).isEqualTo(4)
        assertThat(account.mediaRules().maxGifPixels).isEqualTo(1280L * 720)
    }

    @Test
    fun aBlueskyAccountHasTheFixedBlueskyRules() {
        assertThat(account(Network.BLUESKY, null).mediaRules()).isEqualTo(NetworkMediaRules.bluesky())
    }
}
