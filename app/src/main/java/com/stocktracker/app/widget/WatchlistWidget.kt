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
import com.stocktracker.app.util.Formatting
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.OnSurfaceDark
import com.stocktracker.app.ui.theme.OnSurfaceVariantDark

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
                loaded = prefs.contains(WatchlistWidgetState.ROWS),
                error = prefs[WatchlistWidgetState.ERROR],
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
    loaded: Boolean,
    error: String?,
    hideZeroCents: Boolean,
    backgroundArgb: Long = WidgetBackground.DEFAULT_ARGB,
    backgroundTransparency: Int = WidgetBackground.DEFAULT_TRANSPARENCY,
) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .widgetBackground(backgroundArgb, backgroundTransparency)
            .padding(14.dp)
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
    ) {
        Text(
            text = "Watchlist",
            style = TextStyle(color = ColorProvider(OnSurface), fontSize = 14.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(GlanceModifier.height(6.dp))
        when {
            rows.isNotEmpty() -> rows.take(6).forEach { row -> WatchlistRowItem(row, hideZeroCents) }
            error != null -> Message(error)
            !loaded -> Message("Loading…")
            else -> Message("Add tickers in the app")
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text = text, style = TextStyle(color = ColorProvider(Muted), fontSize = 12.sp))
}

@Composable
private fun WatchlistRowItem(row: WatchlistRow, hideZeroCents: Boolean) {
    val color = if (row.isUp) Up else Down
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
