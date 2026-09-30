package com.stocktracker.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stocktracker.app.data.remote.CompanyProfile
import com.stocktracker.app.ui.components.Pill
import com.stocktracker.app.ui.components.Skeleton
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.Indigo
import com.stocktracker.app.ui.theme.LossRed
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ABOUT-1 — the detail screen's About tab: what this company is, in plain words.
 *
 * A ticker like VICR said nothing to someone who had not met it, and the screen never said what the
 * company does. This leads with one plain sentence (written once per company by the AI from its own
 * description, and marked as such), its market as two pills, who buys from it, four key figures named
 * in everyday words, and the company's own description one tap away.
 *
 * Figures the profile lacks are left out, never drawn as zero. With the AI switch off, or before the
 * plain line exists, the first sentence of the company's own description stands in, unmarked.
 */
@Composable
internal fun AboutTab(lens: Lens<CompanyProfile>, onRetry: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        lens.status == LensStatus.LOADING -> Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Skeleton(Modifier.width(180.dp).height(22.dp))
            Skeleton(Modifier.fillMaxWidth().height(40.dp))
            Skeleton(Modifier.fillMaxWidth().height(64.dp))
        }
        lens.isFailed -> Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
        ) {
            Text("Couldn't load what this company is.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry) { Text("Retry") }
        }
        lens.status == LensStatus.IDLE -> Text(
            "Set up the signals service to see what this company is.",
            style = MaterialTheme.typography.bodyMedium, color = muted,
        )
        lens.value == null -> Text(
            "No company profile for this symbol.", style = MaterialTheme.typography.bodyMedium, color = muted,
        )
        else -> ProfileBody(lens.value)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileBody(p: CompanyProfile) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val lead = AboutText.lead(p)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AboutText.market(p).forEachIndexed { i, m -> Pill(m, if (i == 0) Indigo else MARKET_BLUE) }
            }
            if (p.whatItDoes != null) {
                Text("AI summary", style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
        if (lead != null) Text(lead, style = MaterialTheme.typography.bodyLarge)
        p.customers?.let { Text("Customers: $it", style = MaterialTheme.typography.bodySmall, color = muted) }
    }

    val tiles = AboutText.tiles(p)
    if (tiles.isNotEmpty()) {
        tiles.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { t -> StatTile(t, Modifier.weight(1f)) }
                if (pair.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            }
        }
    }

    var full by rememberSaveable(p.symbol) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AboutText.factsLine(p)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
        if (p.summary != null) {
            Text(
                if (full) "Hide the company's description" else "The company's own description ›",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { full = !full }.padding(vertical = 6.dp),
            )
            if (full) {
                Text(p.summary, style = MaterialTheme.typography.bodyMedium)
                p.website?.let {
                    Text(it.removePrefix("https://").removePrefix("http://"), style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }
    }
}

@Composable
private fun StatTile(t: AboutText.Tile, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(t.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(t.value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
             color = t.color ?: MaterialTheme.colorScheme.onSurface)
        t.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private val MARKET_BLUE = Color(0xFF4C7CE0)

/** Pure wording for the About tab, tested without a screen. */
internal object AboutText {
    data class Tile(val label: String, val value: String, val note: String? = null, val color: Color? = null)

    /** Sector › industry for a company, or the category for a fund. Sentence case, as the app writes. */
    fun market(p: CompanyProfile): List<String> =
        listOfNotNull(p.sector, p.industry).ifEmpty { listOfNotNull(p.category) }
            .map { it.lowercase(Locale.US).replaceFirstChar { c -> c.titlecase(Locale.US) } }

    /** The plain line, else the first sentence of the company's own description. */
    fun lead(p: CompanyProfile): String? =
        p.whatItDoes ?: p.summary?.let { s ->
            val cut = Regex("""(?<=[a-z0-9)])\. (?=[A-Z])""").find(s)?.range?.first
            if (cut != null) s.substring(0, cut + 1) else s
        }

    fun tiles(p: CompanyProfile): List<Tile> = buildList {
        p.marketCap?.let { add(Tile("Company size", "$" + compact(it))) }
        p.revenueGrowthPct?.let {
            add(Tile("Sales growth", signed(it, 0) + "%", "vs a year ago", if (it >= 0) GainGreen else LossRed))
        }
        p.profitMarginPct?.let {
            val cents = it.roundToInt()
            add(
                if (cents >= 0) Tile("Kept as profit", "${cents}¢", "per $1 of sales")
                else Tile("Losing money", "${abs(cents)}¢", "lost per $1 of sales", LossRed),
            )
        }
        // A P/E only exists with a profit; a loss-maker has none rather than a negative one.
        p.pe?.takeIf { it > 0 }?.let { add(Tile("Price per $1 of profit", "$" + it.roundToInt(), "a year's profit")) }
        p.dividendYieldPct?.takeIf { it > 0 }?.let { add(Tile("Dividend", String.format(Locale.US, "%.1f%%", it), "a year")) }
    }

    /** "Andover, MA · 1,092 staff · since 1981", whatever of it is known. */
    fun factsLine(p: CompanyProfile): String? {
        val place = listOfNotNull(p.city, p.state ?: p.country?.takeIf { it != "United States" }).joinToString(", ")
        val parts = listOfNotNull(
            place.ifBlank { null },
            p.employees?.let { String.format(Locale.US, "%,d staff", it) },
            p.founded?.let { "since $it" },
            p.exchange,
        )
        return parts.joinToString(" · ").ifBlank { null }
    }

    private fun compact(v: Double): String = when {
        v >= 1e12 -> String.format(Locale.US, "%.1fT", v / 1e12)
        v >= 1e9 -> String.format(Locale.US, "%.1fB", v / 1e9)
        v >= 1e6 -> String.format(Locale.US, "%.0fM", v / 1e6)
        else -> String.format(Locale.US, "%,.0f", v)
    }

    private fun signed(v: Double, decimals: Int): String =
        (if (v >= 0) "+" else "−") + String.format(Locale.US, "%.${decimals}f", abs(v))
}
