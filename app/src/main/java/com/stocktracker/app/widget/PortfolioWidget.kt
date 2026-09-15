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
            text = "Portfolio",
            style = TextStyle(color = ColorProvider(Muted), fontSize = 12.sp, fontWeight = FontWeight.Medium),
        )
        Spacer(GlanceModifier.height(4.dp))
        when {
            summary != null && summary.holdingCount > 0 -> {
                Text(
                    text = Formatting.price(summary.totalValue, hideZeroCents = hideZeroCents),
                    style = TextStyle(color = ColorProvider(OnSurface), fontSize = 22.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    text = "${Formatting.arrow(summary.isUp)} " +
                        "${Formatting.change(summary.dayChange, hideZeroCents)} (${Formatting.percent(summary.dayChangePercent)})",
                    style = TextStyle(
                        color = ColorProvider(if (summary.isUp) Up else Down),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
            }
            error != null -> Message(error)
            !loaded -> Message("Loading…")
            else -> Message("Set shares on a ticker to track value")
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text = text, style = TextStyle(color = ColorProvider(Muted), fontSize = 12.sp))
}
