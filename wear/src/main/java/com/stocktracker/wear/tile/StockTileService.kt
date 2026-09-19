package com.stocktracker.wear.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.stocktracker.shared.WearContent
import com.stocktracker.shared.wearContent
import com.stocktracker.wear.MainActivity
import com.stocktracker.wear.WearText
import com.stocktracker.wear.data.WearRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.future

/**
 * The WGT-7 tile: one configured ticker, or the portfolio total, exactly as [WearContent] picks --
 * see [com.stocktracker.shared.wearContent] for that choice and for why the freshness/partial
 * judgment is delegated entirely to the phone's own `tickerDisplay`/`portfolioDisplay`.
 *
 * Every render recomputes [WearContent] against `System.currentTimeMillis()` -- the watch's own
 * clock, not whatever moment the phone happened to push at -- so an age label that was accurate when
 * the phone pushed does not silently go stale itself just because the tile hasn't been re-requested
 * since.
 */
class StockTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        scope.future {
            val snapshot = WearRepository.snapshotOrHydrate(applicationContext)
            val content = wearContent(snapshot, nowMs = System.currentTimeMillis())

            TileBuilders.Tile.Builder()
                .setResourcesVersion("1")
                .setFreshnessIntervalMillis(60_000L)
                .setTileTimeline(
                    TimelineBuilders.Timeline.Builder()
                        .addTimelineEntry(
                            TimelineBuilders.TimelineEntry.Builder()
                                .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(layout(content)).build())
                                .build(),
                        )
                        .build(),
                )
                .build()
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        scope.future { ResourceBuilders.Resources.Builder().setVersion("1").build() }

    private fun layout(content: WearContent): LayoutElementBuilders.LayoutElement {
        val clickable = ModifiersBuilders.Clickable.Builder()
            .setId("open_app")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName("com.stocktracker.app")
                            .setClassName(MainActivity::class.java.name)
                            .build(),
                    )
                    .build(),
            )
            .build()
        val modifiers = ModifiersBuilders.Modifiers.Builder().setClickable(clickable).build()

        val accent = ColorBuilders.argb(
            (content as? WearContent.Ticker)?.accentArgb?.toInt() ?: 0xFFB4A0FF.toInt(),
        )
        val muted = ColorBuilders.argb(0xFFA09CB8.toInt())
        val onSurface = ColorBuilders.argb(0xFFFFFFFF.toInt())

        val headline = WearText.headline(content)
        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(WearText.title(content), 13f, accent, bold = true))
            .addContent(spacer(4f))

        if (headline != null) {
            column
                .addContent(text(headline, 22f, onSurface, bold = true))
                .addContent(spacer(2f))
            WearText.changeLine(content)?.let { column.addContent(text(it, 13f, muted)) }
        }
        WearText.statusLine(content)?.takeIf { headline == null || it != WearText.changeLine(content) }
            ?.let {
                column.addContent(spacer(2f))
                column.addContent(text(it, 11f, muted))
            }

        return LayoutElementBuilders.Box.Builder()
            .setModifiers(modifiers)
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(column.build())
            .build()
    }

    private fun text(value: String, sp: Float, color: ColorBuilders.ColorProp, bold: Boolean = false) =
        LayoutElementBuilders.Text.Builder()
            .setText(value)
            .setMaxLines(1)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(DimensionBuilders.sp(sp))
                    .setColor(color)
                    .apply { if (bold) setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD) }
                    .build(),
            )
            .build()

    private fun spacer(dp: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(dp)).build()
}
