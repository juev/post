package org.evsyukov.post

import android.content.Context
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.evsyukov.post.accounts.AccountRemover
import org.evsyukov.post.accounts.AccountSessions
import org.evsyukov.post.accounts.BlueskySignIn
import org.evsyukov.post.accounts.LimitsRefresher
import org.evsyukov.post.accounts.MastodonSignIn
import org.evsyukov.post.accounts.ServerPolicy
import org.evsyukov.post.core.text.TextSegmenter
import org.evsyukov.post.data.accounts.AccountsRepository
import org.evsyukov.post.data.accounts.MastodonAppsRepository
import org.evsyukov.post.data.accounts.RoomAccountsRepository
import org.evsyukov.post.data.accounts.RoomMastodonAppsRepository
import org.evsyukov.post.data.db.PostDatabase
import org.evsyukov.post.data.history.HistoryRepository
import org.evsyukov.post.data.history.RoomHistoryRepository
import org.evsyukov.post.data.posts.AttachmentFiles
import org.evsyukov.post.data.posts.AttachmentSweeper
import org.evsyukov.post.data.posts.FilesDirAttachmentFiles
import org.evsyukov.post.data.posts.FilesInUse
import org.evsyukov.post.data.posts.PostsRepository
import org.evsyukov.post.data.posts.RoomPostsRepository
import org.evsyukov.post.data.schedule.RoomScheduleRepository
import org.evsyukov.post.data.schedule.ScheduleRepository
import org.evsyukov.post.data.secrets.KeystoreTokenCipher
import org.evsyukov.post.data.secrets.TokenCipher
import org.evsyukov.post.data.secrets.SecretStore
import org.evsyukov.post.data.settings.AppSettings
import org.evsyukov.post.media.importer.AttachmentImporter
import org.evsyukov.post.oauth.FilePendingSignInStore
import org.evsyukov.post.oauth.MastodonSignInFlow
import org.evsyukov.post.oauth.PendingSignInStore
import org.evsyukov.post.media.convert.DeviceMediaConverter
import org.evsyukov.post.media.prepare.MediaPreparer
import org.evsyukov.post.media.probe.DeviceMediaDecodeCheck
import org.evsyukov.post.media.probe.MediaDecodeCheck
import org.evsyukov.post.send.AndroidSendNotifier
import org.evsyukov.post.send.MastodonExecutor
import org.evsyukov.post.send.PlanFreezer
import org.evsyukov.post.send.PrepareScheduler
import org.evsyukov.post.send.PublishScheduler
import org.evsyukov.post.send.PublishedActions
import org.evsyukov.post.send.Publisher
import org.evsyukov.post.send.bluesky.BlueskyExecutor
import org.evsyukov.post.send.bluesky.LinkCardFetcher
import org.evsyukov.post.send.schedule.AndroidLateNotifier
import org.evsyukov.post.send.schedule.LateHolder
import org.evsyukov.post.send.schedule.PublishAlarms
import org.evsyukov.post.send.schedule.QueueActions
import org.evsyukov.post.send.schedule.ScheduleRestorer
import org.evsyukov.post.send.schedule.pendingWakeTimes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.evsyukov.post.data.db.PostState
import java.time.ZoneId
import org.evsyukov.post.text.AndroidTextSegmenter
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Objects shared by the whole app, built by hand in [PostApplication.onCreate]. Activities, workers and
 * receivers take it from the application; screens get what they need from it as parameters. Everything
 * that touches the disk is built on first use, so starting the process stays cheap.
 */
class AppContainer(
    context: Context,
    /** Starts publishing of due posts; null uses WorkManager ([PublishScheduler]), tests record the calls. */
    publishTrigger: PublishTrigger? = null,
    /** Starts preparing the media of queued posts; null uses WorkManager ([PrepareScheduler]). */
    prepareTrigger: PrepareTrigger? = null,
    /** Whether this device can decode a file the sender converts (media.md A4); tests answer for themselves. */
    val mediaDecodeCheck: MediaDecodeCheck = DeviceMediaDecodeCheck(),
    // Tests on the JVM pass a stand-in: Robolectric has no Android Keystore. It stays the last parameter,
    // so `AppContainer(context) { FakeCipher() }` keeps meaning the cipher.
    private val cipher: () -> TokenCipher = { KeystoreTokenCipher() },
) {
    val context: Context = context.applicationContext

    /** Work that outlives a screen, such as saving a post the user has left. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * One client for every server, so connections and threads are shared. The User-Agent names the app
     * and its version only; nothing personal goes into headers, and no request or token is logged.
     */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build()) }
        .build()

    /** HTTPS servers only; debug builds also reach local test servers over HTTP. */
    val serverPolicy = ServerPolicy(allowLocalHttp = BuildConfig.DEBUG)

    /** Grapheme, word and sentence boundaries by the platform ICU, for counters and thread splits. */
    val textSegmenter: TextSegmenter = AndroidTextSegmenter()

    val database: PostDatabase by lazy { PostDatabase.open(this.context) }

    val settings: AppSettings by lazy { AppSettings.create(this.context, CoroutineScope(SupervisorJob() + Dispatchers.IO)) }

    /** Tokens and client secrets are sealed with a key in the Android Keystore (R4). */
    val secrets: SecretStore by lazy { SecretStore(cipher()) }

    val attachmentFiles: AttachmentFiles by lazy { FilesDirAttachmentFiles(this.context.filesDir) }

    /** The copies that open composers and Share sheets hold before they are saved. */
    val filesInUse: FilesInUse = FilesInUse()

    /** Deletes copies and variants that nothing refers to (media.md A1). */
    val attachmentSweeper: AttachmentSweeper by lazy { AttachmentSweeper(database.attachmentDao(), this.context.filesDir, filesInUse) }

    /** Copies picked and shared files into app storage and reads their metadata (R9). */
    val attachmentImporter: AttachmentImporter by lazy { AttachmentImporter(this.context.contentResolver, this.context.filesDir, privateDir = this.context.dataDir) }

    val accounts: AccountsRepository by lazy { RoomAccountsRepository(database, secrets) }

    val mastodonApps: MastodonAppsRepository by lazy { RoomMastodonAppsRepository(database, secrets) }

    val posts: PostsRepository by lazy {
        RoomPostsRepository(database, attachmentFiles, historyRetention = { settings.current().historyRetention })
    }

    val history: HistoryRepository by lazy { RoomHistoryRepository(database) { settings.current().historyRetention } }

    val schedule: ScheduleRepository by lazy { RoomScheduleRepository(database) }

    /** Credentials of signed-in accounts for publishing (see [AccountSessions]). */
    val accountSessions: AccountSessions by lazy { AccountSessions(accounts, http) }

    val accountRemover: AccountRemover by lazy { AccountRemover(accounts, mastodonApps, http) }

    val limitsRefresher: LimitsRefresher by lazy { LimitsRefresher(accounts, http) }

    val blueskySignIn: BlueskySignIn by lazy { BlueskySignIn(accounts, http, serverPolicy, onSignedIn = { publisher.onSignedIn(it) }) }

    /** The Mastodon sign-in waiting for its redirect; on disk, so it survives the process (N4). */
    val pendingSignIns: PendingSignInStore by lazy {
        FilePendingSignInStore(File(this.context.noBackupFilesDir, "mastodon-sign-in.json"))
    }

    val mastodonSignIn: MastodonSignIn by lazy {
        MastodonSignIn(accounts, mastodonApps, pendingSignIns, http, serverPolicy, onSignedIn = { publisher.onSignedIn(it) })
    }

    /** The Mastodon sign-in in progress; it outlives screens and activities. */
    val mastodonSignInFlow: MastodonSignInFlow by lazy { MastodonSignInFlow(mastodonSignIn, scope) }

    /** Runs the sender through WorkManager: unique work, expedited, with one successor (scheduling.md M2). */
    val publishScheduler: PublishScheduler by lazy { PublishScheduler(WorkManager.getInstance(this.context), scope) }

    /** Starts publishing of due posts (R21); the composer calls it after saving the post (R27). */
    val publishTrigger: PublishTrigger = publishTrigger ?: PublishTrigger { publishScheduler.publishNow() }

    /** Prepares the media of queued posts through WorkManager: no network needed, expedited (media.md A4). */
    val prepareScheduler: PrepareScheduler by lazy { PrepareScheduler(WorkManager.getInstance(this.context), scope) }

    /** Starts preparing the media of a post just queued (A4); the composer and the Share sheet call it. */
    val prepareTrigger: PrepareTrigger = prepareTrigger ?: PrepareTrigger {
        try {
            prepareScheduler.prepareNow()
        } catch (e: Exception) {
            // No WorkManager, as in a test process: nothing to start, and the sender prepares at send time.
            android.util.Log.w("PostSender", "cannot start preparing media", e)
        }
    }

    /** Variants of attachments for upload (media.md A2, A4). */
    val mediaPreparer: MediaPreparer by lazy { MediaPreparer(database, this.context.filesDir, DeviceMediaConverter(this.context)) }

    val sendNotifier: AndroidSendNotifier by lazy { AndroidSendNotifier(this.context) }

    /** The sender of due posts (R27–R32); each network registers its executor here. */
    val publisher: Publisher by lazy {
        Publisher(
            posts = posts,
            accounts = accounts,
            settings = { settings.current() },
            freezer = PlanFreezer(textSegmenter),
            executors = listOf(
                MastodonExecutor(http, accountSessions, accounts),
                BlueskyExecutor(
                    http = http,
                    sessions = accountSessions,
                    accounts = accounts,
                    segmenter = textSegmenter,
                    linkCards = LinkCardFetcher(http),
                    tempDir = File(this.context.cacheDir, "link-cards"),
                ),
            ),
            preparer = mediaPreparer,
            filesDir = this.context.filesDir,
            notifier = sendNotifier,
            scheduleRetry = { publishScheduler.scheduleRetry(it) },
            trigger = this.publishTrigger,
            beforeSend = { lateHolder.holdLate(it) },
            afterRun = { refreshAlarm() },
            history = { history.get(it) },
        )
    }

    /** Delete published posts through the queue (R39, R41). */
    val publishedActions: PublishedActions by lazy { PublishedActions(posts, history, this.publishTrigger) }

    /** The one exact alarm for the earliest pending time (scheduling.md M1). */
    val publishAlarms: PublishAlarms by lazy { PublishAlarms(this.context, posts, WorkManager.getInstance(this.context)) }

    /** The notification about late posts (Q6). */
    val lateNotifier: AndroidLateNotifier by lazy { AndroidLateNotifier(this.context) }

    /** Puts late posts on hold before the job sends (Q4, Q5). */
    val lateHolder: LateHolder by lazy {
        LateHolder(posts, settings = { settings.current() }, schedule = { schedule.get() }, notifier = lateNotifier)
    }

    /** Queue, Schedule and notification actions on queued posts (S6–S10, Q3, Q6, Q7). */
    val queueActions: QueueActions by lazy { QueueActions(posts, schedule, this.publishTrigger, lateNotifier) }

    /** Boot, time and zone changes, app updates and the periodic check (M3, M4). */
    val scheduleRestorer: ScheduleRestorer by lazy {
        ScheduleRestorer(
            posts,
            refreshAlarm = { refreshAlarm() },
            runJob = { publishScheduler.enqueue() },
            // R31: expired history goes on the periodic check too, not only at start.
            housekeeping = { history.purgeExpired(java.time.Instant.now()) },
        )
    }

    /** Sets the alarm again; a failure is logged and the next change or check tries again. */
    suspend fun refreshAlarm() {
        try {
            publishAlarms.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("PostAlarm", "cannot set the alarm", e)
        }
    }

    /**
     * Follows the queue for as long as the process lives (M1, Q6): the alarm is set again whenever a pending time
     * changes, and the late notification goes once no post is held any more.
     */
    fun startQueueHooks() {
        scope.launch {
            posts.observeQueue().map { pendingWakeTimes(it, ZoneId.systemDefault()) }.distinctUntilChanged().collect { refreshAlarm() }
        }
        scope.launch {
            posts.observeQueue().map { list -> list.count { it.state == PostState.HELD } }.distinctUntilChanged().collect { held ->
                if (held == 0) lateNotifier.cancelLate()
            }
        }
    }

    companion object {
        val USER_AGENT = "Post/${BuildConfig.VERSION_NAME} (+https://github.com/juev/post)"
    }
}

/**
 * Starts publishing the posts that are due, such as one just queued by Send now (R21). Saving the post
 * comes first (R27); the trigger only wakes the sender, so calling it twice is harmless.
 */
fun interface PublishTrigger {
    fun publishNow()
}

/**
 * Starts preparing the media of queued posts (media.md A4): freezes their plans and converts the files
 * right after the post is queued, so a failed conversion shows up then, not at send time.
 */
fun interface PrepareTrigger {
    fun prepareNow()
}
