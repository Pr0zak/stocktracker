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
import androidx.glance.currentState
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
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
import com.stocktracker.app.ui.theme.Signal

private val OnSurface = OnSurfaceDark
private val Muted = OnSurfaceVariantDark
private val Up = GainGreen
private val Down = LossRed

/** Home-screen tile showing total portfolio value + today's change across held positions. */
class PortfolioWidget : GlanceAppWidget() {

    override val stateDefinition = androidx.glance.state.PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: android.content.Context, id: GlanceId) {
        val (backgroundArgb, backgroundTransparency) = WidgetBackground.current()
        provideContent {
            val prefs = currentState<Preferences>()
            PortfolioContent(
                summary = PortfolioWidgetState.readSummary(prefs),
                loaded = prefs.contains(PortfolioWidgetState.SUMMARY),
                error = prefs[PortfolioWidgetState.ERROR],
                lastSuccessMs = prefs[PortfolioWidgetState.LAST_SUCCESS] ?: 0L,
                hideZeroCents = prefs[PortfolioWidgetState.HIDE_ZERO_CENTS] ?: false,
                backgroundArgb = backgroundArgb,
                backgroundTransparency = backgroundTransparency,
            )
        }
    }
}

@Composable
private fun PortfolioContent(
    summary: PortfolioSummary?,
    loaded: Boolean,
    error: String?,
    hideZeroCents: Boolean,
    lastSuccessMs: Long = 0L,
    nowMs: Long = System.currentTimeMillis(),
    backgroundArgb: Long = WidgetBackground.DEFAULT_ARGB,
    backgroundTransparency: Int = WidgetBackground.DEFAULT_TRANSPARENCY,
) {
    val context = LocalContext.current
    val display = portfolioDisplay(summary, loaded, error, lastSuccessMs, nowMs)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .widgetBackground(backgroundArgb, backgroundTransparency)
            .padding(14.dp)
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
    ) {
        Text(
            text = "Portfolio",
            style = TextStyle(color = ColorProvider(Muted), fontSize = 12.sp, fontWeight = FontWeight.Medium),
        )
        Spacer(GlanceModifier.height(4.dp))
        when (display) {
            is PortfolioDisplay.Priced -> {
                val priced = display.summary
                Text(
                    text = Formatting.price(priced.totalValue, hideZeroCents = hideZeroCents),
                    style = TextStyle(color = ColorProvider(OnSurface), fontSize = 22.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    text = "${Formatting.arrow(priced.isUp)} " +
                        "${Formatting.change(priced.dayChange, hideZeroCents)} (${Formatting.percent(priced.dayChangePercent)})",
                    style = TextStyle(
                        color = ColorProvider(if (priced.isUp) Up else Down),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
                // Only part of the portfolio is in the number above — say so in the same amber the
                // rest of the app uses for "this figure is not the whole story".
                display.partialLabel?.let { label ->
                    Text(
                        text = label,
                        style = TextStyle(color = ColorProvider(Signal), fontSize = 11.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1,
                    )
                }
                display.ageLabel?.let { label ->
                    Text(
                        text = label,
                        style = TextStyle(color = ColorProvider(Muted), fontSize = 10.sp),
                        maxLines = 1,
                    )
                }
            }
            is PortfolioDisplay.Message -> Message(display.text)
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text = text, style = TextStyle(color = ColorProvider(Muted), fontSize = 12.sp))
}
