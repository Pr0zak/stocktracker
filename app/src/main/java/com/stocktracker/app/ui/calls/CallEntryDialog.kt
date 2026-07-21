package com.stocktracker.app.ui.calls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.stocktracker.app.data.model.CallPosition
import com.stocktracker.app.data.remote.OptionCandidate
import com.stocktracker.app.data.remote.OptionsResponse
import com.stocktracker.app.ui.ideas.usd
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Pre-fill payload for the entry form (Path A), built from an OC-2 "Play with calls" suggestion. */
data class CallDraft(
    val symbol: String = "",
    val contractSymbol: String = "",
    val strike: Double? = null,
    val expiryIso: String = "",
    val expiryTs: Long? = null,
    val contracts: Int = 1,
    val fillPrice: Double? = null,
)

/** Build a pre-fill draft from a shown call suggestion — the contract's own expiry ts is carried through. */
fun callDraftFrom(symbol: String, options: OptionsResponse, c: OptionCandidate): CallDraft = CallDraft(
    symbol = symbol,
    contractSymbol = c.contractSymbol,
    strike = c.strike,
    expiryIso = options.expiry?.iso?.take(10) ?: "",
    expiryTs = options.expiry?.ts,
    contracts = c.contracts ?: 1,
    fillPrice = c.limitPrice, // default the fill to the suggested limit; user overwrites with their actual fill
)

/**
 * The manual call-tracker entry form (OC-3), shown as a dialog for both paths: pre-filled from a
 * suggestion (Path A) or blank for a fully manual entry (Path B). Beginner framing throughout — the
 * fill is the premium PER SHARE, and the cost basis it implies is also the whole max loss.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallEntryDialog(
    prefill: CallDraft?,
    onDismiss: () -> Unit,
    onSave: (CallPosition) -> Unit,
) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val today = remember { LocalDate.now().toString() }

    var symbol by remember { mutableStateOf(prefill?.symbol?.uppercase() ?: "") }
    var strikeText by remember { mutableStateOf(prefill?.strike?.let { plainNumber(it) } ?: "") }
    var contractsText by remember { mutableStateOf((prefill?.contracts ?: 1).toString()) }
    var fillText by remember { mutableStateOf(prefill?.fillPrice?.let { plainNumber(it) } ?: "") }

    // Expiry: keep the authoritative epoch-seconds ts. Path A carries the option chain's own ts; picking
    // a (new) date recomputes it as UTC-midnight of that date.
    var expiryIso by remember { mutableStateOf(prefill?.expiryIso?.take(10) ?: "") }
    var expiryTs by remember { mutableStateOf(prefill?.expiryTs) }

    var openIso by remember { mutableStateOf(today) }
    val contractSymbol = prefill?.contractSymbol.orEmpty()

    var advanced by remember { mutableStateOf(false) }
    var tpText by remember { mutableStateOf("80") }   // sensible default: plan to take profit at +80%
    var stopText by remember { mutableStateOf("50") }  // sensible default: bail at −50%
    var notes by remember { mutableStateOf("") }

    val strike = strikeText.trim().toDoubleOrNull()
    val contracts = contractsText.trim().toIntOrNull()
    val fill = fillText.trim().toDoubleOrNull()
    val valid = symbol.isNotBlank() && strike != null && strike > 0 &&
        contracts != null && contracts >= 1 && fill != null && fill > 0 &&
        expiryTs != null && expiryIso.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (prefill != null) "Track this call" else "Track a call") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "You bought this on Fidelity — enter the fill and we'll track its live P/L. The premium " +
                        "you paid is also the most you can lose.",
                    style = MaterialTheme.typography.bodySmall,
                    color = neutral,
                )
                OutlinedTextField(
                    value = symbol,
                    onValueChange = { symbol = it.uppercase() },
                    label = { Text("Symbol") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DateFieldButton("Expiry", expiryIso) { millis, iso ->
                    expiryTs = millis / 1000L // store epoch-SECONDS to match the option chain's ts
                    expiryIso = iso
                }
                OutlinedTextField(
                    value = strikeText,
                    onValueChange = { strikeText = it },
                    label = { Text("Strike") },
                    prefix = { Text("$") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = contractsText,
                    onValueChange = { contractsText = it.filter { c -> c.isDigit() } },
                    label = { Text("Contracts") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = fillText,
                    onValueChange = { fillText = it },
                    label = { Text("Fill price (premium / share)") },
                    prefix = { Text("$") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                DateFieldButton("Bought on", openIso) { _, iso -> openIso = iso }

                // Live cost-basis / break-even echo so a beginner sees what they're committing to.
                if (strike != null && contracts != null && contracts >= 1 && fill != null) {
                    Text(
                        "Cost basis (max loss) ${usd(fill * 100.0 * contracts)} · break-even ${usd(strike + fill)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }

                TextButton(onClick = { advanced = !advanced }) {
                    Text(if (advanced) "Hide advanced" else "Advanced · take-profit / stop / notes")
                }
                if (advanced) {
                    OutlinedTextField(
                        value = tpText,
                        onValueChange = { tpText = it },
                        label = { Text("Take-profit %") },
                        suffix = { Text("%") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = stopText,
                        onValueChange = { stopText = it },
                        label = { Text("Stop %") },
                        suffix = { Text("%") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notes") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        CallPosition(
                            symbol = symbol.trim().uppercase(),
                            contractSymbol = contractSymbol.trim(),
                            strike = strike!!,
                            expiryIso = expiryIso,
                            expiryTs = expiryTs!!,
                            contracts = contracts!!,
                            fillPrice = fill!!,
                            openDateIso = openIso,
                            takeProfitPct = tpText.trim().toDoubleOrNull(),
                            stopPct = stopText.trim().toDoubleOrNull(),
                            notes = notes.trim().ifBlank { null },
                        ),
                    )
                },
            ) { Text("Track") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A labelled button that opens a Material date picker and reports the picked UTC-midnight ms + ISO. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateFieldButton(label: String, iso: String, onPicked: (Long, String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { show = true }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (iso.isBlank()) "Pick a date" else shortExpiry(iso))
        }
    }
    if (show) {
        val init = remember(iso) { isoToUtcMillis(iso) ?: System.currentTimeMillis() }
        val dpState = rememberDatePickerState(initialSelectedDateMillis = init)
        DatePickerDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { onPicked(it, utcMillisToIso(it)) }
                    show = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { show = false }) { Text("Cancel") } },
        ) { DatePicker(state = dpState) }
    }
}

private fun plainNumber(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

/** ISO date ("2026-09-17") → UTC-midnight epoch-ms, or null if unparseable. */
fun isoToUtcMillis(iso: String): Long? = runCatching {
    LocalDate.parse(iso.take(10)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}.getOrNull()

/** UTC-midnight epoch-ms → ISO date ("2026-09-17"). */
fun utcMillisToIso(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()

/** ISO date → "Sep 17 '26" for the contract line. */
fun shortExpiry(iso: String): String = runCatching {
    LocalDate.parse(iso.take(10)).format(DateTimeFormatter.ofPattern("MMM d ''yy", Locale.US))
}.getOrDefault(iso)

/** "UNH $420C Sep 17 '26" — the one-line contract identity used in lists and detail. */
fun contractLine(p: CallPosition): String {
    val k = if (p.strike % 1.0 == 0.0) p.strike.toLong().toString() else "%.2f".format(p.strike)
    return "${p.symbol.uppercase()} \$${k}C ${shortExpiry(p.expiryIso)}"
}
