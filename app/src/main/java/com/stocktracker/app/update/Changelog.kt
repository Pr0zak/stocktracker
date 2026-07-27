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
