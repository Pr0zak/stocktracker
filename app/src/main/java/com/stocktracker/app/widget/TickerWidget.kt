package com.stocktracker.app.widget

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.LocalContext
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.stocktracker.app.MainActivity
import com.stocktracker.app.notify.AlertNotifier
import com.stocktracker.app.ui.Routes
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.util.Formatting
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.OnSurfaceDark
import com.stocktracker.app.ui.theme.OnSurfaceVariantDark
import com.stocktracker.shared.TickerDisplay
import com.stocktracker.shared.tickerDisplay

private val OnSurface = OnSurfaceDark
private val Muted = OnSurfaceVariantDark
private val Up = GainGreen
private val Down = LossRed

class TickerWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: android.content.Context, id: GlanceId) {
        val (backgroundArgb, backgroundTransparency) = WidgetBackground.current()
        provideContent {
            val prefs = currentState<Preferences>()
            TickerContent(
                config = TickerWidgetState.readConfig(prefs),
                quote = TickerWidgetState.readQuote(prefs),
                spark = TickerWidgetState.readSpark(prefs),
                error = prefs[TickerWidgetState.ERROR],
                hideZeroCents = prefs[TickerWidgetState.HIDE_ZERO_CENTS] ?: false,
                backgroundArgb = backgroundArgb,
                backgroundTransparency = backgroundTransparency,
            )
        }
    }
}

@Composable
private fun TickerContent(
    config: TickerWidgetConfig,
    quote: Quote?,
    spark: List<Double>,
    error: String?,
    hideZeroCents: Boolean,
    nowMs: Long = System.currentTimeMillis(),
    backgroundArgb: Long = WidgetBackground.DEFAULT_ARGB,
    backgroundTransparency: Int = WidgetBackground.DEFAULT_TRANSPARENCY,
) {
    val context = LocalContext.current
    val accent = Color(config.accentArgb.toInt())
    val display = tickerDisplay(quote, error, nowMs)
    val up = quote?.isUp ?: true
    val changeColor = if (up) Up else Down

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .widgetBackground(backgroundArgb, backgroundTransparency)
            .padding(12.dp)
            .clickable(actionStartActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(AlertNotifier.EXTRA_ROUTE, Routes.detail(config.toAsset())),
            )),
        verticalAlignment = Alignment.Top,
    ) {
        if (config.showName) {
            Text(
                text = config.displayName,
                style = TextStyle(color = ColorProvider(Muted), fontSize = 11.sp),
                maxLines = 1,
            )
        }
        Text(
            text = config.symbol,
            style = TextStyle(color = ColorProvider(accent), fontSize = 15.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        Text(
            text = quote?.let { Formatting.price(it.price, it.currency, hideZeroCents) } ?: "—",
            style = TextStyle(color = ColorProvider(OnSurface), fontSize = 22.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        if (display is TickerDisplay.Priced) {
            val priced = display.quote
            val changeStr = if (config.showChangePercent) {
                "${Formatting.arrow(up)} ${Formatting.percent(priced.changePercent)}"
            } else {
                "${Formatting.arrow(up)} ${Formatting.change(priced.change, hideZeroCents, reference = priced.price)}"
            }
            Text(
                text = changeStr,
                style = TextStyle(color = ColorProvider(changeColor), fontSize = 13.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            // A widget that can't refresh used to keep rendering its last payload indefinitely with
            // no cue — the error state was only reachable when there was NO cached data at all, i.e.
            // never after the first successful fetch. Say when the number stopped being current, and
            // say plainly when the reason is a failed refresh rather than routine staleness.
            display.ageLabel?.let { label ->
                Text(
                    text = label,
                    style = TextStyle(color = ColorProvider(Muted), fontSize = 10.sp),
                    maxLines = 1,
                )
            }
        } else {
            Text(
                text = if ((display as TickerDisplay.NoData).tapToOpen) "Tap to open" else "Loading…",
                style = TextStyle(color = ColorProvider(Muted), fontSize = 11.sp),
                maxLines = 1,
            )
        }

        if (config.showSparkline && spark.size >= 2) {
            Spacer(GlanceModifier.height(6.dp))
            val colorArgb = (if (up) Up else Down).toArgbInt()
            // prevClose is part of the memo key, not just an argument: it changes at the session
            // rollover while the series and direction can both stay put, and a stale bitmap would
            // draw the baseline at yesterday's level — the same class of contradiction the baseline
            // exists to remove.
            val prevClose = quote?.prevClose
            val bmp = remember(spark, up, prevClose) {
                SparklineRenderer.render(
                    spark,
                    widthPx = 320,
                    heightPx = 96,
                    colorArgb = colorArgb,
                    previousClose = prevClose,
                )
            }
            Image(
                provider = ImageProvider(bmp),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = GlanceModifier.fillMaxWidth().height(36.dp),
            )
        }
    }
}

private fun Color.toArgbInt(): Int {
    val a = (alpha * 255).toInt() shl 24
    val r = (red * 255).toInt() shl 16
    val g = (green * 255).toInt() shl 8
    val b = (blue * 255).toInt()
    return a or r or g or b
}
