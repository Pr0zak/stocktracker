package com.stocktracker.app.ui.watchlist

import com.stocktracker.app.data.model.AssetType

/**
 * Sorting the watchlist into verticals.
 *
 * Kept pure and separate from the screen so the bucketing can be tested without a Compose harness —
 * the interesting decisions here are all about what a missing answer means, and those are exactly
 * the ones that are invisible on a screenshot.
 */
object WatchlistVerticals {

    const val FAVORITES = "★ Favorites"
    const val ETFS = "ETFs"
    const val CRYPTO = "Crypto"

    /** The server looked and returned no sector. True of ETFs and warrants at Yahoo. */
    const val OTHER = "Other"

    /**
     * Nobody has looked yet — the lookup has not landed, or the backend is unreachable.
     *
     * Deliberately NOT merged into [OTHER]. "We asked and this security has no sector" and "we have
     * not asked" are different facts, and only the first is something the app knows. Merging them
     * would render an outage as a confident classification, and the user would have no way to tell
     * a genuinely unclassifiable warrant from every stock they own during a backend outage.
     */
    const val UNCLASSIFIED = "Not classified yet"

    /** Catch-alls sink to the bottom in this order, whatever their size. A vertical is a statement
     *  about what a company does; these four are statements about the shape of our data, so they
     *  read as an appendix rather than as the most important section on the screen. */
    private val TRAILING = listOf(ETFS, CRYPTO, OTHER, UNCLASSIFIED)

    /**
     * Which vertical a row belongs to.
     *
     * [knownSectors] maps symbol to the server's answer, where a null VALUE means "classified, no
     * sector" and an ABSENT KEY means "not looked up". Asset type and ETF-ness win over the sector
     * map because they are known locally and are the more useful split: an S&P fund filed under
     * Financial Services would be technically defensible and practically useless.
     */
    fun verticalFor(
        type: AssetType,
        symbol: String,
        isEtf: Boolean,
        knownSectors: Map<String, String?>,
    ): String = when {
        type == AssetType.CRYPTO -> CRYPTO
        isEtf -> ETFS
        else -> {
            val key = symbol.uppercase()
            when {
                !knownSectors.containsKey(key) -> UNCLASSIFIED
                else -> knownSectors[key]?.takeIf { it.isNotBlank() } ?: OTHER
            }
        }
    }

    /**
     * Section order: favourites, then the real verticals largest-first, then the catch-alls.
     *
     * Largest-first rather than alphabetical because the list is read top-down and the biggest
     * concentration is the one worth seeing without scrolling. Ties break by name so the order is
     * stable across refreshes — a section that reshuffles on every price tick is unreadable.
     */
    fun sectionOrder(counts: Map<String, Int>): List<String> {
        val real = counts.keys.filter { it != FAVORITES && it !in TRAILING }
            .sortedWith(compareByDescending<String> { counts[it] ?: 0 }.thenBy { it })
        val trailing = TRAILING.filter { counts.containsKey(it) }
        return listOfNotNull(FAVORITES.takeIf { counts.containsKey(it) }) + real + trailing
    }

    /**
     * Bucket rows into sections, favourites lifted out of their vertical into one pinned group.
     *
     * A favourite appears ONCE, at the top, and not again under its sector. Showing it twice would
     * double every count on the screen and leave the user checking whether two rows for the same
     * ticker meant two positions.
     */
    fun <T> group(
        rows: List<T>,
        isFavorite: (T) -> Boolean,
        verticalOf: (T) -> String,
    ): LinkedHashMap<String, List<T>> {
        val buckets = LinkedHashMap<String, MutableList<T>>()
        rows.forEach { row ->
            val key = if (isFavorite(row)) FAVORITES else verticalOf(row)
            buckets.getOrPut(key) { mutableListOf() }.add(row)
        }
        val counts = buckets.mapValues { it.value.size }
        val out = LinkedHashMap<String, List<T>>()
        sectionOrder(counts).forEach { name -> buckets[name]?.let { out[name] = it } }
        return out
    }
}
