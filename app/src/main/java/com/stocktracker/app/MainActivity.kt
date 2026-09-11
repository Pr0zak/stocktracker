package com.stocktracker.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.stocktracker.app.notify.AlertNotifier
import com.stocktracker.app.ui.StockTrackerRoot
import com.stocktracker.app.ui.theme.StockTrackerTheme

class MainActivity : ComponentActivity() {

    /**
     * The route a notification asked for, waiting to be navigated to.
     *
     * Held as state rather than read once in onCreate because the activity may already be running
     * when the tap happens; then the intent arrives at onNewIntent and there is no recomposition to
     * hang it off. Cleared by the root the moment it navigates, so a configuration change does not
     * yank the user back to a screen they have since left.
     */
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark, always, so say so rather than letting the system decide. This used to key
        // off the SYSTEM night setting while the app's own theme setting could say otherwise, which
        // is how a user on light-system-plus-dark-app got black status-bar icons on a black bar.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        pendingRoute = intent?.getStringExtra(AlertNotifier.EXTRA_ROUTE)
        setContent {
            // No gate on a persisted theme preference any more: there is one theme, so there is
            // nothing to wait for and no wrong-theme flash to avoid on cold start.
            StockTrackerTheme {
                StockTrackerRoot(
                    pendingRoute = pendingRoute,
                    onRouteConsumed = { pendingRoute = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute = intent.getStringExtra(AlertNotifier.EXTRA_ROUTE)
    }
}
