package com.stocktracker.app.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Price / change formatting shared by the app UI and the Glance widgets. */
object Formatting {

    fun price(value: Double, currency: String = "USD", hideZeroCents: Boolean = false): String {
        val symbol = if (currency.equals("USD", ignoreCase = true)) "$" else ""
        return symbol + money(value, hideZeroCents)
    }

    fun change(value: Double, hideZeroCents: Boolean = false): String {
        val sign = if (value >= 0) "+" else "-"
        return sign + money(abs(value), hideZeroCents)
    }

    fun percent(value: Double): String {
        val sign = if (value >= 0) "+" else "-"
        return sign + String.format(Locale.US, "%.2f", abs(value)) + "%"
    }

    fun arrow(up: Boolean): String = if (up) "▲" else "▼"

    /** Share quantity without trailing zeros: 10.0 → "10", 2.5 → "2.5". */
    fun shares(value: Double): String =
        if (value % 1.0 == 0.0) {
            String.format(Locale.US, "%,d", value.toLong())
        } else {
            String.format(Locale.US, "%,.4f", value).trimEnd('0').trimEnd('.')
        }

    /** "▲ +2.71 (+1.20%)" */
    fun changeLine(change: Double, percent: Double, up: Boolean, hideZeroCents: Boolean = false): String =
        "${arrow(up)} ${change(change, hideZeroCents)} (${percent(percent)})"

    /** Formats a positive magnitude. When [hideZeroCents], whole-dollar amounts drop the ".00". */
    private fun money(value: Double, hideZeroCents: Boolean): String {
        val a = abs(value)
        if (hideZeroCents && (a * 100).roundToLong() % 100L == 0L) {
            return String.format(Locale.US, "%,d", value.roundToLong())
        }
        return when {
            a >= 1000.0 -> String.format(Locale.US, "%,.2f", value)
            a >= 1.0 -> String.format(Locale.US, "%.2f", value)
            else -> String.format(Locale.US, "%.4f", value) // sub-dollar (some crypto)
        }
    }
}
