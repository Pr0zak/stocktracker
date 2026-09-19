package com.stocktracker.app.wear

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.widget.PortfolioWidget
import com.stocktracker.app.widget.PortfolioWidgetState
import com.stocktracker.app.widget.TickerWidget
import com.stocktracker.app.widget.TickerWidgetState
import com.stocktracker.shared.WEAR_SNAPSHOT_JSON_KEY
import com.stocktracker.shared.WEAR_SNAPSHOT_PATH
import com.stocktracker.shared.WearPortfolioSnapshot
import com.stocktracker.shared.WearSnapshot
import com.stocktracker.shared.WearTickerSnapshot
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString

private const val TAG = "StockTrackerWear"

/**
 * Mirrors the phone widgets' last-refreshed state to a paired watch over the Wear OS Data Layer
 * (WGT-7).
 *
 * This is a ONE-WAY push, run as an extra step from [com.stocktracker.app.widget.WidgetRefreshWorker]
 * AFTER the existing widget refreshes have already written their state -- it reads the same Glance
 * state the phone widgets already render from and reshapes it into [WearSnapshot], but it does not
 * fetch anything itself and does not change what [TickerWidget]/[PortfolioWidget] compute or store.
 * A watch with no Play Services, no pairing, or simply no tile installed just means
 * [Wearable.getDataClient] fails or has nothing to deliver to -- caught here exactly like every other
 * isolated step in [com.stocktracker.app.widget.WidgetRefreshWorker], so it can never take the
 * phone's own widgets down with it.
 *
 * There is no config UI on the watch, so when more than one ticker widget is placed on the phone
 * this deterministically picks the FIRST one ([GlanceAppWidgetManager.getGlanceIds] order) -- the
 * same "pick one" problem [com.stocktracker.shared.wearContent] resolves on the watch side for
 * ticker-vs-portfolio priority.
 */
object WearSync {

    suspend fun push(context: Context) {
        val ticker = firstTickerSnapshot(context)
        val portfolio = firstPortfolioSnapshot(context)
        if (ticker == null && portfolio == null) {
            // Nothing configured on the phone yet -- nothing honest to show on the watch either.
            return
        }
        val snapshot = WearSnapshot(ticker = ticker, portfolio = portfolio)
        val json = Http.json.encodeToString(snapshot)
        val request = PutDataMapRequest.create(WEAR_SNAPSHOT_PATH).apply {
            dataMap.putString(WEAR_SNAPSHOT_JSON_KEY, json)
            dataMap.putLong("pushed_at", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(context).putDataItem(request).await()
        Log.d(TAG, "pushed wear snapshot (ticker=${ticker != null}, portfolio=${portfolio != null})")
    }

    private suspend fun firstTickerSnapshot(context: Context): WearTickerSnapshot? {
        val id = GlanceAppWidgetManager(context).getGlanceIds(TickerWidget::class.java).firstOrNull()
            ?: return null
        val prefs: Preferences = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val config = TickerWidgetState.readConfig(prefs)
        return WearTickerSnapshot(
            quote = TickerWidgetState.readQuote(prefs),
            error = prefs[TickerWidgetState.ERROR],
            displayName = config.displayName.ifBlank { config.symbol },
            accentArgb = config.accentArgb,
        )
    }

    private suspend fun firstPortfolioSnapshot(context: Context): WearPortfolioSnapshot? {
        val id = GlanceAppWidgetManager(context).getGlanceIds(PortfolioWidget::class.java).firstOrNull()
            ?: return null
        val prefs: Preferences = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        return WearPortfolioSnapshot(
            summary = PortfolioWidgetState.readSummary(prefs),
            loaded = prefs.contains(PortfolioWidgetState.SUMMARY),
            error = prefs[PortfolioWidgetState.ERROR],
            lastSuccessMs = prefs[PortfolioWidgetState.LAST_SUCCESS] ?: 0L,
        )
    }
}
