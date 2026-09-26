package com.stocktracker.app.notify

import android.content.Context
import com.stocktracker.app.data.remote.ReportSummary
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.Routes
import com.stocktracker.app.ui.report.ReportPortfolioStore
import com.stocktracker.app.ui.report.ReportRead
import kotlinx.coroutines.flow.first

/**
 * RPT-1 — announces each new weekly and monthly report, run from the 15-minute
 * [com.stocktracker.app.widget.WidgetRefreshWorker]. Tapping the alert opens that report.
 *
 * The backend builds a report once, after the period's last close (15:25 CT); this only notices it.
 * Before posting it prices the user's portfolio for the report's period, so the alert can lead with
 * their own number and the report opens with that section already filled in.
 *
 * A report is marked announced only once the post was actually delivered, so a blocked notification
 * retries on the next tick. A report built more than [FRESH_SECONDS] ago, or whose period ended more
 * than a few days ago (a catch-up rebuild of last month, a fresh install), is marked seen without an
 * alert: it is history, not news.
 */
object ReportNotifier {

    private const val FRESH_SECONDS = 36 * 3600
    /** The list call is small, but there is no reason to make it every 15 minutes all week. */
    private const val POLL_EVERY_MS = 30 * 60 * 1000L
    private const val KEEP_IDS = 200
    private val api = SignalsApiService()
    @Volatile private var lastPollMs = 0L

    suspend fun check(context: Context) {
        val settings = ServiceLocator.settingsStore
        val url = settings.signalsApiUrl.first()
        if (url.isBlank()) return
        val weekly = settings.reportWeeklyNotifyEnabled.first()
        val monthly = settings.reportMonthlyNotifyEnabled.first()
        if (!weekly && !monthly) return
        val now = System.currentTimeMillis()
        if (now - lastPollMs < POLL_EVERY_MS) return
        val rows = runCatching { api.reports(url, limit = 6) }.getOrNull()?.reports ?: return
        lastPollMs = now

        val seen = settings.reportNotifiedIds.first()
        val out = seen.toMutableSet()
        for (r in rows.sortedBy { it.madeAt ?: 0.0 }) {
            val id = r.id ?: continue
            if (id in seen) continue
            val enabled = if (r.kind == "month") monthly else weekly
            val ageS = now / 1000.0 - (r.madeAt ?: 0.0)
            if (!enabled || ageS > FRESH_SECONDS || !ReportRead.isNews(r.end, java.time.LocalDate.now(), r.kind)) {
                out += id
                continue
            }
            if (announce(context, url, r)) out += id
        }
        if (out != seen) settings.setReportNotifiedIds(prune(out))
    }

    private suspend fun announce(context: Context, url: String, r: ReportSummary): Boolean {
        val id = r.id ?: return false
        val full = runCatching { api.report(url, id) }.getOrNull()?.takeIf { it.available }
        val you = full?.let { runCatching { ReportPortfolioStore.getOrCompute(it) }.getOrNull() }
            ?.takeIf { it.priced }?.changePct
        return AlertNotifier.notifyReport(
            context,
            "report_$id".hashCode(),
            ReportRead.notificationTitle(r.kind, r.label, you, r.sp500Pct),
            ReportRead.notificationBody(r),
            Routes.report(id),
        )
    }

    /** Newest ids per kind, so a year of weekly reports cannot push every monthly id out. */
    internal fun prune(ids: Set<String>): Set<String> =
        ids.groupBy { it.substringBefore('-') }
            .flatMap { (_, v) -> v.sortedBy { it.substringAfter('-') }.takeLast(KEEP_IDS / 2) }
            .toSet()
}
