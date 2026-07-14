package com.stocktracker.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/** Watchlist + settings. */
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "stocktracker")

/**
 * Quote / sparkline cache — a SEPARATE store on purpose: cache writes must not re-emit the
 * watchlist/settings flows (that caused a fetch⇄write feedback loop).
 */
val Context.priceCacheStore: DataStore<Preferences> by preferencesDataStore(name = "price_cache")
