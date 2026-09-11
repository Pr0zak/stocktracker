package com.stocktracker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.stocktracker.app.ui.StockTrackerRoot
import com.stocktracker.app.ui.theme.StockTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark, always, so say so rather than letting the system decide. This used to key
        // off the SYSTEM night setting while the app's own theme setting could say otherwise, which
        // is how a user on light-system-plus-dark-app got black status-bar icons on a black bar.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            // No gate on a persisted theme preference any more: there is one theme, so there is
            // nothing to wait for and no wrong-theme flash to avoid on cold start.
            StockTrackerTheme {
                StockTrackerRoot()
            }
        }
    }
}
