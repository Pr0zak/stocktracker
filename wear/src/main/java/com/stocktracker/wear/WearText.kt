package com.stocktracker.wear

import com.stocktracker.shared.PortfolioDisplay
import com.stocktracker.shared.TickerDisplay
import com.stocktracker.shared.WearContent
import java.util.Locale
import kotlin.math.abs

/**
 * Turns a [WearContent] (already decided by [com.stocktracker.shared.wearContent], which itself
 * defers all freshness/partial-result judgment to the phone's own `tickerDisplay`/`portfolioDisplay`)
 * into the handful of short strings the tile layout and the complication's SHORT_TEXT/LONG_TEXT
 * actually have room for.
 *
 * This is presentation only -- number formatting for a small, fixed-width surface -- not a second
 * copy of the honesty rules. It is intentionally SIMPLER than the phone's
 * `com.stocktracker.app.util.Formatting` (no sub-cent significant-figure handling for very cheap
 * crypto, no "hide zero cents" setting): a tile/complication has only a few characters to work with
 * regardless, so the extra precision the phone affords full-size widgets would just get truncated.
 * If that gap turns out to matter in practice, promoting `Formatting` itself into `:shared` (it has
 * no Android dependency either) is the natural follow-up -- deliberately not done here to keep this
 * module's dependency surface to what WGT-7 actually needs.
 */
object WearText {

    /** "AAPL" / "Apple" / "Portfolio" / "StockTracker" (nothing configured yet). */
    fun title(content: WearContent): String = when (content) {
        is WearContent.Ticker -> content.displayName.ifBlank { "Ticker" }
        is WearContent.Portfolio -> "Portfolio"
        WearContent.NotConfigured -> "StockTracker"
    }

    /** The big number: a price or a portfolio total. Null only for [WearContent.NotConfigured] or a
     *  portfolio/ticker still waiting on its first phone-side fetch. */
    fun headline(content: WearContent): String? = when (content) {
        is WearContent.Ticker -> (content.display as? TickerDisplay.Priced)?.let { price(it.quote.price, it.quote.currency) }
        is WearContent.Portfolio -> (content.display as? PortfolioDisplay.Priced)?.let { price(it.summary.totalValue) }
        WearContent.NotConfigured -> null
    }

    /** "▲ +1.20%" / "▼ -0.80%" underneath the headline. Null alongside a null [headline]. */
    fun changeLine(content: WearContent): String? = when (content) {
        is WearContent.Ticker -> (content.display as? TickerDisplay.Priced)?.let {
            "${arrow(it.quote.isUp)} ${percent(it.quote.changePercent)}"
        }
        is WearContent.Portfolio -> (content.display as? PortfolioDisplay.Priced)?.let {
            "${arrow(it.summary.isUp)} ${percent(it.summary.dayChangePercent)}"
        }
        WearContent.NotConfigured -> null
    }

    /** The freshness/partial/error/loading line -- the whole reason this module reuses
     *  `tickerDisplay`/`portfolioDisplay` rather than inventing its own age check. Never null when
     *  there is nothing else to show (a message-only state always has SOME text). */
    fun statusLine(content: WearContent): String? = when (content) {
        is WearContent.Ticker -> when (val d = content.display) {
            is TickerDisplay.Priced -> d.ageLabel
            is TickerDisplay.NoData -> if (d.tapToOpen) "Tap to open" else "Loading…"
        }
        is WearContent.Portfolio -> when (val d = content.display) {
            is PortfolioDisplay.Priced -> listOfNotNull(d.partialLabel, d.ageLabel).joinToString(" · ").ifBlank { null }
            is PortfolioDisplay.Message -> d.text
        }
        WearContent.NotConfigured -> "Open the phone app to configure"
    }

    /** SHORT_TEXT is a handful of characters on most watch faces -- the symbol/"Total" plus, when
     *  there is room, a compact price. No arrow/percent: LONG_TEXT is where those fit. */
    fun complicationShortText(content: WearContent): String = when (content) {
        is WearContent.Ticker -> headline(content)?.let { compactPrice(it) } ?: content.displayName.take(7)
        is WearContent.Portfolio -> headline(content)?.let { compactPrice(it) } ?: "Total"
        WearContent.NotConfigured -> "StockTracker".take(7)
    }

    /** LONG_TEXT has roughly 30 characters of room across most watch faces. */
    fun complicationLongText(content: WearContent): String {
        val title = title(content)
        val head = headline(content)
        val change = changeLine(content)
        return when {
            head != null && change != null -> "$title $head $change"
            head != null -> "$title $head"
            else -> statusLine(content) ?: title
        }.take(40)
    }

    // --- formatting -----------------------------------------------------------------------------

    private fun price(value: Double, currency: String = "USD"): String {
        if (!value.isFinite()) return "—"
        val symbol = if (currency.equals("USD", ignoreCase = true)) "$" else ""
        val a = abs(value)
        val body = when {
            a >= 1000.0 -> String.format(Locale.US, "%,.2f", value)
            a >= 1.0 -> String.format(Locale.US, "%.2f", value)
            else -> String.format(Locale.US, "%.4f", value) // sub-dollar (crypto)
        }
        return symbol + body
    }

    /** Drops the "$..." price to something that plausibly fits a SHORT_TEXT slot, e.g. "$1.2K". */
    private fun compactPrice(formatted: String): String =
        if (formatted.length <= 7) formatted else {
            val numeric = formatted.removePrefix("$").replace(",", "").toDoubleOrNull()
            if (numeric == null) formatted.take(7) else {
                val (scaled, suffix) = when {
                    abs(numeric) >= 1e9 -> numeric / 1e9 to "B"
                    abs(numeric) >= 1e6 -> numeric / 1e6 to "M"
                    abs(numeric) >= 1e3 -> numeric / 1e3 to "K"
                    else -> return formatted.take(7)
                }
                "$" + String.format(Locale.US, "%.1f", scaled) + suffix
            }
        }

    private fun percent(value: Double): String {
        if (!value.isFinite()) return "—"
        val sign = if (value >= 0) "+" else "-"
        return sign + String.format(Locale.US, "%.2f", abs(value)) + "%"
    }

    private fun arrow(up: Boolean): String = if (up) "▲" else "▼"
}
