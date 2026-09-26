package com.stocktracker.app.ui.funds

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.remote.FundOverlapResponse
import com.stocktracker.app.ui.components.GlowCard
import com.stocktracker.app.ui.components.Skeleton
import com.stocktracker.app.ui.detail.FundCostText
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import com.stocktracker.app.ui.theme.Signal
import java.util.Locale

/** A plain back-arrow screen shell for the Funds drill-downs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DrillScaffold(title: String, subtitle: String?, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title)
                        subtitle?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** The group colour for each fund: its overlap group's colour, grey on its own. */
private fun colorsFor(resp: FundOverlapResponse, values: Map<String, Double>): Map<String, Color> {
    val ov = FundsLogic.overlapView(resp, values)
    val m = mutableMapOf<String, Color>()
    ov.groups.forEachIndexed { i, g -> g.forEach { m[it] = groupColor(i) } }
    ov.singles.forEach { m[it] = OwnWayColor }
    return m
}

/**
 * FUND-5 — every fund ranked by return over 1, 3 or 5 years (dividends in), or by the smallest
 * worst drop, or by fee. An unknown figure shows "—" and sorts last, never as zero.
 */
@Composable
fun FundRankingScreen(vm: FundsViewModel, initialSort: RankSort, onBack: () -> Unit, onOpenDetail: (Asset) -> Unit) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val resp = ui.overlap
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val syms = resp?.funds?.keys.orEmpty()
    var period by rememberSaveable { mutableStateOf(FundsLogic.bestPeriod(ui.perf, syms)) }
    var sort by rememberSaveable { mutableStateOf(initialSort) }
    DrillScaffold(
        title = "Performance",
        subtitle = listOfNotNull("${syms.size} funds", FundsLogic.asOfLine(ui.perf?.alignedTo)?.let { "to $it" }).joinToString(" · "),
        onBack = onBack,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Over", style = MaterialTheme.typography.labelMedium, color = neutral, modifier = Modifier.width(52.dp))
            listOf("1y", "3y", "5y").forEach { p ->
                FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.uppercase()) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Rank by", style = MaterialTheme.typography.labelMedium, color = neutral, modifier = Modifier.width(52.dp))
            listOf(RankSort.RETURN to "Return", RankSort.DROP to "Smallest drop", RankSort.FEE to "Cheapest").forEach { (s, label) ->
                FilterChip(selected = sort == s, onClick = { sort = s }, label = { Text(label) })
            }
        }
        when {
            resp == null -> Skeleton(Modifier.fillMaxWidth().height(300.dp))
            ui.perf == null && !ui.perfFailed -> Skeleton(Modifier.fillMaxWidth().height(300.dp))
            else -> {
                if (ui.perfFailed) Text("Couldn't load returns; ranking by what is known.", style = MaterialTheme.typography.labelMedium, color = Signal)
                val colors = colorsFor(resp, ui.values)
                val rows = FundsLogic.ranking(syms, ui.perf, resp.funds, period, sort)
                val max = rows.mapNotNull { it.ret }.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.width(22.dp))
                        Text("FUND", style = MaterialTheme.typography.labelSmall, color = neutral, modifier = Modifier.weight(1f))
                        Text("${period.uppercase()} RETURN", style = MaterialTheme.typography.labelSmall, color = neutral, textAlign = TextAlign.End, modifier = Modifier.width(72.dp))
                        Text("WORST DROP", style = MaterialTheme.typography.labelSmall, color = neutral, textAlign = TextAlign.End, modifier = Modifier.width(76.dp).padding(start = 8.dp))
                        Text("FEE", style = MaterialTheme.typography.labelSmall, color = neutral, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
                    }
                    rows.forEachIndexed { i, r ->
                        val name = resp.funds[r.symbol]?.name
                        Column(
                            Modifier.fillMaxWidth().clickable { onOpenDetail(Asset(r.symbol, AssetType.STOCK, name ?: r.symbol)) }
                                .padding(vertical = 7.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${i + 1}", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
                                    color = neutral, modifier = Modifier.width(16.dp))
                                Box(Modifier.padding(horizontal = 3.dp).size(8.dp).clip(RoundedCornerShape(4.dp)).background(colors[r.symbol] ?: OwnWayColor))
                                Column(Modifier.weight(1f)) {
                                    Text(r.symbol, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                                    Text(FundCostText.shortName(name), style = MaterialTheme.typography.labelSmall, color = neutral,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(FundsLogic.pct(r.ret), style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.width(72.dp),
                                    color = when { r.ret == null -> neutral; r.ret >= 0 -> GainGreen; else -> LossRed })
                                Text(FundsLogic.pct(r.worstDrop), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
                                    textAlign = TextAlign.End, modifier = Modifier.width(76.dp), color = if (r.worstDrop == null) neutral else LossRed)
                                Text(r.feePct?.let { FundCostText.perTenK(it) } ?: "—", style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
                            }
                            ReturnBar(r.ret, max, Modifier.fillMaxWidth().padding(start = 30.dp))
                        }
                    }
                }
                Text("Returns include dividends. Worst drop is the deepest fall from a high in the last 5 years (less for a younger fund). " +
                    "Fee is per \$10,000 a year. A fund without a record that long shows — and sorts last.",
                    style = MaterialTheme.typography.bodySmall, color = neutral)
                Text("Past returns show what a fund held, not what comes next. Context, not advice.",
                    style = MaterialTheme.typography.bodySmall, color = neutral)
            }
        }
    }
}

/**
 * FUND-1 — the groups of funds that move together, each with how close every pair is, how they
 * ranked inside the group, what actually differs, and the cheapest way to hold it; then the funds
 * that move on their own and how close their nearest match is.
 */
@Composable
fun FundOverlapScreen(vm: FundsViewModel, onBack: () -> Unit, onOpenDetail: (Asset) -> Unit, onOpenCompare: (List<String>) -> Unit) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val resp = ui.overlap
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    DrillScaffold(
        title = "Overlap",
        subtitle = resp?.let { "${it.funds.size} funds · 2 years of prices" },
        onBack = onBack,
    ) {
        if (resp == null) {
            Skeleton(Modifier.fillMaxWidth().height(300.dp))
            return@DrillScaffold
        }
        val ov = FundsLogic.overlapView(resp, ui.values)
        if (ov.groups.isEmpty()) {
            Text("None of these funds move together.", style = MaterialTheme.typography.bodyMedium, color = neutral)
        }
        ov.groups.forEachIndexed { gi, g ->
            val color = groupColor(gi)
            GlowCard(tint = color, spacing = 8.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
                    Text("Move together · ${g.size} funds", style = MaterialTheme.typography.labelLarge, color = neutral)
                }
                Text(FundsLogic.groupSentence(g, resp.funds), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val weakest = g.flatMap { a -> g.filter { it > a }.map { b -> resp.pair(a, b)?.corr } }.filterNotNull().minOrNull()
                weakest?.let {
                    Text("Every pair moved together at ${FundsLogic.corr(it)} or closer, so owning more than one adds little spread.",
                        style = MaterialTheme.typography.bodySmall, color = neutral)
                }
                if (g.size in 2..6) ClosenessGrid(g, resp)
                // Inside the group: who did best, and what each costs.
                val period = FundsLogic.bestPeriod(ui.perf, g)
                val rows = FundsLogic.ranking(g, ui.perf, resp.funds, period, RankSort.RETURN)
                val max = rows.mapNotNull { it.ret }.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("Inside the group, ${FundsLogic.periodWords(period)}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    Text("FEE", style = MaterialTheme.typography.labelSmall, color = neutral)
                }
                rows.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpenDetail(Asset(r.symbol, AssetType.STOCK, resp.funds[r.symbol]?.name ?: r.symbol)) },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(r.symbol, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp))
                        ReturnBar(r.ret, max, Modifier.weight(1f))
                        Text(FundsLogic.pct(r.ret), style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.End, modifier = Modifier.width(64.dp),
                            color = when { r.ret == null -> neutral; r.ret >= 0 -> GainGreen; else -> LossRed })
                        Text(r.feePct?.let { FundCostText.perTenK(it) } ?: "—", style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace, textAlign = TextAlign.End, modifier = Modifier.width(48.dp))
                    }
                }
                Text("What each holds", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                g.forEach { s ->
                    resp.funds[s]?.let { p ->
                        Text("$s: ${FundsLogic.covers(p, sectors = 2)}", style = MaterialTheme.typography.bodySmall, color = neutral)
                    }
                }
                val cheapest = rows.filter { it.feePct != null }.minByOrNull { it.feePct!! }
                val copies = FundsLogic.cheaperCopies(g, resp.funds, ui.groupsById).filter { it.to !in g }
                if (cheapest != null) {
                    val tied = rows.filter { it.feePct != null && it.feePct - cheapest.feePct!! < 1e-9 }.map { it.symbol }
                    Text("Cheapest here: ${FundsLogic.joinNames(tied)}, ${FundCostText.perTenK(cheapest.feePct!!)} a year per \$10,000" +
                        (copies.firstOrNull()?.let { c -> " · cheaper still: ${c.to}" + (c.toNote?.let { " ($it)" } ?: "") } ?: ""),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (g.size >= 2) {
                    androidx.compose.material3.TextButton(onClick = { onOpenCompare(g.take(3)) }) {
                        Text("Compare ${FundsLogic.joinNames(g.take(3))}")
                    }
                }
            }
        }
        if (ov.singles.isNotEmpty()) {
            GlowCard(tint = null, spacing = 6.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(OwnWayColor))
                    Text("Each moves its own way · ${ov.singles.size}", style = MaterialTheme.typography.labelLarge, color = neutral)
                }
                ov.singles.forEach { s ->
                    val closest = resp.pairs.filter { (it.a == s || it.b == s) && it.corr != null }.maxByOrNull { it.corr!! }
                    Column(
                        Modifier.fillMaxWidth().clickable { onOpenDetail(Asset(s, AssetType.STOCK, resp.funds[s]?.name ?: s)) }
                            .padding(vertical = 4.dp),
                    ) {
                        Text(s, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        resp.funds[s]?.let { Text(FundsLogic.covers(it, sectors = 2), style = MaterialTheme.typography.labelSmall, color = neutral) }
                        Text(
                            closest?.let { c ->
                                val other = if (c.a == s) c.b else c.a
                                "Closest: $other · ${PairVerdict.of(c.corr).words.lowercase()} (${FundsLogic.corr(c.corr)})"
                            } ?: "Nothing to compare it with here",
                            style = MaterialTheme.typography.labelSmall, color = neutral,
                        )
                    }
                }
            }
        }
        AlsoOverlapping(resp)
        Text("How close: same fund (0.98+), close copies (0.90+), similar (0.80+), different. Measured from two years of " +
            "weekly returns. Shared holdings count only each fund's 10 largest, all Yahoo lists.",
            style = MaterialTheme.typography.bodySmall, color = neutral)
    }
}

/** A small grid of how closely each pair in a group moves. */
@Composable
private fun ClosenessGrid(g: List<String>, resp: FundOverlapResponse) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val same = Color(0xFF6E5FB0)
    val close = Color(0xFF4A416F)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Spacer(Modifier.weight(1f))
            g.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = neutral, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        g.forEach { a ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(a, style = MaterialTheme.typography.labelSmall, color = neutral, modifier = Modifier.weight(1f))
                g.forEach { b ->
                    val c = if (a == b) null else resp.pair(a, b)?.corr
                    val bg = when {
                        a == b -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)
                        PairVerdict.of(c) == PairVerdict.SAME_FUND -> same
                        PairVerdict.of(c) == PairVerdict.MOVE_TOGETHER -> close
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
                    }
                    Box(
                        Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(6.dp)).background(bg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (a == b) "—" else FundsLogic.corr(c), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendDot(same, "Same fund")
            LegendDot(close, "Close copies")
        }
    }
}

/**
 * FUND-3/4 — cheaper funds that hold the same thing: for the user's own funds first (with what
 * they pay now, in holdings mode), then every measured look-alike group.
 */
@Composable
fun FundCopiesScreen(vm: FundsViewModel, onBack: () -> Unit, onOpenDetail: (Asset) -> Unit) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val resp = ui.overlap
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    DrillScaffold(title = "Cheaper copies", subtitle = "Funds that hold the same thing for less", onBack = onBack) {
        if (resp != null && ui.mode == FundsViewModel.Mode.HOLDINGS && ui.values.isNotEmpty()) {
            Label("WHAT YOUR FUNDS COST")
            FeesCard(ui, resp)
        }
        if (resp != null) {
            val holdings = ui.mode == FundsViewModel.Mode.HOLDINGS && ui.values.isNotEmpty()
            val copies = FundsLogic.cheaperCopies(resp.funds.keys, resp.funds, ui.groupsById, if (holdings) ui.values else emptyMap())
            Label("FOR THESE FUNDS")
            GlowCard(tint = if (copies.isNotEmpty()) GainGreen else null, spacing = 8.dp) {
                when {
                    ui.groups == null && !ui.groupsFailed -> Skeleton(Modifier.fillMaxWidth().height(60.dp))
                    ui.groups == null -> Text("Couldn't load the look-alike groups.", style = MaterialTheme.typography.bodySmall, color = Signal)
                    copies.isEmpty() -> Text("Each of these is already the cheapest of its measured look-alikes, or has none.",
                        style = MaterialTheme.typography.bodyMedium, color = neutral)
                    else -> copies.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenDetail(Asset(c.to, AssetType.STOCK, c.to)) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("${c.from} → ${c.to}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                c.toNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("saves ${FundCostText.dollars(c.savesPer10k)}", style = MaterialTheme.typography.labelLarge,
                                    fontFamily = FontFamily.Monospace, color = GainGreen)
                                Text("per \$10,000 a year", style = MaterialTheme.typography.labelSmall, color = neutral)
                                c.savesYours?.takeIf { holdings }?.let {
                                    Text("${FundCostText.dollars(it)} on yours", style = MaterialTheme.typography.labelSmall, color = GainGreen)
                                }
                            }
                        }
                    }
                }
                if (copies.isNotEmpty()) {
                    Text("Selling to switch can mean tax on gains, so the gap matters most for new money.",
                        style = MaterialTheme.typography.labelSmall, color = neutral)
                }
            }
        }
        Label("ALL LOOK-ALIKE GROUPS")
        GroupsCard(ui, onOpen = { vm.loadGroupPerf(it) }, onOpenDetail = onOpenDetail)
        Text(String.format(Locale.US, "Measured groups: funds whose returns moved together at 0.995 or better over two years."),
            style = MaterialTheme.typography.bodySmall, color = neutral)
    }
}
