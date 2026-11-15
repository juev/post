package org.evsyukov.post

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
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
import org.evsyukov.post.data.secrets.SecretStore
import org.evsyukov.post.data.settings.AppSettings
import java.util.concurrent.TimeUnit

/**
 * Objects shared by the whole app, built by hand in [PostApplication.onCreate]. Activities, workers and
 * receivers take it from the application; screens get what they need from it as parameters. Everything
 * that touches the disk is built on first use, so starting the process stays cheap.
 */
class AppContainer(context: Context) {
    val context: Context = context.applicationContext

    /** Work that outlives a screen, such as saving a post the user has left. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One client for every server, so connections and threads are shared. */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val database: PostDatabase by lazy { PostDatabase.open(this.context) }

    val settings: AppSettings by lazy { AppSettings.create(this.context, CoroutineScope(SupervisorJob() + Dispatchers.IO)) }

    /** Tokens and client secrets are sealed with a key in the Android Keystore (R4). */
    val secrets: SecretStore by lazy { SecretStore(KeystoreTokenCipher()) }

    val attachmentFiles: AttachmentFiles by lazy { FilesDirAttachmentFiles(this.context.filesDir) }

    val accounts: AccountsRepository by lazy { RoomAccountsRepository(database, secrets) }

    val mastodonApps: MastodonAppsRepository by lazy { RoomMastodonAppsRepository(database, secrets) }

    val posts: PostsRepository by lazy {
        RoomPostsRepository(database, attachmentFiles, historyRetention = { settings.current().historyRetention })
    }

    val history: HistoryRepository by lazy { RoomHistoryRepository(database) { settings.current().historyRetention } }

    val schedule: ScheduleRepository by lazy { RoomScheduleRepository(database) }
}
