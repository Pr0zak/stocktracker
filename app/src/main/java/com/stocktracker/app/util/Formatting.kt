package com.stocktracker.app.util

import java.util.Locale
import kotlin.math.abs

/** Price / change formatting shared by the app UI and the Glance widgets. */
object Formatting {

    fun price(value: Double, currency: String = "USD"): String {
        val symbol = if (currency.equals("USD", ignoreCase = true)) "$" else ""
        val body = when {
            abs(value) >= 1000.0 -> String.format(Locale.US, "%,.2f", value)
            abs(value) >= 1.0 -> String.format(Locale.US, "%.2f", value)
            else -> String.format(Locale.US, "%.4f", value) // sub-dollar (some crypto)
        }
        return symbol + body
    }

    fun change(value: Double): String {
        val sign = if (value >= 0) "+" else "-"
        return sign + String.format(Locale.US, "%,.2f", abs(value))
    }

    fun percent(value: Double): String {
        val sign = if (value >= 0) "+" else "-"
        return sign + String.format(Locale.US, "%.2f", abs(value)) + "%"
    }

    fun arrow(up: Boolean): String = if (up) "▲" else "▼"

    /** "▲ +2.71 (+1.20%)" */
    fun changeLine(change: Double, percent: Double, up: Boolean): String =
        "${arrow(up)} ${change(change)} (${percent(percent)})"
}
