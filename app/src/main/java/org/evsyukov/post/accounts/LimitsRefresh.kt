package org.evsyukov.post.accounts

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import org.evsyukov.post.core.mastodon.MastodonClient
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.accounts.AccountLimits
import org.evsyukov.post.data.accounts.AccountsRepository
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Reads the limits and formats of Mastodon accounts again (networks.md N1, formatting.md F1). Bluesky
 * limits are fixed and are not read. A server that fails keeps the values stored before.
 */
class LimitsRefresher(
    private val accounts: AccountsRepository,
    private val http: OkHttpClient,
    private val clock: Clock = Clock.systemUTC(),
) {
    /**
     * Refreshes every Mastodon account whose limits are older than [maxAge] (all of them with
     * [Duration.ZERO]). Returns how many accounts got new limits.
     */
    suspend fun refresh(maxAge: Duration = Duration.ZERO): Int {
        val now = clock.instant()
        val due = accounts.observe().first().filter { it.network == Network.MASTODON && isStale(it, now, maxAge) }
        var updated = 0
        for (account in due) {
            try {
                val capabilities = readCapabilities(MastodonClient(rootOf(account.server), http))
                accounts.updateLimits(account.id, AccountLimits.Mastodon(capabilities), clock.instant())
                updated++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // N1: the old values stay until the next refresh.
            }
        }
        return updated
    }

    private fun isStale(account: Account, now: java.time.Instant, maxAge: Duration): Boolean {
        val fetched = account.limitsFetchedAt ?: return true
        return maxAge.isZero || Duration.between(fetched, now) >= maxAge
    }

    companion object {
        val MAX_AGE: Duration = Duration.ofHours(24)
    }
}

/** Gives the worker its [LimitsRefresher]; the application implements it. */
interface LimitsRefresherHost {
    val limitsRefresher: LimitsRefresher
}

/** Runs [LimitsRefresher.refresh]: all accounts for the daily job, stale ones for the start-up job. */
class LimitsRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? LimitsRefresherHost ?: return Result.failure()
        val onlyStale = inputData.getBoolean(KEY_ONLY_STALE, false)
        host.limitsRefresher.refresh(if (onlyStale) LimitsRefresher.MAX_AGE else Duration.ZERO)
        return Result.success()
    }

    companion object {
        const val DAILY = "limits-refresh-daily"
        const val ON_START = "limits-refresh-start"
        private const val KEY_ONLY_STALE = "only-stale"

        private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /**
         * The daily refresh (N1), a unique periodic job with network, and a one-time job that refreshes the
         * limits older than 24 hours when the app starts. Calling it again keeps the jobs already there.
         */
        fun schedule(workManager: WorkManager) {
            workManager.enqueueUniquePeriodicWork(
                DAILY,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<LimitsRefreshWorker>(1, TimeUnit.DAYS).setConstraints(connected).build(),
            )
            workManager.enqueueUniqueWork(
                ON_START,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<LimitsRefreshWorker>()
                    .setConstraints(connected)
                    .setInputData(workDataOf(KEY_ONLY_STALE to true))
                    .build(),
            )
        }
    }
}
