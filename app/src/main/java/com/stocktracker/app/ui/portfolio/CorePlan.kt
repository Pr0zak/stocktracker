package com.stocktracker.app.ui.portfolio

import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * The user's core plan: a few fund groups with a target share each, a regular deposit, and an
 * optional goal. Stored as JSON in [com.stocktracker.app.data.prefs.SettingsStore.corePlan].
 *
 * A group lists every symbol that does its job (VOO, SPYM, VTI and FXAIX all count as "US stocks"),
 * so holding a copy of the fund still counts toward the target. A symbol listed in two groups counts
 * toward the first only.
 */
@Serializable
data class CoreSlot(
    val name: String,
    val symbols: List<String>,
    val targetPct: Double,
)

@Serializable
data class CorePlan(
    val slots: List<CoreSlot> = CorePlans.US_INTL_80_20,
    /** Regular deposit in dollars; null = not set, and the next-buy split is not shown. */
    val depositAmount: Double? = null,
    /** Day of the month the deposit is made, 1..28 so every month has it. */
    val depositDay: Int = 1,
    /** A group further than this many points from its target counts as off target. */
    val bandPts: Double = 5.0,
    val goalAmount: Double? = null,
    val goalYear: Int? = null,
    /** Post a reminder on the deposit day. */
    val remind: Boolean = true,
)

object CorePlans {
    val US = listOf("VOO", "SPYM", "VTI", "IVV", "SPY", "SPLG", "ITOT", "SCHB", "FXAIX", "FSKAX", "FZROX", "FNILX")
    val INTL = listOf("VXUS", "IXUS", "VEU", "VEA", "IEFA", "SCHF", "FTIHX", "FZILX", "FSPSX")
    val BONDS = listOf("BND", "AGG", "SCHZ", "FXNAX")

    val US_INTL_80_20 = listOf(
        CoreSlot("US stocks", US, 80.0),
        CoreSlot("International", INTL, 20.0),
    )
    val WITH_BONDS_70_20_10 = listOf(
        CoreSlot("US stocks", US, 70.0),
        CoreSlot("International", INTL, 20.0),
        CoreSlot("Bonds", BONDS, 10.0),
    )
    val FIDELITY_80_20 = listOf(
        CoreSlot("US stocks", listOf("FXAIX", "FSKAX", "FZROX") + US, 80.0),
        CoreSlot("International", listOf("FTIHX", "FZILX") + INTL, 20.0),
    )
    val WORLD = listOf(CoreSlot("Whole world", listOf("VT", "ACWI"), 100.0))

    /** Assumed yearly returns for the goal projection: a low and a high case, and the middle one
     *  used for "needs $X a month". Assumptions, labelled as such wherever they are shown. */
    const val LOW_PCT = 4.0
    const val MID_PCT = 6.0
    const val HIGH_PCT = 8.0
}

data class SlotStatus(
    val slot: CoreSlot,
    val value: Double,
    /** Share of the core, or null when the core holds nothing yet. */
    val pct: Double?,
    /** pct − target, in points; null when the core holds nothing yet. */
    val driftPts: Double?,
    /** The group's symbols actually held, largest first. */
    val held: List<String>,
    /** What to buy for this group: the largest one held, else the first listed. */
    val buySymbol: String,
)

data class CoreStatus(
    val slots: List<SlotStatus>,
    val coreValue: Double,
    val outsideValue: Double,
    val outsideSymbols: List<String>,
    /** Core's share of everything priced; null when nothing is priced. */
    val corePctOfBook: Double?,
    /** The group furthest from its target, when the core holds anything. */
    val worst: SlotStatus?,
    val onTarget: Boolean,
)

data class BuySplit(val slot: CoreSlot, val symbol: String, val dollars: Double)

object CorePlanMath {

    fun targetsValid(plan: CorePlan): Boolean =
        plan.slots.isNotEmpty() &&
            plan.slots.all { it.targetPct > 0 && it.symbols.isNotEmpty() } &&
            abs(plan.slots.sumOf { it.targetPct } - 100.0) < 0.01

    /** [holdings] are (symbol, priced value) pairs; unpriced holdings must be left out by the caller. */
    fun evaluate(plan: CorePlan, holdings: List<Pair<String, Double>>): CoreStatus {
        val bySymbol = holdings
            .groupBy({ it.first.uppercase() }, { it.second })
            .mapValues { it.value.sum() }
        val claimed = mutableSetOf<String>()
        val raw = plan.slots.map { slot ->
            val mine = slot.symbols.map { it.uppercase() }.distinct().filter { it in bySymbol && it !in claimed }
            claimed += mine
            val held = mine.sortedByDescending { bySymbol.getValue(it) }
            Triple(slot, held.sumOf { bySymbol.getValue(it) }, held)
        }
        val core = raw.sumOf { it.second }
        val total = bySymbol.values.sum()
        val slots = raw.map { (slot, value, held) ->
            val pct = if (core > 0) value / core * 100.0 else null
            SlotStatus(
                slot = slot,
                value = value,
                pct = pct,
                driftPts = pct?.let { it - slot.targetPct },
                held = held,
                buySymbol = held.firstOrNull() ?: slot.symbols.firstOrNull()?.uppercase() ?: slot.name,
            )
        }
        val outside = bySymbol.filterKeys { it !in claimed }
        // Ties go to the group UNDER target: with two groups the drifts always mirror each other,
        // and the short one is where the next deposit goes.
        val worst = if (core > 0) {
            slots.maxWithOrNull(compareBy<SlotStatus>({ Math.round(abs(it.driftPts ?: 0.0) * 100) }, { -(it.driftPts ?: 0.0) }))
        } else null
        return CoreStatus(
            slots = slots,
            coreValue = core,
            outsideValue = outside.values.sum(),
            outsideSymbols = outside.entries.sortedByDescending { it.value }.map { it.key },
            corePctOfBook = if (total > 0) core / total * 100.0 else null,
            worst = worst,
            onTarget = core > 0 && slots.all { abs(it.driftPts ?: 0.0) <= plan.bandPts },
        )
    }

    /**
     * Where [amount] should go so the core moves toward its targets without selling anything. Each
     * group is short (target share of the core AFTER the deposit) − (what it holds); the shortfalls
     * of the groups under target always add up to at least [amount] (they equal it when no group is
     * over), so the amount is shared out in proportion to them. Whole dollars; the parts add up to
     * the amount rounded down to a dollar. Groups that would get nothing are left out.
     */
    fun split(status: CoreStatus, amount: Double): List<BuySplit> {
        if (amount < 1.0 || status.slots.isEmpty()) return emptyList()
        val after = status.coreValue + amount
        val needs = status.slots.map { maxOf(0.0, it.slot.targetPct / 100.0 * after - it.value) }
        val totalNeed = needs.sum()
        val exact = if (totalNeed > 0.0) needs.map { amount * it / totalNeed }
        else status.slots.map { amount * it.slot.targetPct / 100.0 }
        val whole = floor(amount).toLong()
        val floors = exact.map { floor(it).toLong() }.toMutableList()
        var short = whole - floors.sum()
        // Hand the leftover dollars to the largest fractional parts first.
        exact.indices.sortedByDescending { exact[it] - floor(exact[it]) }.forEach { i ->
            if (short > 0) { floors[i]++; short-- }
        }
        return status.slots.mapIndexedNotNull { i, s ->
            floors[i].takeIf { it > 0 }?.let { BuySplit(s.slot, s.buySymbol, it.toDouble()) }
        }
    }

    /** The next deposit day on or after [today]. */
    fun nextDepositDate(plan: CorePlan, today: LocalDate): LocalDate {
        val day = plan.depositDay.coerceIn(1, 28)
        val thisMonth = today.withDayOfMonth(day)
        return if (today.dayOfMonth <= day) thisMonth else thisMonth.plusMonths(1)
    }

    private fun monthlyRate(annualPct: Double) = (1.0 + annualPct / 100.0).pow(1.0 / 12.0) - 1.0

    /** Value after [months] of [monthly] deposits on top of [start], at a steady [annualPct]. */
    fun project(start: Double, monthly: Double, months: Int, annualPct: Double): Double {
        val m = monthlyRate(annualPct)
        val g = (1.0 + m).pow(months)
        return start * g + if (m == 0.0) monthly * months else monthly * (g - 1.0) / m
    }

    /** Monthly deposit that reaches [goal] in [months] at [annualPct]; 0 when [start] already gets there. */
    fun monthlyNeeded(start: Double, goal: Double, months: Int, annualPct: Double): Double? {
        if (months <= 0) return null
        val m = monthlyRate(annualPct)
        val g = (1.0 + m).pow(months)
        val gap = goal - start * g
        if (gap <= 0) return 0.0
        return if (m == 0.0) gap / months else gap * m / (g - 1.0)
    }

    /** Whole months from [today] to the end of [year]. */
    fun monthsUntil(today: LocalDate, year: Int): Int =
        (year - today.year) * 12 + (12 - today.monthValue)
}
