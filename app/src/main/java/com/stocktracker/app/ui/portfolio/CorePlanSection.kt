package com.stocktracker.app.ui.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.components.AnswerDivider
import com.stocktracker.app.ui.components.AnswerGroup
import com.stocktracker.app.ui.components.AnswerRow
import com.stocktracker.app.ui.components.DONUT_COLORS
import com.stocktracker.app.ui.components.Pill
import com.stocktracker.app.ui.theme.GainGreen
import com.stocktracker.app.ui.theme.NumberSmall
import com.stocktracker.app.ui.theme.Signal
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

private fun dollars(v: Double): String = "$" + String.format(Locale.US, "%,d", v.roundToLong())
private fun pct0(v: Double): String = String.format(Locale.US, "%.0f%%", v)
private fun pts(v: Double): String = String.format(Locale.US, "%s%.1f pts", if (v >= 0) "+" else "−", abs(v))

/**
 * The core plan on the Portfolio tab: target mix against today's, where the next deposit goes, and
 * the goal. [holdings] are the priced holdings; [unpriced] are left out of every share shown here and
 * named when one belongs to the plan. [cash] is the "Cash to invest" amount, split too when set.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CorePlanSection(
    holdings: List<Holding>,
    unpriced: List<String>,
    totalValue: Double,
    cash: Double,
) {
    val settings = ServiceLocator.settingsStore
    val scope = rememberCoroutineScope()
    // (loaded, plan): nothing is drawn until the stored plan has been read, so a saved plan never
    // flashes the "Set a core plan" row first.
    val loaded by produceState<Pair<Boolean, CorePlan?>>(false to null) {
        settings.corePlan.collect { value = true to it }
    }
    var editing by remember { mutableStateOf(false) }
    if (!loaded.first) return
    val plan = loaded.second

    if (editing) {
        CorePlanEditor(
            initial = plan,
            onSave = { p ->
                editing = false
                scope.launch {
                    settings.setCorePlan(p)
                    // Set up after this month's buy day has passed: no reminder for a day already gone.
                    val today = LocalDate.now()
                    if (today.dayOfMonth > p.depositDay) {
                        settings.setCorePlanRemindedMonth(today.toString().take(7))
                    }
                }
            },
            onRemove = { editing = false; scope.launch { settings.setCorePlan(null) } },
            onDismiss = { editing = false },
        )
    }

    if (plan == null) {
        AnswerGroup {
            AnswerRow(
                icon = Icons.Filled.PieChart, tint = GainGreen,
                title = "Set a core plan",
                subtitle = "Target mix, buy day and goal · e.g. 80% VOO, 20% VXUS",
                onClick = { editing = true },
            )
        }
        return
    }

    val status = CorePlanMath.evaluate(plan, holdings.map { it.asset.symbol to it.value })
    val planSymbols = plan.slots.flatMap { s -> s.symbols.map { it.uppercase() } }.toSet()
    val missing = unpriced.filter { it.uppercase() in planSymbols }

    AnswerGroup(
        title = "Core plan",
        trailingTitle = {
            TextButton(onClick = { editing = true }) { Text("Edit") }
        },
    ) {
        // The mix: one bar per group, filled to today's share with a tick at the target.
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            status.slots.forEachIndexed { i, s ->
                SlotBar(s, DONUT_COLORS[i % DONUT_COLORS.size], plan.bandPts)
            }
            val core = status.corePctOfBook
            if (status.coreValue <= 0.0) {
                Text(
                    "You hold none of these funds yet. Your first buys set the mix.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (core != null) {
                Text(
                    "Core ${dollars(status.coreValue)} · ${pct0(core)} of portfolio" +
                        if (status.outsideSymbols.isNotEmpty()) {
                            " · rest: " + status.outsideSymbols.take(4).joinToString(", ") +
                                if (status.outsideSymbols.size > 4) " +${status.outsideSymbols.size - 4}" else ""
                        } else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (missing.isNotEmpty()) {
                Text(
                    "Leaves out ${missing.joinToString(", ")} — no price",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        AnswerDivider()
        val worst = status.worst
        val drift = worst?.driftPts
        AnswerRow(
            icon = Icons.Filled.Balance,
            tint = if (status.onTarget || worst == null) GainGreen else Signal,
            title = when {
                worst == null -> "Mix starts with your first buy"
                status.onTarget -> "On target"
                else -> "${worst.slot.name} is off target"
            },
            subtitle = when {
                worst == null || drift == null -> "Within ±${plan.bandPts.toInt()} pts counts as on target"
                status.onTarget -> "All within ±${plan.bandPts.toInt()} pts · largest ${worst.slot.name} ${pts(drift)}"
                drift < 0 -> "Send new money there, no selling needed"
                else -> "Send new money to the others"
            },
            trailing = if (drift != null) {
                { Pill(pts(drift), if (status.onTarget) GainGreen else Signal) }
            } else null,
        )

        AnswerDivider()
        val today = LocalDate.now()
        val next = CorePlanMath.nextDepositDate(plan, today)
        val deposit = plan.depositAmount
        val depositSplit = deposit?.let { CorePlanMath.split(status, it) }.orEmpty()
        val cashSplit = CorePlanMath.split(status, cash)
        val whenText = if (next == today) "today" else next.format(DateTimeFormatter.ofPattern("MMM d", Locale.US))
        AnswerRow(
            icon = Icons.Filled.CalendarMonth, tint = MaterialTheme.colorScheme.primary,
            title = "Next buy: $whenText",
            subtitle = if (deposit != null && depositSplit.isNotEmpty()) {
                dollars(deposit) + " → " + depositSplit.joinToString(" · ") { "${it.symbol} ${dollars(it.dollars)}" }
            } else {
                "Set a monthly amount to see the split"
            },
            key = "core-next",
        ) {
            if (cashSplit.isNotEmpty()) {
                Text(
                    "Cash to invest ${dollars(cash)} → " + cashSplit.joinToString(" · ") { "${it.symbol} ${dollars(it.dollars)}" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                "Each deposit fills the group furthest under target first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Buy on the day, dip or not: in the sandbox, waiting for dips cost money.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val goal = plan.goalAmount
        val year = plan.goalYear
        if (goal != null && goal > 0 && year != null) {
            AnswerDivider()
            val months = CorePlanMath.monthsUntil(today, year)
            val monthly = deposit ?: 0.0
            val low = CorePlanMath.project(totalValue, monthly, months, CorePlans.LOW_PCT)
            val high = CorePlanMath.project(totalValue, monthly, months, CorePlans.HIGH_PCT)
            val need = CorePlanMath.monthlyNeeded(totalValue, goal, months, CorePlans.MID_PCT)
            val progress = if (goal > 0) totalValue / goal * 100.0 else 0.0
            AnswerRow(
                icon = Icons.Filled.Flag, tint = GainGreen,
                title = "Goal: ${dollars(goal)} by $year",
                subtitle = if (months > 0) {
                    "At ${CorePlans.LOW_PCT.toInt()}–${CorePlans.HIGH_PCT.toInt()}% a year: ${dollars(low)}–${dollars(high)}"
                } else {
                    "The goal year has passed"
                },
                key = "core-goal",
                trailing = { Pill(pct0(progress), GainGreen) },
            ) {
                if (need != null) {
                    Text(
                        if (need <= 0.0) "On track with no more deposits at ${CorePlans.MID_PCT.toInt()}% a year."
                        else "Needs ${dollars(need)} a month at ${CorePlans.MID_PCT.toInt()}% a year" +
                            (deposit?.let { " (you put in ${dollars(it)})" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    "Uses the whole portfolio, ${dollars(totalValue)} today. Returns are assumed, not promised; real years swing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SlotBar(s: SlotStatus, color: Color, band: Double) {
    val drift = s.driftPts
    val off = drift != null && abs(drift) > band
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Column(Modifier.weight(1f)) {
                Text(s.slot.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (s.held.isEmpty()) "None held · buy ${s.buySymbol}" else s.held.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                (s.pct?.let { pct0(it) } ?: "—") + " / " + pct0(s.slot.targetPct),
                style = NumberSmall,
                fontWeight = FontWeight.Bold,
                color = if (off) Signal else MaterialTheme.colorScheme.onSurface,
            )
        }
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(8.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f), RoundedCornerShape(50)),
        ) {
            val frac = ((s.pct ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(color, RoundedCornerShape(50)))
            // Target tick.
            val t = (s.slot.targetPct / 100.0).toFloat().coerceIn(0f, 1f)
            Box(
                Modifier.offset(x = maxWidth * t - 1.dp).width(2.dp).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.onSurface),
            )
        }
    }
}

/** One editable group row in the editor. Text fields hold raw text until Save. */
private class SlotDraft(name: String, symbols: String, target: String) {
    var name by mutableStateOf(name)
    var symbols by mutableStateOf(symbols)
    var target by mutableStateOf(target)
}

private fun num(s: String): Double? = s.replace(",", "").removePrefix("$").trim().toDoubleOrNull()
private fun plain(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CorePlanEditor(
    initial: CorePlan?,
    onSave: (CorePlan) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val start = initial ?: CorePlan()
    val slots = remember {
        mutableStateListOf<SlotDraft>().apply {
            start.slots.forEach { add(SlotDraft(it.name, it.symbols.joinToString(", "), plain(it.targetPct))) }
        }
    }
    var deposit by remember { mutableStateOf(start.depositAmount?.let { plain(it) } ?: "") }
    var day by remember { mutableStateOf(start.depositDay.toString()) }
    var band by remember { mutableStateOf(start.bandPts) }
    var goal by remember { mutableStateOf(start.goalAmount?.let { plain(it) } ?: "") }
    var goalYear by remember { mutableStateOf(start.goalYear?.toString() ?: "") }
    var remind by remember { mutableStateOf(start.remind) }

    fun preset(list: List<CoreSlot>) {
        slots.clear()
        list.forEach { slots.add(SlotDraft(it.name, it.symbols.joinToString(", "), plain(it.targetPct))) }
    }

    val built = CorePlan(
        slots = slots.map { d ->
            CoreSlot(
                name = d.name.trim().ifEmpty { "Group" },
                symbols = d.symbols.split(',', ' ', ';').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.distinct(),
                targetPct = num(d.target) ?: 0.0,
            )
        },
        depositAmount = num(deposit)?.takeIf { it > 0 },
        depositDay = day.trim().toIntOrNull()?.coerceIn(1, 28) ?: 1,
        bandPts = band,
        goalAmount = num(goal)?.takeIf { it > 0 },
        goalYear = goalYear.trim().toIntOrNull()?.takeIf { it >= LocalDate.now().year },
        remind = remind,
    )
    val sum = built.slots.sumOf { it.targetPct }
    val valid = CorePlanMath.targetsValid(built)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Set a core plan" else "Edit core plan") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Start from", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(false, { preset(CorePlans.US_INTL_80_20) }, { Text("80/20 VOO·VXUS") })
                    FilterChip(false, { preset(CorePlans.FIDELITY_80_20) }, { Text("80/20 Fidelity") })
                    FilterChip(false, { preset(CorePlans.WITH_BONDS_70_20_10) }, { Text("70/20/10 bonds") })
                    FilterChip(false, { preset(CorePlans.WORLD) }, { Text("VT only") })
                }
                slots.forEachIndexed { i, d ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = d.name, onValueChange = { d.name = it },
                            label = { Text("Group") }, singleLine = true, modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = d.target, onValueChange = { d.target = it },
                            label = { Text("%") }, singleLine = true, modifier = Modifier.width(72.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        if (slots.size > 1) {
                            IconButton(onClick = { slots.removeAt(i) }) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove ${d.name}")
                            }
                        }
                    }
                    OutlinedTextField(
                        value = d.symbols, onValueChange = { d.symbols = it },
                        label = { Text("Funds that count") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextButton(onClick = { slots.add(SlotDraft("", "", "")) }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Add group")
                }
                Text(
                    "Targets add up to ${plain(sum)}%" + if (valid) "" else " — must be 100%",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = deposit, onValueChange = { deposit = it },
                        label = { Text("Monthly buy") }, prefix = { Text("$") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        value = day, onValueChange = { day = it },
                        label = { Text("Day 1–28") }, singleLine = true, modifier = Modifier.width(96.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Text("Off target beyond", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(3.0, 5.0, 10.0).forEach { b ->
                        FilterChip(band == b, { band = b }, { Text("±${b.toInt()} pts") })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = goal, onValueChange = { goal = it },
                        label = { Text("Goal") }, prefix = { Text("$") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        value = goalYear, onValueChange = { goalYear = it },
                        label = { Text("By year") }, singleLine = true, modifier = Modifier.width(96.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Remind me on buy day", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = remind, onCheckedChange = { remind = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(built) }, enabled = valid) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (initial != null) TextButton(onClick = onRemove) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
