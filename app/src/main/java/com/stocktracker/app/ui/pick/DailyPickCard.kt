package com.stocktracker.app.ui.pick

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.remote.DailyPickResponse
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.PriceSmall
import com.stocktracker.app.ui.theme.Signal
import com.stocktracker.app.ui.watchlist.GateRead
import com.stocktracker.app.ui.watchlist.GateVerdict
import com.stocktracker.app.ui.watchlist.MarketChecksDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * DP-5 — the Daily Pick at the top of the Watchlist tab. Collapsible; the collapsed state is remembered.
 *
 * Every state renders as ITSELF: a failed load says it failed (and shows no pick), yesterday's pick is
 * headed with yesterday's date, a server-side failure is not dressed up as "no pick today", and the card
 * always says when the pick was made and how old its price is.
 */
@Composable
fun DailyPickCard(onOpenSymbol: (symbol: String, name: String?) -> Unit, onOpenSettings: () -> Unit) {
    val vm: DailyPickViewModel = viewModel()
    val state by vm.state.collectAsState()
    val collapsed by ServiceLocator.settingsStore.dailyPickCardCollapsed.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var boughtOpen by rememberSaveable { mutableStateOf(false) }
    val explain = rememberExplainState()
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }
    // The pick is fixed for the day; this only keeps the live price and the "in zone" read current.
    LaunchedEffect(Unit) {
        while (true) { delay(120_000); vm.load() }
    }

    val shape = state.shape
    if (shape is DailyPickRead.Shape.NotConfigured) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        HeaderRow(shape, state, collapsed, nowMs,
            onToggle = { scope.launch { ServiceLocator.settingsStore.setDailyPickCardCollapsed(!collapsed) } },
            onRefresh = { vm.load() })
        if (!collapsed) {
            when (shape) {
                is DailyPickRead.Shape.Pick -> PickBody(
                    shape.resp, state, explain,
                    onWhy = { sheetOpen = true; vm.loadHistory() },
                    onBought = { boughtOpen = true },
                    onOpenSymbol = onOpenSymbol,
                )
                is DailyPickRead.Shape.NoPick -> NoPickBody(shape.resp, onWhy = { sheetOpen = true; vm.loadHistory() }, onOpenSymbol)
                is DailyPickRead.Shape.RunFailed -> Text(
                    "The pick could not be made: ${shape.error}", style = MaterialTheme.typography.bodyMedium, color = LossRed,
                )
                is DailyPickRead.Shape.LoadFailed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(shape.message, style = MaterialTheme.typography.bodyMedium, color = LossRed)
                    TextButton(onClick = { vm.load() }) { Text("Try again") }
                }
                is DailyPickRead.Shape.NeverRun -> Column {
                    Text(shape.reason, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onOpenSettings) { Text("Daily pick settings") }
                }
                DailyPickRead.Shape.Loading -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("Loading today's pick…", style = MaterialTheme.typography.bodyMedium)
                }
                DailyPickRead.Shape.NotConfigured -> Unit
            }
        }
    }

    if (sheetOpen) {
        val resp = (shape as? DailyPickRead.Shape.Pick)?.resp ?: (shape as? DailyPickRead.Shape.NoPick)?.resp
        if (resp != null) {
            DailyPickSheet(resp, state, explain, onDismiss = { sheetOpen = false }, onOpenSymbol = onOpenSymbol)
        }
    }
    if (boughtOpen) {
        val resp = (shape as? DailyPickRead.Shape.Pick)?.resp
        if (resp != null) {
            BoughtDialog(resp, onDismiss = { boughtOpen = false }, onConfirm = { sh, px -> vm.logBought(sh, px); boughtOpen = false })
        }
    }
    ExplainDialog(explain.key, explain.title, onDismiss = { explain.dismiss() })
}

@Composable
private fun HeaderRow(
    shape: DailyPickRead.Shape,
    state: DailyPickUiState,
    collapsed: Boolean,
    nowMs: Long,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val resp = (shape as? DailyPickRead.Shape.Pick)?.resp ?: (shape as? DailyPickRead.Shape.NoPick)?.resp
    val stale = (shape as? DailyPickRead.Shape.Pick)?.stale == true || (shape as? DailyPickRead.Shape.NoPick)?.stale == true ||
        (shape as? DailyPickRead.Shape.RunFailed)?.stale == true
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                DailyPickRead.header(shape),
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = if (stale) Signal else neutral,
                modifier = Modifier.semantics { heading() },
            )
            val sub = buildList {
                resp?.ts?.let { DailyPickRead.pickedAt(it) }?.let { add(it) }
                if (resp?.isPick == true) add(DailyPickRead.priceAge(resp.live?.quoteTs, nowMs))
                if (collapsed && resp?.isPick == true) resp.pick?.symbol?.let { add(it) }
            }.joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.labelSmall, color = neutral)
            if (stale) Text(DailyPickRead.STALE_NOTE, style = MaterialTheme.typography.labelSmall, color = Signal)
        }
        if (state.loading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onRefresh, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh today's pick", tint = neutral, modifier = Modifier.size(18.dp))
            }
        }
        Icon(
            if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
            contentDescription = if (collapsed) "Expand today's pick" else "Collapse today's pick", tint = neutral,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickBody(
    resp: DailyPickResponse,
    state: DailyPickUiState,
    explain: ExplainState,
    onWhy: () -> Unit,
    onBought: () -> Unit,
    onOpenSymbol: (String, String?) -> Unit,
) {
    val p = resp.pick ?: return
    val sym = p.symbol ?: return
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val price = resp.live?.price
    val change = resp.live?.changePct

    // Symbol, name, live price.
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onOpenSymbol(sym, p.name) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.clickable(onClickLabel = "What confidence means") { explain.show("conviction", "Confidence") }) {
            ConvictionRing(p.conviction)
            Text("confidence ⓘ", style = MaterialTheme.typography.labelSmall, color = neutral)
        }
        Column(Modifier.weight(1f)) {
            Text(sym, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            p.name?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = neutral, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(DailyPickRead.money(price), style = PriceSmall)
            change?.let {
                Text(String.format(Locale.US, "%+.2f%%", it), style = MaterialTheme.typography.labelMedium,
                    color = if (it >= 0) GainGreen else LossRed)
            }
        }
    }
    ContextChips(resp)

    p.thesis?.takeIf { it.isNotBlank() }?.let {
        Text("“$it”", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
    }

    // WHY — at most four on the collapsed card, at least one against when there is one.
    val factors = p.factors.associateBy { it.key }
    val forR = p.reasons.filter { it.supports }
    val againstR = p.reasons.filterNot { it.supports }
    val shown = (forR.take(3) + againstR.take(1)).take(4)
    Text("WHY", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = neutral)
    for (r in shown) {
        FactorRow(
            supports = r.supports, factor = factors[r.factor], fallbackLabel = r.factor, text = r.text,
            onExplain = { explain.show(r.factor, factors[r.factor]?.label ?: r.factor) },
            // The card shows the measured reading only; a factor with no reading falls back to the
            // AI's sentence so the row is never empty.
            showText = factors[r.factor]?.display.isNullOrBlank(),
        )
    }

    // PLAN
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("PLAN", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = neutral)
        InfoButton("the plan") { explain.show("ladder", "The plan") }
    }
    PlanLadder(p.levels, price)
    val risk = DailyPickRead.riskLine(p.riskReward?.riskPerShare, p.riskReward?.rewardPerShare, p.riskReward?.rrRatio)
    val chase = DailyPickRead.chaseLabel(resp.chase?.status, resp.chase?.pct)
    listOfNotNull(risk, chase).takeIf { it.isNotEmpty() }?.let {
        Text(it.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
            color = if (resp.chase?.status == "chase_too_deep") LossRed else neutral)
    }
    if (p.levels?.entryHigh == null && p.levels?.stop == null && p.levels?.target == null) {
        Text("The AI gave no price levels it could back up with the data.", style = MaterialTheme.typography.bodySmall, color = neutral)
    }

    // Track record, fit, repeats.
    DailyPickRead.trackRecordLine(p.trackRecord)?.let {
        val thin = (p.trackRecord?.n ?: 0) < DailyPickRead.MIN_TRUSTED_N
        Text(it + if (thin) " — too few to lean on" else "", style = MaterialTheme.typography.bodySmall,
            color = if (thin) neutral.copy(alpha = 0.6f) else neutral)
    }
    state.fit?.sentence?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = neutral) }
        ?: state.fitError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = neutral) }
    DailyPickRead.repeatLine(resp.repeats?.symbol, sym)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = Signal)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onWhy) { Text("Why · details") }
        val logged = state.loggedKey == "${resp.date}|$sym"
        if (resp.stale != true) {
            TextButton(onClick = onBought, enabled = !logged) { Text(if (logged) "Logged ✓" else "I bought it") }
        }
    }
    state.journalNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GainGreen) }
    Text("A reading, not advice. Nothing here places a trade.", style = MaterialTheme.typography.labelSmall,
        color = neutral.copy(alpha = 0.7f))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContextChips(resp: DailyPickResponse) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    var checksOpen by rememberSaveable { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // The market checks, worded by the same reader the Watchlist's gate card uses, and tappable
        // for a summary of all five. Colour follows the verdict; an unmeasured check is never red.
        val g = resp.gate
        val summary = g?.let { GateRead.summary(it.toGateResponse()) }
        val color = when (summary?.verdict) {
            GateVerdict.OPEN -> GainGreen
            GateVerdict.SHUT -> Signal
            GateVerdict.UNMEASURED -> Signal
            else -> neutral
        }
        PickChip(summary?.chip ?: "Market checks unavailable", color,
            onClick = if (g != null) ({ checksOpen = true }) else null)
        // Only a pick has an earnings date to know. On a no-pick day there is nothing to be unknown about.
        val e = resp.pick?.earnings
        if (resp.isPick) when {
            e == null || !e.ok -> PickChip("earnings date unknown", Signal)
            e.date != null -> PickChip("earnings ${DailyPickRead.shortDate(e.date)}", if ((e.sessions ?: 99) <= 10) Signal else neutral)
            else -> PickChip("no earnings this week", neutral)
        }
        if (resp.macroAvailable == false) PickChip("news backdrop unknown", Signal)
        DailyPickRead.scanLagNote(resp)?.let { PickChip("based on an older close", Signal) }
    }
    val g = resp.gate
    if (checksOpen && g != null) {
        MarketChecksDialog(
            g.toGateResponse(),
            footer = if (g.passed == false) "Because a check fails, today's pick needed a confidence of 70 instead of 60." else null,
            onDismiss = { checksOpen = false },
        )
    }
}

@Composable
private fun NoPickBody(resp: DailyPickResponse, onWhy: () -> Unit, onOpenSymbol: (String, String?) -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Text(resp.noneReason ?: "Nothing was convincing enough today.", style = MaterialTheme.typography.bodyMedium)
    ContextChips(resp)
    resp.pick?.closest?.let { c ->
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onOpenSymbol(c.symbol, c.name) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Closest: ", style = MaterialTheme.typography.bodySmall, color = neutral)
            Text(c.symbol, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = neutral)
            c.price?.let { Text("  ${DailyPickRead.money(it)}", style = MaterialTheme.typography.bodySmall, color = neutral) }
        }
    }
    resp.pick?.rejectedConviction?.let {
        Text("Its confidence was $it out of 100; today it needed ${resp.pick.convictionFloor ?: 60}.",
            style = MaterialTheme.typography.bodySmall, color = neutral)
    }
    TextButton(onClick = onWhy) { Text("What came close · past picks") }
}
