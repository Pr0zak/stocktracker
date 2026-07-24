package com.stocktracker.app.ui.sandbox

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * ONE [SandboxViewModel] shared by the Sandbox screen and its settings page.
 *
 * Navigation Compose gives every destination its own ViewModelStore, so a plain `viewModel()` in each
 * screen creates two independent instances with two independent caches. That made changes made on the
 * settings page invisible when you navigated back — most visibly a Reset, which cleared the server but
 * left the main screen still rendering its pre-reset positions and trade log.
 *
 * Scoping to the Activity gives both destinations the same instance, so a reset (or any settings edit)
 * updates the single shared StateFlow and both screens recompose from it.
 */
@Composable
internal fun sandboxViewModel(): SandboxViewModel =
    viewModel(viewModelStoreOwner = LocalContext.current as ComponentActivity)
