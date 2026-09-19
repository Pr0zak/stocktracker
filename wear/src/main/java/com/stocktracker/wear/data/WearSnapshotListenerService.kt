package com.stocktracker.wear.data

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.stocktracker.shared.WEAR_SNAPSHOT_JSON_KEY
import com.stocktracker.shared.WEAR_SNAPSHOT_PATH

/**
 * Receives the [com.stocktracker.shared.WearSnapshot] the phone pushes after every widget refresh
 * (WGT-7, see `com.stocktracker.app.wear.WearSync` in `:app`) and caches it via [WearRepository] so
 * the tile and complication can render without waiting on a fresh Data Layer round trip.
 *
 * This service ONLY receives; it never calls back out to the phone, and it never talks to Yahoo,
 * Finnhub, CoinGecko, or the signals backend -- the watch has no fetch path of its own.
 */
class WearSnapshotListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == WEAR_SNAPSHOT_PATH) {
                val json = DataMapItem.fromDataItem(event.dataItem).dataMap.getString(WEAR_SNAPSHOT_JSON_KEY)
                if (json != null) WearRepository.save(applicationContext, json)
            }
        }
        dataEvents.release()
    }
}
