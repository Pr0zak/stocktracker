package com.stocktracker.app.notify

import android.content.Context
import com.stocktracker.app.data.remote.SandboxTrade
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/**
 * Notifies when the AI paper trader executes trades in the sandbox.
 *
 * The trading loop runs SERVER-SIDE (a systemd timer near the close), so the app can't observe a fill as
 * it happens — instead the existing 15-minute [com.stocktracker.app.widget.WidgetRefreshWorker] polls
 * the trade log and announces anything newer than the stored watermark
 * ([com.stocktracker.app.data.prefs.SettingsStore.lastSandboxTradeTs]). That makes it exactly-once per
 * fill across worker runs, app restarts, and re-installs of the same data.
 *
 * Gated on the server-side `notify_on_trade` setting (it rides with the sandbox account) plus a
 * configured Signals URL. Purely INFORMATIONAL — fictional money, no action required.
 */
object SandboxTradeNotifier {

    private val signalsApi = SignalsApiService()

    suspend fun check(context: Context) {
        val settings = ServiceLocator.settingsStore
        val base = settings.signalsApiUrl.first()
        if (base.isBlank()) return

        val state = signalsApi.sandboxState(base) ?: return
        if (!state.settings.notifyOnTrade) return

        val watermark = settings.lastSandboxTradeTs.first()
        val trades = signalsApi.sandboxTrades(base, limit = 40)
            .filter { it.status == "filled" && (it.side == "buy" || it.side == "sell") }
        if (trades.isEmpty()) return

        val newest = trades.maxOf { it.ts }
        // First run after enabling: adopt the current head so we don't announce the whole backlog.
        if (watermark <= 0.0) {
            settings.setLastSandboxTradeTs(newest)
            return
        }
        val fresh = trades.filter { it.ts > watermark }.sortedBy { it.ts }
        if (fresh.isEmpty()) return

        val (title, body) = summarize(fresh, state.equity)
        AlertNotifier.notifySandbox(context, "sandbox_trades".hashCode(), title, body)
        settings.setLastSandboxTradeTs(newest)
    }

    /** One trade → a specific headline; several → a digest, so a multi-trade day is a single ping. */
    internal fun summarize(fresh: List<SandboxTrade>, equity: Double): Pair<String, String> {
        val title = if (fresh.size == 1) {
            val t = fresh.first()
            "Sandbox: ${t.side.replaceFirstChar { c -> c.uppercase() }} ${sym(t.symbol)} ${shares(t.shares)} sh"
        } else {
            val buys = fresh.count { it.side == "buy" }
            val sells = fresh.size - buys
            "Sandbox: ${listOfNotNull(
                buys.takeIf { it > 0 }?.let { "$it buy${if (it > 1) "s" else ""}" },
                sells.takeIf { it > 0 }?.let { "$it sell${if (it > 1) "s" else ""}" },
            ).joinToString(" · ")}"
        }
        val lines = fresh.joinToString("\n") { t ->
            val px = t.price?.let { " @ $" + trim(it) } ?: ""
            val why = t.reason.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
            "${t.side.uppercase()} ${sym(t.symbol)} ${shares(t.shares)} sh$px$why"
        }
        val tail = if (equity > 0) "\n\nBalance $" + trim(equity) else ""
        return title to (lines + tail)
    }

    private fun sym(s: String) = s.removeSuffix("-USD")
    private fun shares(v: Double) = if (v == kotlin.math.floor(v)) v.toInt().toString() else String.format(java.util.Locale.US, "%.4f", v)
    private fun trim(v: Double) =
        if (abs(v) >= 1000) String.format(java.util.Locale.US, "%,.0f", v) else String.format(java.util.Locale.US, "%.2f", v)
}
