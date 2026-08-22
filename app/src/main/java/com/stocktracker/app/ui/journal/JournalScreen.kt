package com.stocktracker.app.ui.journal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.stocktracker.app.data.model.ExitTaxonomy
import com.stocktracker.app.data.model.JournalComparison
import com.stocktracker.app.data.model.JournalReplay
import com.stocktracker.app.data.model.JournalStatus
import com.stocktracker.app.data.model.PairedStat
import com.stocktracker.app.data.model.RiskMultiple
import com.stocktracker.app.data.model.TakenState
import com.stocktracker.app.data.model.VerdictJournal
import com.stocktracker.app.data.model.VerdictJournalEntry
import com.stocktracker.app.ui.components.BackendStatusBanner
import com.stocktracker.app.ui.components.PairedStatBlock
import com.stocktracker.app.ui.ideas.usd
import com.stocktracker.app.ui.theme.BenchmarkGrey
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.LossRed
import java.time.LocalDate

/**
 * THE VERDICT JOURNAL (SWT-8) — what you did with each verdict, drawn against what the plan would
 * have done on its own.
 *
 * The equity twin of MyCallsSection: log a position, then review the history. Two things make this
 * screen different from every other in the app, and both are about honesty rather than layout.
 *
 * DECLINING IS ONE TAP, everywhere a verdict appears. If passing were harder than ignoring, only the
 * trades you took would ever be recorded, the denominator would quietly become "verdicts I liked
 * enough to log", and the headline — how many verdicts you passed on — would be silently wrong in
 * the direction that flatters. The "Passed" button is therefore a peer of "Took it" on every
 * undecided row, not a menu item behind a dialog.
 *
 * THE TWO CURVES SHARE A POPULATION OR THEY ARE NOT DRAWN. See [JournalComparison]: an entry with no
 * replay, an unfilled plan or a trade still running belongs to NEITHER series, the exclusions are
 * counted by reason under the chart, and with nothing to draw the screen says what is missing rather
 * than showing empty axes — an empty chart with axes reads as "you made nothing", which is a claim
 * about a track record that does not exist yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(onBack: () -> Unit) {
    val vm: JournalViewModel = viewModel()
    val ui by vm.state.collectAsStateWithLifecycle()
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant

    val record = remember(ui.entries) { VerdictJournal.record(ui.entries) }
    val paired = remember(ui.entries) { JournalComparison.pair(ui.entries) }
    var detailId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Verdict journal") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    val pending = ui.needingReplay.size
                    IconButton(
                        onClick = { vm.replayPending() },
                        enabled = !ui.replaying && pending > 0 && ui.configured == true,
                    ) {
                        if (ui.replaying) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, "Replay plans")
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BackendStatusBanner()

            if (ui.entries.isEmpty()) {
                Text(
                    if (ui.loaded) "No verdicts logged yet." else "Loading…",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 24.dp),
                )
                Text(
                    "Open any ticker, ask for an entry plan, and tap “Log to journal”. Then say what you " +
                        "did — including passing on it. The passes are half the point: a journal that only " +
                        "holds the trades you took can't tell you anything about the ones you skipped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = neutral,
                )
                Box(Modifier.height(24.dp))
                return@Column
            }

            HeadlineCard(record, paired)
            CurvesCard(paired, ui)

            ui.replayError?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = LossRed)
            }
            if (ui.replaying && ui.replayTotal > 0) {
                Text(
                    "Replaying plans — ${ui.replayDone} of ${ui.replayTotal}. Each one walks the daily bars " +
                        "that followed it, so this takes a moment.",
                    style = MaterialTheme.typography.labelSmall,
                    color = neutral,
                )
            } else if (ui.configured == true && ui.needingReplay.isNotEmpty()) {
                Text(
                    "${ui.needingReplay.size} plan${if (ui.needingReplay.size == 1) "" else "s"} not replayed " +
                        "yet — tap the refresh icon to ask the analyst service what they would have done.",
                    style = MaterialTheme.typography.labelSmall,
                    color = neutral,
                )
            } else if (ui.configured == false) {
                Text(
                    "No Signals service URL set, so no plan can be replayed — the journal still records " +
                        "everything you did, it just has nothing to draw it against.",
                    style = MaterialTheme.typography.labelSmall,
                    color = neutral,
                )
            }

            // Undecided first: those are the rows asking you for something.
            Section("Waiting on you", ui.entries.filter { it.taken == TakenState.UNDECIDED }, vm) { detailId = it }
            Section("Taken — still open", ui.entries.filter { it.isOpen || it.status == JournalStatus.TAKEN_UNFILLED }, vm) { detailId = it }
            Section("Closed", ui.entries.filter { it.isClosed }, vm) { detailId = it }
            Section("Passed", ui.entries.filter { it.taken == TakenState.NOT_TAKEN }, vm) { detailId = it }

            Box(Modifier.height(16.dp))
        }
    }

    detailId?.let { id ->
        val entry = ui.entries.firstOrNull { it.id == id }
        if (entry == null) {
            detailId = null
        } else {
            JournalEntryDialog(
                entry = entry,
                replaying = ui.replaying,
                configured = ui.configured == true,
                onTaken = { price, shares, date -> vm.markTaken(entry, price, shares, date) },
                onDeclined = { vm.markDeclined(entry) },
                onUndecided = { vm.markUndecided(entry) },
                onExit = { price, date -> vm.recordExit(entry, price, date) },
                onReplay = { vm.replayOne(entry) },
                onDelete = { vm.delete(entry.id); detailId = null },
                onDismiss = { detailId = null },
            )
        }
    }
}

/**
 * The headline: what you decided, what you got, and what the plan got over the same trades.
 *
 * EVERY PERFORMANCE NUMBER HERE IS A [PairedStat] (SWT-9), never a bare figure. The two expectancies
 * belong to different KINDS of evidence — the plan's is a simulation walked over bars that had
 * already traded, yours is a record of fills taken before the outcome was known — and the labels say
 * which is which, because the second is the one worth trusting as it accumulates and the first is the
 * one that flatters. Your own record over EVERY close is a pair too, with its simulated half stated
 * as absent: no replay covers the closes the plan was never run against, and letting that number
 * stand alone is how a forward figure quietly acquires the authority of a tested one.
 *
 * Under the shared small-sample floor no expectancy is dressed up as an edge; the counts are printed
 * and the caveat travels with the number rather than sitting where it can be missed.
 */
@Composable
private fun HeadlineCard(record: VerdictJournal.ActualRecord, paired: JournalComparison.Paired) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "${record.entryCount} verdict${if (record.entryCount == 1) "" else "s"} logged",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        // COUNTS, always. This line is the feature's headline and it is a ratio of two integers, not
        // a measurement — "you took 4, passed 6" says everything a percentage would and cannot be
        // mistaken for a rate estimated over a sample.
        Text(
            "${record.takenCount} taken · ${record.notTakenCount} passed" +
                if (record.undecidedCount > 0) " · ${record.undecidedCount} undecided" else "",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        val decided = record.takenCount + record.notTakenCount
        if (decided == 0) {
            Text(
                "Nothing decided yet — undecided entries are not passes, so they count toward neither.",
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
            )
        } else {
            val rate = record.takeRatePct
            Text(
                if (rate != null && decided >= RiskMultiple.MIN_SCORED_FOR_EXPECTANCY) {
                    "You act on ${"%.0f".format(rate)}% of the verdicts you decide on."
                } else {
                    // Under the floor a percentage over a handful of decisions reads as a habit when
                    // it is an accident of four coin flips.
                    "You took ${record.takenCount} of the $decided verdicts you decided on."
                },
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
            )
        }

        // --- your own R record, over every close you have ---
        //
        // A PAIR WITH ONE HALF MISSING, not a lone number. Your expectancy over every close is the
        // app's most flattering-looking figure and it has no simulated twin over the same trades:
        // an entry the backend never replayed is on your side of the ledger and on nobody else's.
        // Stating that absence is the whole job — an unqualified "+0.8R" here reads as a tested
        // result rather than as what it is, a handful of real trades.
        val overall = record.expectancyR
        if (overall != null) {
            PairedStatBlock(
                stat = PairedStat(
                    label = "Your expectancy, over every close you have",
                    unit = PairedStat.StatUnit.R_MULTIPLE,
                    backtest = PairedStat.Side.backtest(
                        subject = "the plan, over these same closes",
                        sample = null,
                        absentReason = if (paired.pairedCount > 0) {
                            "no replay covers all ${record.scored} of them — the plan's figure is " +
                                "below, over the ${paired.pairedCount} both sides could take"
                        } else {
                            "no plan here has been replayed yet, so there is nothing to compare " +
                                "these closes against"
                        },
                    ),
                    forward = PairedStat.Side.forward(
                        subject = "your real fills",
                        sample = PairedStat.StatSample.of(n = record.scored, value = overall),
                        absentReason = "nothing of yours has been scored yet",
                    ),
                ),
                modifier = Modifier.padding(top = 8.dp),
                tint = { side -> side.sample?.value?.let { if (it >= 0.0) GainGreen else LossRed } },
            )
            if (record.unscoreable > 0) {
                Text(
                    "${record.unscoreable} of your ${record.closedCount} closes are not scored at all — " +
                        "the plan snapshot carried no stop, so there is no risk to divide by.",
                    style = MaterialTheme.typography.labelSmall,
                    color = neutral,
                )
            }
        } else if (record.closedCount > 0) {
            Text(
                "No expectancy in R yet — none of your ${record.closedCount} closed entries could be " +
                    "scored. R needs the stop the plan was written with, and a plan snapshotted " +
                    "without one can never be scored after the fact.",
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // --- you vs the plan, over the SHARED population only ---
        //
        // The pairing this screen exists for. Both halves come out of JournalComparison, so they are
        // the same trades in the same order and their expectancies are comparable by construction;
        // the labels carry the difference that matters, which is that one of them was simulated.
        val mine = paired.yours.avgR
        val plan = paired.mechanical.avgR
        if (mine != null || plan != null) {
            val population = JournalComparison.populationSentence(paired)
            PairedStatBlock(
                stat = PairedStat(
                    label = "Expectancy per trade, over the entries both sides could take",
                    unit = PairedStat.StatUnit.R_MULTIPLE,
                    backtest = PairedStat.Side.backtest(
                        subject = "the plan as written, replayed bar by bar",
                        sample = PairedStat.StatSample.of(paired.mechanical.scored, plan),
                        absentReason = population,
                    ),
                    forward = PairedStat.Side.forward(
                        subject = "your real fills on the same entries",
                        sample = PairedStat.StatSample.of(paired.yours.scored, mine),
                        absentReason = population,
                    ),
                ),
                modifier = Modifier.padding(top = 8.dp),
                tint = { side -> side.sample?.value?.let { if (it >= 0.0) GainGreen else LossRed } },
            )
            paired.executionGapR?.let { gap ->
                Text(
                    if (gap >= 0) {
                        "You are ${RiskMultiple.format(gap)} ahead of the mechanical plan in total."
                    } else {
                        "You are ${RiskMultiple.format(gap)} behind the mechanical plan in total."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (gap >= 0) GainGreen else LossRed,
                )
            }
            Text(
                PairedStat.EVIDENCE_NOTE,
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
            )
        }
    }
}

/** Your cumulative R against the plan's, plus the population the comparison is honest over. */
@Composable
private fun CurvesCard(paired: JournalComparison.Paired, ui: JournalUiState) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("You vs the plan", style = MaterialTheme.typography.labelLarge, color = neutral)

        val mechanical = paired.curve.mechanical
        if (paired.isEmpty || mechanical == null) {
            // NO EMPTY AXES. A chart frame with nothing in it reads as a flat, break-even record;
            // what is true is that no entry yet has both halves, and the reasons are the useful part.
            Text(
                "Nothing to draw yet.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                JournalComparison.populationSentence(paired),
                style = MaterialTheme.typography.bodySmall,
                color = neutral,
            )
            Text(
                if (ui.configured != true) {
                    "A curve needs both halves: your closed trades, and the same plans replayed by the " +
                        "signals service. Set its URL in Settings to get the second one."
                } else {
                    "A curve needs both halves: an entry you took, filled and closed, AND a replay of the " +
                        "same plan. Nothing is drawn from one of them alone."
                },
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
            )
            return@Column
        }

        val actual = paired.curve.actual
        // Non-null past the isEmpty guard above — the curve has points, so it has an endpoint. Read
        // through a fallback colour rather than a fallback NUMBER: a `?: 0.0` here would be the house
        // defect in miniature, an absent total silently becoming a break-even one.
        val finalMine = paired.curve.finalActualR
        val yourColor = when {
            finalMine == null -> MaterialTheme.colorScheme.primary
            finalMine >= 0.0 -> GainGreen
            else -> LossRed
        }
        TwoCurveChart(
            actual = actual,
            mechanical = mechanical,
            yourColor = yourColor,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendDot("You", yourColor, dashed = false)
            LegendDot("The plan", BenchmarkGrey, dashed = true)
        }
        val finalPlan = paired.curve.finalMechanicalR
        if (finalMine != null && finalPlan != null) {
            Text(
                "You ${RiskMultiple.format(finalMine)} · the plan ${RiskMultiple.format(finalPlan)} cumulative",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
        // The population, stated. Without it the picture is a claim with no denominator.
        Text(
            JournalComparison.populationSentence(paired),
            style = MaterialTheme.typography.labelSmall,
            color = neutral,
        )
        if (paired.ambiguousCount > 0) {
            // The one number that says how much of the grey line is tape and how much is convention.
            Text(
                "${paired.ambiguousCount} of the plan's ${paired.pairedCount} trades hit the stop and the " +
                    "target inside the same day. The bars can't say which came first, so the replay " +
                    "assumed the stop — the pessimistic reading.",
                style = MaterialTheme.typography.labelSmall,
                color = neutral,
            )
        }
        Text(
            "Both curves are the same trades in the order YOU closed them, so they can be read against " +
                "each other point for point. The gap between them is execution, not analysis.",
            style = MaterialTheme.typography.labelSmall,
            color = neutral,
        )
    }
}

/**
 * Two cumulative-R lines on one pair of axes, plotted by index so the points stand side by side.
 *
 * The plan's line is grey AND dashed. Shape is a second channel alongside hue — roughly 8% of men
 * have red-green colour vision deficiency and your line is spending green or red on its result — and
 * it also says something true: the mechanical curve is a reference, not a gain or a loss of yours,
 * so it should not be spending a semantic colour at all. Same reasoning as [ChartLineOverlay.dashed].
 */
@Composable
private fun TwoCurveChart(
    actual: List<VerdictJournal.CurvePoint>,
    mechanical: List<VerdictJournal.CurvePoint>,
    yourColor: Color,
    modifier: Modifier = Modifier,
) {
    val zeroColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    Canvas(modifier) {
        val mine = actual.map { it.cumulativeR }
        val plan = mechanical.map { it.cumulativeR }
        if (mine.isEmpty() || plan.isEmpty()) return@Canvas

        // Zero is always in frame: a cumulative-R curve is read against break-even, and a chart scaled
        // to its own extremes hides whether the whole run is under water.
        val lo = minOf(mine.min(), plan.min(), 0.0)
        val hi = maxOf(mine.max(), plan.max(), 0.0)
        val range = (hi - lo).takeIf { it > 0.0 } ?: 1.0
        val pad = 6.dp.toPx()
        fun yOf(v: Double) = pad + (1f - ((v - lo) / range).toFloat()) * (size.height - 2 * pad)
        fun xOf(i: Int) = if (mine.size == 1) size.width / 2f else i * size.width / (mine.size - 1)

        val zeroY = yOf(0.0)
        drawLine(
            color = zeroColor,
            start = Offset(0f, zeroY),
            end = Offset(size.width, zeroY),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()), 0f),
        )

        fun draw(values: List<Double>, color: Color, dashed: Boolean) {
            if (values.size == 1) {
                // One shared trade is a point, not a line. Drawing a segment from an implied origin
                // would invent a second observation.
                drawCircle(color, radius = 3.5.dp.toPx(), center = Offset(xOf(0), yOf(values[0])))
                return
            }
            val path = Path()
            values.forEachIndexed { i, v ->
                if (i == 0) path.moveTo(xOf(i), yOf(v)) else path.lineTo(xOf(i), yOf(v))
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = if (dashed) {
                        PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()), 0f)
                    } else {
                        null
                    },
                ),
            )
        }
        draw(plan, BenchmarkGrey, dashed = true)
        draw(mine, yourColor, dashed = false)
    }
}

@Composable
private fun LegendDot(label: String, color: Color, dashed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Canvas(Modifier.size(width = 18.dp, height = 6.dp)) {
            drawLine(
                color = color,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = if (dashed) {
                    PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()), 0f)
                } else {
                    null
                },
            )
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Section(
    title: String,
    entries: List<VerdictJournalEntry>,
    vm: JournalViewModel,
    onOpen: (String) -> Unit,
) {
    if (entries.isEmpty()) return
    Text(
        "$title (${entries.size})",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp),
    )
    entries.forEach { EntryRow(it, vm, onOpen) }
}

/**
 * One journal row.
 *
 * An UNDECIDED row carries both decisions inline, and "Passed" writes immediately — see the file
 * doc. "Took it" opens a dialog only because it needs a fill price to be worth anything; passing
 * needs nothing from you, so it asks for nothing.
 */
@Composable
private fun EntryRow(entry: VerdictJournalEntry, vm: JournalViewModel, onOpen: (String) -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    var fillPrompt by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(entry.id) }
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.symbol.uppercase(), fontWeight = FontWeight.Bold)
                Text(
                    "verdict ${entry.verdictDateIso}" + planLevels(entry)?.let { " · $it" }.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = neutral,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // R prints ONLY where it exists. No dash standing in for a number, no 0.0R standing
                // in for a trade that has not finished.
                entry.rMultiple?.let {
                    Text(
                        RiskMultiple.format(it),
                        fontWeight = FontWeight.Medium,
                        color = if (it >= 0) GainGreen else LossRed,
                    )
                }
                Text(
                    "plan: " + JournalReplay.describe(entry.replay) +
                        (entry.mechanicalR?.let { " ${RiskMultiple.format(it)}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = neutral,
                )
            }
        }
        if (entry.taken == TakenState.UNDECIDED) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { fillPrompt = true }) { Text("Took it") }
                // ONE TAP. No confirmation, no dialog — see the file doc.
                OutlinedButton(onClick = { vm.markDeclined(entry) }) { Text("Passed") }
            }
        }
    }

    if (fillPrompt) {
        FillDialog(
            entry = entry,
            onConfirm = { price, shares, date -> vm.markTaken(entry, price, shares, date); fillPrompt = false },
            onDismiss = { fillPrompt = false },
        )
    }
}

/** "$98–102 · stop $90 · target $130", omitting whatever the analyst never supplied. Null when none. */
private fun planLevels(entry: VerdictJournalEntry): String? {
    val p = entry.plan
    val zone = when {
        p.entryLow != null && p.entryHigh != null -> "${usd(p.entryLow!!)}–${usd(p.entryHigh!!)}"
        p.entryHigh != null -> "at ${usd(p.entryHigh!!)}"
        p.entryLow != null -> "at ${usd(p.entryLow!!)}"
        else -> null
    }
    // A $0 level would read as a real instruction. Absent levels are omitted, never zero-filled.
    val bits = listOfNotNull(zone, p.stop?.let { "stop ${usd(it)}" }, p.target?.let { "target ${usd(it)}" })
    return bits.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** Full entry: the plan as snapshotted, what you did, what the plan did, and why it is (not) on the curves. */
@Composable
private fun JournalEntryDialog(
    entry: VerdictJournalEntry,
    replaying: Boolean,
    configured: Boolean,
    onTaken: (Double?, Double?, String?) -> Unit,
    onDeclined: () -> Unit,
    onUndecided: () -> Unit,
    onExit: (Double, String?) -> Unit,
    onReplay: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    var fillPrompt by remember { mutableStateOf(false) }
    var exitPrompt by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${entry.symbol.uppercase()} · ${entry.verdictDateIso}") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(statusLabel(entry), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                Text("The plan you were given", style = MaterialTheme.typography.labelLarge, color = neutral, modifier = Modifier.padding(top = 6.dp))
                entry.plan.action?.let { StatRow("Action", it.replace('_', ' ')) }
                planLevels(entry)?.let { StatRow("Levels", it) }
                entry.plan.conviction?.let { StatRow("Conviction", "$it/100") }
                entry.plan.thesis?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                if (!entry.plan.hasLevels) {
                    Text(
                        "This plan was snapshotted with no stop and no target, so nothing about it can be " +
                            "scored in R or bucketed by how it ended.",
                        style = MaterialTheme.typography.labelSmall,
                        color = neutral,
                    )
                }

                Text("What you did", style = MaterialTheme.typography.labelLarge, color = neutral, modifier = Modifier.padding(top = 8.dp))
                // Every one of these prints only when it exists. A missing fill is not a fill of zero.
                entry.fillPrice?.let { StatRow("Your fill", usd(it)) }
                entry.shares?.let { StatRow("Shares", plainNum(it)) }
                entry.fillDateIso?.let { StatRow("Bought", it) }
                entry.exitPrice?.let { StatRow("Your exit", usd(it)) }
                entry.exitDateIso?.let { StatRow("Sold", it) }
                entry.realizedPnl?.let { StatRow("Realized", usd(it)) }
                entry.rMultiple?.let { StatRow("Your R", RiskMultiple.format(it)) }
                entry.exitKind?.let { StatRow("Ended", ExitTaxonomy.label(it)) }
                if (entry.taken == TakenState.NOT_TAKEN) {
                    Text(
                        "You passed on this one. It counts in the taken-vs-passed headline and in nothing " +
                            "else — a trade you did not take has no result to score.",
                        style = MaterialTheme.typography.labelSmall,
                        color = neutral,
                    )
                }

                Text("What the plan did", style = MaterialTheme.typography.labelLarge, color = neutral, modifier = Modifier.padding(top = 8.dp))
                Text(JournalReplay.describe(entry.replay), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                val r = entry.replay
                if (r == null) {
                    Text(
                        if (configured) {
                            "Nobody has asked the service what this plan would have done yet."
                        } else {
                            "No Signals service URL set, so this plan has never been replayed."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = neutral,
                    )
                } else {
                    r.entryPrice?.let { StatRow("Plan filled", usd(it) + (r.entryDate?.let { d -> " on $d" } ?: "")) }
                    r.exitPrice?.let { StatRow("Plan exited", usd(it) + (r.exitDate?.let { d -> " on $d" } ?: "")) }
                    // A MARK, never in the exit row: the plan is still open and has not sold anything.
                    r.markPrice?.let { StatRow("Marked at", usd(it) + (r.markDate?.let { d -> " on $d" } ?: "")) }
                    r.barsHeld?.let { StatRow("Sessions held", it.toString()) }
                    r.scoredR?.let { StatRow("Plan's R", RiskMultiple.format(it)) }
                    if (r.isResolved && r.scoredR == null) {
                        Text(
                            "The replay finished but carries no R — the plan named no stop, so there is no " +
                                "risk to divide by. Not 0R, which would say it made exactly what it risked.",
                            style = MaterialTheme.typography.labelSmall,
                            color = neutral,
                        )
                    }
                    if (r.ambiguous) {
                        Text(
                            "One session touched BOTH the stop and the target. Daily bars can't say which " +
                                "came first, so the replay took the stop — against the trade.",
                            style = MaterialTheme.typography.labelSmall,
                            color = neutral,
                        )
                    }
                    r.reason?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = neutral)
                    }
                    r.barsSkipped?.takeIf { it > 0 }?.let {
                        Text(
                            "$it session${if (it == 1) "" else "s"} in the window were unusable and were " +
                                "walked over.",
                            style = MaterialTheme.typography.labelSmall,
                            color = neutral,
                        )
                    }
                }

                // Why this entry is or is not on the chart, said on the entry itself rather than only
                // in an aggregate the user has to reverse-engineer.
                val why = JournalComparison.exclusionFor(entry)
                Text(
                    when {
                        entry.taken != TakenState.TAKEN ->
                            "Not on the curves — the curves cover trades you took."
                        why == null -> "On both curves."
                        else -> "Not on either curve — ${JournalComparison.label(why)}."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = neutral,
                    modifier = Modifier.padding(top = 8.dp),
                )
                entry.notes?.takeIf { it.isNotBlank() }?.let {
                    Text("Notes", style = MaterialTheme.typography.labelLarge, color = neutral, modifier = Modifier.padding(top = 6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }

                Text("Record what happened", style = MaterialTheme.typography.labelLarge, color = neutral, modifier = Modifier.padding(top = 10.dp))
                if (entry.taken != TakenState.TAKEN) {
                    OutlinedButton(onClick = { fillPrompt = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("I took it — record my fill")
                    }
                }
                if (entry.taken != TakenState.NOT_TAKEN) {
                    OutlinedButton(
                        onClick = { onDeclined(); onDismiss() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("I passed on it") }
                }
                if (entry.taken == TakenState.TAKEN && entry.fillPrice != null && entry.exitPrice == null) {
                    OutlinedButton(onClick = { exitPrompt = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("I sold — record my exit")
                    }
                }
                if (entry.taken == TakenState.TAKEN) {
                    OutlinedButton(onClick = { fillPrompt = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Edit my fill")
                    }
                }
                if (entry.taken != TakenState.UNDECIDED) {
                    TextButton(onClick = { onUndecided(); onDismiss() }) { Text("Back to undecided") }
                }
                OutlinedButton(
                    onClick = onReplay,
                    enabled = configured && !replaying,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (entry.replay == null) "Replay this plan" else "Replay again") }
                r?.replayedAtMs?.let {
                    // WHEN this was recorded, because a replay is evidence with a date on it: the
                    // horizon defaults can move and a vendor can restate a split, so "the plan made
                    // +2R" is only meaningful alongside when we asked.
                    val day = java.time.Instant.ofEpochMilli(it)
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    Text(
                        "Replayed $day",
                        style = MaterialTheme.typography.labelSmall,
                        color = neutral,
                    )
                }
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Delete this entry", color = LossRed)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )

    if (fillPrompt) {
        FillDialog(
            entry = entry,
            onConfirm = { price, shares, date -> onTaken(price, shares, date); fillPrompt = false },
            onDismiss = { fillPrompt = false },
        )
    }
    if (exitPrompt) {
        ExitDialog(
            entry = entry,
            onConfirm = { price, date -> onExit(price, date); exitPrompt = false },
            onDismiss = { exitPrompt = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this entry?") },
            text = {
                Text(
                    "It disappears from the taken-vs-passed counts and from both curves. Deleting the " +
                        "verdicts that went badly is how a track record becomes fiction.",
                    style = MaterialTheme.typography.bodySmall,
                    color = neutral,
                )
            },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete", color = LossRed) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Your real fill.
 *
 * The price and the share count are OPTIONAL. "I took it, I'll enter the numbers tonight" is a real
 * state the model has a name for, and forcing a number here would either lose the decision or invite
 * a made-up one — and a made-up fill scores a made-up R.
 */
@Composable
private fun FillDialog(
    entry: VerdictJournalEntry,
    onConfirm: (Double?, Double?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val today = remember { LocalDate.now().toString() }
    var price by remember { mutableStateOf(entry.fillPrice?.let { plainNum(it) } ?: "") }
    var shares by remember { mutableStateOf(entry.shares?.let { plainNum(it) } ?: "") }
    var date by remember { mutableStateOf(entry.fillDateIso ?: today) }
    // NaN and Infinity are what `toDoubleOrNull` hands back for "NaN" and "Infinity" typed into a
    // decimal field. They are not prices: a non-finite fill poisons the cost basis, the realized P&L
    // and every aggregate the entry ever reaches, and it renders as "$NaN" on the way there.
    val p = price.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    val s = shares.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("You took ${entry.symbol.uppercase()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "What you actually paid — not the plan's entry zone. R is measured from YOUR fill " +
                        "against the plan's stop, which is the only way the number describes your " +
                        "execution instead of re-scoring the analyst.",
                    style = MaterialTheme.typography.bodySmall,
                    color = neutral,
                )
                OutlinedTextField(
                    value = price, onValueChange = { price = it },
                    label = { Text("Fill price / share") }, prefix = { Text("$") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = shares, onValueChange = { shares = it },
                    label = { Text("Shares") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = date, onValueChange = { date = it },
                    label = { Text("Date bought (yyyy-mm-dd)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (p != null && entry.plan.stop != null) {
                    val risk = p - entry.plan.stop!!
                    Text(
                        if (risk > 0) {
                            "Risk ${usd(risk)}/share to the plan's stop — that is your 1R."
                        } else {
                            "The plan's stop is at or above this fill, so this entry can't be scored in R."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = neutral,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(p, s, date) }) { Text("Record") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Your real exit. A price is REQUIRED here — that is what closing means. */
@Composable
private fun ExitDialog(
    entry: VerdictJournalEntry,
    onConfirm: (Double, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val today = remember { LocalDate.now().toString() }
    var price by remember { mutableStateOf(entry.exitPrice?.let { plainNum(it) } ?: "") }
    var date by remember { mutableStateOf(entry.exitDateIso ?: today) }
    // A zero exit is legal — an equity can go to nothing — but a non-finite one is not a price.
    val p = price.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
    val preview = p?.let { exit ->
        entry.fillPrice?.let { fill -> RiskMultiple.rMultiple(entry = fill, exit = exit, stop = entry.plan.stop) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("You sold ${entry.symbol.uppercase()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = price, onValueChange = { price = it },
                    label = { Text("Exit price / share") }, prefix = { Text("$") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = date, onValueChange = { date = it },
                    label = { Text("Date sold (yyyy-mm-dd)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "The date puts this trade in the sequence both curves are drawn in — without it the " +
                        "entry is counted but can't be plotted.",
                    style = MaterialTheme.typography.labelSmall,
                    color = neutral,
                )
                // Only when it exists. No preview at all beats a confident 0.0R on an unscoreable plan.
                preview?.let {
                    Text(
                        "That is ${RiskMultiple.format(it)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (it >= 0) GainGreen else LossRed,
                    )
                }
            }
        },
        confirmButton = {
            // Latch on first press: the dialog unmounts asynchronously and a fast double-tap would
            // write the exit twice.
            var submitting by remember { mutableStateOf(false) }
            TextButton(
                enabled = p != null && !submitting,
                onClick = { submitting = true; onConfirm(p!!, date) },
            ) { Text("Record exit") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun statusLabel(entry: VerdictJournalEntry): String = when (entry.status) {
    JournalStatus.UNDECIDED -> "No decision recorded yet"
    JournalStatus.NOT_TAKEN -> "You passed"
    JournalStatus.TAKEN_UNFILLED -> "Taken — no fill recorded"
    JournalStatus.OPEN -> "Taken — still open"
    JournalStatus.CLOSED -> "Closed"
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun plainNum(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
