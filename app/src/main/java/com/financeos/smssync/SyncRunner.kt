package com.financeos.smssync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock

const val DISCONNECTED_NOTICE = "This phone was disconnected"

object SyncRunner {
    const val ONE_OFF = "sms-sync-once"
    const val PERIODIC = "sms-sync-periodic"

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun enqueueNow(context: Context, delaySeconds: Long = 0) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_OFF, ExistingWorkPolicy.REPLACE, req)
    }

    fun schedulePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(ONE_OFF)
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
    }

    /** One full sync with the stored session. Throws IOException when a retry makes sense. */
    fun syncOnce(context: Context, stopped: () -> Boolean = { false }): SyncOutcome? =
        // credentials are read under the lock so a disconnect in between is seen
        SyncEngine.LOCK.withLock {
            val store = LocalStore(context)
            val site = store.site
            val token = store.token
            if (site == null || token == null) return@withLock null
            val outcome = SyncEngine(
                HttpApi(site, token), SmsReader(context), store,
                cancelled = { stopped() || LocalStore.disconnecting || !store.isConnected },
            ).run()
            if (outcome == SyncOutcome.UNAUTHORIZED) {
                cancelAll(context)
                store.clearAll(DISCONNECTED_NOTICE)
            }
            outcome
        }
}

class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        SyncRunner.syncOnce(applicationContext) { isStopped }
        Result.success()
    } catch (e: SecurityException) {
        Result.success()
    } catch (e: HttpFailure) {
        if (e.retryable) Result.retry() else Result.failure()
    } catch (e: IOException) {
        Result.retry()
    }
}
