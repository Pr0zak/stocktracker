package com.stocktracker.app

import android.app.Application
import com.stocktracker.app.di.ServiceLocator

class StockTrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
