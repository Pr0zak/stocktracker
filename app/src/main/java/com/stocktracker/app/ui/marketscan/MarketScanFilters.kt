package com.stocktracker.app.ui.marketscan

import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType

/**
 * The query the market scan is asked for — filters, sort direction, and how many rows — as pure
 * data, so every rule about what goes on the wire is testable without a Compose harness.
 *
 * The names here are NOT invented. `GET /market_scan` validates every query parameter against
 * `scan_store.FILTER_NAMES` (`min_<metric>` / `max_<metric>` over the metric columns, plus the three
 * bare booleans) and answers a 422 that names anything it does not recognise. So the vocabulary is
 * built here from the same metric keys the rows are already rendered with — a metric that can be
 * shown is a metric that can be filtered — and nothing else is ever emitted.
 */

/**
 * One of the scan's three-valued booleans, offered as three-valued.
 *
 * `above_sma50` is null in the store for a name with under 50 bars — "no 50-day average to be above
 * or below", which is not the same as "below". The filter inherits that: not filtering at all, and
 * filtering for `false`, are different questions, and a two-state checkbox could only ask one of
 * them. Hence [ScanBoolFilter.trueLabel] / [ScanBoolFilter.falseLabel] — the chip says which side it
 * is asking for, never a bare metric name whose state the reader has to guess.
 */
data class ScanBoolFilter(
    val key: String,
    val trueLabel: String,
    val falseLabel: String,
) {
    fun label(value: Boolean): String = if (value) trueLabel else falseLabel
}

/** The scan's three booleans, spelled as `scan_store.BOOL_FILTERS` spells them. */
val SCAN_BOOL_FILTERS: List<ScanBoolFilter> = listOf(
    ScanBoolFilter("above_sma50", "Above 50-day", "Below 50-day"),
    ScanBoolFilter("above_sma200", "Above 200-day", "Below 200-day"),
    ScanBoolFilter("ma_stacked", "MAs stacked", "MAs not stacked"),
)

/**
 * The metrics offered as min/max, in the order a reader meets them.
 *
 * Derived from [RANKED_SCAN_METRICS] rather than listed again: every key there is a column of the
 * scan table, so `min_<key>`/`max_<key>` are in the server's vocabulary by construction, and a
 * metric added to one list cannot go missing from the other.
 */
val SCAN_FILTER_METRICS: List<ScanMetric> get() = RANKED_SCAN_METRICS

/** Every parameter name this file can put on the wire. Nothing outside it is ever emitted. */
val SCAN_FILTER_NAMES: Set<String> =
    SCAN_BOOL_FILTERS.map { it.key }.toSet() +
        RANKED_SCAN_METRICS.flatMap { listOf("min_${it.key}", "max_${it.key}") }

/** A half-open, half-closed or closed range on one metric. Either end may be absent. */
data class ScanBound(val min: Double? = null, val max: Double? = null) {
    val isEmpty: Boolean get() = min == null && max == null
}

/**
 * The filter set currently asked for. Empty means "the whole night", which is the only state in
 * which the screen may present its rows as the market's leaders without qualification.
 */
data class MarketScanFilters(
    val bools: Map<String, Boolean> = emptyMap(),
    val bounds: Map<String, ScanBound> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = bools.isEmpty() && bounds.values.none { !it.isEmpty }

    /** How many separate questions this set asks — one per bound end, one per boolean. */
    val activeCount: Int get() = chips().size

    fun withBool(key: String, value: Boolean?): MarketScanFilters =
        copy(bools = if (value == null) bools - key else bools + (key to value))

    /**
     * A bound end, refused rather than stored when it is not a finite number.
     *
     * `min_rsi14=NaN` is a 422 on the server, and on any client that swallowed it every comparison
     * would be false — a legitimately-shaped empty cross-section produced by a typo, which reads as
     * "nothing in the market qualifies".
     */
    fun withBound(key: String, min: Double?, max: Double?): MarketScanFilters {
        val b = ScanBound(min?.takeIf { it.isFinite() }, max?.takeIf { it.isFinite() })
        return copy(bounds = if (b.isEmpty) bounds - key else bounds + (key to b))
    }

    fun cleared(): MarketScanFilters = MarketScanFilters()

    /**
     * The query parameters, in a fixed order so the same filter set is the same request (and so the
     * response cache on the server is hit rather than split by key order).
     *
     * Iterating the DECLARED vocabulary rather than the maps is what makes an unknown key
     * unsendable: a bound stored under a name the route would refuse simply never reaches the wire.
     */
    fun queryParams(): List<Pair<String, String>> = buildList {
        SCAN_BOOL_FILTERS.forEach { f -> bools[f.key]?.let { add(f.key to it.toString()) } }
        SCAN_FILTER_METRICS.forEach { m ->
            val b = bounds[m.key] ?: return@forEach
            b.min?.takeIf { it.isFinite() }?.let { add("min_${m.key}" to num(it)) }
            b.max?.takeIf { it.isFinite() }?.let { add("max_${m.key}" to num(it)) }
        }
    }

    /**
     * One label per active filter, for chips that sit ON the screen rather than inside a sheet.
     *
     * A filtered list with no visible filter is this codebase's recurring defect wearing a new
     * coat: "no names matched" under a hidden `min_adx14=90` is indistinguishable from a market in
     * which nothing is trending, and the reader has no way to tell which one they are looking at.
     */
    fun chips(): List<String> = buildList {
        SCAN_BOOL_FILTERS.forEach { f -> bools[f.key]?.let { add(f.label(it)) } }
        SCAN_FILTER_METRICS.forEach { m ->
            val b = bounds[m.key] ?: return@forEach
            b.min?.takeIf { it.isFinite() }?.let { add("${m.label} ≥ ${num(it)}") }
            b.max?.takeIf { it.isFinite() }?.let { add("${m.label} ≤ ${num(it)}") }
        }
    }

    /** The chips as one line. Empty string when nothing is filtered — never "no filters", which
     *  would occupy the same space as a real filter and train the eye to skip it. */
    fun summary(): String = chips().joinToString(" · ")

    private fun num(v: Double): String =
        if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
}

// ------------------------------------------------------------------------------------ sorting

/**
 * `sort=rel_strength` is the ergonomic name the route defaults to; the column is `rel_strength_3mo`.
 * The server echoes back what it was ASKED, so a response can carry either spelling of the same
 * ordering — and a screen that compared them as strings would decide the rows it is showing are not
 * the rows it asked for.
 */
private val SCAN_SORT_ALIASES: Map<String, String> = mapOf("rel_strength" to "rel_strength_3mo")

/** The metric a sort string names, with the "-" and any alias resolved. Null when it names none. */
fun scanSortBase(sort: String?): String? {
    val s = sort?.trim()?.removePrefix("-")?.takeIf { it.isNotEmpty() } ?: return null
    return SCAN_SORT_ALIASES[s] ?: s
}

/** True when the sort string asks for ASCENDING — the "-" prefix the route documents. */
fun scanSortAscending(sort: String?): Boolean = sort?.trim()?.startsWith("-") == true

/** The wire form: "adx14" descending, "-adx14" ascending. */
fun scanSortParam(metric: String, ascending: Boolean): String =
    if (ascending) "-$metric" else metric

/**
 * Whether two sort strings name the same ordering.
 *
 * Used to light the right chip from [MarketScanUiState.appliedSort] — what the server actually did,
 * which is what the rows belong to — without demanding it be spelled the way we asked.
 */
fun scanSortMatches(a: String?, b: String?): Boolean =
    scanSortMetricMatches(a, b) && scanSortAscending(a) == scanSortAscending(b)

/**
 * Whether two sort strings name the same METRIC, direction aside.
 *
 * What a metric chip is lit by. The direction is its own control, so "-rsi14" and "rsi14" are the
 * same chip seen from opposite ends — comparing the full string would unlight every chip the moment
 * the toggle flipped, and the screen would show a ranked list with nothing claiming to rank it.
 */
fun scanSortMetricMatches(a: String?, b: String?): Boolean = scanSortBase(a) == scanSortBase(b)

// ------------------------------------------------------------------------------------- limit

/**
 * The most rows the route will return, whatever is asked for: `limit = max(1, min(200, limit))` in
 * `market_scan_endpoint`, because the whole night is ~3,100 rows and that is a bulk export.
 *
 * THE SERVER IS AUTHORITATIVE. Offering "500" in the picker would put a number on screen that no
 * response can ever satisfy: the request would come back with 200 rows and `limit: 200` echoed, and
 * the control would sit there claiming a depth of list the user is not looking at. Clamping here to
 * the same 200 means the picker only ever offers slices that actually arrive.
 */
const val MARKET_SCAN_LIMIT_MAX: Int = 200

/** The offered depths. Every one is <= [MARKET_SCAN_LIMIT_MAX], and a test pins that. */
val MARKET_SCAN_LIMITS: List<Int> = listOf(25, 50, 100, 200)

/** The server's clamp, mirrored: `limit = max(1, min(200, limit))`. */
fun clampScanLimit(limit: Int): Int = limit.coerceIn(1, MARKET_SCAN_LIMIT_MAX)

// ------------------------------------------------------------------------- request identity

/**
 * Everything about a request that changes the rows it comes back with.
 *
 * A response is only allowed onto the screen if this still describes what the screen is asking for.
 * The sort half of that guard already existed; filters need it for exactly the same reason and with
 * worse consequences — a slow response issued under `min_adx14=25` painted under chips that now say
 * `above_sma200` is a list that contradicts the controls above it, and nothing on screen would say
 * so.
 */
data class ScanRequest(
    val sort: String?,
    val filters: MarketScanFilters,
    val limit: Int,
)

/**
 * Drop this response, or paint it?
 *
 * Two reasons to drop: it is older than one already shown ([mySeq] < [applied]), or it was issued
 * for a query the screen has since moved off. Sequence numbers rather than the response's echoed
 * `sort`, because the server may normalise that (see [SCAN_SORT_ALIASES]) and a strict string match
 * against a normalised echo would discard every response forever — an empty list reading "nothing
 * found", which is the system saying it looked when in truth it threw every answer away.
 */
fun scanResponseIsStale(
    mySeq: Int,
    applied: Int,
    requested: ScanRequest,
    current: ScanRequest,
): Boolean = mySeq < applied || requested != current

// -------------------------------------------------------------------------------- watchlist

/**
 * The watchlist symbols a scan row could collide with, upper-cased.
 *
 * STOCK entries only, and that is not a shortcut: [Asset.id] is `"STOCK:AAPL"` for equities and
 * `"CRYPTO:<coingecko id>"` for coins, and `WatchlistStore.add` de-duplicates on that id. The scan
 * universe is equities, so a row is added as a STOCK — meaning a crypto entry that happens to share
 * a ticker would NOT stop the add, and reporting it as "already on your list" would be a lie the
 * user could not act on.
 */
fun watchedStockSymbols(watchlist: List<Asset>): Set<String> =
    watchlist.asSequence()
        .filter { it.type == AssetType.STOCK }
        .map { it.symbol.trim().uppercase() }
        .filter { it.isNotEmpty() }
        .toSet()

/** Case-insensitive, because a watchlist entry may be hand-typed and the scan is always upper. */
fun isWatched(watched: Set<String>, symbol: String): Boolean =
    watched.contains(symbol.trim().uppercase())

// ------------------------------------------------------------------------------ match count

/**
 * "Top 50 of 812 matching · 3,101 scanned" — what makes a filter checkable instead of a black box.
 *
 * NULL when there is nothing honest to say. Each half is printed only if the server sent it: a
 * missing `total_matching` is not zero (that would say the filter matched nothing, which is a claim
 * about the market), and a missing `scanned` is not the row count on screen.
 */
fun scanMatchLine(shown: Int, totalMatching: Int?, scanned: Int?): String? {
    val parts = buildList {
        if (totalMatching != null) add("Top ${n(shown)} of ${n(totalMatching)} matching")
        else if (shown > 0) add("Showing ${n(shown)}")
        scanned?.takeIf { it > 0 }?.let { add("${n(it)} scanned") }
    }
    return parts.joinToString(" · ").ifEmpty { null }
}

private fun n(v: Int): String = String.format(java.util.Locale.US, "%,d", v)

/**
 * What the screen asks for before the user chooses anything.
 *
 * Named explicitly rather than left null: the route's own default is `rel_strength`, an ALIAS whose
 * echo would not match any chip key, and the direction toggle needs a string to read a direction
 * off before the first response lands.
 */
const val DEFAULT_SCAN_SORT: String = "rel_strength_3mo"
