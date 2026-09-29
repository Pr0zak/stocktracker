package com.stocktracker.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.stocktracker.app.data.remote.CoinGeckoKeyStatus
import com.stocktracker.app.data.remote.HttpStatusException
import com.stocktracker.app.data.remote.SignalsApiService
import kotlinx.coroutines.launch

/**
 * PX-1 — the CoinGecko key that lives on the signals service, not on the phone.
 *
 * Crypto prices come from the service first, and without a key CoinGecko refuses this household's IP
 * for hours at a time (2026-09-29), so every crypto row falls back to Yahoo with a one-day sparkline.
 * The key is sent up once and never comes back: the service reports only whether one is set and its
 * last four characters.
 */
@Composable
internal fun CoinGeckoKeySetting(signalsUrl: String) {
    val api = remember { SignalsApiService() }
    val scope = rememberCoroutineScope()
    var field by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<CoinGeckoKeyStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun explain(e: Throwable): String = when ((e as? HttpStatusException)?.code) {
        401, 403 -> "Your signals token is missing or wrong — set it under AI analyst"
        else -> "Couldn't reach your signals service"
    }

    LaunchedEffect(signalsUrl) {
        status = null; error = null
        if (signalsUrl.isBlank()) return@LaunchedEffect
        runCatching { api.coinGeckoKeyStatus(signalsUrl) }
            .onSuccess { status = it }
            .onFailure { error = explain(it) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("CoinGecko key (optional, kept on your server)", style = MaterialTheme.typography.bodyLarge)
        val s = status
        val line = when {
            signalsUrl.isBlank() -> "Set the signals service URL under AI analyst first."
            error != null -> error!!
            s == null -> "Checking…"
            s.keySet == null -> "Your signals service is too old for this — update it first."
            s.keySet -> "Key set ${s.hint} · CoinGecko ${s.status ?: "status unknown"}"
            else -> "No key · CoinGecko ${s.status ?: "status unknown"}"
        }
        val warn = error != null || s?.status?.startsWith("refused") == true
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (signalsUrl.isNotBlank() && s?.keySet != null) {
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                label = { Text(if (s.keySet) "Replace key" else "CoinGecko Demo key") },
                singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { show = !show }) {
                        Icon(
                            if (show) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = if (show) "Hide key" else "Show key",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy && field.isNotBlank(),
                    onClick = {
                        scope.launch {
                            busy = true; error = null
                            runCatching { api.setCoinGeckoKey(signalsUrl, field.trim()) }
                                .onSuccess { status = it; field = "" }
                                .onFailure { error = explain(it) }
                            busy = false
                        }
                    },
                ) { Text("Save key") }
                if (s.keySet) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true; error = null
                                runCatching { api.setCoinGeckoKey(signalsUrl, null) }
                                    .onSuccess { status = it }
                                    .onFailure { error = explain(it) }
                                busy = false
                            }
                        },
                    ) { Text("Remove") }
                }
            }
        }
        HelperText(
            "Free \"Demo\" key from coingecko.com/en/api. Brings back CoinGecko's crypto prices and " +
                "7-day lines when it's blocking your network.",
        )
    }
}
