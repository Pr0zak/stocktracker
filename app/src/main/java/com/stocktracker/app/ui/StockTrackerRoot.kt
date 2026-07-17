package com.stocktracker.app.ui

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.ui.calendar.CalendarScreen
import com.stocktracker.app.ui.detail.DetailScreen
import com.stocktracker.app.ui.detail.VixDetailScreen
import com.stocktracker.app.ui.gallery.WidgetGalleryScreen
import com.stocktracker.app.ui.ideas.IdeasScreen
import com.stocktracker.app.ui.portfolio.PortfolioScreen
import com.stocktracker.app.ui.search.AddTickerScreen
import com.stocktracker.app.ui.settings.SettingsScreen
import com.stocktracker.app.ui.watchlist.WatchlistScreen
import com.stocktracker.app.update.UpdateDialog
import com.stocktracker.app.update.rememberUpdateController

private sealed class TopDest(val route: String, val label: String, val icon: ImageVector) {
    data object Watchlist : TopDest("watchlist", "Watchlist", Icons.Filled.ShowChart)
    data object Portfolio : TopDest("portfolio", "Portfolio", Icons.Filled.PieChart)
    data object Ideas : TopDest("ideas", "Ideas", Icons.Filled.Lightbulb)
    data object Widgets : TopDest("widgets", "Widgets", Icons.Filled.Widgets)
    data object Settings : TopDest("settings", "Settings", Icons.Filled.Settings)
}

private val topDestinations =
    listOf(TopDest.Watchlist, TopDest.Portfolio, TopDest.Ideas, TopDest.Widgets, TopDest.Settings)

private fun detailRoute(asset: Asset): String {
    val name = Uri.encode(asset.displayName)
    val cg = Uri.encode(asset.coinGeckoId ?: "")
    return "detail/${asset.type.name}/${Uri.encode(asset.symbol)}?name=$name&cg=$cg"
}

@Composable
fun StockTrackerRoot() {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = topDestinations.any { it.route == currentRoute }

    // Launch-time update check (silent — only surfaces a dialog if a newer release exists).
    val updater = rememberUpdateController()
    LaunchedEffect(Unit) { updater.check(silent = true) }
    UpdateDialog(updater)

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topDestinations.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = {
                                nav.navigate(dest.route) {
                                    popUpTo(TopDest.Watchlist.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(dest.icon, contentDescription = dest.label) },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = nav,
            startDestination = TopDest.Watchlist.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopDest.Watchlist.route) {
                WatchlistScreen(
                    onOpenDetail = { nav.navigate(detailRoute(it)) },
                    onAdd = { nav.navigate("add") },
                    onOpenVix = { nav.navigate("vix") },
                    onOpenCalendar = { nav.navigate("calendar") },
                )
            }
            composable("vix") { VixDetailScreen(onBack = { nav.popBackStack() }) }
            composable(
                route = "calendar?symbol={symbol}",
                arguments = listOf(navArgument("symbol") { type = NavType.StringType; defaultValue = "" }),
            ) { entry ->
                val sym = entry.arguments?.getString("symbol").orEmpty().ifBlank { null }
                CalendarScreen(onBack = { nav.popBackStack() }, symbol = sym)
            }
            composable(TopDest.Portfolio.route) { PortfolioScreen() }
            composable(TopDest.Ideas.route) {
                IdeasScreen(onOpenDetail = { nav.navigate(detailRoute(it)) })
            }
            composable(TopDest.Widgets.route) { WidgetGalleryScreen() }
            composable(TopDest.Settings.route) { SettingsScreen() }
            composable("add") { AddTickerScreen(onBack = { nav.popBackStack() }) }
            composable(
                route = "detail/{type}/{symbol}?name={name}&cg={cg}",
                arguments = listOf(
                    navArgument("type") { type = NavType.StringType },
                    navArgument("symbol") { type = NavType.StringType },
                    navArgument("name") { type = NavType.StringType; defaultValue = "" },
                    navArgument("cg") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val type = runCatching {
                    AssetType.valueOf(entry.arguments?.getString("type") ?: "STOCK")
                }.getOrDefault(AssetType.STOCK)
                val symbol = entry.arguments?.getString("symbol").orEmpty()
                val name = entry.arguments?.getString("name").orEmpty()
                val cg = entry.arguments?.getString("cg").orEmpty().ifBlank { null }
                val asset = Asset(symbol, type, name.ifBlank { symbol }, cg)
                DetailScreen(
                    asset = asset,
                    onBack = { nav.popBackStack() },
                    onOpenCalendar = {
                        // Crypto calendars use the backend's Yahoo-form symbol (BTC → BTC-USD).
                        val calSym = if (asset.type == AssetType.CRYPTO) "${asset.symbol}-USD" else asset.symbol
                        nav.navigate("calendar?symbol=${Uri.encode(calSym)}")
                    },
                )
            }
        }
    }
}
