package org.evsyukov.post

import android.app.Application
import androidx.work.WorkManager
import org.evsyukov.post.accounts.LimitsRefreshWorker
import org.evsyukov.post.accounts.LimitsRefresher
import org.evsyukov.post.accounts.LimitsRefresherHost

class PostApplication : Application(), LimitsRefresherHost {
    lateinit var container: AppContainer
        private set

    override val limitsRefresher: LimitsRefresher get() = container.limitsRefresher

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // N1: account limits are read once a day, and on start when they are older than a day.
        LimitsRefreshWorker.schedule(WorkManager.getInstance(this))
    }
}
