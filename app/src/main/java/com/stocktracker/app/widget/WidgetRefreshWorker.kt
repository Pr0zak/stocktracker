package com.stocktracker.app.widget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        WidgetRefresh.refreshAllTickers(applicationContext)
        WidgetRefresh.refreshWatchlist(applicationContext)
        WidgetRefresh.refreshPortfolio(applicationContext)
        com.stocktracker.app.notify.AlertChecker.check(applicationContext)
        com.stocktracker.app.notify.SignalScanNotifier.check(applicationContext)
        com.stocktracker.app.notify.CallExitNotifier.check(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}

object WidgetRefreshScheduler {

    private const val PERIODIC_WORK = "stocktracker_widget_refresh"
    private const val ONE_SHOT_WORK = "stocktracker_widget_refresh_now"

    /** WorkManager's minimum periodic interval is 15 minutes; per-widget intervals gate above that. */
    fun ensureScheduled(context: Context) {
        val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Coalesce redundant "refresh now" requests into a single in-flight job. */
    fun refreshNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_SHOT_WORK, ExistingWorkPolicy.KEEP, request)
    }
}
