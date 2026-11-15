package org.evsyukov.post

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Objects shared by the whole app, built by hand in [PostApplication.onCreate]. Activities, workers and
 * receivers take it from the application; screens get what they need from it as parameters.
 */
class AppContainer(val context: Context) {
    /** Work that outlives a screen, such as saving a post the user has left. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One client for every server, so connections and threads are shared. */
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
}
