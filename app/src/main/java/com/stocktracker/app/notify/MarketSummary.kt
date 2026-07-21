package com.stocktracker.app.notify

import com.stocktracker.app.util.MarketClock
import com.stocktracker.app.util.MarketPhase
import java.util.Locale
import kotlin.math.abs

/** One tracked stock/ETF's day + after-hours performance, as fed to [MarketSummary.build]. */
data class Mover(
    val symbol: String,
    /** Regular-session % change on the day. */
    val dayChangePct: Double,
    /** After-hours % move vs the regular close; null when there's no post-market data. */
    val afterHoursPct: Double? = null,
)

/** A ready-to-post recap: which recap it is, plus the notification title + body text. */
data class MoverSummary(val kind: Kind, val title: String, val body: String) {
    enum class Kind { CLOSE, AFTER_HOURS }
}

/**
 * PURE, unit-testable decision logic for the market-close / after-hours movers recap. No I/O and no
 * Android deps, so the timing gates and message formatting can be tested directly.
 *
 * This is purely INFORMATIONAL decision support — a snapshot of the watchlist's biggest movers, not
 * a signal to trade. The caller is responsible for filtering the input to stocks/ETFs (crypto has no
 * US session) and for the once-per-day dedup (via the [alreadySent*] flags).
 */
object MarketSummary {

    /** How many names to list per side (top gainers / top losers). */
    const val TOP_N = 3

    /** |day %| below this counts as flat and is omitted from the movers line. */
    const val FLAT_EPS = 0.05

    /** |after-hours %| below this (or null) counts as "didn't move after the close". */
    const val AH_FLAT_EPS = 0.05

    /**
     * Decide whether to post a recap and, if so, build it.
     *
     * @param phase             current [MarketPhase] (from MarketClock).
     * @param etSecondsOfDay    seconds-of-day in America/New_York (0..86399).
     * @param isTradingDay      false on weekends / market holidays.
     * @param movers            the watchlist's stocks/ETFs only (caller filters out crypto).
     * @param alreadySentClose  true if the close recap already fired today (dedup).
     * @param alreadySentAfterHours true if the after-hours recap already fired today (dedup).
     * @return the recap to post, or null when nothing should fire right now.
     */
    fun build(
        phase: MarketPhase,
        etSecondsOfDay: Int,
        isTradingDay: Boolean,
        movers: List<Mover>,
        alreadySentClose: Boolean,
        alreadySentAfterHours: Boolean,
    ): MoverSummary? = when {
        // Close recap: any time during the after-hours window (16:00–20:00 ET), once per day. The
        // AFTER phase already implies a trading day, so no extra guard is needed here.
        phase == MarketPhase.AFTER && !alreadySentClose -> buildClose(movers)

        // After-hours recap: after the post session ends (≥ 20:00 ET) on a trading day, once per day.
        phase == MarketPhase.CLOSED &&
            etSecondsOfDay >= MarketClock.WINDOW_END &&
            isTradingDay &&
            !alreadySentAfterHours -> buildAfterHours(movers)

        else -> null
    }

    private fun buildClose(movers: List<Mover>): MoverSummary? {
        if (movers.isEmpty()) return null

        val ups = movers.filter { it.dayChangePct > FLAT_EPS }
            .sortedByDescending { it.dayChangePct }.take(TOP_N)
        val downs = movers.filter { it.dayChangePct < -FLAT_EPS }
            .sortedBy { it.dayChangePct }.take(TOP_N)

        val upCount = movers.count { it.dayChangePct > 0 }
        val overview = "$upCount/${movers.size} up on the day"
        val moversLine = moversLine(ups, downs) { it.dayChangePct }
        val body = if (moversLine.isEmpty()) "$overview · little movement" else "$overview\n$moversLine"

        return MoverSummary(
            MoverSummary.Kind.CLOSE,
            "Market close · ${movers.size} tracked",
            body,
        )
    }

    private fun buildAfterHours(movers: List<Mover>): MoverSummary {
        val moved = movers.filter { it.afterHoursPct != null && abs(it.afterHoursPct) >= AH_FLAT_EPS }
        if (moved.isEmpty()) {
            return MoverSummary(
                MoverSummary.Kind.AFTER_HOURS,
                "After-hours movers",
                "Quiet after-hours — no notable moves after the close.",
            )
        }

        val ups = moved.filter { it.afterHoursPct!! > 0 }
            .sortedByDescending { it.afterHoursPct!! }.take(TOP_N)
        val downs = moved.filter { it.afterHoursPct!! < 0 }
            .sortedBy { it.afterHoursPct!! }.take(TOP_N)

        val overview = if (moved.size == 1) "1 name moved after hours" else "${moved.size} names moved after hours"
        val moversLine = moversLine(ups, downs) { it.afterHoursPct!! }

        return MoverSummary(
            MoverSummary.Kind.AFTER_HOURS,
            "After-hours movers",
            "$overview\n$moversLine",
        )
    }

    /** "▲ NVDA +3.2% · MSFT +2.1%   ▼ AAPL −2.1%" — only the side(s) that have names. */
    private fun moversLine(ups: List<Mover>, downs: List<Mover>, pct: (Mover) -> Double): String {
        val parts = buildList {
            if (ups.isNotEmpty()) add("▲ " + ups.joinToString(" · ") { "${it.symbol} ${fmtPct(pct(it))}" })
            if (downs.isNotEmpty()) add("▼ " + downs.joinToString(" · ") { "${it.symbol} ${fmtPct(pct(it))}" })
        }
        return parts.joinToString("   ")
    }

    /** "+3.2%" / "−2.1%" — one decimal, unicode minus for the down side (matches the app's convention). */
    private fun fmtPct(v: Double): String {
        val sign = if (v >= 0) "+" else "−"
        return sign + String.format(Locale.US, "%.1f", abs(v)) + "%"
    }
}
