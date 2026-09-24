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
import androidx.compose.material3.Button
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
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
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

    // The glow is the day's verdict: green for a fresh pick, gold for a fresh no-pick day. A stale,
    // failed or loading card gets no glow — it has not earned a mood.
    val glow = when {
        shape is DailyPickRead.Shape.Pick && !shape.stale -> GainGreen
        shape is DailyPickRead.Shape.NoPick && !shape.stale -> ZoneGold
        else -> null
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .spotlightGlow(glow)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeaderRow(shape, state, collapsed, nowMs,
            onToggle = { scope.launch { ServiceLocator.settingsStore.setDailyPickCardCollapsed(!collapsed) } },
            onRefresh = { vm.load() })
        if (!collapsed) {
            when (shape) {
                is DailyPickRead.Shape.Pick -> {
                    PickBody(
                        shape.resp, state, explain,
                        onWhy = { sheetOpen = true; vm.loadHistory() },
                        onBought = { boughtOpen = true },
                        onOpenSymbol = onOpenSymbol,
                    )
                    if (!shape.stale) RecheckSection(shape.resp, state, explain, onRecheck = { vm.recheck() }, onOpenSymbol)
                }
                is DailyPickRead.Shape.NoPick -> {
                    NoPickBody(shape.resp, onWhy = { sheetOpen = true; vm.loadHistory() }, onOpenSymbol)
                    if (!shape.stale) RecheckSection(shape.resp, state, explain, onRecheck = { vm.recheck() }, onOpenSymbol)
                }
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
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
                color = if (stale) Signal else neutral,
                modifier = Modifier.semantics { heading() },
            )
            val sub = buildList {
                resp?.ts?.let { DailyPickRead.pickedAt(it) }?.let { add(it) }
                if (resp?.isPick == true) add(DailyPickRead.priceAge(resp.live?.quoteTs, nowMs))
                if (collapsed && resp?.isPick == true) resp.pick?.symbol?.let { add(it) }
            }.joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.labelSmall, color = neutral)
            if (stale) Text(DailyPickRead.staleNote(), style = MaterialTheme.typography.labelSmall, color = Signal)
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

/**
 * The pick, kept to what fits a glance: who, the verdict in one line, three factor bars, the plan
 * line, and two buttons. Everything else — the track record, how it fits your portfolio, the full
 * thesis — sits behind "More" or in the Details sheet, so the card never becomes a page.
 */
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
    var more by rememberSaveable(resp.date, sym) { mutableStateOf(false) }

    // Who, big, with the confidence dial beside it.
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onOpenSymbol(sym, p.name) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(sym, fontSize = 34.sp, fontWeight = FontWeight.Black, lineHeight = 36.sp, letterSpacing = (-0.5).sp)
            p.name?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = neutral, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        ConfidenceDial(
            p.conviction,
            modifier = Modifier.clickable(onClickLabel = "What confidence means") { explain.show("conviction", "Confidence") },
        )
    }

    (p.headline?.takeIf { it.isNotBlank() } ?: p.thesis)?.let {
        Text(it, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
    }

    // Price now, today's move, and where that sits against the buy zone.
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(DailyPickRead.money(price), fontSize = 22.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
        change?.let {
            Text(String.format(Locale.US, "%+.2f%%", it), style = MaterialTheme.typography.labelLarge,
                color = if (it >= 0) GainGreen else LossRed)
        }
        DailyPickRead.chaseLabel(resp.chase?.status, resp.chase?.pct)?.let {
            PickChip(it, when (resp.chase?.status) {
                "in_zone" -> ZoneGold
                "chase_too_deep" -> LossRed
                else -> neutral
            })
        }
    }
    ContextChips(resp)

    // Pills, no sentences: two reasons for, one against. The readings and the AI's words are in Details.
    val factors = p.factors.associateBy { it.key }
    val shown = (p.reasons.filter { it.supports }.take(2) + p.reasons.filterNot { it.supports }.take(1))
    if (shown.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (r in shown) {
                FactorPill(factors[r.factor], fallbackLabel = r.factor, supports = r.supports,
                    onClick = { explain.show(r.factor, factors[r.factor]?.label ?: r.factor) })
            }
        }
    }

    PlanBar(p.levels, price, middle = p.riskReward?.rrRatio?.let { String.format(Locale.US, "reward %.1f× risk", it) })

    if (more) {
        DailyPickRead.trackRecordLine(p.trackRecord)?.let {
            val thin = (p.trackRecord?.n ?: 0) < DailyPickRead.MIN_TRUSTED_N
            Text(it + if (thin) " — too few to lean on" else "", style = MaterialTheme.typography.bodySmall,
                color = if (thin) neutral.copy(alpha = 0.6f) else neutral)
        }
        (state.fit?.sentence ?: state.fitError)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = neutral) }
        DailyPickRead.repeatLine(resp.repeats?.symbol, sym)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Signal) }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onWhy) { Text("Details") }
        TextButton(onClick = { more = !more }) { Text(if (more) "Less" else "More") }
        Box(Modifier.weight(1f))
        val logged = state.loggedKey == "${resp.date}|$sym"
        if (resp.stale != true) {
            TextButton(onClick = onBought, enabled = !logged) { Text(if (logged) "Logged ✓" else "I bought it") }
        }
    }
    state.journalNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = GainGreen) }
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
    // Kept to three lines on purpose: on a no-pick day this card sits above the whole watchlist.
    // It used to say the same thing twice ("scored 63… needed 70", then "Its confidence was 63…
    // it needed 70") and fill most of the first screen. The full reason is one tap away.
    val p = resp.pick
    val closest = p?.closest
    val conv = p?.rejectedConviction
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (closest != null && conv != null) NearMissRing(conv)
        if (closest != null && conv != null) {
            Text(
                buildAnnotatedString {
                    append("Closest: ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(closest.symbol) }
                    append(" · needed ${p.convictionFloor ?: 60}")
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).clickable { onOpenSymbol(closest.symbol, closest.name) },
            )
        } else {
            Text(resp.noneReason ?: "Nothing was convincing enough today.", style = MaterialTheme.typography.bodyMedium)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { ContextChips(resp) }
        TextButton(onClick = onWhy) { Text("Details ›") }
    }
}


/**
 * The intraday re-check, in one line plus a button. What it concluded reads against this morning
 * ("Still DK (68/100), was 72"); the reasons and the full explanation open under "More". Visibly
 * separate from the pick above it, which it never replaces.
 */
@Composable
private fun RecheckSection(
    resp: DailyPickResponse,
    state: DailyPickUiState,
    explain: ExplainState,
    onRecheck: () -> Unit,
    onOpenSymbol: (String, String?) -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val rc = resp.recheck
    var more by rememberSaveable(rc?.ts) { mutableStateOf(false) }
    androidx.compose.material3.HorizontalDivider(color = neutral.copy(alpha = 0.25f))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val headline = DailyPickRead.recheckHeadline(rc, resp.pick?.conviction)
            when {
                state.rechecking -> Text("Re-checking with live prices…", style = MaterialTheme.typography.bodyMedium)
                headline != null -> Text(
                    headline,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (rc?.sameAsMorning == false) Signal else MaterialTheme.colorScheme.onSurface,
                    modifier = rc?.pick?.symbol?.let { s -> Modifier.clickable { onOpenSymbol(s, rc.pick.name) } } ?: Modifier,
                )
                rc?.status == "failed" -> Text("Last re-check failed", style = MaterialTheme.typography.bodyMedium, color = LossRed)
                else -> Text("Still hold up? Re-check with live prices", style = MaterialTheme.typography.bodySmall, color = neutral)
            }
            // The time and the "Why" link share one line, so the reasons cost no space until asked for.
            val hasMore = rc != null && (rc.pick != null || rc.noneReason != null || rc.error != null)
            Row(verticalAlignment = Alignment.CenterVertically) {
                DailyPickRead.recheckStamp(rc?.ts)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = neutral)
                }
                if (hasMore) {
                    Text(
                        if (more) " · Less" else " · Why",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClickLabel = if (more) "Hide the reasons" else "Show why") { more = !more }
                            .padding(vertical = 12.dp),
                    )
                }
            }
        }
        if (state.rechecking) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onRecheck) { Text("Re-check") }
        }
    }
    if (more && rc != null) {
        rc.error?.takeIf { rc.status == "failed" }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LossRed) }
        (rc.noneDetail ?: rc.noneReason)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = neutral) }
        rc.pick?.let { p ->
            val factors = p.factors.associateBy { it.key }
            factors["today_move"]?.let { f -> Text("Today: ${f.display}", style = MaterialTheme.typography.bodySmall, color = neutral) }
            (p.reasons.filter { it.supports }.take(1) + p.reasons.filterNot { it.supports }.take(1)).forEach { reason ->
                FactorRow(
                    supports = reason.supports, factor = factors[reason.factor], fallbackLabel = reason.factor,
                    text = reason.text, onExplain = { explain.show(reason.factor, factors[reason.factor]?.label ?: reason.factor) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Not graded — the morning pick stays as it is.", style = MaterialTheme.typography.labelSmall, color = neutral,
                modifier = Modifier.weight(1f))
            InfoButton("a re-check") { explain.show("recheck", "Re-check") }
        }
    }
    state.recheckNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = neutral) }
    state.recheckError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LossRed) }
}
