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

    /** Trades older than this are never announced — they are backlog, not news. Guards against a
     *  restored watermark replaying weeks of activity as though it had just happened. */
    private const val MAX_BACKLOG_SECONDS = 2 * 24 * 60 * 60.0

    private val signalsApi = SignalsApiService()

    suspend fun check(context: Context) {
        val settings = ServiceLocator.settingsStore
        val base = settings.signalsApiUrl.first()
        if (base.isBlank()) return

        val state = signalsApi.sandboxState(base) ?: return
        if (!state.settings.notifyOnTrade) return

        val watermark = settings.lastSandboxTradeTs.first()
        // A null list means the FETCH failed — bail rather than treat it as "no trades", which
        // would otherwise be indistinguishable from a quiet day.
        val trades = (signalsApi.sandboxTrades(base, limit = 40) ?: return)
            .filter { it.status == "filled" && (it.side == "buy" || it.side == "sell") }
        if (trades.isEmpty()) return

        val newest = trades.maxOf { it.ts }
        // First run after enabling: adopt the current head so we don't announce the whole backlog.
        if (watermark <= 0.0) {
            settings.setLastSandboxTradeTs(newest)
            return
        }
        var fresh = trades.filter { it.ts > watermark }.sortedBy { it.ts }
        if (fresh.isEmpty()) return
        // A cloud restore or device transfer brings the DataStore back with an OLD watermark (the
        // holdings in the same store must be restored, so it can't simply be excluded), which would
        // re-announce every trade since. Anything older than this isn't news — adopt it silently.
        val cutoff = (System.currentTimeMillis() / 1000.0) - MAX_BACKLOG_SECONDS
        if (fresh.first().ts < cutoff) {
            fresh = fresh.filter { it.ts >= cutoff }
            if (fresh.isEmpty()) {
                settings.setLastSandboxTradeTs(newest)
                return
            }
        }

        val (title, body) = summarize(fresh, state.equity)
        // A per-batch id, not one constant. NotificationManager REPLACES on a repeated id, so a
        // second batch destroyed an unread first one — and the watermark had already moved past it,
        // making those trades unannounceable forever. Keyed on the newest ts so each batch is its
        // own tray entry.
        val id = ("sandbox_trades:" + newest.toLong()).hashCode()
        // Only advance the watermark when the notification actually reached the system. Recording a
        // delivery that never happened is how alerts silently disappear.
        if (AlertNotifier.notifySandbox(context, id, title, body)) {
            settings.setLastSandboxTradeTs(newest)
        }
    }

    /**
     * Deliberately BRIEF — a glanceable headline plus one short line. The AI's full reasoning lives in
     * the app's trade log; a notification that repeats it is a wall of text on the lock screen.
     * One trade → "Sandbox: Bought 2 MSFT @ $384"; several → a compact "BUY MSFT 2 · SELL IBIT 10" digest.
     */
    internal fun summarize(fresh: List<SandboxTrade>, equity: Double): Pair<String, String> {
        val balance = if (equity > 0) "Balance $" + trim(equity) else ""
        if (fresh.size == 1) {
            val t = fresh.first()
            val verb = if (t.side == "buy") "Bought" else "Sold"
            val px = t.price?.let { " @ $" + trim(it) } ?: ""
            return "Sandbox: $verb ${shares(t.shares)} ${sym(t.symbol)}$px" to balance
        }
        val buys = fresh.count { it.side == "buy" }
        val sells = fresh.size - buys
        val title = "Sandbox: " + listOfNotNull(
            buys.takeIf { it > 0 }?.let { "$it buy${if (it > 1) "s" else ""}" },
            sells.takeIf { it > 0 }?.let { "$it sell${if (it > 1) "s" else ""}" },
        ).joinToString(" · ")
        val body = fresh.joinToString(" · ") { t ->
            "${t.side.uppercase()} ${sym(t.symbol)} ${shares(t.shares)}"
        } + if (balance.isNotEmpty()) " — $balance" else ""
        return title to body
    }

    private fun sym(s: String) = s.removeSuffix("-USD")
    private fun shares(v: Double) = if (v == kotlin.math.floor(v)) v.toInt().toString() else String.format(java.util.Locale.US, "%.4f", v)
    private fun trim(v: Double) =
        if (abs(v) >= 1000) String.format(java.util.Locale.US, "%,.0f", v) else String.format(java.util.Locale.US, "%.2f", v)
}
