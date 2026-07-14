package com.stocktracker.app

import android.app.Application
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.notify.AlertNotifier
import com.stocktracker.app.widget.WidgetRefreshScheduler

class StockTrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        AlertNotifier.ensureChannel(this)
        // Keep the periodic worker running so price alerts fire even with no widgets placed.
        WidgetRefreshScheduler.ensureScheduled(this)
    }
}
