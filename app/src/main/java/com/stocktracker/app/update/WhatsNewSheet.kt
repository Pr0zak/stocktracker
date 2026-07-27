package com.stocktracker.app.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stocktracker.app.BuildConfig
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.flow.first

/**
 * "What's new", shown once after the app updates to a new version.
 *
 * Deliberately quiet: nothing on a fresh install (a new user has no "new"), nothing when the build
 * has no notes recorded, and it marks itself seen as soon as it is shown so it can never nag. A few
 * short lines and a dismiss — this appears over the watchlist at launch, so it earns very little
 * of the user's attention before it becomes an obstacle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet() {
    var notes by remember { mutableStateOf<List<String>>(emptyList()) }
    var version by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val settings = ServiceLocator.settingsStore
        val current = BuildConfig.VERSION_NAME
        val lastSeen = settings.lastSeenVersion.first()
        if (lastSeen == current) return@LaunchedEffect
        if (lastSeen == null) {
            // Fresh install: record the version so the NEXT upgrade shows notes, but say nothing now.
            settings.setLastSeenVersion(current)
            return@LaunchedEffect
        }
        val entries = Changelog.between(lastSeen, current)
        // Mark seen regardless — a build with no notes must not leave the flag stale and surface an
        // old changelog at some later upgrade.
        settings.setLastSeenVersion(current)
        if (entries.isNotEmpty()) {
            version = current
            notes = entries
        }
    }

    if (notes.isEmpty()) return
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = { notes = emptyList() },
        sheetState = sheetState,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "What's new in $version",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            notes.forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyMedium)
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = { notes = emptyList() }) { Text("Got it") }
            }
        }
    }
}
