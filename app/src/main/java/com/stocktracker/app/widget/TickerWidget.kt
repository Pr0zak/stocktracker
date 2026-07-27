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
import androidx.glance.background
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
import com.stocktracker.app.R
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.util.Formatting

private val OnSurface = Color(0xFFE6E1E9)
private val Muted = Color(0xFFCAC4D3)
private val Up = Color(0xFF4ADE80)
private val Down = Color(0xFFF87171)

class TickerWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: android.content.Context, id: GlanceId) {
        provideContent {
            val prefs = currentState<Preferences>()
            TickerContent(
                config = TickerWidgetState.readConfig(prefs),
                quote = TickerWidgetState.readQuote(prefs),
                spark = TickerWidgetState.readSpark(prefs),
                error = prefs[TickerWidgetState.ERROR],
                lastSuccessMs = prefs[TickerWidgetState.LAST_SUCCESS] ?: 0L,
                hideZeroCents = prefs[TickerWidgetState.HIDE_ZERO_CENTS] ?: false,
            )
        }
    }
}

/** Beyond this the displayed price is no longer "now" and the widget says so. */
private const val STALE_AFTER_MS = 45L * 60 * 1000

private fun staleLabel(ageMs: Long): String {
    val mins = ageMs / 60_000
    return when {
        mins < 120 -> "${mins}m ago"
        mins < 60 * 48 -> "${mins / 60}h ago"
        else -> "${mins / (60 * 24)}d ago"
    }
}

@Composable
private fun TickerContent(
    config: TickerWidgetConfig,
    quote: Quote?,
    spark: List<Double>,
    error: String?,
    lastSuccessMs: Long = 0L,
    hideZeroCents: Boolean,
) {
    val context = LocalContext.current
    val accent = Color(config.accentArgb.toInt())
    val up = quote?.isUp ?: true
    val changeColor = if (up) Up else Down

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.widget_background))
            .padding(12.dp)
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
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
        if (quote != null) {
            val changeStr = if (config.showChangePercent) {
                "${Formatting.arrow(up)} ${Formatting.percent(quote.changePercent)}"
            } else {
                "${Formatting.arrow(up)} ${Formatting.change(quote.change, hideZeroCents)}"
            }
            Text(
                text = changeStr,
                style = TextStyle(color = ColorProvider(changeColor), fontSize = 13.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            // A widget that can't refresh kept rendering its last payload indefinitely with no cue —
            // the error state was only reachable when there was NO cached data at all, i.e. never
            // after the first successful fetch. Say when the number stopped being current.
            val ageMs = if (lastSuccessMs > 0L) System.currentTimeMillis() - lastSuccessMs else 0L
            if (ageMs > STALE_AFTER_MS) {
                Text(
                    text = "as of " + staleLabel(ageMs),
                    style = TextStyle(color = ColorProvider(Muted), fontSize = 10.sp),
                    maxLines = 1,
                )
            }
        } else {
            Text(
                text = if (error != null) "Tap to open" else "Loading…",
                style = TextStyle(color = ColorProvider(Muted), fontSize = 11.sp),
                maxLines = 1,
            )
        }

        if (config.showSparkline && spark.size >= 2) {
            Spacer(GlanceModifier.height(6.dp))
            val colorArgb = (if (up) Up else Down).toArgbInt()
            val bmp = remember(spark, up) {
                SparklineRenderer.render(spark, widthPx = 320, heightPx = 96, colorArgb = colorArgb)
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
