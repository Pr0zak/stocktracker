package com.stocktracker.app.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Price / change formatting shared by the app UI and the Glance widgets. */
object Formatting {

    /**
     * What a non-finite number renders as. The app's convention everywhere else for "no value".
     *
     * Two reasons this guard exists at every entry point. It is a CRASH fix first: `roundToLong()`
     * throws IllegalArgumentException on NaN, and both the hide-zero-cents path and [compact] call
     * it, so a single NaN quote took down the composable that drew the row -- reachable with one
     * user setting turned on. And it is an honesty fix second: without the guard the other paths
     * printed "$NaN" and "$Infinity" straight into the price column, which is the same defect as
     * showing a stale price as current -- a value the user reads as a number because it is sitting
     * where numbers go.
     */
    private const val NA = "—"

    private fun Double.usable(): Boolean = this.isFinite()

    fun price(value: Double, currency: String = "USD", hideZeroCents: Boolean = false): String {
        if (!value.usable()) return NA
        val symbol = if (currency.equals("USD", ignoreCase = true)) "$" else ""
        return symbol + money(value, hideZeroCents)
    }

    fun change(value: Double, hideZeroCents: Boolean = false): String {
        if (!value.usable()) return NA
        val sign = if (value >= 0) "+" else "-"
        return sign + money(abs(value), hideZeroCents)
    }

    fun percent(value: Double): String {
        if (!value.usable()) return NA
        val sign = if (value >= 0) "+" else "-"
        return sign + String.format(Locale.US, "%.2f", abs(value)) + "%"
    }

    fun arrow(up: Boolean): String = if (up) "▲" else "▼"

    /** Large counts with a K/M/B/T suffix: 34554391 → "34.55M", 2.97e10 → "29.75B". */
    fun compact(value: Double): String {
        if (!value.usable()) return NA
        val a = abs(value)
        val (scaled, suffix) = when {
            a >= 1e12 -> value / 1e12 to "T"
            a >= 1e9 -> value / 1e9 to "B"
            a >= 1e6 -> value / 1e6 to "M"
            a >= 1e3 -> value / 1e3 to "K"
            else -> return String.format(Locale.US, "%,d", value.roundToLong())
        }
        return String.format(Locale.US, "%.2f", scaled) + suffix
    }

    /** Share quantity without trailing zeros: 10.0 → "10", 2.5 → "2.5". */
    fun shares(value: Double): String =
        if (!value.usable()) {
            NA
        } else if (value % 1.0 == 0.0) {
            String.format(Locale.US, "%,d", value.toLong())
        } else {
            String.format(Locale.US, "%,.4f", value).trimEnd('0').trimEnd('.')
        }

    /** "▲ +2.71 (+1.20%)" */
    fun changeLine(change: Double, percent: Double, up: Boolean, hideZeroCents: Boolean = false): String {
        // No arrow either. A direction drawn beside an unknown move is a claim the data cannot
        // support, and green/red on "—" reads as a real up or down day at a glance.
        if (!change.usable() || !percent.usable()) return NA
        return "${arrow(up)} ${change(change, hideZeroCents)} (${percent(percent)})"
    }

    /** Formats a positive magnitude. When [hideZeroCents], whole-dollar amounts drop the ".00". */
    private fun money(value: Double, hideZeroCents: Boolean): String {
        val a = abs(value)
        // `a >= 1.0` gates the shortcut. Without it the rounding test is trivially true for every
        // sub-dollar value -- 0.00001208 * 100 rounds to 0, 0 % 100 == 0 -- so "hide zero cents"
        // rendered a real SHIB price as "$0". The setting means "drop .00 from whole dollars"; it
        // was never meant to have an opinion about assets that cost less than a dollar.
        if (hideZeroCents && a >= 1.0 && (a * 100).roundToLong() % 100L == 0L) {
            return String.format(Locale.US, "%,d", value.roundToLong())
        }
        return when {
            a >= 1000.0 -> String.format(Locale.US, "%,.2f", value)
            a >= 1.0 -> String.format(Locale.US, "%.2f", value)
            a >= 0.01 || a == 0.0 -> String.format(Locale.US, "%.4f", value) // sub-dollar (some crypto)
            // Below a cent, four decimals is not enough: SHIB trades near $0.000012, which rendered
            // as "$0.0000" -- a price of zero for an asset the user may hold thousands of dollars
            // of. Four SIGNIFICANT figures instead, so the number shown is the number that exists.
            // Mirrors _round_price in the backend's sandbox_job, which had the same defect in the
            // trade log.
            else -> {
                val decimals = (-kotlin.math.floor(kotlin.math.log10(a)).toInt() + 3).coerceAtMost(12)
                String.format(Locale.US, "%.${decimals}f", value)
            }
        }
    }
}
