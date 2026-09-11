package com.stocktracker.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.SmartToy
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
import com.stocktracker.app.ui.watchlist.DipListScreen
import com.stocktracker.app.ui.watchlist.WatchlistScreen
import com.stocktracker.app.update.UpdateDialog
import com.stocktracker.app.update.rememberUpdateController

private sealed class TopDest(val route: String, val label: String, val icon: ImageVector) {
    data object Watchlist : TopDest(Routes.WATCHLIST, "Watchlist", Icons.Filled.ShowChart)
    data object Portfolio : TopDest(Routes.PORTFOLIO, "Portfolio", Icons.Filled.PieChart)
    data object Ideas : TopDest(Routes.IDEAS, "Ideas", Icons.Filled.Lightbulb)
    // A route, not a sixth bottom tab — six tabs wrap the longer labels onto two lines.
    data object Heatmap : TopDest(Routes.HEATMAP, "Heat map", Icons.Filled.GridView)
    // Same reasoning: the whole-market screens are reached from the watchlist's app bar, not from a
    // tab bar that is already at the width its labels can take.
    data object MarketScan : TopDest(Routes.MARKET_SCAN, "Market scan", Icons.Filled.Leaderboard)
    data object Sandbox : TopDest(Routes.SANDBOX, "Sandbox", Icons.Filled.SmartToy)
    // Reached from Portfolio's app bar, for the same reason Ideas is: the tab bar is already at the
    // width its five labels can take, and the journal is something you visit after a decision rather
    // than a place you live.
    data object Journal : TopDest(Routes.JOURNAL, "Verdict journal", Icons.AutoMirrored.Filled.MenuBook)
    // The Widgets gallery gave up its tab. Its job is a one-time pin of a home-screen widget, and
    // it held a fifth of the app's permanent navigation to do it — while Market scan, Heat map, the
    // catalyst calendar, the dip radar and the VIX detail had no labelled entrance at all.
    data object Widgets : TopDest(Routes.WIDGETS, "Widgets", Icons.Filled.Widgets)
    data object Markets : TopDest(Routes.MARKETS, "Markets", Icons.Filled.Leaderboard)
    data object Settings : TopDest(Routes.SETTINGS, "Settings", Icons.Filled.Settings)
}

// Ideas is deliberately NOT a top-level tab — it's reached from Portfolio ("Find new"), since deploying
// cash into new names is a portfolio action. Keeping it out also keeps the bar to five readable labels.
private val topDestinations =
    listOf(TopDest.Watchlist, TopDest.Portfolio, TopDest.Markets, TopDest.Sandbox, TopDest.Settings)

/**
 * @param pendingRoute a route a notification tap asked for, or null. Navigated to once and then
 *   handed back through [onRouteConsumed]; see MainActivity for why it is state rather than a
 *   one-shot read.
 */
@Composable
fun StockTrackerRoot(
    pendingRoute: String? = null,
    onRouteConsumed: () -> Unit = {},
) {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    // The bar used to vanish on all ten pushed screens, so a hub-grade destination — the market
    // scan, the heat map, the dip list — was a dead end you could only leave backwards. These are
    // spokes of a tab, not modal tasks, so the bar stays and the tab they belong to stays lit.
    val spokeParent: Map<String, TopDest> = mapOf(
        TopDest.MarketScan.route to TopDest.Markets,
        TopDest.Heatmap.route to TopDest.Markets,
        Routes.DIPS to TopDest.Markets,
        Routes.VIX to TopDest.Markets,
        TopDest.Ideas.route to TopDest.Portfolio,
        TopDest.Journal.route to TopDest.Portfolio,
    )
    // Calendar is a spoke too, but it is also opened per-asset from a ticker's overflow, where it
    // IS a modal task. Only the market-wide form (no symbol argument) keeps the bar.
    val isMarketCalendar = currentRoute == Routes.CALENDAR_PATTERN &&
        backStackEntry?.arguments?.getString("symbol").orEmpty().isBlank()
    val parentTab = spokeParent[currentRoute] ?: if (isMarketCalendar) TopDest.Markets else null
    val showBottomBar = topDestinations.any { it.route == currentRoute } || parentTab != null

    // A notification's tap, arriving as a route. The back stack is left intact underneath, so Back
    // from a deep-linked detail screen lands on the watchlist rather than closing the app.
    LaunchedEffect(pendingRoute) {
        val route = pendingRoute ?: return@LaunchedEffect
        runCatching { nav.navigate(route) { launchSingleTop = true } }
        onRouteConsumed()
    }

    // Launch-time update check (silent — only surfaces a dialog if a newer release exists).
    val updater = rememberUpdateController()
    LaunchedEffect(Unit) { updater.check(silent = true) }
    UpdateDialog(updater)

    // Shown once after an upgrade — silent on a fresh install and on builds with no notes.
    com.stocktracker.app.update.WhatsNewSheet()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    topDestinations.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route || parentTab == dest,
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
                    onOpenDetail = { nav.navigate(Routes.detail(it)) },
                    onAdd = { nav.navigate(Routes.ADD) },
                    onOpenVix = { nav.navigate(Routes.VIX) },
                    onOpenCalendar = { nav.navigate(Routes.calendar()) },
                    onOpenDips = { nav.navigate(Routes.DIPS) },
                    onOpenHeatmap = { nav.navigate(TopDest.Heatmap.route) },
                    onOpenMarketScan = { nav.navigate(TopDest.MarketScan.route) },
                )
            }
            composable(Routes.VIX) { VixDetailScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.DIPS) {
                DipListScreen(
                    onBack = { nav.popBackStack() },
                    onOpenDetail = { nav.navigate(Routes.detail(it)) },
                )
            }
            composable(
                route = Routes.CALENDAR_PATTERN,
                arguments = listOf(navArgument("symbol") { type = NavType.StringType; defaultValue = "" }),
            ) { entry ->
                val sym = entry.arguments?.getString("symbol").orEmpty().ifBlank { null }
                CalendarScreen(
                    onBack = { nav.popBackStack() },
                    symbol = sym,
                    onOpenDetail = { nav.navigate(Routes.detail(it)) },
                )
            }
            composable(TopDest.Portfolio.route) {
                PortfolioScreen(
                    // Ideas is a PUSHED route reached from Portfolio, not a tab being switched to.
                    // It used to navigate with the tab options — popUpTo(Watchlist) + restoreState —
                    // which meant back from Ideas landed on the Watchlist, stranding the user
                    // mid-task on a screen with no bottom bar to get out of.
                    onOpenIdeas = { nav.navigate(TopDest.Ideas.route) },
                    onOpenJournal = { nav.navigate(TopDest.Journal.route) },
                    onOpenDetail = { nav.navigate(Routes.detail(it)) },
                )
            }
            composable(TopDest.Journal.route) {
                com.stocktracker.app.ui.journal.JournalScreen(onBack = { nav.popBackStack() })
            }
            composable(TopDest.Heatmap.route) {
                com.stocktracker.app.ui.heatmap.HeatmapScreen(
                    onOpenDetail = { nav.navigate(Routes.detail(it)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(TopDest.MarketScan.route) {
                // The scan is equities-only and its rows are not necessarily on the watchlist — the
                // detail route builds its Asset from the URL, so it opens either way.
                com.stocktracker.app.ui.marketscan.MarketScanScreen(
                    onBack = { nav.popBackStack() },
                    onOpenDetail = { nav.navigate(Routes.detail(it)) },
                )
            }
            composable(TopDest.Ideas.route) {
                IdeasScreen(
                    onOpenDetail = { nav.navigate(Routes.detail(it)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(TopDest.Sandbox.route) {
                com.stocktracker.app.ui.sandbox.SandboxScreen(
                    onOpenSettings = { nav.navigate(Routes.SANDBOX_SETTINGS) },
                )
            }
            composable(Routes.SANDBOX_SETTINGS) {
                com.stocktracker.app.ui.sandbox.SandboxSettingsScreen(onBack = { nav.popBackStack() })
            }
            // Still reachable, just not with a tab: Settings → Home-screen widgets.
            composable(TopDest.Widgets.route) { WidgetGalleryScreen() }
            composable(TopDest.Markets.route) {
                com.stocktracker.app.ui.markets.MarketsScreen(
                    onOpenScan = { nav.navigate(TopDest.MarketScan.route) },
                    onOpenHeatmap = { nav.navigate(TopDest.Heatmap.route) },
                    onOpenCalendar = { nav.navigate(Routes.calendar()) },
                    onOpenDips = { nav.navigate(Routes.DIPS) },
                    onOpenVix = { nav.navigate(Routes.VIX) },
                )
            }
            composable(TopDest.Settings.route) {
                SettingsScreen(
                    onOpenMethodology = { nav.navigate(Routes.METHODOLOGY) },
                    onOpenWidgets = { nav.navigate(TopDest.Widgets.route) },
                )
            }
            composable(Routes.METHODOLOGY) {
                com.stocktracker.app.ui.settings.MethodologyScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.ADD) { AddTickerScreen(onBack = { nav.popBackStack() }) }
            composable(
                route = Routes.DETAIL_PATTERN,
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
                    onOpenCalls = {
                        nav.navigate(TopDest.Portfolio.route) {
                            popUpTo(TopDest.Watchlist.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenCalendar = { nav.navigate(Routes.calendar(Routes.calendarSymbol(asset))) },
                )
            }
        }
    }
}
