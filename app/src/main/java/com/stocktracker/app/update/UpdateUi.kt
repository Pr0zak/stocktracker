package com.stocktracker.app.update

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val update: UpdateChecker.Update) : UpdateUiState
    data object Downloading : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

class UpdateController(
    private val scope: CoroutineScope,
    private val context: Context,
) {
    var state by mutableStateOf<UpdateUiState>(UpdateUiState.Idle)
        private set

    /** [silent] = don't surface "checking"/"up to date"; used for the launch-time auto-check. */
    fun check(silent: Boolean = false) {
        if (state is UpdateUiState.Downloading) return
        if (!silent) state = UpdateUiState.Checking
        scope.launch {
            val update = UpdateChecker.check()
            state = when {
                update != null -> UpdateUiState.Available(update)
                silent -> UpdateUiState.Idle
                else -> UpdateUiState.UpToDate
            }
        }
    }

    fun install(update: UpdateChecker.Update) {
        state = UpdateUiState.Downloading
        scope.launch {
            runCatching {
                val installer = ApkInstaller(context)
                installer.install(installer.download(update.apkUrl, update.newVersion))
            }.onSuccess {
                state = UpdateUiState.Idle // system installer has taken over
            }.onFailure {
                state = UpdateUiState.Error(it.message ?: "Update failed")
            }
        }
    }

    fun dismiss() {
        state = UpdateUiState.Idle
    }
}

@Composable
fun rememberUpdateController(): UpdateController {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return remember { UpdateController(scope, context) }
}

/** Modal shown by the launch-time auto-check when a newer release is available. */
@Composable
fun UpdateDialog(controller: UpdateController) {
    when (val s = controller.state) {
        is UpdateUiState.Available -> {
            val u = s.update
            AlertDialog(
                onDismissRequest = { controller.dismiss() },
                title = { Text("Update available") },
                text = {
                    Text(
                        buildString {
                            append("v${u.newVersion} is available (you have v${u.current}).")
                            if (!u.notes.isNullOrBlank()) append("\n\n${u.notes}")
                        },
                    )
                },
                confirmButton = { TextButton(onClick = { controller.install(u) }) { Text("Update") } },
                dismissButton = { TextButton(onClick = { controller.dismiss() }) { Text("Later") } },
            )
        }
        is UpdateUiState.Downloading -> {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Downloading update…") },
                text = { CircularProgressIndicator() },
                confirmButton = {},
            )
        }
        is UpdateUiState.Error -> {
            AlertDialog(
                onDismissRequest = { controller.dismiss() },
                title = { Text("Update failed") },
                text = { Text(s.message) },
                confirmButton = { TextButton(onClick = { controller.dismiss() }) { Text("OK") } },
            )
        }
        else -> Unit
    }
}
