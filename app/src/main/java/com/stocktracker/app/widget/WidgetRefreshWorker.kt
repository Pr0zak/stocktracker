package com.stocktracker.app.widget

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.stocktracker.app.di.ServiceLocator
import java.util.concurrent.TimeUnit

private const val TAG = "StockTrackerWork"

class WidgetRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    /**
     * Refresh the widgets, then run every notifier.
     *
     * Each step is ISOLATED. This used to be one `try` block ending in `catch { Result.retry() }`,
     * which chained all nine steps to the first failure: if a widget refresh threw — say no widget
     * was configured, or Glance hit a transient error — then AlertChecker and every notifier after it
     * were skipped, silently, on every single run. Nothing was logged, so a permanently broken step
     * looked exactly like a quiet market with no alerts to send. A price-alert pipeline that fails
     * closed and says nothing is worse than one that does not exist, because you believe it is armed.
     */
    override suspend fun doWork(): Result {
        val failures = mutableListOf<String>()
        var ran = 0

        suspend fun step(name: String, block: suspend () -> Unit) {
            ran++
            try {
                block()
            } catch (e: Exception) {  // noqa — one step must never take the others down
                failures += name
                Log.w(TAG, "step '$name' failed", e)
            }
        }

        step("widgets:tickers") { WidgetRefresh.refreshAllTickers(applicationContext) }
        step("widgets:watchlist") { WidgetRefresh.refreshWatchlist(applicationContext) }
        step("widgets:portfolio") { WidgetRefresh.refreshPortfolio(applicationContext) }
        step("alerts") { com.stocktracker.app.notify.AlertChecker.check(applicationContext) }
        step("signalScan") { com.stocktracker.app.notify.SignalScanNotifier.check(applicationContext) }
        step("callExit") { com.stocktracker.app.notify.CallExitNotifier.check(applicationContext) }
        step("marketSummary") { com.stocktracker.app.notify.MarketSummaryNotifier.check(applicationContext) }
        step("dailyBrief") { com.stocktracker.app.notify.AiDailyBriefNotifier.check(applicationContext) }
        step("sandboxTrades") { com.stocktracker.app.notify.SandboxTradeNotifier.check(applicationContext) }

        // Recorded so "are my alerts even running?" is answerable from inside the app instead of
        // requiring a USB cable and logcat.
        runCatching {
            ServiceLocator.settingsStore.recordBackgroundRun(
                System.currentTimeMillis(), failures.joinToString(","),
            )
        }

        if (failures.isNotEmpty()) Log.w(TAG, "background run: ${failures.size}/$ran step(s) failed: $failures")

        // Retry only when EVERYTHING failed — that pattern means something shared (network, the
        // process) is broken and is worth backing off for. A single bad step is not a reason to
        // re-run the eight that already succeeded; the 15-minute period covers it.
        return if (failures.size == ran) Result.retry() else Result.success()
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
