package com.stocktracker.app.data.model

import com.stocktracker.app.data.remote.SplitEvent
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * MONEY-4 — a split silently corrupts a holding.
 *
 * Nothing on the holdings path adjusts for a stock split: the morning after a 10-for-1, the Detail
 * card shows roughly -90% (unadjusted cost vs a post-split price) and the rebalance payload carries a
 * share count ten times too small. The user's real position is unchanged; only the app's RECORD of it
 * is wrong. This file is pure detection + arithmetic — the caller (a ViewModel) is what decides to ask
 * the user before touching anything; nothing here writes to a [Lot] on its own.
 */

/** One [Lot]'s share count/cost is affected by one or more splits that happened after it was bought.
 *  [ratio] is the COMBINED multiplier across every applicable split (they compound). */
data class LotSplitAdjustment(val lotIndex: Int, val ratio: Double, val label: String)

private fun Lot.acquiredEpochMs(): Long? {
    val iso = acquiredDateIso ?: return null
    return runCatching {
        LocalDate.parse(iso.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }.getOrNull()
}

/** The EARLIEST known lot date across [lots] (epoch ms, UTC midnight) — the "since" bound a caller
 *  fetches splits from. Null when no lot has a recorded date at all, i.e. there is nothing to check
 *  a split against. */
fun earliestKnownLotEpochMs(lots: List<Lot>): Long? = lots.mapNotNull { it.acquiredEpochMs() }.minOrNull()

/**
 * Which of [lots] a split in [splits] applies to: any split whose ex-date is AFTER that lot's own
 * acquisition date. A lot with a null or unparseable [Lot.acquiredDateIso] is left out entirely —
 * this app never assumes a split happened before or after an unknown purchase date, the same "unknown
 * means unknown" rule [Asset.avgCost] already applies to cost. Multiple splits since one lot's
 * purchase COMPOUND into a single [LotSplitAdjustment.ratio] (a 2:1 then a later 3:1 is 6x, not two
 * separate prompts for the same lot).
 */
fun detectSplitAdjustments(lots: List<Lot>, splits: List<SplitEvent>): List<LotSplitAdjustment> {
    if (splits.isEmpty()) return emptyList()
    return lots.mapIndexedNotNull { i, lot ->
        val acquired = lot.acquiredEpochMs() ?: return@mapIndexedNotNull null
        val applicable = splits.filter { it.epochMs > acquired }
        if (applicable.isEmpty()) return@mapIndexedNotNull null
        val ratio = applicable.fold(1.0) { acc, s -> acc * s.ratio }
        LotSplitAdjustment(lotIndex = i, ratio = ratio, label = applicable.joinToString(", ") { it.label })
    }
}

/**
 * Apply [adjustments] to [lots], returning a NEW list — never mutates in place, and never called
 * except in direct response to the user confirming the prompt [detectSplitAdjustments] drives.
 * Multiplies [Lot.shares] and DIVIDES [Lot.costPerShare] by each adjustment's ratio (a 4-for-1 leaves
 * total cost basis — shares × cost — unchanged, exactly as a real split does); [Lot.acquiredDateIso]
 * is untouched, because the shares were bought on the same day either way.
 */
fun applySplitAdjustments(lots: List<Lot>, adjustments: List<LotSplitAdjustment>): List<Lot> {
    if (adjustments.isEmpty()) return lots
    val byIndex = adjustments.associateBy { it.lotIndex }
    return lots.mapIndexed { i, lot ->
        val adj = byIndex[i] ?: return@mapIndexed lot
        lot.copy(
            shares = lot.shares * adj.ratio,
            costPerShare = lot.costPerShare?.let { it / adj.ratio },
        )
    }
}
