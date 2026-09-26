package com.stocktracker.app.ui.funds

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.remote.FundGroup
import com.stocktracker.app.data.remote.FundOverlapResponse
import com.stocktracker.app.data.remote.FundProfile
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.components.BackendStatusBanner
import com.stocktracker.app.ui.components.GlowCard
import com.stocktracker.app.ui.components.Pill
import com.stocktracker.app.ui.components.Skeleton
import com.stocktracker.app.ui.detail.FundCostText
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.Signal
import kotlinx.coroutines.launch

/**
 * FUND-1..6 — the Funds spoke of the Markets tab: how your funds overlap (and how many different
 * bets they really are), what they cost you a year, a check before buying another, a side-by-side
 * comparison, and every group of funds measured to hold the same thing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FundsScreen(
    onBack: () -> Unit,
    onOpenDetail: (Asset) -> Unit,
    onOpenCompare: (List<String>) -> Unit,
    onOpenSignalsSettings: () -> Unit = {},
) {
    val vm: FundsViewModel = viewModel()
    val ui by vm.state.collectAsStateWithLifecycle()
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Funds") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BackendStatusBanner()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = ui.mode == FundsViewModel.Mode.HOLDINGS,
                    onClick = { vm.setMode(FundsViewModel.Mode.HOLDINGS) },
                    label = { Text("Your holdings") },
                )
                FilterChip(
                    selected = ui.mode == FundsViewModel.Mode.WATCHLIST,
                    onClick = { vm.setMode(FundsViewModel.Mode.WATCHLIST) },
                    label = { Text("Watchlist") },
                )
            }
            val resp = ui.overlap
            when {
                !ui.configured -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Fund comparisons come from the self-hosted signals service, which isn't set up yet.",
                        style = MaterialTheme.typography.bodyMedium, color = neutral)
                    TextButton(onClick = onOpenSignalsSettings) { Text("Set up signals") }
                }
                ui.loading && resp == null -> {
                    Skeleton(Modifier.fillMaxWidth().height(96.dp))
                    Skeleton(Modifier.fillMaxWidth().height(160.dp))
                }
                ui.failed && resp == null -> Column {
                    Text("Couldn't load your funds.", style = MaterialTheme.typography.bodyMedium, color = Signal)
                    TextButton(onClick = { vm.load() }) { Text("Retry") }
                }
                resp != null -> {
                    if (ui.failed) {
                        Text("Couldn't refresh — showing the last result.", style = MaterialTheme.typography.labelMedium, color = Signal)
                    }
                    HeadlineCard(ui, resp)
                    if (resp.funds.isNotEmpty()) {
                        Label("HOW THEY OVERLAP")
                        BetCards(ui, resp, onOpenDetail)
                        AlsoOverlapping(resp)
                    }
                    if (ui.mode == FundsViewModel.Mode.HOLDINGS && ui.values.isNotEmpty()) {
                        Label("WHAT THEY COST YOU")
                        FeesCard(ui, resp)
                    }
                }
            }
            if (ui.configured) {
                Label("BEFORE YOU BUY")
                BeforeYouBuyCard(ui.check, onCheck = { vm.check(it) }, onClear = { vm.clearCheck() },
                    onCompare = { sym ->
                        val mine = ui.overlap?.funds?.keys?.toList().orEmpty()
                        onOpenCompare((listOf(sym) + mine.filter { it != sym }).take(2))
                    })
                CompareCard(resp, ui.values) { onOpenCompare(it) }
                Label("LOOK-ALIKE FUNDS")
                GroupsCard(ui, onOpen = { vm.loadGroupPerf(it) }, onOpenDetail = onOpenDetail)
                FeeAlertToggle()
            }
            Text(
                "Overlap is measured from two years of prices: funds that rise and fall together count as one bet. " +
                    "Holdings are each fund's 10 largest, all Yahoo lists. Context, not advice.",
                style = MaterialTheme.typography.bodySmall,
                color = neutral,
                modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
            )
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun HeadlineCard(ui: FundsViewModel.UiState, resp: FundOverlapResponse) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    GlowCard(tint = null, spacing = 6.dp) {
        Text(if (ui.mode == FundsViewModel.Mode.HOLDINGS) "Your funds" else "Your watchlist's funds",
            style = MaterialTheme.typography.labelLarge, color = neutral)
        // What could not be looked up or measured is named, never folded into "not a fund".
        val gaps = @Composable {
            if (resp.unknown.isNotEmpty()) {
                Text("Couldn't look up ${resp.unknown.joinToString(", ")}: the quote service didn't answer, so " +
                    "they're left out.", style = MaterialTheme.typography.labelMedium, color = Signal)
            }
            if (resp.unmeasured.isNotEmpty()) {
                Text("Too many funds to measure at once; left out: ${resp.unmeasured.joinToString(", ")}.",
                    style = MaterialTheme.typography.labelMedium, color = Signal)
            }
        }
        if (resp.funds.isEmpty()) {
            Text("No funds here", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (resp.unknown.isEmpty()) {
                Text(if (ui.mode == FundsViewModel.Mode.HOLDINGS) "None of your holdings is a fund." else "Your watchlist has no funds.",
                    style = MaterialTheme.typography.bodySmall, color = neutral)
            }
            gaps()
            return@GlowCard
        }
        Text(FundsLogic.betsHeadline(resp.funds.size, resp.sameBets.size),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (ui.mode == FundsViewModel.Mode.HOLDINGS && ui.values.isNotEmpty()) {
            val fees = FundsLogic.feesYouPay(ui.values, resp.funds, ui.groupsById)
            val total = FundCostText.dollars(Math.round(ui.values.values.sum()).toDouble())
            Text(
                when {
                    // A total that excluded money must say so; one that counted nothing is not "$0".
                    fees.knownCount == 0 -> "$total in funds · yearly fees unknown"
                    fees.unknownFee.isEmpty() -> "$total in funds · about ${FundCostText.dollars(fees.perYear)} a year in fees"
                    else -> "$total in funds · about ${FundCostText.dollars(fees.perYear)} a year in fees on the " +
                        "${FundCostText.dollars(Math.round(fees.countedValue).toDouble())} with a known fee"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (ui.mode == FundsViewModel.Mode.HOLDINGS && ui.unpriced.isNotEmpty()) {
            Text("Couldn't price ${ui.unpriced.joinToString(", ")}, so there are no dollar figures here.",
                style = MaterialTheme.typography.labelMedium, color = Signal)
        }
        if (ui.cachePriced.isNotEmpty()) {
            Text("Priced from the last saved quote (a live one failed): ${ui.cachePriced.joinToString(", ")}.",
                style = MaterialTheme.typography.labelSmall, color = neutral)
        }
        gaps()
        if (ui.noHeldFunds) {
            Text("None of your holdings is a fund, so this shows your watchlist's.",
                style = MaterialTheme.typography.bodySmall, color = neutral)
        }
    }
}

@Composable
private fun BetCards(ui: FundsViewModel.UiState, resp: FundOverlapResponse, onOpenDetail: (Asset) -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val total = ui.values.values.sum()
    val bets = resp.sameBets.sortedWith(
        compareByDescending<List<String>> { bet -> bet.sumOf { ui.values[it] ?: 0.0 } }.thenByDescending { it.size },
    )
    for (bet in bets) {
        GlowCard(tint = null, spacing = 6.dp) {
            if (bet.size > 1) {
                val weakest = bet.flatMap { a -> bet.filter { it > a }.map { b -> resp.pair(a, b)?.corr } }
                    .filterNotNull().minOrNull()
                val verdict = PairVerdict.of(weakest)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("One bet: ${bet.size} funds", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Pill(verdict.words, if (verdict == PairVerdict.SAME_FUND) Signal else MaterialTheme.colorScheme.primary)
                }
                Text(
                    if (bet.size == 2) "They rise and fall together (${FundsLogic.corr(weakest)}), so owning both adds little."
                    else "They rise and fall together (every pair ${FundsLogic.corr(weakest)} or closer), so owning several adds little.",
                    style = MaterialTheme.typography.bodySmall, color = neutral,
                )
            } else {
                Text("Its own bet", style = MaterialTheme.typography.labelLarge, color = neutral)
            }
            val value = bet.sumOf { ui.values[it] ?: 0.0 }
            if (ui.mode == FundsViewModel.Mode.HOLDINGS && value > 0.0 && total > 0.0) {
                Text("Your money here: ${FundCostText.dollars(Math.round(value).toDouble())} " +
                    "(${String.format(java.util.Locale.US, "%.0f", value / total * 100)}% of your funds)",
                    style = MaterialTheme.typography.bodyMedium)
            }
            for (sym in bet) {
                val p = resp.funds[sym] ?: continue
                FundLine(p, ui.values[sym], onClick = { onOpenDetail(Asset(sym, com.stocktracker.app.data.model.AssetType.STOCK, p.name ?: sym)) })
            }
            if (bet.size > 1) {
                val pairs = bet.flatMap { a -> bet.filter { it > a }.mapNotNull { b -> resp.pair(a, b) } }
                    .sortedByDescending { it.corr ?: -1.0 }.take(3)
                for (p in pairs) {
                    val v = PairVerdict.of(p.corr)
                    Text("${p.a} + ${p.b}: ${v.words.lowercase()} (${FundsLogic.corr(p.corr)})" +
                        if (p.sharedTopCount > 0) " · ${p.sharedTopCount} top holdings shared" else "",
                        style = MaterialTheme.typography.labelMedium, color = neutral)
                }
            }
            FundsLogic.directCryptoNote(bet.map { resp.funds[it]?.groupId }, ui.heldCoins)?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = Signal)
            }
        }
    }
}

/** One fund in a bet: ticker, what it covers, its fee, and what the user has in it. */
@Composable
private fun FundLine(p: FundProfile, value: Double?, onClick: () -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(p.symbol, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(FundCostText.shortName(p.name), style = MaterialTheme.typography.labelMedium, color = neutral,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(FundsLogic.covers(p), style = MaterialTheme.typography.labelSmall, color = neutral, maxLines = 2)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(FundCostText.rowFee(p.toCost()) + "/yr", style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
            value?.let {
                Text(FundCostText.dollars(Math.round(it).toDouble()), style = MaterialTheme.typography.labelSmall, color = neutral)
            }
        }
    }
}

/**
 * Pairs in DIFFERENT bets that still overlap (0.80 or closer), and pairs nobody could measure.
 *
 * A bet needs every member to clear 0.90 against every other, so a pair can move together and still
 * be counted apart: SPMO sat at 0.92 with QQQM and 0.87 with VOO on 2026-09-26. Those are the pairs
 * most worth seeing. And a pair with too little shared history is "not measured", which is not the
 * same as "different" — the bet count treats it as separate, so the screen says why.
 */
@Composable
private fun AlsoOverlapping(resp: FundOverlapResponse) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val betOf = resp.sameBets.flatMapIndexed { i, b -> b.map { it to i } }.toMap()
    val close = setOf(PairVerdict.SAME_FUND, PairVerdict.MOVE_TOGETHER, PairVerdict.OVERLAP_A_LOT)
    val lots = resp.pairs.filter { PairVerdict.of(it.corr) in close && betOf[it.a] != betOf[it.b] }
        .sortedByDescending { it.corr }
    val unmeasured = resp.pairs.filter { it.corr == null }
    if (lots.isEmpty() && unmeasured.isEmpty()) return
    GlowCard(tint = null, spacing = 4.dp) {
        if (lots.isNotEmpty()) {
            Text("Separate bets that still overlap", style = MaterialTheme.typography.labelLarge, color = neutral)
            lots.take(6).forEach { p ->
                Text("${p.a} + ${p.b}: ${PairVerdict.of(p.corr).words.lowercase()} (${FundsLogic.corr(p.corr)})" +
                    if (p.sharedTopCount > 0) " · ${p.sharedTopCount} top holdings shared" else "",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (unmeasured.isNotEmpty()) {
            Text("Not measured (too little shared price history), so counted as separate: " +
                unmeasured.take(8).joinToString(", ") { "${it.a} + ${it.b}" } +
                if (unmeasured.size > 8) " and ${unmeasured.size - 8} more" else "",
                style = MaterialTheme.typography.labelSmall, color = Signal)
        }
    }
}

@Composable
private fun FeesCard(ui: FundsViewModel.UiState, resp: FundOverlapResponse) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val fees = FundsLogic.feesYouPay(ui.values, resp.funds, ui.groupsById)
    GlowCard(tint = null, spacing = 6.dp) {
        Text("Fund fees", style = MaterialTheme.typography.labelLarge, color = neutral)
        if (fees.knownCount == 0) {
            // Nothing was counted, so there is no total to state — "$0" here would be a claim.
            Text("Yearly fees unknown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("No fee could be found for ${fees.unknownFee.joinToString(", ")}.",
                style = MaterialTheme.typography.labelSmall, color = Signal)
            return@GlowCard
        }
        Text("About ${FundCostText.dollars(fees.perYear)} a year" +
            if (fees.unknownFee.isNotEmpty()) " on the ${FundCostText.dollars(Math.round(fees.countedValue).toDouble())} with a known fee" else "",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        when (val c = fees.cheapestPerYear) {
            null -> Text("Couldn't check the cheaper look-alikes right now.", style = MaterialTheme.typography.bodySmall, color = neutral)
            else -> if (fees.switches.isEmpty()) {
                // "Cheapest" is only said about funds that were actually compared with something.
                when {
                    fees.compared.isEmpty() -> Text("None of these has a measured look-alike to compare it with.",
                        style = MaterialTheme.typography.bodySmall, color = neutral)
                    fees.notCompared.isEmpty() && fees.unknownFee.isEmpty() ->
                        Text("Each of your funds is already the cheapest of its look-alikes.",
                            style = MaterialTheme.typography.bodySmall, color = GainGreen)
                    else -> Text("${fees.compared.joinToString(", ")}: already the cheapest of ${if (fees.compared.size == 1) "its" else "their"} look-alikes.",
                        style = MaterialTheme.typography.bodySmall, color = GainGreen)
                }
            } else {
                Text("In the cheapest look-alikes: about ${FundCostText.dollars(c)} a year", style = MaterialTheme.typography.bodyMedium)
                fees.switches.take(4).forEach { s ->
                    Text("${s.from} → ${s.to}${s.toNote?.let { " ($it)" } ?: ""}: saves about " +
                        "${FundCostText.dollars(s.savesPerYear)} a year on your ${FundCostText.dollars(Math.round(s.value).toDouble())}",
                        style = MaterialTheme.typography.bodySmall, color = GainGreen)
                }
                Text("Selling to switch can mean tax on gains, so the gap matters most for new money.",
                    style = MaterialTheme.typography.labelSmall, color = neutral)
            }
        }
        if (fees.notCompared.isNotEmpty() && fees.compared.isNotEmpty()) {
            Text("No measured look-alike for ${fees.notCompared.joinToString(", ")}.",
                style = MaterialTheme.typography.labelSmall, color = neutral)
        }
        if (fees.unknownFee.isNotEmpty()) {
            Text("Fee unknown, left out: ${fees.unknownFee.joinToString(", ")}", style = MaterialTheme.typography.labelSmall, color = Signal)
        }
        val source = FundCostText.sourceLine(ui.values.keys.mapNotNull { resp.funds[it]?.toCost() }, resp.live)
        if (source.isNotBlank()) Text(source, style = MaterialTheme.typography.labelSmall, color = neutral)
        if (ui.unpriced.isNotEmpty()) {
            Text("Couldn't price ${ui.unpriced.joinToString(", ")}, so they're left out of these dollars.",
                style = MaterialTheme.typography.labelSmall, color = Signal)
        }
    }
}

@Composable
private fun BeforeYouBuyCard(
    check: FundsViewModel.Check?,
    onCheck: (String) -> Unit,
    onClear: () -> Unit,
    onCompare: (String) -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    var text by rememberSaveable { mutableStateOf("") }
    GlowCard(tint = null, spacing = 8.dp) {
        Text("Check a fund against what you own", style = MaterialTheme.typography.labelLarge, color = neutral)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.uppercase().take(12) },
                label = { Text("Ticker, e.g. QQQM") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onCheck(text) }),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onCheck(text) }, enabled = text.isNotBlank()) { Text("Check") }
        }
        when {
            check == null -> Unit
            check.loading -> Skeleton(Modifier.fillMaxWidth().height(48.dp))
            check.failed -> Text("Couldn't check ${check.symbol} right now.", style = MaterialTheme.typography.bodySmall, color = Signal)
            check.notAFund -> Text("${check.symbol} isn't a fund (or Yahoo doesn't know it), so there's nothing to overlap.",
                style = MaterialTheme.typography.bodySmall, color = neutral)
            else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(check.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                check.fee?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                check.covers?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = neutral) }
                check.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Row {
                    TextButton(onClick = { onCompare(check.symbol) }) { Text("Compare side by side") }
                    TextButton(onClick = { text = ""; onClear() }) { Text("Clear") }
                }
            }
        }
    }
}

@Composable
private fun CompareCard(resp: FundOverlapResponse?, values: Map<String, Double>, onCompare: (List<String>) -> Unit) {
    val picks = resp?.funds?.keys?.sortedByDescending { values[it] ?: 0.0 }?.take(2).orEmpty()
    GlowCard(tint = null, spacing = 6.dp, modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { onCompare(picks) }) {
        Text("Compare funds side by side", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text("Returns with dividends, worst drop, fees in dollars, and how much they overlap.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GroupsCard(
    ui: FundsViewModel.UiState,
    onOpen: (FundGroup) -> Unit,
    onOpenDetail: (Asset) -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val groups = ui.groups?.groups
    when {
        groups == null && ui.groupsFailed -> Text("Couldn't load the look-alike groups.", style = MaterialTheme.typography.bodySmall, color = Signal)
        groups == null -> Skeleton(Modifier.fillMaxWidth().height(80.dp))
        else -> GlowCard(tint = null, spacing = 2.dp) {
            Text("Funds measured to hold the same thing, and what each charges a year per \$10,000.",
                style = MaterialTheme.typography.bodySmall, color = neutral)
            groups.forEach { g -> GroupRow(g, ui.groupPerf[g.id], onOpen, onOpenDetail) }
        }
    }
}

@Composable
private fun GroupRow(
    g: FundGroup,
    perf: FundsViewModel.GroupPerf?,
    onOpen: (FundGroup) -> Unit,
    onOpenDetail: (Asset) -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    var open by rememberSaveable(g.id) { mutableStateOf(false) }
    // Also when an open row is restored after the screen was recreated, not only on a tap.
    LaunchedEffect(open) { if (open) onOpen(g) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .clickable { open = !open }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(g.label.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                val cheapestEtf = g.funds.firstOrNull { it.kind == "etf" && it.expenseRatioPct != null }
                val cheapestFid = g.funds.firstOrNull { it.fidelity && it.expenseRatioPct != null }
                Text(listOfNotNull(
                    FundsLogic.costRange(g),
                    cheapestEtf?.let { "cheapest ETF ${it.symbol}" },
                    cheapestFid?.takeIf { it.symbol != cheapestEtf?.symbol }?.let { "Fidelity ${it.symbol}" },
                ).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = neutral)
            }
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = neutral)
        }
        if (open) {
            val cheapest = g.funds.firstOrNull { it.expenseRatioPct != null }
            val base2y = cheapest?.let { perf?.resp?.funds?.get(it.symbol)?.returns?.get("2y") }
            g.funds.forEach { f ->
                val r2 = perf?.resp?.funds?.get(f.symbol)?.returns?.get("2y")
                val gap = if (f.symbol == cheapest?.symbol) "cheapest"
                else if (r2 != null && base2y != null) "${FundsLogic.pct(r2 - base2y).replace("%", " pts")} vs ${cheapest?.symbol}"
                else null
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable { onOpenDetail(Asset(f.symbol, com.stocktracker.app.data.model.AssetType.STOCK, f.name ?: f.symbol)) }
                        .padding(start = 8.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(f.symbol, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Text(FundCostText.shortName(f.name), style = MaterialTheme.typography.labelMedium, color = neutral,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val tags = FundCostText.tags(f, isSelf = false)
                        val line = listOfNotNull(tags.ifBlank { null }, gap?.let { "2 years: $it" }).joinToString(" · ")
                        if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.labelSmall,
                            color = if (f.fidelity) MaterialTheme.colorScheme.primary else neutral)
                    }
                    Text(FundCostText.rowFee(f), style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace)
                }
            }
            when {
                perf?.loading == true -> Text("Measuring 2-year results…", style = MaterialTheme.typography.labelSmall, color = neutral)
                perf?.failed == true -> Text("Couldn't load 2-year results.", style = MaterialTheme.typography.labelSmall, color = Signal)
                else -> Text("\"2 years\" is each fund's result minus the cheapest one's, dividends in: the fee plus any hidden costs.",
                    style = MaterialTheme.typography.labelSmall, color = neutral)
            }
            g.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = neutral) }
            g.funds.firstOrNull { it.feeSource == "issuer" }?.feeDated?.let {
                Text("* the fund company's own figure (${FundCostText.shortDate(it)}): Yahoo's was wrong or missing.",
                    style = MaterialTheme.typography.labelSmall, color = neutral)
            }
            g.funds.firstOrNull { it.feeSource == "saved" }?.feeDated?.let {
                Text("† from a saved list (${FundCostText.shortDate(it)}): Yahoo didn't answer.",
                    style = MaterialTheme.typography.labelSmall, color = neutral)
            }
        }
    }
}

@Composable
private fun FeeAlertToggle() {
    val settings = ServiceLocator.settingsStore
    val on by settings.fundFeeNotifyEnabled.collectAsState(initial = true)
    val scope = rememberCoroutineScope()
    GlowCard(tint = null, spacing = 2.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Fee change alerts", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text("Tell me when a fund I hold raises or cuts its fee.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = on, onCheckedChange = { v -> scope.launch { settings.setFundFeeNotifyEnabled(v) } })
        }
    }
}

