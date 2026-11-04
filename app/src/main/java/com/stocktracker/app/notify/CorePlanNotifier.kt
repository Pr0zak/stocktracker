package com.stocktracker.app.notify

import android.content.Context
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.Routes
import com.stocktracker.app.ui.portfolio.CorePlan
import com.stocktracker.app.ui.portfolio.CorePlanMath
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Reminds the user on their core plan's buy day, once a month. Run from the 15-minute
 * [com.stocktracker.app.widget.WidgetRefreshWorker].
 *
 * The body names the split from the holdings' saved share counts and the last cached prices, so it
 * says where the money goes without a network call; the Portfolio tab shows it again from live
 * prices. A month counts as reminded only once the notification was delivered, so a blocked one is
 * retried on the next run that day or later in the month.
 */
object CorePlanNotifier {

    /** "yyyy-MM" of [today] when a reminder is due, else null. Pure, for tests. */
    internal fun dueMonth(plan: CorePlan?, today: LocalDate, remindedMonth: String): String? {
        if (plan == null || !plan.remind) return null
        val month = today.toString().take(7)
        if (remindedMonth == month) return null
        return month.takeIf { today.dayOfMonth >= plan.depositDay.coerceIn(1, 28) }
    }

    suspend fun check(context: Context) {
        val settings = ServiceLocator.settingsStore
        val plan = settings.corePlan.first()
        val month = dueMonth(plan, LocalDate.now(), settings.corePlanRemindedMonth.first()) ?: return
        plan ?: return

        val amount = plan.depositAmount
        val title = if (amount != null) "Buy day: ${dollars(amount)} for your core" else "Buy day for your core plan"
        val body = amount?.let { splitLine(plan, it) } ?: "Open Portfolio to see where it goes."
        if (AlertNotifier.notifyBuyDay(context, "core_buy_day".hashCode(), title, body, Routes.PORTFOLIO)) {
            settings.setCorePlanRemindedMonth(month)
        }
    }

    private fun dollars(v: Double) = "$" + String.format(Locale.US, "%,d", v.roundToLong())

    private suspend fun splitLine(plan: CorePlan, amount: Double): String {
        val cache = ServiceLocator.priceCache
        val planSymbols = plan.slots.flatMap { s -> s.symbols.map { it.uppercase() } }.toSet()
        val held = ServiceLocator.watchlistStore.watchlist.first()
            .filter { (it.shares ?: 0.0) > 0.0 }
            .map { a -> a.symbol to runCatching { cache.getQuote(a.id)?.price }.getOrNull()?.let { (a.shares ?: 0.0) * it } }
        // A plan fund with no cached price would make its group look empty and pull the whole
        // deposit toward it. Name no split rather than a wrong one.
        if (held.any { (sym, v) -> v == null && sym.uppercase() in planSymbols }) return "Open Portfolio to see where it goes."
        val split = CorePlanMath.split(CorePlanMath.evaluate(plan, held.mapNotNull { (sym, v) -> v?.let { sym to it } }), amount)
        if (split.isEmpty()) return "Open Portfolio to see where it goes."
        return split.joinToString(" · ") { "${it.symbol} ${dollars(it.dollars)}" } + " · from last prices"
    }
}
