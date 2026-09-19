package com.stocktracker.app.widget

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.stocktracker.app.MainActivity
import com.stocktracker.app.notify.AlertNotifier
import com.stocktracker.app.ui.Routes
import com.stocktracker.app.util.Formatting
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.OnSurfaceDark
import com.stocktracker.app.ui.theme.OnSurfaceVariantDark
import com.stocktracker.app.ui.theme.Signal

private val OnSurface = OnSurfaceDark
private val Muted = OnSurfaceVariantDark
private val Up = GainGreen
private val Down = LossRed

class WatchlistWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: android.content.Context, id: GlanceId) {
        val (backgroundArgb, backgroundTransparency) = WidgetBackground.current()
        provideContent {
            val prefs = currentState<Preferences>()
            WatchlistContent(
                rows = WatchlistWidgetState.readRows(prefs),
                expectedCount = prefs[WatchlistWidgetState.EXPECTED_COUNT] ?: 0,
                loaded = prefs.contains(WatchlistWidgetState.ROWS),
                error = prefs[WatchlistWidgetState.ERROR],
                lastSuccessMs = prefs[WatchlistWidgetState.LAST_SUCCESS] ?: 0L,
                hideZeroCents = prefs[WatchlistWidgetState.HIDE_ZERO_CENTS] ?: false,
                backgroundArgb = backgroundArgb,
                backgroundTransparency = backgroundTransparency,
            )
        }
    }
}

@Composable
private fun WatchlistContent(
    rows: List<WatchlistRow>,
    expectedCount: Int,
    loaded: Boolean,
    error: String?,
    hideZeroCents: Boolean,
    lastSuccessMs: Long = 0L,
    nowMs: Long = System.currentTimeMillis(),
    backgroundArgb: Long = WidgetBackground.DEFAULT_ARGB,
    backgroundTransparency: Int = WidgetBackground.DEFAULT_TRANSPARENCY,
) {
    val context = LocalContext.current
    val heightDp = LocalSize.current.height.value
    val display = watchlistDisplay(rows, expectedCount, error, loaded, heightDp, lastSuccessMs, nowMs)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .widgetBackground(backgroundArgb, backgroundTransparency)
            .padding(14.dp)
            .clickable(actionStartActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(AlertNotifier.EXTRA_ROUTE, Routes.WATCHLIST),
            )),
    ) {
        Text(
            text = "Watchlist",
            style = TextStyle(color = ColorProvider(OnSurface), fontSize = 14.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(GlanceModifier.height(6.dp))
        when (display) {
            is WatchlistDisplay.Rows -> {
                display.visible.forEach { row -> WatchlistRowItem(row, hideZeroCents, nowMs) }
                // A partial load (or a truncated list) must not read as the complete, current
                // watchlist -- the same amber the rest of the app uses for "not the whole story".
                display.footerLabel?.let { label ->
                    Spacer(GlanceModifier.height(4.dp))
                    Text(
                        text = label,
                        style = TextStyle(color = ColorProvider(Signal), fontSize = 11.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                    )
                }
            }
            is WatchlistDisplay.Message -> Message(display.text)
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text = text, style = TextStyle(color = ColorProvider(Muted), fontSize = 12.sp))
}

@Composable
private fun WatchlistRowItem(row: WatchlistRow, hideZeroCents: Boolean, nowMs: Long) {
    // A stale row's move is not today's -- drop the confident green/red rather than assert a
    // direction the data can no longer back up. Mirrors TickerWidgetState's age handling.
    val stale = watchlistRowIsStale(row, nowMs)
    val color = if (stale) Muted else if (row.isUp) Up else Down
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = row.symbol,
                style = TextStyle(color = ColorProvider(OnSurface), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
        Text(
            text = Formatting.price(row.price, row.currency, hideZeroCents),
            style = TextStyle(color = ColorProvider(OnSurface), fontSize = 13.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
        Spacer(GlanceModifier.width(10.dp))
        Text(
            text = "${Formatting.arrow(row.isUp)} ${Formatting.percent(row.changePercent)}",
            style = TextStyle(color = ColorProvider(color), fontSize = 12.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
    }
}
