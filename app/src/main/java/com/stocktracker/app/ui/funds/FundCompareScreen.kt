package com.stocktracker.app.ui.funds

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.model.ChartRange
import com.stocktracker.app.data.model.PricePoint
import com.stocktracker.app.data.remote.FundOverlapResponse
import com.stocktracker.app.data.remote.FundPerformanceResponse
import com.stocktracker.app.data.remote.FundProfile
import com.stocktracker.app.data.remote.SignalsApiService
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.components.ChartLineOverlay
import com.stocktracker.app.ui.components.GlowCard
import com.stocktracker.app.ui.components.PriceChart
import com.stocktracker.app.ui.components.Skeleton
import com.stocktracker.app.ui.detail.FundCostText
import com.stocktracker.app.ui.theme.CategoricalRamp
import com.stocktracker.app.ui.theme.Signal
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** FUND-5 — two or three funds side by side: returns, worst drop, fees in dollars, overlap. */
class FundCompareViewModel(initial: List<String>) : ViewModel() {

    data class UiState(
        val symbols: List<String>,
        val loading: Boolean = false,
        val failed: Boolean = false,
        val overlap: FundOverlapResponse? = null,
        val perf: FundPerformanceResponse? = null,
    )

    private val api = SignalsApiService()
    private val _state = MutableStateFlow(UiState(initial.map { it.uppercase() }.distinct().take(MAX)))
    val state = _state.asStateFlow()
    private var job: Job? = null

    init { load() }

    fun add(raw: String) {
        val s = raw.trim().uppercase()
        if (s.isBlank() || s in _state.value.symbols || _state.value.symbols.size >= MAX) return
        // Old results cleared, so the new fund is never drawn against a response that lacks it.
        _state.update { it.copy(symbols = it.symbols + s, overlap = null, perf = null) }
        load()
    }

    fun remove(sym: String) {
        _state.update { it.copy(symbols = it.symbols - sym, overlap = null, perf = null) }
        load()
    }

    fun load() {
        job?.cancel()
        val syms = _state.value.symbols
        if (syms.isEmpty()) {
            _state.update { it.copy(loading = false, overlap = null, perf = null) }
            return
        }
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            val base = ServiceLocator.settingsStore.signalsApiUrl.first()
            val ov = async { runCatching { api.fundOverlap(base, syms) }.getOrNull() }
            val pf = async { runCatching { api.fundPerformance(base, syms, series = true) }.getOrNull() }
            val o = ov.await()
            val p = pf.await()
            ensureActive()
            _state.update { it.copy(loading = false, failed = o == null || p == null, overlap = o, perf = p) }
        }
    }

    companion object { const val MAX = 3 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FundCompareScreen(initial: List<String>, onBack: () -> Unit) {
    val vm: FundCompareViewModel = viewModel(key = "compare:" + initial.joinToString(",")) { FundCompareViewModel(initial) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compare funds") },
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
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ui.symbols.forEachIndexed { i, s ->
                    InputChip(
                        selected = true,
                        onClick = { vm.remove(s) },
                        label = { Text(s, fontFamily = FontFamily.Monospace) },
                        leadingIcon = { Swatch(drawnColor(s, ui.symbols, ui.perf)) },
                        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove $s", modifier = Modifier.size(16.dp)) },
                    )
                }
            }
            if (ui.symbols.size < FundCompareViewModel.MAX) {
                var text by rememberSaveable { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it.uppercase().take(12) },
                        label = { Text("Add a fund") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { vm.add(text); text = "" }),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { vm.add(text); text = "" }, enabled = text.isNotBlank()) { Text("Add") }
                }
            }
            when {
                ui.symbols.isEmpty() -> Text("Add up to three funds to compare.", style = MaterialTheme.typography.bodyMedium, color = neutral)
                ui.loading && ui.perf == null -> {
                    Skeleton(Modifier.fillMaxWidth().height(220.dp))
                    Skeleton(Modifier.fillMaxWidth().height(260.dp))
                }
                ui.perf == null || ui.overlap == null -> Column {
                    Text("Couldn't load the comparison.", style = MaterialTheme.typography.bodyMedium, color = Signal)
                    TextButton(onClick = { vm.load() }) { Text("Retry") }
                }
                else -> {
                    val notFunds = ui.symbols.filter { it in ui.overlap!!.notFunds }
                    if (notFunds.isNotEmpty()) {
                        Text("${notFunds.joinToString(", ")} ${if (notFunds.size == 1) "isn't a fund" else "aren't funds"}: " +
                            "returns shown, fund details left blank.", style = MaterialTheme.typography.labelMedium, color = Signal)
                    }
                    val unknown = ui.symbols.filter { it in ui.overlap!!.unknown }
                    if (unknown.isNotEmpty()) {
                        Text("Couldn't look up ${unknown.joinToString(", ")} right now: fund details left blank.",
                            style = MaterialTheme.typography.labelMedium, color = Signal)
                    }
                    ChartCard(ui.symbols, ui.perf!!)
                    TableCard(ui.symbols, ui.overlap!!, ui.perf!!)
                    OverlapCard(ui.symbols, ui.overlap!!)
                    Text(
                        "Returns include dividends. They show what these funds held, not what comes next; the fee is the one " +
                            "number you can count on. \"Fees over 20 years\" assumes 7% a year growth before fees. Context, not advice.",
                        style = MaterialTheme.typography.bodySmall, color = neutral, modifier = Modifier.padding(bottom = 20.dp),
                    )
                }
            }
        }
    }
}

private fun lineColor(i: Int): Color = CategoricalRamp[i % CategoricalRamp.size]

/**
 * The colour each fund is actually drawn in. PriceChart paints its main (first) series by direction,
 * green or red, so the key and the chips read that too: a key that shows purple for a green line is
 * wrong about the only thing it exists to say.
 */
private fun drawnColor(sym: String, symbols: List<String>, perf: FundPerformanceResponse?): Color {
    val chart = perf?.chart
    val drawn = symbols.filter { chart?.lines?.containsKey(it) == true }
    return if (drawn.isNotEmpty() && drawn.first() == sym) {
        if ((chart!!.lines.getValue(sym).lastOrNull() ?: 0.0) >= 0.0) com.stocktracker.app.ui.theme.GainGreen
        else com.stocktracker.app.ui.theme.LossRed
    } else lineColor(symbols.indexOf(sym))
}

@Composable
private fun Swatch(c: Color) {
    Canvas(Modifier.size(10.dp)) { drawCircle(c) }
}

@Composable
private fun ChartCard(symbols: List<String>, perf: FundPerformanceResponse) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val chart = perf.chart
    GlowCard(tint = null, spacing = 6.dp) {
        Text("Growth, dividends in", style = MaterialTheme.typography.labelLarge, color = neutral)
        val drawn = symbols.filter { chart?.lines?.containsKey(it) == true }
        if (chart == null || drawn.isEmpty() || chart.dates.size < 2) {
            Text("Not enough shared price history to draw.", style = MaterialTheme.typography.bodySmall, color = neutral)
            return@GlowCard
        }
        fun ms(d: String) = runCatching { LocalDate.parse(d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrDefault(0L)
        val primary = drawn.first()
        val points = chart.dates.mapIndexed { i, d -> PricePoint(ms(d), chart.lines.getValue(primary)[i]) }
        val overlays = drawn.drop(1).map { s ->
            ChartLineOverlay(s, lineColor(symbols.indexOf(s)), chart.lines.getValue(s))
        }
        PriceChart(
            points = points,
            up = (chart.lines.getValue(primary).lastOrNull() ?: 0.0) >= 0.0,
            showAxis = true,
            overlays = overlays,
            modifier = Modifier.fillMaxWidth().height(220.dp),
            valueFormatter = { String.format(Locale.US, "%+.0f%%", it) },
            timeFormatter = { com.stocktracker.app.util.formatChartTimestamp(it, ChartRange.ALL) },
            chartDescription = "Growth of ${drawn.joinToString(", ")} since ${chart.dates.first()}",
        )
        val since = runCatching { LocalDate.parse(chart.dates.first()).format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)) }
            .getOrDefault(chart.dates.first())
        Text("Since $since, the first week all of them traded. ${drawn.first()} is the solid line.",
            style = MaterialTheme.typography.labelSmall, color = neutral)
        drawn.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Swatch(drawnColor(s, symbols, perf))
                Text("$s  ${FundsLogic.pct(chart.lines.getValue(s).lastOrNull())}", style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun TableCard(symbols: List<String>, ov: FundOverlapResponse, perf: FundPerformanceResponse) {
    val profiles: List<FundProfile?> = symbols.map { ov.funds[it] }
    val fees = profiles.map { it?.expenseRatioPct }
    // "Cheapest" is a comparison: with fewer than two known fees there is nothing to rank.
    val minFee = fees.filterNotNull().takeIf { it.size >= 2 }?.minOrNull()
    GlowCard(tint = null, spacing = 4.dp) {
        TableRow("", symbols, header = true)
        TableRow("Yearly fee per \$10,000", fees.map { f -> f?.let { FundCostText.perTenK(it) } ?: "—" })
        TableRow("Fees over 20 years on \$10,000", fees.map { f ->
            when {
                f == null || minFee == null -> "—"
                f - minFee < 1e-9 -> "cheapest"
                else -> "+" + FundCostText.dollars(Math.round(FundsLogic.feeDrag(f, minFee)).toDouble())
            }
        })
        for ((label, key) in listOf("1 year" to "1y", "3 years" to "3y", "5 years" to "5y")) {
            TableRow(label, symbols.map { s ->
                val p = perf.funds[s]
                if (p == null || !p.available) "—" else FundsLogic.pct(p.returns[key])
            })
        }
        TableRow("Worst drop", symbols.map { s ->
            perf.funds[s]?.takeIf { it.available }
                ?.let { FundsLogic.worstDrop(it.worstDropPct, it.worstDropFrom, it.worstDropTo).replaceFirst(" (", "\n(") } ?: "—"
        }, mono = false)
        TableRow("Holds", profiles.map { it?.regionLabel ?: "—" }, mono = false)
        TableRow("Biggest sectors", profiles.map { p ->
            p?.sectors?.take(2)?.joinToString("\n") { "${it.label} ${String.format(Locale.US, "%.0f", it.pct)}%" }
                ?.ifBlank { null } ?: "—"
        }, mono = false)
        TableRow("Fund size", profiles.map { FundsLogic.size(it?.netAssets) ?: "—" }, mono = false)
        TableRow("Trading gap, \$10,000 round trip", profiles.map { p ->
            when {
                p == null -> "—"
                p.isMutualFund -> "none"
                p.spreadPct != null -> FundCostText.dollars(p.spreadPct * 100.0)
                else -> "—"
            }
        })
        profiles.filterNotNull().mapNotNull { it.spreadAt.takeIf { _ -> !it.isMutualFund } }.minOrNull()?.let { oldest ->
            FundsLogic.ago(oldest)?.let {
                Text("Trading gaps as last measured in market hours; oldest reading $it.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        perf.alignedTo?.let { d ->
            Text("Returns measured to $d, the latest day all of them have a price.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        profiles.forEach { p -> FundsLogic.smallFundWarning(p?.netAssets)?.let {
            Text("${p!!.symbol}: $it", style = MaterialTheme.typography.labelSmall, color = Signal)
        } }
        if (profiles.any { it != null && !it.isMutualFund && it.spreadPct == null }) {
            Text("Trading gaps are measured during market hours.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TableRow(label: String, cells: List<String>, header: Boolean = false, mono: Boolean = true) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = neutral, modifier = Modifier.weight(1.3f))
        cells.forEach { c ->
            Text(
                c,
                style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun OverlapCard(symbols: List<String>, ov: FundOverlapResponse) {
    val funds = symbols.filter { it in ov.funds }
    if (funds.size < 2) return
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    GlowCard(tint = null, spacing = 4.dp) {
        Text("How much they overlap", style = MaterialTheme.typography.labelLarge, color = neutral)
        for (i in funds.indices) for (j in i + 1 until funds.size) {
            val p = ov.pair(funds[i], funds[j]) ?: continue
            val v = PairVerdict.of(p.corr)
            Text("${funds[i]} & ${funds[j]}: ${v.words.lowercase()} (${FundsLogic.corr(p.corr)})",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            val detail = listOfNotNull(
                "${p.sharedTopCount} top holdings shared".takeIf { p.sharedTopCount > 0 },
                p.sectorAlikePct?.let { "sector mix ${String.format(Locale.US, "%.0f", it)}% alike" },
            )
            if (detail.isNotEmpty()) Text(detail.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = neutral)
        }
    }
}
