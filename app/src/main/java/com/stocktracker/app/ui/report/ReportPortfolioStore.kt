package com.stocktracker.app.ui.report

import com.stocktracker.app.data.model.ChartRange
import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.data.remote.Report
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.time.LocalDate

/**
 * Where each report's portfolio section is computed and kept.
 *
 * Computed once — by the notifier when the report is announced, or the first time it is opened — and
 * then read back, so an old report goes on describing that week's portfolio after the holdings change.
 * "Recalculate" is the only thing that overwrites one.
 */
object ReportPortfolioStore {

    private const val KEEP = 60
    private val mutex = Mutex()
    private val mapSer = MapSerializer(String.serializer(), ReportPortfolio.serializer())

    private suspend fun readAll(): Map<String, ReportPortfolio> {
        val raw = ServiceLocator.settingsStore.reportPortfolioSnapshots.first()
        if (raw.isBlank()) return emptyMap()
        return runCatching { Http.json.decodeFromString(mapSer, raw) }.getOrDefault(emptyMap())
    }

    suspend fun get(id: String): ReportPortfolio? = readAll()[id]

    suspend fun all(): Map<String, ReportPortfolio> = readAll()

    private suspend fun put(p: ReportPortfolio) = mutex.withLock {
        val kept = (readAll() + (p.reportId to p)).entries
            .sortedByDescending { it.value.computedAtMs }
            .take(KEEP)
            .associate { it.key to it.value }
        ServiceLocator.settingsStore.setReportPortfolioSnapshots(Http.json.encodeToString(mapSer, kept))
    }

    /**
     * Price the current holdings over [report]'s period. Null when nothing is held. A result where
     * nothing could be priced (offline, Yahoo down) is returned but NOT stored, so the next open tries
     * again instead of pinning "unavailable" to the report forever.
     */
    suspend fun compute(report: Report): ReportPortfolio? {
        val id = report.id ?: return null
        val start = runCatching { LocalDate.parse(report.startClose) }.getOrNull() ?: return null
        val end = runCatching { LocalDate.parse(report.end) }.getOrNull() ?: return null
        val held = ServiceLocator.watchlistStore.snapshot().filter { (it.shares ?: 0.0) > 0.0 }
        if (held.isEmpty()) return null
        val repo = ServiceLocator.repository
        val inputs = coroutineScope {
            held.map { a ->
                async {
                    val points = runCatching { repo.history(a, ChartRange.YEAR) }.getOrDefault(emptyList())
                    ReportPortfolioMath.Input(a.symbol, a.displayName, a.shares ?: 0.0, points)
                }
            }.awaitAll()
        }
        val p = ReportPortfolioMath.compute(id, start, end, inputs, System.currentTimeMillis()) ?: return null
        if (p.priced) put(p)
        return p
    }

    /** The stored section for [report], computing it the first time. */
    suspend fun getOrCompute(report: Report): ReportPortfolio? =
        report.id?.let { get(it) } ?: compute(report)
}
