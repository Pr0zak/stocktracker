package com.stocktracker.wear.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.stocktracker.shared.wearContent
import com.stocktracker.wear.MainActivity
import com.stocktracker.wear.WearText
import com.stocktracker.wear.data.WearRepository

/**
 * The WGT-7 complication: same content and freshness rules as [com.stocktracker.wear.tile.StockTileService],
 * rendered into the couple of characters a watch face slot allows. See [WearText] for how the
 * decided [com.stocktracker.shared.WearContent] gets squeezed into SHORT_TEXT/LONG_TEXT.
 */
class StockComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? = when (type) {
        ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder("$150").build(),
            contentDescription = PlainComplicationText.Builder("StockTracker").build(),
        ).build()
        ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
            text = PlainComplicationText.Builder("AAPL $150.23 ▲1.20%").build(),
            contentDescription = PlainComplicationText.Builder("StockTracker").build(),
        ).build()
        else -> null
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val snapshot = WearRepository.snapshotOrHydrate(applicationContext)
        val content = wearContent(snapshot, nowMs = System.currentTimeMillis())

        val tapAction = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val description = PlainComplicationText.Builder(WearText.complicationLongText(content)).build()

        return when (request.complicationType) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                text = PlainComplicationText.Builder(WearText.complicationShortText(content)).build(),
                contentDescription = description,
            ).setTapAction(tapAction).build()

            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                text = PlainComplicationText.Builder(WearText.complicationLongText(content)).build(),
                contentDescription = description,
            ).setTapAction(tapAction).build()

            else -> null
        }
    }
}
