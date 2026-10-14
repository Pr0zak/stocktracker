package com.stocktracker.app.widget

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
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
import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.notify.AlertNotifier
import com.stocktracker.app.ui.Routes
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.OnSurfaceDark
import com.stocktracker.app.ui.theme.OnSurfaceVariantDark
import com.stocktracker.app.util.Formatting
import com.stocktracker.shared.TickerDisplay
import com.stocktracker.shared.tickerDisplay

private val OnSurface = OnSurfaceDark
private val Muted = OnSurfaceVariantDark
private val Up = GainGreen
private val Down = LossRed

/** Two tickers side by side in a 4×1 strip. Each half opens its own detail screen. */
class PairWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: android.content.Context, id: GlanceId) {
        val (backgroundArgb, backgroundTransparency) = WidgetBackground.current()
        provideContent {
            val prefs = currentState<Preferences>()
            val config = PairWidgetState.readConfig(prefs)
            val hideZeroCents = prefs[PairWidgetState.HIDE_ZERO_CENTS] ?: false
            val accent = Color(config.accentArgb.toInt())
            Row(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .widgetBackground(backgroundArgb, backgroundTransparency)
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PairHalf(
                    slot = config.left,
                    quote = PairWidgetState.readQuote(prefs, PairWidgetState.QUOTE_LEFT),
                    error = prefs[PairWidgetState.ERROR_LEFT],
                    showChangePercent = config.showChangePercent,
                    hideZeroCents = hideZeroCents,
                    accent = accent,
                    modifier = GlanceModifier.defaultWeight(),
                )
                Box(
                    modifier = GlanceModifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .padding(vertical = 6.dp)
                        .background(Muted.copy(alpha = 0.3f)),
                ) {}
                PairHalf(
                    slot = config.right,
                    quote = PairWidgetState.readQuote(prefs, PairWidgetState.QUOTE_RIGHT),
                    error = prefs[PairWidgetState.ERROR_RIGHT],
                    showChangePercent = config.showChangePercent,
                    hideZeroCents = hideZeroCents,
                    accent = accent,
                    modifier = GlanceModifier.defaultWeight(),
                )
            }
        }
    }
}

@Composable
private fun PairHalf(
    slot: PairSlot,
    quote: Quote?,
    error: String?,
    showChangePercent: Boolean,
    hideZeroCents: Boolean,
    accent: Color,
    modifier: GlanceModifier,
    nowMs: Long = System.currentTimeMillis(),
) {
    val context = LocalContext.current
    // A stored quote for a symbol this side no longer tracks (mid-reconfigure) is someone else's price.
    val ownQuote = quote?.takeIf { it.symbol.equals(slot.symbol, ignoreCase = true) }
    val display = tickerDisplay(ownQuote, error, nowMs)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(horizontal = 10.dp)
            .clickable(actionStartActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(AlertNotifier.EXTRA_ROUTE, Routes.detail(slot.toAsset())),
            )),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = slot.symbol,
                style = TextStyle(color = ColorProvider(accent), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                text = ownQuote?.let { Formatting.price(it.price, it.currency, hideZeroCents) } ?: "—",
                style = TextStyle(color = ColorProvider(OnSurface), fontSize = 15.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.height(2.dp))
        when (display) {
            is TickerDisplay.Priced -> {
                val q = display.quote
                val up = q.isUp
                // An old move is not today's: when the age line shows, drop the green/red so the
                // colour doesn't assert a direction the data can no longer back up.
                val color = if (display.ageLabel != null) Muted else if (up) Up else Down
                val change = if (showChangePercent) {
                    "${Formatting.arrow(up)} ${Formatting.percent(q.changePercent)}"
                } else {
                    "${Formatting.arrow(up)} ${Formatting.change(q.change, hideZeroCents, reference = q.price)}"
                }
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = change,
                        style = TextStyle(color = ColorProvider(color), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    display.ageLabel?.let { label ->
                        Text(
                            text = label,
                            style = TextStyle(color = ColorProvider(Muted), fontSize = 10.sp),
                            maxLines = 1,
                        )
                    }
                }
            }
            is TickerDisplay.NoData -> Text(
                text = if (display.tapToOpen) "Couldn't load" else "Loading…",
                style = TextStyle(color = ColorProvider(Muted), fontSize = 11.sp),
                maxLines = 1,
            )
        }
    }
}
