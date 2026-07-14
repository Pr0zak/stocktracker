package com.stocktracker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.stocktracker.app.data.prefs.ThemeMode
import com.stocktracker.app.di.ServiceLocator
import com.stocktracker.app.ui.StockTrackerRoot
import com.stocktracker.app.ui.theme.StockTrackerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings = ServiceLocator.settingsStore
            // Null initial: hold content until the persisted values load, so a non-system theme
            // preference doesn't flash the wrong theme on cold start.
            val themeMode by settings.themeMode.collectAsState(initial = null)
            val dynamicColor by settings.dynamicColor.collectAsState(initial = null)

            val mode = themeMode
            val dynamic = dynamicColor
            if (mode == null || dynamic == null) {
                Box(Modifier.fillMaxSize()) {} // window background shows briefly
            } else {
                val dark = when (mode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.DARK -> true
                    ThemeMode.LIGHT -> false
                }
                StockTrackerTheme(darkTheme = dark, dynamicColor = dynamic) {
                    StockTrackerRoot()
                }
            }
        }
    }
}
