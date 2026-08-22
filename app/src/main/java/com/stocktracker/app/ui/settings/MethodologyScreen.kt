package com.stocktracker.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stocktracker.app.data.model.PairedStat
import com.stocktracker.app.data.model.RiskMultiple

/**
 * HOW THESE NUMBERS ARE MADE (SWT-10) — the page a sceptical reader uses to check the work.
 *
 * NOT A DISCLAIMER. A disclaimer protects the author; this exists to make the app's own figures
 * falsifiable. Every claim on it names a real threshold, a real file, or a real decision, so a reader
 * who does not believe a number can go and look. A page of generic hedging ("past performance is no
 * guarantee…") would be worth nothing here, because nothing on it could be checked.
 *
 * IT ALSO NAMES THE GUESSES. The 40-session replay horizon, the 5-day fill window, the 10× split
 * guard, the 20-trade floor, the 10 bps fee: none of these was measured, all of them were chosen.
 * Presenting a chosen constant as though it were derived is the same class of dishonesty as
 * presenting a 23-trade sample as an edge, and the whole SWT wave exists to stamp that out — so the
 * judgement calls get their own section rather than being quietly folded into the prose.
 *
 * THE THRESHOLDS ARE READ FROM THE CODE, not typed in as text. [RiskMultiple.MIN_SCORED_FOR_EXPECTANCY]
 * and [PairedStat.FLOOR] are interpolated live, so the page cannot drift out of step with the app the
 * way a hand-written "we use 20 trades" would the first time the constant moved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MethodologyScreen(onBack: () -> Unit) {
    val floor = RiskMultiple.MIN_SCORED_FOR_EXPECTANCY

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("How these numbers are made") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "What the figures in this app do not account for — in enough detail that you can go " +
                    "and check. Nothing here is legal boilerplate; every threshold named below is a " +
                    "real constant, and every one that was chosen rather than measured says so.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )

            Section(
                title = "Simulated, or actually recorded?",
                source = "PairedStat.kt · JournalComparison.kt",
                paragraphs = listOf(
                    "Two completely different kinds of number appear in this app and they are always " +
                        "labelled. A BACKTESTED figure is a rule run over bars that had already traded: " +
                        "the outcome existed before the question was asked. A FORWARD figure is a call " +
                        "recorded before the outcome was known, then scored later.",
                    "Backtested here: the win rate, drawdown and edge-vs-buy-and-hold on a stock's " +
                        "Signals card, and the grey “plan” curve in the verdict journal. Forward " +
                        "here: your own fills in the journal, the options history, and the analyst " +
                        "scorecards the signals service keeps.",
                    "Only the forward half is a track record, and it is the half worth trusting as it " +
                        "accumulates. That is why no performance number in this app is shown without " +
                        "its opposite half beside it, each with the sample it was measured over — or, " +
                        "where the other half does not exist, an explicit statement that it does not. " +
                        "A missing forward record is not a forward record of zero.",
                ),
            )

            Section(
                title = "No slippage, no spread, almost no fees",
                source = "Backtest.kt · POST /journal/replay",
                paragraphs = listOf(
                    "The on-device backtest fills at the closing price of the bar that produced the " +
                        "signal and charges a flat 10 basis points of the position on each side of a " +
                        "trade. That is the entire cost model. There is no bid/ask spread, no slippage, " +
                        "no allowance for a gap straight through the intended price, and no per-order " +
                        "commission. Real fills differ, and they differ most exactly where a signal " +
                        "fires — after a gap, in a thin name, on a day everyone else saw the same thing.",
                    "The plan replay behind the journal's grey curve subtracts nothing at all. It " +
                        "assumes you got the plan's price: filled somewhere inside the entry zone, " +
                        "exited exactly at the stop or exactly at the target. Every number derived " +
                        "from it — the plan's expectancy, the execution gap against your own fills — " +
                        "is therefore slightly better than the plan could actually have done.",
                    "The consequence is directional and worth holding on to: costs make the simulated " +
                        "half look better than the live half, so a backtest that only just beats your " +
                        "real record has not beaten it.",
                ),
            )

            Section(
                title = "Survivorship: the universe is today's",
                source = "market_scan_job.py · Nasdaq Trader symbol directory",
                paragraphs = listOf(
                    "The nightly market scan runs the symbols that are tradeable TODAY — roughly 3,100 " +
                        "of the 3,150 in the Nasdaq Trader directory — against past prices. Companies " +
                        "that delisted, went bankrupt or were acquired are not in today's directory, so " +
                        "they are largely absent from anything historical this app computes.",
                    "This flatters every historical rate in the same direction: the worst outcomes a " +
                        "strategy could have had are the ones most likely to have left the list. Treat " +
                        "any backtested win rate as an optimistic reading for that reason alone, before " +
                        "any of the others on this page.",
                    "The per-stock backtest on a detail screen has the same problem in a smaller form: " +
                        "it can only run on a chart that still exists to be fetched.",
                ),
            )

            Section(
                title = "A time exit that finished green is still a win",
                source = "ExitTaxonomy.kt",
                paragraphs = listOf(
                    "A trade closed by a clock rather than by a level is counted inside the headline " +
                        "win rate as long as it finished green. It never reached its target; it simply " +
                        "ran out of time in profit. Both facts are true and only one of them is usually " +
                        "published.",
                    "So this app never shows one win rate alone. A HARD WIN RATE (the plan reached the " +
                        "target it was written with) is always shown beside a PROFITABLE EXIT RATE " +
                        "(finished green by any route), over the same denominator, returned from one " +
                        "function so there is no way to get the flattering one without the other.",
                    "The gap between them can be enormous. The reference track record this measurement " +
                        "was modelled on published 65.3% profitable exits and 12.5% hard wins over the " +
                        "same 72 closes — 41 of them were ten-day time exits that happened to end a " +
                        "little green.",
                    "Exits that cannot be assessed against a plan get named rather than dropped: a " +
                        "position closed with no levels recorded is “no plan recorded”, and an " +
                        "option that expired worthless is its own bucket and is never filed as a stop " +
                        "— a stop is the plan working, an expiry is the plan abandoned, usually at " +
                        "about twice the intended loss.",
                ),
            )

            Section(
                title = "Small samples are a direction, not a verdict",
                source = "RiskMultiple.MIN_SCORED_FOR_EXPECTANCY = $floor",
                paragraphs = listOf(
                    "Below $floor scored trades this app will not print a rate as a percentage. It " +
                        "prints the counts instead — “3 of 4 closed green” rather than " +
                        "“75%” — because a percentage over four trades reads as a measured " +
                        "property and is an accident of four coin flips.",
                    "The same floor governs expectancy, profit factor and the exit-taxonomy rates, and " +
                        "it is one constant shared by all of them rather than three that could drift " +
                        "apart.",
                    "When a backtested figure and a forward one disagree sharply and the sample behind " +
                        "either is under the floor, the app says so next to the number rather than in a " +
                        "footnote. The disagreement that motivated this: a published backtest of 514 " +
                        "trades at a 1.53 profit factor, beside a live journal of 23 closed trades at " +
                        "5.17. More than three times the edge, on a twentieth of the evidence.",
                ),
            )

            Section(
                title = "The plan replay is the pessimistic reading",
                source = "JournalReplay.kt · ambiguous",
                paragraphs = listOf(
                    "The journal's grey curve walks each plan forward over DAILY bars. A daily bar has " +
                        "an open, a high, a low and a close, and nothing about the order they happened " +
                        "in — so when one bar's range covers both the stop and the target, the bars " +
                        "genuinely cannot say which was hit first.",
                    "The replay resolves that against the trade: it assumes the stop. The assumption is " +
                        "recorded on the row rather than hidden, and the journal states how many of the " +
                        "plan's trades rest on it, because a backtest that silently resolves its own " +
                        "ambiguities in its own favour is the standard way one flatters itself.",
                    "So the mechanical curve is deliberately the pessimistic reading of the tape, and " +
                        "an execution gap in your favour is that much less impressive than it looks.",
                ),
            )

            Section(
                title = "What R measures, and what it does not",
                source = "RiskMultiple.kt",
                paragraphs = listOf(
                    "R is (exit − entry) ÷ (entry − stop): what a trade made as a multiple of what it " +
                        "risked at the moment it was opened. It measures the DECISION, not the position. " +
                        "A 10% gain on 2% of the book and a 10% gain on 20% of it are the same call at " +
                        "different sizes, and R is the only unit here that says so.",
                    "It says nothing about money. A +3R trade on a tiny position made less than a +0.5R " +
                        "trade on a large one, and R will never tell you that — the portfolio and the " +
                        "options history hold the dollars.",
                    "R cannot be reconstructed after the fact. It needs the stop that was in force at " +
                        "entry, and nothing recoverable — price history, the P/L, your notes — says what " +
                        "that was. Trades closed without a stop recorded are permanently unscoreable. " +
                        "They are EXCLUDED from every average and COUNTED beside it, never treated as " +
                        "0R: 0R is a real and different claim, that the trade exited exactly where it " +
                        "entered.",
                    "Every R figure in the app therefore arrives with how many of the closes it could " +
                        "actually be computed for. An expectancy over 4 of 30 closed trades is not that " +
                        "account's expectancy.",
                ),
            )

            Section(
                title = "The forward scorecard is scored against the index",
                source = "MemoryStats · Scorecard",
                paragraphs = listOf(
                    "The signals service grades each of its own calls 20 trading days later. The " +
                        "headline is not “did the price go up” — equities drift up, so roughly " +
                        "55–60% of any 20-day window is positive regardless of skill, and a raw win rate " +
                        "measures the market rather than the analyst.",
                    "A BUY is right when the name beat the index over those 20 sessions. A SELL is " +
                        "right when the name then UNDERPERFORMED the index — because owning the index " +
                        "was the alternative to holding it. Scored on raw return instead, every sell in " +
                        "a bull market would be marked wrong no matter how good the judgement was.",
                    "The inversion is applied on the server, so the same number means the same thing on " +
                        "every card: higher is better and 0.50 is a coin flip. The size of the edge is " +
                        "reported separately (excess return for buys, avoided loss for sells), signed so " +
                        "positive is good on both sides.",
                    "A block is absent until enough decisions have been graded, and absent is not zero " +
                        "— it takes about 20 trading days after a call before it can be scored at all.",
                ),
            )

            Section(
                title = "A percentile is a rank, not a grade",
                source = "MetricRank.kt",
                paragraphs = listOf(
                    "The market scan ranks each metric against that night's whole cross-section. " +
                        "“96th percentile” means 96% of the names scanned had a lower value. " +
                        "It does not mean good.",
                    "The 99th percentile of average daily range is the most volatile name in the market, " +
                        "not the best one; a high RSI is the most extended, not the strongest. The word " +
                        "“percentile” and the population it is a percentile OF always travel " +
                        "with the number for that reason — “96th percentile of 3,101 scanned” " +
                        "cannot be misread the way a bare “96th percentile” can.",
                    "A missing rank renders as nothing at all, never as “0th”.",
                ),
            )

            Section(
                title = "Where the data itself is wrong",
                source = "market_scan_job.py · QualityResponse.sharesChangeReliable",
                paragraphs = listOf(
                    "Some vendor series are corrupt in ways no amount of careful statistics fixes. " +
                        "Yahoo serves certain reverse-split names as interleaved pre- and post-split " +
                        "bars with no adjustment applied — one ticker oscillating between $0.59 and " +
                        "$17.85 day to day, another reporting 25,652% 20-day momentum. A single-bar 10× " +
                        "move guard rejects about a dozen names a night into their own bucket, kept " +
                        "apart from network failures so a data problem is never filed as an outage.",
                    "The same trap sits in fundamentals: a 10-for-1 split moves the raw reported share " +
                        "count without diluting anyone, which once read as “+1074% dilution”. " +
                        "When a change is too large to be organic the app refuses to render it as " +
                        "buybacks or dilution at all.",
                    "Anything filtered this way is counted and shown as a count. A refusal to measure is " +
                        "a fact about the data, and hiding it would make a broken night look like a " +
                        "quiet one.",
                ),
            )

            Section(
                title = "The numbers that were chosen, not measured",
                source = "judgement calls, listed so they can be argued with",
                paragraphs = listOf(
                    "None of the following was derived from anything. They are defensible choices, and " +
                        "a different choice would move the figures they feed:",
                ),
                bullets = listOf(
                    "$floor scored trades as the small-sample floor. The literature puts it at 20–30; " +
                        "this is the low end of that band, picked so the caveat fires as rarely as is " +
                        "defensible rather than never.",
                    "A 40-session horizon for the plan replay, with a 5-session window to fill. A plan " +
                        "still running at 40 sessions is closed at the clock and counted as a time exit.",
                    "20 trading days as the scoring horizon for the analyst's forward scorecards.",
                    "10 basis points per side as the on-device backtest's entire cost model.",
                    "A 10× single-bar move as the line between a real gap and a corrupt series.",
                    "${PairedStat.SHARP_RATE_GAP_PCT.toInt()} percentage points, " +
                        "${PairedStat.SHARP_R_GAP}R, or ${PairedStat.SHARP_RATIO_FACTOR.toInt()}× as " +
                        "the point where a backtested figure and a forward one count as sharply " +
                        "divergent.",
                    "Resolving a bar that touched both stop and target in favour of the stop.",
                ),
            )

            Section(
                title = "The rule underneath all of it",
                source = "the invariant every card is reviewed against",
                paragraphs = listOf(
                    "Absent is never zero. A statistic with no sample is null and says so; it never " +
                        "renders as 0%, a missing forward record is never a forward record of zero, and " +
                        "“we could not measure this” never prints as “this measured " +
                        "nothing”.",
                    "If you find a number in this app that breaks that rule, it is a bug, and it is the " +
                        "most serious kind this app has.",
                ),
            )

            Box(Modifier.height(24.dp))
        }
    }
}

/** One titled block: a source line you can go and read, then the prose, then any bullets. */
@Composable
private fun Section(
    title: String,
    source: String,
    paragraphs: List<String>,
    bullets: List<String> = emptyList(),
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            // WHERE TO CHECK. A methodology page whose claims cannot be traced back to code is just
            // another marketing surface; naming the file is what makes disagreeing with it possible.
            source,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            bullets.forEach { b ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("·", style = MaterialTheme.typography.bodySmall)
                    Text(b, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
