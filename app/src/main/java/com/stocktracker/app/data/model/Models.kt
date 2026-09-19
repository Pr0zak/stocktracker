package com.stocktracker.app.data.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
enum class AssetType { STOCK, CRYPTO }

/**
 * One purchase lot behind a holding (MONEY-2).
 *
 * A holding used to be two scalars — [Asset.shares] and [Asset.avgCost] — with no history behind
 * them: adding to a position meant re-typing a new blended total by hand, and there was no record of
 * WHEN any of it was bought. That missing date is why a tax-aware rebalance can't tell short-term
 * from long-term, why a split has no anchor to correct against, and why there is no realised-gains
 * history for equities. A lot is the fix: [shares]/[avgCost] on [Asset] are now derived by folding
 * over a list of these.
 *
 * [costPerShare] and [acquiredDateIso] are independently nullable, and null means UNKNOWN in both —
 * never zero, never "today", never rendered as a real value. That is what lets a lot synthesized from
 * the pre-MONEY-2 shape (bare shares + avgCost, no date at all) decode honestly instead of inventing
 * a purchase date nobody recorded.
 *
 * NEGATIVE [shares] (MONEY-3) records a DISPOSAL — shares an assigned short call took away, most
 * commonly — rather than an acquisition. [costPerShare] on a disposal lot is the price the shares left
 * at (a fact worth keeping for history/notes), NOT a cost input: see [Asset.avgCost] for how the two
 * kinds are folded differently.
 */
@Serializable
data class Lot(
    val shares: Double,
    /** What was paid per share for this lot — or, on a disposal (negative [shares]), what it left at.
     *  Null means unknown, not free — see [Asset.avgCost]. */
    val costPerShare: Double? = null,
    /** ISO yyyy-MM-dd. Null means unknown — a migrated pre-MONEY-2 holding always lands here. */
    val acquiredDateIso: String? = null,
)

/**
 * A tracked instrument. [coinGeckoId] is set for crypto (e.g. "bitcoin").
 *
 * [lots] replaced the old bare `shares`/`avgCost` scalars (MONEY-2); those are now derived getters
 * below so every existing read site ([Asset.shares], [Asset.avgCost]) keeps compiling and behaving
 * exactly as before. [AssetSerializer] is what makes an [Asset] persisted in the old shape decode as
 * a single migrated [Lot] with a null date, so nothing already on a watchlist or in a backup silently
 * loses its position on the next read.
 */
@Serializable(with = AssetSerializer::class)
data class Asset(
    val symbol: String,          // "AAPL", "BTC"
    val type: AssetType,
    val displayName: String,     // "Apple Inc.", "Bitcoin"
    val coinGeckoId: String? = null,
    /** Purchase lots behind this holding. Empty means no position — same meaning as the old null. */
    val lots: List<Lot> = emptyList(),
    val alerts: AssetAlerts? = null,     // price / percent threshold alerts
    val groups: List<String> = emptyList(), // named watchlists this asset belongs to
    /**
     * Starred, so the row is pinned above the sector sections.
     *
     * Defaults to FALSE, and the default is the point: being on the watchlist is not the same claim
     * as being a favourite. Every existing entry deserializes without this key and lands on false,
     * so the feature arrives empty and means something the first time it is used. A migration that
     * starred the whole list to "preserve" it would have produced a favourites section identical to
     * the watchlist -- a filter that filters nothing, which is worse than no filter at all.
     */
    val favorite: Boolean = false,
) {
    /**
     * Stable identity. Crypto is keyed by CoinGecko id (falling back to ticker) so distinct coins
     * that reuse a ticker symbol don't collide; stocks are keyed by symbol.
     */
    val id: String get() = when (type) {
        AssetType.CRYPTO -> "CRYPTO:${coinGeckoId ?: symbol.uppercase()}"
        AssetType.STOCK -> "STOCK:${symbol.uppercase()}"
    }

    /**
     * Total shares held, derived from [lots]. Null — not 0.0 — when there is no position at all, so
     * every existing `asset.shares ?: 0.0` / `asset.shares != null` read site keeps meaning what it
     * always meant.
     */
    val shares: Double? get() = lots.takeIf { it.isNotEmpty() }?.sumOf { it.shares }

    /**
     * Weighted average cost per share across [lots]. Null when there is no position, AND null when
     * ANY acquisition lot's cost is unknown — blending a real cost against a missing one would silently
     * treat the unpriced lot as free and understate the true basis, which is exactly the
     * confident-looking wrong number this project refuses to print.
     *
     * A NEGATIVE-shares lot (MONEY-3) is a DISPOSAL — shares an assigned short call took away — not an
     * acquisition, and it is folded differently on purpose. Average-cost accounting means selling part
     * of a position does not change the average cost of what is LEFT; it only shrinks the share count.
     * So a disposal removes its shares from the running pool AT THE POOL'S OWN AVERAGE COST SO FAR,
     * never at the disposal lot's own [Lot.costPerShare] — that field on a disposal is the price the
     * shares left AT (the strike), a fact worth keeping for history, not an input to what remains.
     * Folding it in like an acquisition would blend the strike into the weighted average and silently
     * UNDERSTATE the surviving shares' cost basis whenever they left above cost — exactly the trade a
     * covered call is. Worked: 200 sh @ $50 avg, then 100 sh called away at a $60 strike → the average
     * cost of the 100 sh left is still $50, not $40.
     */
    val avgCost: Double?
        get() {
            if (lots.isEmpty()) return null
            var shares = 0.0
            var cost = 0.0
            var costKnown = true
            for (lot in lots) {
                if (lot.shares >= 0.0) {
                    if (lot.costPerShare == null) costKnown = false else cost += lot.shares * lot.costPerShare
                    shares += lot.shares
                } else {
                    val disposed = -lot.shares
                    if (costKnown && shares > 0.0) cost -= disposed * (cost / shares)
                    shares += lot.shares // negative: shrinks the pool
                }
            }
            if (!costKnown || shares <= 0.0) return null
            return cost / shares
        }

    /**
     * Would replacing these lots with one blended [shares]/[avgCost] total throw away history?
     *
     * The Edit-holdings dialog can only express a single total — it has no way to say WHICH of
     * several lots the user meant to correct — so any real change collapses the list. That is
     * acceptable when the lots carry no dates anyway, and destructive when they do: the dates are
     * exactly what a tax-aware rebalance and a split adjustment read. So the caller asks this
     * first and warns, rather than quietly discarding a purchase history the user did not know
     * they had.
     */
    fun editWouldDiscardDatedLots(newShares: Double?, newAvgCost: Double?): Boolean {
        val unchanged = newShares == shares && newAvgCost == avgCost
        if (unchanged) return false
        return lots.count { it.acquiredDateIso != null } > 0 && lots.size > 0
    }

    /** How many dated lots an [editWouldDiscardDatedLots] edit would collapse. */
    fun datedLotCount(): Int = lots.count { it.acquiredDateIso != null }
}

/** US long-term capital-gains treatment starts at MORE THAN one year of holding — matches the
 *  backend's `sandbox_job._LONG_TERM_DAYS` exactly, so the two never disagree about the boundary. */
const val LONG_TERM_HOLDING_DAYS = 366

/** What selling some shares of a lotted holding would realise, tax-wise. Only ever built for a
 *  sale that touches a SHORT_TERM or MIXED lot, or one with an unknown date — see [Asset.saleTaxNote]. */
enum class LotTaxStatus { SHORT_TERM, MIXED, UNKNOWN }

data class SaleTaxNote(
    val status: LotTaxStatus,
    /** Days until the youngest still-short lot this sale touches turns long-term. Null for
     *  [LotTaxStatus.UNKNOWN], where no date-based countdown can be trusted. */
    val daysToLongTerm: Int? = null,
)

/**
 * What selling [sharesToSell] shares of this holding would realise, tax-wise (MONEY-1), consuming
 * lots FIFO — the IRS default and the same order the backend's own `ledger_cost` accounting uses.
 *
 * Returns null when there is nothing to warn about: no lots, nothing to sell, or every lot the sale
 * would touch is already safely long-term. Otherwise the whole thing is [LotTaxStatus.UNKNOWN] the
 * moment the sale would touch ANY lot with no recorded [Lot.acquiredDateIso] — a lot's null date is
 * unknown, never short- or long-term by default, so it must never be silently skipped in favor of the
 * dated lots the caller CAN see (which is exactly the confident-wrong-number [Asset.avgCost] already
 * refuses to produce for cost, one layer up). An unparseable date is treated the same way.
 */
fun Asset.saleTaxNote(sharesToSell: Double, today: java.time.LocalDate = java.time.LocalDate.now()): SaleTaxNote? {
    if (sharesToSell <= 0.0 || lots.isEmpty()) return null
    // FIFO: oldest known lot first. An undated lot is never assumed to be the oldest — it sorts
    // LAST, so a sale small enough to be filled entirely from known lots correctly says nothing is
    // unknown about it, rather than an arbitrary ordering choice hiding the undated lot from a small
    // sale that would never actually touch it.
    val ordered = lots.sortedWith(compareBy(nullsLast()) { it.acquiredDateIso })
    var remaining = sharesToSell
    var touchedUnknown = false
    val shortDaysRemaining = mutableListOf<Long>()
    var touchedLong = false
    for (lot in ordered) {
        if (remaining <= 1e-9) break
        val take = minOf(lot.shares, remaining)
        if (take <= 0.0) continue
        remaining -= take
        val dateIso = lot.acquiredDateIso
        val acquired = dateIso?.let { runCatching { java.time.LocalDate.parse(it.take(10)) }.getOrNull() }
        if (acquired == null) {
            touchedUnknown = true
            continue
        }
        val age = java.time.temporal.ChronoUnit.DAYS.between(acquired, today)
        if (age >= LONG_TERM_HOLDING_DAYS) touchedLong = true else shortDaysRemaining.add(age)
    }
    return when {
        touchedUnknown -> SaleTaxNote(LotTaxStatus.UNKNOWN)
        shortDaysRemaining.isEmpty() -> null   // every touched lot is cleanly long-term already
        else -> {
            val daysToLongTerm = (LONG_TERM_HOLDING_DAYS - shortDaysRemaining.min()).toInt()
            SaleTaxNote(if (touchedLong) LotTaxStatus.MIXED else LotTaxStatus.SHORT_TERM, daysToLongTerm)
        }
    }
}

/** UI copy for a [SaleTaxNote] — what the rebalance dialog shows under a sell move. */
fun SaleTaxNote.toDisplayText(): String = when (status) {
    LotTaxStatus.SHORT_TERM ->
        "Short-term gain" + (daysToLongTerm?.let { " — long-term in $it day${if (it == 1) "" else "s"}" } ?: "")
    LotTaxStatus.MIXED ->
        "Partly short-term" + (daysToLongTerm?.let { " — fully long-term in $it day${if (it == 1) "" else "s"}" } ?: "")
    LotTaxStatus.UNKNOWN -> "Acquisition date unknown for part of this position — tax impact unclear"
}

/**
 * Hand-written so an [Asset] persisted before MONEY-2 (a bare `shares`/`avgCost` pair, no `lots` key
 * at all) decodes as a single [Lot] with a null — unknown — acquisition date, instead of the position
 * quietly vanishing the moment `lots` was added with a default of `emptyList()`. New data is always
 * written with `lots` only; `shares`/`avgCost` are read-only legacy keys on the wire now that the
 * Kotlin properties of those names are derived getters rather than stored fields.
 */
object AssetSerializer : KSerializer<Asset> {

    @Serializable
    private data class Surrogate(
        val symbol: String,
        val type: AssetType,
        val displayName: String,
        val coinGeckoId: String? = null,
        val lots: List<Lot> = emptyList(),
        // Pre-MONEY-2 shape only. A decode with these but no `lots` synthesizes one lot (see below);
        // encode never sets them, so a round trip through this app always upgrades a legacy Asset.
        val shares: Double? = null,
        val avgCost: Double? = null,
        val alerts: AssetAlerts? = null,
        val groups: List<String> = emptyList(),
        val favorite: Boolean = false,
    )

    override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

    override fun deserialize(decoder: Decoder): Asset {
        val s = decoder.decodeSerializableValue(Surrogate.serializer())
        val lots = s.lots.ifEmpty {
            // A legacy "no position" Asset has shares == null or 0.0 — that migrates to no lots at
            // all, not a zero-share lot.
            listOfNotNull(
                s.shares?.takeIf { it != 0.0 }
                    ?.let { Lot(shares = it, costPerShare = s.avgCost, acquiredDateIso = null) },
            )
        }
        return Asset(
            symbol = s.symbol,
            type = s.type,
            displayName = s.displayName,
            coinGeckoId = s.coinGeckoId,
            lots = lots,
            alerts = s.alerts,
            groups = s.groups,
            favorite = s.favorite,
        )
    }

    override fun serialize(encoder: Encoder, value: Asset) {
        encoder.encodeSerializableValue(
            Surrogate.serializer(),
            Surrogate(
                symbol = value.symbol,
                type = value.type,
                displayName = value.displayName,
                coinGeckoId = value.coinGeckoId,
                lots = value.lots,
                alerts = value.alerts,
                groups = value.groups,
                favorite = value.favorite,
            ),
        )
    }
}

/**
 * A technical condition that can be armed overnight, alongside the price thresholds.
 *
 * The vocabulary is deliberately short. MACD signal crosses and Bollinger-lower-band touches were
 * considered and dropped: they are the highest-frequency and lowest-conviction triggers available,
 * and this project's own 20,768-episode study measured buying general weakness as negative. What
 * survives is the small set that marks a change of state rather than a wiggle.
 *
 * [minBars] is what the condition needs to be answerable at all. It is a count of DAILY bars, which
 * is why the evaluator chooses its own range rather than reusing whatever the chart last showed —
 * ChartRange.ALL is weekly, so an SMA(200) computed there is a 200-WEEK average wearing a 200-day
 * label.
 */
@Serializable
enum class AlertCondition(val key: String, val label: String, val minBars: Int) {
    CLOSE_ABOVE_SMA50("above_sma50", "Closes above its 50-day average", 50),
    CLOSE_BELOW_SMA50("below_sma50", "Closes below its 50-day average", 50),
    CLOSE_ABOVE_SMA200("above_sma200", "Closes above its 200-day average", 200),
    CLOSE_BELOW_SMA200("below_sma200", "Closes below its 200-day average", 200),
    CLOSE_AT_52W_HIGH("at_52w_high", "Closes at a 52-week high", 252),
}

/** Which of the four level alerts a switch can turn off without throwing the level away. */
@Serializable
enum class AlertKind { PRICE_ABOVE, PRICE_BELOW, PERCENT_UP, PERCENT_DOWN }

/**
 * Per-asset notification thresholds.
 *
 * A level and whether it is armed used to be the same field: null meant off, so turning a switch
 * off had to erase the number, and re-arming meant typing it again from memory. People do not set
 * an alert at $390 by accident — flipping it off for a week is a normal thing to do, and the app
 * charged them the level for it.
 *
 * [disarmed] separates the two. It defaults to empty, so every alert already stored — which by
 * definition has a value and was on — decodes as armed, and nothing has to be migrated.
 */
@Serializable
data class AssetAlerts(
    val priceAbove: Double? = null,   // notify when price >= this
    val priceBelow: Double? = null,   // notify when price <= this
    val percentUp: Double? = null,    // notify when day change % >= this
    val percentDown: Double? = null,  // notify when day change % <= -this
    /** Armed technical conditions. Defaulted so older backups and stored watchlists decode. */
    val conditions: Set<AlertCondition> = emptySet(),
    /** Levels that are kept but not firing. Defaulted, so older data decodes as fully armed. */
    val disarmed: Set<AlertKind> = emptySet(),
) {
    /** The level, or null when there is no level OR the user has switched this one off. */
    val armedPriceAbove: Double? get() = priceAbove.takeIf { AlertKind.PRICE_ABOVE !in disarmed }
    val armedPriceBelow: Double? get() = priceBelow.takeIf { AlertKind.PRICE_BELOW !in disarmed }
    val armedPercentUp: Double? get() = percentUp.takeIf { AlertKind.PERCENT_UP !in disarmed }
    val armedPercentDown: Double? get() = percentDown.takeIf { AlertKind.PERCENT_DOWN !in disarmed }
    /**
     * True when nothing is armed at all.
     *
     * `conditions` is part of this test, and that is load-bearing rather than tidy: AlertChecker
     * filters the watchlist on `!isEmpty` before evaluating anything, so an asset carrying only a
     * technical condition would have been skipped entirely — armed in the UI, never run, and no
     * error anywhere to say so.
     */
    val isEmpty: Boolean
        get() = armedPriceAbove == null && armedPriceBelow == null && armedPercentUp == null &&
            armedPercentDown == null && conditions.isEmpty()

    /** How many alerts are armed, for the badge. Same reasoning as [isEmpty]. */
    val activeCount: Int
        get() = listOfNotNull(
            armedPriceAbove, armedPriceBelow, armedPercentUp, armedPercentDown,
        ).size + conditions.size
}

/** A point-in-time price snapshot. */
@Serializable
data class Quote(
    val symbol: String,
    val price: Double,
    val change: Double,          // absolute change over the day
    val changePercent: Double,   // percent change over the day
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val prevClose: Double? = null,
    val volume: Double? = null,   // stocks: shares traded today; crypto: 24h USD volume
    val currency: String = "USD",
    val asOfEpochMs: Long = 0L,
    /** Yahoo classifies the symbol as an ETF (meta.instrumentType == "ETF") — drives the row accent. */
    val isEtf: Boolean = false,
    /** Last post-market (after-hours) price; null unless the symbol is in/after the post session. */
    val postMarketPrice: Double? = null,
    /** After-hours % move vs the regular-session close; null outside post-market. */
    val postMarketChangePercent: Double? = null,
    /** Yahoo's session tag ("REGULAR" | "POST" | "POSTPOST" | "CLOSED" | "PRE" | "PREPRE"); null if absent. */
    val marketState: String? = null,
) {
    val isUp: Boolean get() = change >= 0.0
}

/** CBOE Volatility Index snapshot (^VIX). Higher = more expected volatility ("fear"). Serializable
 *  so DATA-9 can persist the last reading across a process restart (see MarketContextCache). */
@Serializable
data class VixQuote(
    val value: Double,
    val change: Double,
    val changePercent: Double,
) {
    val zone: VixZone get() = VixZone.forValue(value)
    /** VIX up = more fear (bad); down = calmer (good). Sentiment is inverted vs a normal ticker. */
    val calmer: Boolean get() = change <= 0.0
}

/** Risk bands for the VIX fear gauge. [ceiling] is the band's exclusive upper bound. */
enum class VixZone(val label: String, val ceiling: Double) {
    CALM("Calm", 15.0),
    NORMAL("Normal", 20.0),
    ELEVATED("Elevated", 30.0),
    HIGH("High", 40.0),
    EXTREME("Extreme", Double.MAX_VALUE);

    companion object {
        fun forValue(v: Double): VixZone = entries.first { v < it.ceiling }
    }
}

/** A single (time, price) sample for charts / sparklines. [extended] = pre/post-market. */
@Serializable
data class PricePoint(
    val epochMs: Long,
    val price: Double,
    val extended: Boolean = false,
    val volume: Double? = null,
    /**
     * The bar's true extremes, when the source reports them. Null means closes only (CoinGecko).
     *
     * [price] is the bar's CLOSE, and a close series has no memory of what happened inside the bar.
     * That is invisible on the line itself but wrong for a high/low marker, because each chart range
     * asks Yahoo for a different bar size — 1D in 1-minute bars, 1W in 5-minute, 1M in 30-minute. The
     * same trading day therefore yields a different "low" per range, and the wider view can report a
     * HIGHER low than the narrower one it contains: measured on GME 2026-08-11, 1D showed $18.59 and
     * 1W showed $18.70 for a window that includes it. Bar extremes nest the way closes do not — a
     * 5-minute bar's low IS the lowest of its five 1-minute lows.
     */
    val high: Double? = null,
    val low: Double? = null,
    /**
     * The bar's OPEN. Yahoo has always returned it and this app has always discarded it.
     *
     * Null carries the same meaning as [high]/[low]: the source did not report one. It is NOT
     * defaulted to [price] — a bar whose open equals its close is a doji, a specific and confident
     * reading about a session that fought to a standstill, and inventing one is exactly the class of
     * claim this file is careful not to make.
     */
    val open: Double? = null,
)

/** Chart time ranges shown on the detail screen. */
enum class ChartRange(val label: String) {
    DAY("1D"), WEEK("1W"), MONTH("1M"), QUARTER("3M"), YEAR("1Y"), THREE_YEAR("3Y"), ALL("ALL")
}

/** A symbol-search hit from Finnhub (stocks) or CoinGecko (crypto). */
data class SearchResult(
    val symbol: String,
    val name: String,
    val type: AssetType,
    val coinGeckoId: String? = null,
) {
    fun toAsset() = Asset(symbol = symbol, type = type, displayName = name, coinGeckoId = coinGeckoId)
}
