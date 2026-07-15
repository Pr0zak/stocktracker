package com.stocktracker.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context

/**
 * Ask the launcher to pin a StockTracker widget to the home screen.
 * Returns false if the launcher doesn't support pin requests (user must add it manually).
 */
object WidgetPinning {

    fun requestPinTicker(context: Context): Boolean =
        requestPin(context, TickerWidgetReceiver::class.java)

    fun requestPinWatchlist(context: Context): Boolean =
        requestPin(context, WatchlistWidgetReceiver::class.java)

    fun requestPinPortfolio(context: Context): Boolean =
        requestPin(context, PortfolioWidgetReceiver::class.java)

    private fun requestPin(context: Context, receiver: Class<*>): Boolean {
        val manager = context.getSystemService(AppWidgetManager::class.java) ?: return false
        if (!manager.isRequestPinAppWidgetSupported) return false
        val provider = ComponentName(context, receiver)
        return manager.requestPinAppWidget(provider, null, null)
    }
}
