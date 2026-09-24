package com.stocktracker.app.ui.components

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stocktracker.app.ui.theme.CryptoAccent
import com.stocktracker.app.ui.theme.EtfAccent
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.PriceMedium
import com.stocktracker.app.ui.theme.Signal
import com.stocktracker.app.ui.theme.NumberSmall
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
fun AssetRow(
    symbol: String,
    name: String,
    priceText: String,
    changeText: String,
    up: Boolean,
    sparkline: List<Double>,
    onClick: () -> Unit,
    /** Yesterday's close — the level `changeText` is measured from. Draws the sparkline's baseline. */
    previousClose: Double? = null,
    /** "41 sh" — the part that is never dropped. */
    holdingsShares: String? = null,
    /** "$15,397.96" — dropped whole when the row is too narrow for it. */
    holdingsValue: String? = null,
    isCrypto: Boolean = false,
    isEtf: Boolean = false,
    belowLine: Boolean = false,
    showDragHandle: Boolean = false,
    /** Starred — pinned to the Favorites section at the top of the watchlist. */
    favorite: Boolean = false,
    /** Null hides the star entirely, for the screens that render a row without the concept
     *  (detail, widgets, search results). The star is only meaningful where a list is grouped. */
    onToggleFavorite: (() -> Unit)? = null,
    /** Today's % change. Sets how strong the row's colour wash is; null draws no wash. */
    changePercent: Double? = null,
) {
    // Crypto takes precedence over ETF; equities get no accent.
    val accent: Color? = if (isCrypto) CryptoAccent else if (isEtf) EtfAccent else null
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            // 16dp all round put ~2.5 rows on a phone screen. Fintech users are scanning a list,
            // not reading it — the padding was costing a third of the viewport to whitespace nobody
            // looks at. Trimmed vertically (the horizontal gutter still needs to breathe), and the
            // company name moved up beside the ticker rather than taking a line of its own. Nothing
            // is dropped; the row just stops being three stacked lines when two will do.
            modifier = Modifier
                .fillMaxWidth()
                .moveWash(changePercent)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Leading accent stripe marks crypto (amber) and ETF (teal) rows for quick visual
            // separation from single equities.
            if (accent != null) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(28.dp)
                        .background(accent, RoundedCornerShape(2.dp)),
                )
                Spacer12()
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        symbol,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = accent ?: MaterialTheme.colorScheme.onSurface,
                    )
                    // Amber "below its 200-week line" marker — long-term value context, not a buy flag.
                    if (belowLine) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            // Below its 200-week (about 4-year) average — the long-term value marker.
                            "↓ 4-yr avg",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Signal,
                            modifier = Modifier
                                .background(Signal.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                    // Beside the ticker, not beneath it. It is a disambiguator, not a headline —
                    // it takes whatever width is left and ellipsizes rather than owning a line.
                    Spacer(Modifier.width(6.dp))
                    Text(
                        name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).padding(bottom = 1.dp),
                    )
                }
                if (holdingsShares != null) {
                    // "41 sh · $15,397.96" was one string at maxLines = 1, so a narrow row clipped
                    // it mid-value and left "41 sh ·" — a separator pointing at nothing, which is
                    // the half-legible row this component's own rules forbid. The value is dropped
                    // whole instead: measure it once, and if it does not fit, draw the shares alone.
                    var valueFits by remember(holdingsShares, holdingsValue) { mutableStateOf(true) }
                    val full = if (holdingsValue != null && valueFits) {
                        "$holdingsShares · $holdingsValue"
                    } else {
                        holdingsShares
                    }
                    Text(
                        full,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        onTextLayout = { if (it.hasVisualOverflow && valueFits) valueFits = false },
                    )
                }
            }

            if (sparkline.size >= 2) {
                Sparkline(
                    values = sparkline,
                    up = up,
                    previousClose = previousClose,
                    modifier = Modifier
                        .width(58.dp)
                        .height(26.dp),
                )
                Spacer12()
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(priceText, style = PriceMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    changeText,
                    // Mono and tabular, like the price it sits under. In bodySmall the two lines
                    // disagreed about where the digits were, so the change appeared to shuffle
                    // sideways under a price that stayed put.
                    style = NumberSmall,
                    color = if (up) GainGreen else LossRed,
                    fontWeight = FontWeight.Medium,
                    // A filled pill, narrow on purpose (6dp a side): this row is width-starved.
                    modifier = Modifier
                        .padding(top = 1.dp)
                        .background((if (up) GainGreen else LossRed).copy(alpha = 0.13f), RoundedCornerShape(50))
                        .padding(horizontal = 6.dp),
                )
            }
            // The star sits where the eye already ends up — after the price, at the trailing edge.
            // An outlined star for "not a favourite" rather than no icon at all: an absent control
            // cannot be discovered, and the whole feature depends on the user finding it once.
            if (onToggleFavorite != null) {
                // A 48dp target inside a 36dp slot.
                //
                // This is the most-rendered control in the app, it sits inside a row that is itself
                // tappable, and at 36dp a miss did not do nothing — it opened the detail screen.
                // But simply widening it to 48 took the 12dp out of the column beside it, which
                // truncated every company name and left the holdings line reading "41 sh ·" with
                // nothing after the separator. requiredSize ignores the parent's constraints, so
                // the button draws and receives touches at 48dp while the row still measures 36:
                // the target grows and the layout does not.
                Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.requiredSize(48.dp),
                    ) {
                        Icon(
                            if (favorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                            contentDescription = if (favorite) "Remove $symbol from favorites" else "Add $symbol to favorites",
                            tint = if (favorite) Signal else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // Drag-to-reorder affordance, shown only in the reorderable "All" tab. The whole row is
            // the long-press-drag handle; this icon makes that discoverable.
            if (showDragHandle) {
                Spacer12()
                Icon(
                    Icons.Default.DragHandle,
                    contentDescription = "Drag to reorder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Spacer12() {
    androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
}


/**
 * A faint wash of the day's colour from the row's trailing edge, stronger with the size of the move
 * (saturating at 3%), so a big day stands out before any number is read. Null or non-finite → none.
 */
private fun Modifier.moveWash(pct: Double?): Modifier {
    if (pct == null || !pct.isFinite()) return this
    val c = if (pct >= 0) GainGreen else LossRed
    val a = 0.04f + (kotlin.math.abs(pct) / 3.0).toFloat().coerceIn(0f, 1f) * 0.14f
    return drawBehind {
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, c.copy(alpha = a)), startX = size.width * 0.35f, endX = size.width))
    }
}
