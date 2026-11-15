package org.evsyukov.post

import android.content.Context
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
import org.evsyukov.post.data.accounts.AccountsRepository
import org.evsyukov.post.data.accounts.MastodonAppsRepository
import org.evsyukov.post.data.accounts.RoomAccountsRepository
import org.evsyukov.post.data.accounts.RoomMastodonAppsRepository
import org.evsyukov.post.data.db.PostDatabase
import org.evsyukov.post.data.history.HistoryRepository
import org.evsyukov.post.data.history.RoomHistoryRepository
import org.evsyukov.post.data.posts.AttachmentFiles
import org.evsyukov.post.data.posts.FilesDirAttachmentFiles
import org.evsyukov.post.data.posts.PostsRepository
import org.evsyukov.post.data.posts.RoomPostsRepository
import org.evsyukov.post.data.schedule.RoomScheduleRepository
import org.evsyukov.post.data.schedule.ScheduleRepository
import org.evsyukov.post.data.secrets.KeystoreTokenCipher
import org.evsyukov.post.data.secrets.TokenCipher
import org.evsyukov.post.data.secrets.SecretStore
import org.evsyukov.post.data.settings.AppSettings
import org.evsyukov.post.oauth.FilePendingSignInStore
import org.evsyukov.post.oauth.MastodonSignInFlow
import org.evsyukov.post.oauth.PendingSignInStore
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Objects shared by the whole app, built by hand in [PostApplication.onCreate]. Activities, workers and
 * receivers take it from the application; screens get what they need from it as parameters. Everything
 * that touches the disk is built on first use, so starting the process stays cheap.
 */
class AppContainer(
    context: Context,
    // Tests on the JVM pass a stand-in: Robolectric has no Android Keystore.
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

    val database: PostDatabase by lazy { PostDatabase.open(this.context) }

    val settings: AppSettings by lazy { AppSettings.create(this.context, CoroutineScope(SupervisorJob() + Dispatchers.IO)) }

    /** Tokens and client secrets are sealed with a key in the Android Keystore (R4). */
    val secrets: SecretStore by lazy { SecretStore(cipher()) }

    val attachmentFiles: AttachmentFiles by lazy { FilesDirAttachmentFiles(this.context.filesDir) }

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

    val blueskySignIn: BlueskySignIn by lazy { BlueskySignIn(accounts, http, serverPolicy) }

    /** The Mastodon sign-in waiting for its redirect; on disk, so it survives the process (N4). */
    val pendingSignIns: PendingSignInStore by lazy {
        FilePendingSignInStore(File(this.context.noBackupFilesDir, "mastodon-sign-in.json"))
    }

    val mastodonSignIn: MastodonSignIn by lazy { MastodonSignIn(accounts, mastodonApps, pendingSignIns, http, serverPolicy) }

    /** The Mastodon sign-in in progress; it outlives screens and activities. */
    val mastodonSignInFlow: MastodonSignInFlow by lazy { MastodonSignInFlow(mastodonSignIn, scope) }

    companion object {
        val USER_AGENT = "Post/${BuildConfig.VERSION_NAME} (+https://github.com/juev/post)"
    }
}
