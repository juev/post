package org.evsyukov.post.send

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.Executors

/** The application of these tests hands the workers a publisher, as PostApplication does. */
class PublisherTestApp : Application(), PublisherHost {
    override lateinit var publisher: Publisher
    override lateinit var publishScheduler: PublishScheduler
}

/** The publish trigger on WorkManager (scheduling.md M2): unique, expedited, connected, one successor. */
@RunWith(AndroidJUnit4::class)
@Config(application = PublisherTestApp::class)
class PublishSchedulerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val app = context as PublisherTestApp
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: PublishScheduler
    private val fixture = SendFixture()
    private val executor = Executors.newSingleThreadExecutor()

    @Before
    fun setUp() {
        val config = Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(executor).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        workManager = WorkManager.getInstance(context)
        scheduler = PublishScheduler(workManager, CoroutineScope(SupervisorJob() + Dispatchers.IO), fixture.clock)
        app.publishScheduler = scheduler
        app.publisher = fixture.publisher
    }

    @After
    fun tearDown() {
        fixture.close()
        executor.shutdownNow()
    }

    private fun infos(name: String = PublishScheduler.PUBLISH): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(name).get()

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(20)
        }
    }

    @Test
    fun theRequestIsExpeditedAndNeedsANetwork() {
        val spec = PublishScheduler.publishRequest().workSpec
        assertThat(spec.expedited).isTrue()
        assertThat(spec.outOfQuotaPolicy).isEqualTo(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        assertThat(spec.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }

    @Test
    fun publishNowEnqueuesTheUniqueWorkOnce() = runBlocking<Unit> {
        scheduler.enqueue()
        scheduler.enqueue()

        val work = infos()
        assertThat(work).hasSize(1)
        assertThat(work.single().state).isEqualTo(WorkInfo.State.ENQUEUED)
        assertThat(work.single().constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)
    }

    @Test
    fun aRunInProgressGetsExactlyOneSuccessor() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture.beforeSend = {
            entered.complete(Unit)
            release.await()
        }
        scheduler.enqueue()
        val first = infos().single().id
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(first)
        entered.await()
        waitFor { infos().single().state == WorkInfo.State.RUNNING }

        // A post saved now may not be seen by the run in progress: one successor waits behind it.
        scheduler.enqueue()
        scheduler.enqueue()
        val chain = infos()
        assertThat(chain).hasSize(2)
        val successor = chain.single { it.id != first }
        assertThat(successor.state).isAnyOf(WorkInfo.State.BLOCKED, WorkInfo.State.ENQUEUED)

        fixture.beforeSend = {}
        release.complete(Unit)
        waitFor { infos().first { it.id == first }.state == WorkInfo.State.SUCCEEDED }
    }

    @Test
    fun afterARunEndedANewRunIsEnqueued() = runBlocking<Unit> {
        scheduler.enqueue()
        val first = infos().single().id
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(first)
        waitFor { infos().single().state == WorkInfo.State.SUCCEEDED }

        scheduler.enqueue()

        assertThat(infos().count { it.state == WorkInfo.State.ENQUEUED }).isEqualTo(1)
    }

    @Test
    fun aRetryWakesTheSenderAtTheEarliestTime() = runBlocking<Unit> {
        scheduler.scheduleRetry(fixture.clock.now.plusSeconds(30))
        scheduler.scheduleRetry(fixture.clock.now.plusSeconds(90))

        val retry = infos(PublishScheduler.RETRY).single { it.state == WorkInfo.State.ENQUEUED }
        assertThat(retry.initialDelayMillis).isEqualTo(Duration.ofSeconds(90).toMillis())
        assertThat(retry.constraints.requiredNetworkType).isEqualTo(NetworkType.CONNECTED)

        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setInitialDelayMet(retry.id)
        driver.setAllConstraintsMet(retry.id)
        waitFor { infos(PublishScheduler.RETRY).first { it.id == retry.id }.state == WorkInfo.State.SUCCEEDED }

        // The retry only starts a run of the sender.
        waitFor { infos().isNotEmpty() }
        assertThat(infos().single().state).isEqualTo(WorkInfo.State.ENQUEUED)
    }

    @Test
    fun theWorkerSendsTheDuePosts() = runBlocking<Unit> {
        val server = FakeMastodon(fixture.servers) { fixture.clock.now }
        val alice = fixture.mastodon()
        val id = fixture.queue("From the worker", listOf(alice))

        scheduler.enqueue()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(infos().single().id)
        waitFor { infos().single().state == WorkInfo.State.SUCCEEDED }

        assertThat(server.statuses.single().text).isEqualTo("From the worker")
        assertThat(fixture.post(id)!!.state).isEqualTo(org.evsyukov.post.data.db.PostState.SENT)
    }
}
