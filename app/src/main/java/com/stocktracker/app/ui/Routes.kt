package com.stocktracker.app.ui

import android.net.Uri
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType

/**
 * Every route in the app, in one place.
 *
 * This exists because notifications could not reach any of them. Each of the app's six notifiers —
 * price and percent alerts, armed technical conditions, the market recap, the AI morning brief, the
 * nightly signal scan and dip radar, sandbox paper trades, call-exit warnings — built the same bare
 * `Intent(context, MainActivity::class.java)`. A notification saying "NVDA rose above $390" opened
 * the app on whatever screen you happened to leave it on, and finding NVDA from there was the
 * user's problem. The notification knew exactly which screen it was about and threw that away at
 * the last step.
 *
 * The fix is not a second navigation mechanism for notifications. It is this: one set of route
 * strings, built by the same functions, used by the nav graph, by every in-app tap, and by the
 * `PendingIntent`. A notification tap and a row tap now run the same code, so they cannot drift —
 * which is the actual failure mode a parallel deep-link table would have reintroduced the first
 * time a route was renamed.
 *
 * The PATTERN constants are what the nav graph registers; the functions build a filled instance of
 * one. Keep them adjacent — that adjacency is the only thing keeping the two in step.
 */
object Routes {
    const val WATCHLIST = "watchlist"
    const val PORTFOLIO = "portfolio"
    const val IDEAS = "ideas"
    const val HEATMAP = "heatmap"
    const val MARKET_SCAN = "market_scan"
    const val SANDBOX = "sandbox"
    const val SANDBOX_SETTINGS = "sandbox_settings"
    const val JOURNAL = "journal"
    const val WIDGETS = "widgets"
    const val MARKETS = "markets"
    const val SETTINGS = "settings"
    const val METHODOLOGY = "methodology"
    const val VIX = "vix"
    const val DIPS = "dips"
    const val ADD = "add"

    const val REPORTS = "reports"

    const val CALENDAR_PATTERN = "calendar?symbol={symbol}"
    const val REPORT_PATTERN = "report/{id}"
    const val DETAIL_PATTERN = "detail/{type}/{symbol}?name={name}&cg={cg}"

    /**
     * A ticker's detail screen. Everything the screen needs rides in the URL, so this opens for a
     * name that is not on the watchlist — a scan row, a dip, a symbol a notification names.
     */
    fun detail(asset: Asset): String {
        val name = Uri.encode(asset.displayName)
        val cg = Uri.encode(asset.coinGeckoId ?: "")
        return "detail/${asset.type.name}/${Uri.encode(asset.symbol)}?name=$name&cg=$cg"
    }

    /** One weekly or monthly report by its id ("week-2026-09-25"), as the backend names it. */
    fun report(id: String): String = "report/${Uri.encode(id)}"

    /** The catalyst calendar: whole-market with no symbol, one ticker's with one. */
    fun calendar(symbol: String? = null): String =
        if (symbol.isNullOrBlank()) "calendar" else "calendar?symbol=${Uri.encode(symbol)}"

    /**
     * The calendar symbol for an asset. Crypto calendars are keyed by the backend's Yahoo form
     * (BTC becomes BTC-USD); getting this wrong returns an empty calendar rather than an error.
     */
    fun calendarSymbol(asset: Asset): String =
        if (asset.type == AssetType.CRYPTO) "${asset.symbol}-USD" else asset.symbol
}
