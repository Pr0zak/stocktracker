package com.stocktracker.app.update

/**
 * What changed in each release, shown once after the app updates.
 *
 * Bundled rather than fetched. The GitHub release body is the obvious source and the app already
 * talks to that endpoint — but it describes the LATEST release, not the one that happens to be
 * installed, so a user who skipped a version (or is offline right after updating, which is common)
 * would be shown notes for something they aren't running. A bundled map can only ever describe the
 * build it ships in.
 *
 * Keep entries SHORT: 3-5 bullets, each a single line, written for someone who did not read the
 * commit log. Say what changed for them, not what was refactored.
 */
object Changelog {

    /** Newest first. Key is the exact `versionName` (no leading "v"). */
    private val entries: Map<String, List<String>> = mapOf(
        "1.9.1" to listOf(
            "Fixed: opening the Sandbox could close the app if the server refused the request",
            "Fixed: saving in Settings could erase your Signals service address",
            "Fixed: the Markets tab showed the wrong scan's age, and the Sandbox blamed the network for a token problem",
        ),
        "1.9.0" to listOf(
            "New: a Daily Pick card \u2014 one stock a morning (or \"no pick today\"), with the reasons for and against drawn out",
            "New: a morning notification for the pick, and alerts when it reaches its buy zone, exit price or target",
            "New: tap the market-checks chip to see the five market health tests in plain words",
            "Plainer wording across the app: \"top 4%\" instead of percentiles, \"\u00d7 risk\" instead of R, options stats in words",
        ),
        "1.8.0" to listOf(
            "New: a Wear OS tile and complication \u2014 your phone does the fetching, the watch just shows it",
            "New: holdings with 100+ shares are marked income-eligible on the portfolio screen",
            "New: the morning brief now says when the day's news touches something you own",
            "Screen readers can now use the alert switches, list tabs, heat map and charts",
        ),
        "1.7.1" to listOf(
            "New: an access token field for the signals service, if your backend asks for one",
        ),
        "1.7.0" to listOf(
            "Fixed: home-screen widgets no longer show a stale or partial total as if it were current",
            "Fixed: a price alert is only marked sent once it actually arrives \u2014 blocked notifications used to swallow it silently",
            "New: holdings remember when you bought them, so a sale can warn you if it's short-term",
            "New: import your holdings from a Fidelity positions export instead of typing them in",
            "New: watchlist widgets can each show a different list, sort and \u0024/%",
        ),
        "1.6.0" to listOf(
            "New: choose whether the sandbox's recurring deposit lands once or twice a month",
        ),
        "1.5.0" to listOf(
            "New: choose your widgets' card colour and transparency, letting the wallpaper show through",
        ),
        "1.4.0" to listOf(
            "New: a redesigned, dark-only look across the watchlist, ticker and portfolio screens",
            "New: a Markets tab gathers the scan, heat map, calendar, dip radar and VIX in one labelled place",
            "The ticker screen now leads with your position and alerts, not eleven screens of market commentary",
            "Every empty card now says why it's empty instead of leaving a blank space",
            "Chart marks and colours now carry a second signal, so a reading doesn't depend on telling hues apart",
        ),
        "1.3.2" to listOf(
            "Fixed: the \"too many bars for candles\" note could be struck through by the price or axis labels",
        ),
        "1.3.1" to listOf(
            "Fixed: BTC's \"1D\" chart was drawing 48 hours, not 24 — every crypto range now matches its label",
        ),
        "1.3.0" to listOf(
            "A stop is now required on every call position, since it's what R gets measured against",
        ),
        "1.2.1" to listOf(
            "Fixed: the catalyst calendar silently cut events past row 30; it now says how many were dropped",
        ),
        "1.2.0" to listOf(
            "Fixed: the Stochastic indicator read closes instead of highs/lows, skewing its overbought signal",
            "Fixed: an alert with only a condition (no price levels) could silently never fire",
            "Fixed: editing one price alert could wipe every other armed level on the same asset",
            "New: armed price alerts now draw on the chart, plus real price labels and double-tap to exit a zoom",
            "New: alerts for moving-average crossings and 52-week highs, and a volume-profile overlay",
        ),
        "1.1.0" to listOf(
            "The market-scan cross-section can now be searched and acted on directly",
        ),
        "1.0.0" to listOf(
            "The watchlist now shows the regime gate, and the dip strip has the states it was missing",
        ),
        "0.99.0" to listOf(
            "Fixed: the dip radar showed \"No dips right now\" whenever it actually failed to load",
            "New: a chase warning on a ticker's entry zone, and a market-scan screen with each metric's rank",
            "Closed call positions are now measured by what they risked, and classified by how they ended",
            "New: a trade journal recording what you did against a verdict, and how it compares to the plan",
            "Performance stats no longer show half a track record, and now say what the numbers leave out",
        ),
        "0.92.0" to listOf(
            "New: sandbox drawdown is shown and shaded on the curve, alongside a list of settings changes",
        ),
        "0.91.0" to listOf(
            "Fixed: a bad quote could crash the app or print as \"\$NaN\"; sub-penny prices no longer read as \$0",
        ),
        "0.90.0" to listOf(
            "Watchlist sectors are now collapsible, and down to one control row instead of two",
        ),
        "0.89.0" to listOf(
            "New: a \"smallest company it may buy\" control for the sandbox",
        ),
        "0.88.0" to listOf(
            "Sandbox now asks for your date of birth instead of a typed-in current age",
            "New: watchlist sector verticals, with your favourite names pinned on top",
        ),
        "0.87.0" to listOf(
            "New: a chart of every sandbox arm's trajectory over time",
        ),
        "0.86.0" to listOf(
            "New: switch between sandbox arms and compare them side by side",
        ),
        "0.85.0" to listOf(
            "Sandbox fills now flag when a trade filled for a different size than the AI asked for",
        ),
        "0.84.0" to listOf(
            "Fixed: chart high/low markers were built from closing prices, not the real intraday high and low",
        ),
        "0.83.0" to listOf(
            "The app now shows when prices were last read, and refreshing them actually refreshes them",
        ),
        "0.82.0" to listOf(
            "The watchlist value card is gone; the cash form now sits below your holdings",
        ),
        "0.81.0" to listOf(
            "Fixed: a BTC sparkline could climb all day in red because it ignored the previous close",
            "The benchmark overlay and the VIX gauge no longer rely on colour alone to be read",
            "The watchlist opens with your portfolio total first; context cards collapse behind one line",
            "Watchlist rows are more compact, with the ticker and name on one line",
        ),
        "0.80.0" to listOf(
            "New: the heat map now groups tiles into labelled sector blocks",
        ),
        "0.79.0" to listOf(
            "Fixed: the after-hours recap was reporting a quiet market every single night",
        ),
        "0.78.0" to listOf(
            "Fixed: one failing alert channel could silently kill every notification after it",
        ),
        "0.77.0" to listOf(
            "Fixed: the sandbox trend line was counting your deposits as investment performance",
        ),
        "0.76.0" to listOf(
            "New: tap a holding to see everything it paid — average cost and every fill behind it",
            "The sandbox trade log is now one line per trade, with the AI's reasoning a tap away",
            "New: a macro backdrop card showing the risk the sandbox trader reasons against",
            "New: pick which BTC ETF the sandbox buys, under Universe",
        ),
        "0.75.2" to listOf(
            "Heat map: the signals view now says how old the scan behind it is",
            "Heat map: names the scan couldn't measure are listed instead of just missing",
            "Heat map: labels no longer clip if you use a larger system font size",
        ),
        "0.75.1" to listOf(
            "Heat map: oversold names are no longer shown as if nothing was flagged",
            "Heat map: outside market hours it now colours by the pre/post-market move",
            "Heat map: a big move looks bigger \u2014 colour no longer maxes out at 4%",
        ),
        "0.75.0" to listOf(
            "New: a heat map — grid icon on the watchlist. Tile size is market cap, colour is today's move",
            "Switch to \"My signals\" to size by how far a name is below its 52-week high",
        ),
        "0.74.0" to listOf(
            "New: \"Who's been buying\" — insider and congressional buying across your watchlist",
        ),
        "0.73.0" to listOf(
            "The value screen says which list it ran over and whether it's out of date",
            "A holding we couldn't fetch is no longer reported as \"not enough history\"",
            "Share count is only called dilution when it actually is",
        ),
        "0.72.0" to listOf(
            "The value screen now appears if you set the Signals URL after opening Ideas",
        ),
        "0.71.0" to listOf(
            "New: a \"cheap — or broken?\" read on names below their 200-week line",
            "Fixed: a stock split was being reported as huge share dilution",
        ),
        "0.70.0" to listOf(
            "New: a 200-week value screen on Ideas — names trading far below their long-term trend",
            "It's free (no AI cost) and works with the AI analyst switched off",
        ),
        "0.69.0" to listOf(
            "AI totals now warn when your book mixes currencies",
            "A failed review or plan retries when you reopen it instead of sticking",
            "The AI dialogs label their total as including cash, unlike the screen behind them",
        ),
        "0.68.0" to listOf(
            "Refresh on the portfolio review and rebalance now really refetches",
            "The rebalance screen warns when part of your book couldn't be priced",
        ),
        "0.67.0" to listOf(
            "Portfolio review now sends every holding, not just the ones the app could price",
            "A plan is dropped when your holdings change, so it can't name a position you sold",
            "Rebalance plans are checked against your real book before you see them",
            "Fractional crypto trades no longer show as \"0.00 sh\"",
        ),
        "0.66.0" to listOf(
            "Portfolio review now flags any holding it couldn't price",
        ),
        "0.65.0" to listOf(
            "Options, wheel and covered-call cards now say when they were priced",
        ),
        "0.64.0" to listOf(
            "The one-tap AI refresh keeps the deep model instead of quietly downgrading it",
            "A covered-call suggestion clears when you change the shares it was sized against",
            "Adding an exclusion can no longer wipe the rest of the list",
            "Fund and withdraw now show the server's actual reason when they fail",
        ),
        "0.63.0" to listOf(
            "A failed AI refresh now says so instead of leaving yesterday's verdict looking current",
            "AI verdicts show when they were produced, so a cached one can't pass for fresh",
            "Missing analyst levels are omitted rather than shown as \"$0\"",
            "Ideas clears its picks when you change the cash, scope or model behind them",
            "Withdraw asks for confirmation",
        ),
        "0.62.0" to listOf(
            "\"Run a decision cycle now\" actually runs one — the request was being sent empty",
            "Margin mode can be switched back off, and wash-sale avoidance back on",
            "A sandbox setting that fails to save now says so instead of claiming success",
            "Funding and reset can't be double-submitted",
            "A half-loaded sandbox no longer shows \"No trades yet\" over real data",
        ),
        "0.61.0" to listOf(
            "Sparklines are back for stocks — and now drawn from real intraday prices, not guesswork",
        ),
        "0.60.0" to listOf(
            "Widgets now say when their price stopped being current",
            "Stock sparklines only appear when there's a real recent window behind them",
            "The chart's cost line is hidden when some holdings have no cost entered",
        ),
        "0.59.0" to listOf(
            "Portfolio totals now flag when they mix currencies — no exchange rate is applied",
            "Switching chart ranges quickly no longer leaves the wrong window on screen",
            "Price alerts no longer fire on a stale quote from a previous session",
            "Option expiry countdowns and alerts are no longer a day early",
            "Double-tapping \"Record sale\" can no longer double-count realized P&L",
        ),
        "0.58.0" to listOf(
            "A comma or currency symbol in a position field no longer wipes your shares and cost",
            "Alerts are no longer marked as sent when they were never actually delivered",
            "A new batch of alerts no longer replaces an unread one",
            "The S&P overlay no longer draws a flat line on intraday charts",
            "Widgets show all your tickers, or say when they couldn't",
        ),
        "0.57.0" to listOf(
            "Tap the version in Settings → About to see what changed in recent releases",
        ),
        "0.56.0" to listOf(
            "The app now shows a short summary of what changed after each update",
            "Options suggestions no longer claim an expiry is clear of earnings when the date is unknown",
            "Premiums quoted from a days-old trade are flagged instead of shown as current",
        ),
        "0.55.0" to listOf(
            "Alerts no longer repeat themselves after the app is closed mid-check",
            "Portfolio totals now say when a holding couldn't be priced instead of quietly omitting it",
            "Prices older than a few hours are labelled \"last known\" rather than \"Today\"",
            "Widgets no longer show another ticker's price after you reconfigure them",
        ),
        "0.54.0" to listOf(
            "Restoring a backup can no longer wipe your holdings if you pick the wrong file",
            "Backups now include your option positions and realized P&L history",
            "Deleting a ticker can be undone — it takes your shares and cost basis with it",
            "Re-exporting over an existing backup no longer corrupts it",
        ),
        "0.53.0" to listOf(
            "The sandbox scorecard now grades sell decisions as well as buys",
        ),
    )

    /** Bullets for [version], or empty when that build shipped no user-facing notes. */
    fun forVersion(version: String): List<String> = entries[version].orEmpty()

    /** Recent releases, newest first, for the on-demand view in Settings → About. */
    fun recent(limit: Int = 10): List<Pair<String, List<String>>> =
        entries.entries.take(limit).map { it.key to it.value }

    /**
     * Everything worth showing when moving [from] → [to], newest first.
     *
     * Skipping releases is normal — the in-app updater only offers the latest — so a user on 0.53.0
     * who updates straight to 0.55.0 should see both intervening sets, not just the newest.
     * Capped so a long gap can't produce a wall of text.
     */
    fun between(from: String?, to: String, limit: Int = 8): List<String> {
        if (from == null || from == to) return forVersion(to).take(limit)
        val versions = entries.keys.toList()
        val toIdx = versions.indexOf(to)
        if (toIdx < 0) return emptyList()
        val fromIdx = versions.indexOf(from)
        // `from` unknown (older than anything recorded, or a dev build) → just show the new version.
        val slice = if (fromIdx < 0) listOf(to) else versions.subList(toIdx, fromIdx)
        return slice.flatMap { forVersion(it) }.take(limit)
    }
}
