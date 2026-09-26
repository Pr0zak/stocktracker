package com.stocktracker.app.notify

import android.content.Context
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.remote.FundCost
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.Routes
import com.stocktracker.app.ui.detail.FundCostText
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/**
 * FUND-6 — tells the user when a fund they hold changes its yearly fee. Run from the 15-minute
 * [com.stocktracker.app.widget.WidgetRefreshWorker], checking at most every [CHECK_EVERY_MS].
 *
 * The case that prompted it: HODL's launch waiver ended on 2026-07-31 and its fee went from 0% to
 * 0.20% with nothing on any screen saying so. A fee change is rare and silent, which is exactly the
 * kind of thing an alert is for.
 *
 * The first check only records what it sees. A change is recorded as seen only once its alert was
 * delivered, so a blocked notification is retried rather than lost.
 */
object FundFeeNotifier {

    private const val CHECK_EVERY_MS = 12 * 3600 * 1000L
    /** Smaller moves are rounding (SPY is 0.0945% on Yahoo and 0.095% in the saved table), not a
     *  fee change. Real changes run to hundredths of a point: VXUS 0.08 -> 0.05, QQQ 0.20 -> 0.18. */
    private const val MIN_CHANGE_PCT = 0.001
    private val api = SignalsApiService()

    data class FeeChange(val symbol: String, val name: String?, val was: Double, val now: Double)

    /**
     * Compare fresh fee rows with the fees last seen, returning the changes and the new seen-map.
     *
     * Only a fee from a checked source counts: live from Yahoo, or the fund company's own figure. A
     * figure from the server's saved list means Yahoo did not answer, and a "change" against it would
     * be noise. Unknown on either side is never a change.
     */
    internal fun diff(seen: Map<String, Double>, rows: Collection<FundCost>): Pair<List<FeeChange>, Map<String, Double>> {
        val next = seen.toMutableMap()
        val changes = mutableListOf<FeeChange>()
        for (r in rows) {
            if (!r.isFund) continue
            val fee = r.expenseRatioPct ?: continue
            if (r.feeSource != "yahoo" && r.feeSource != "issuer") continue
            val old = seen[r.symbol]
            if (old != null && abs(old - fee) >= MIN_CHANGE_PCT) changes += FeeChange(r.symbol, r.name, old, fee)
            next[r.symbol] = fee
        }
        return changes to next
    }

    internal fun encode(m: Map<String, Double>): Set<String> = m.map { "${it.key}=${it.value}" }.toSet()

    internal fun decode(s: Set<String>): Map<String, Double> = s.mapNotNull { e ->
        val k = e.substringBefore('=')
        e.substringAfter('=', "").toDoubleOrNull()?.let { k to it }
    }.toMap()

    internal fun title(c: FeeChange): String =
        "${c.symbol}'s yearly fee went ${if (c.now > c.was) "up" else "down"}"

    internal fun body(c: FeeChange): String =
        "Now ${FundCostText.perTenK(c.now)} a year per \$10,000, was ${FundCostText.perTenK(c.was)}."

    suspend fun check(context: Context) {
        val settings = ServiceLocator.settingsStore
        if (!settings.fundFeeNotifyEnabled.first()) return
        val url = settings.signalsApiUrl.first()
        if (url.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - settings.fundFeeCheckedMs.first() < CHECK_EVERY_MS) return

        val held = ServiceLocator.watchlistStore.watchlist.first()
            .filter { it.type == AssetType.STOCK && (it.shares ?: 0.0) > 0.0 }
            .map { it.symbol.uppercase() }
            .distinct()
        if (held.isEmpty()) {
            settings.setFundFeeCheckedMs(now)
            return
        }
        val rows = mutableListOf<FundCost>()
        for (chunk in held.chunked(60)) {
            // A failed read leaves the clock alone, so the next tick tries again.
            val r = runCatching { api.fundCosts(url, chunk) }.getOrNull() ?: return
            rows += chunk.mapNotNull { r.funds[it] }
        }
        val seen = decode(settings.fundFeesSeen.first())
        val (changes, next) = diff(seen, rows)
        val undelivered = changes.filterNot { c ->
            AlertNotifier.notifyFundFee(
                context,
                "fund_fee_${c.symbol}".hashCode(),
                title(c),
                body(c),
                Routes.detail(Asset(c.symbol, AssetType.STOCK, c.name ?: c.symbol)),
            )
        }.map { it.symbol }.toSet()
        val store = next.mapValues { (k, v) -> if (k in undelivered) seen[k] ?: v else v }
        settings.setFundFeesSeen(encode(store))
        settings.setFundFeeCheckedMs(now)
    }
}
