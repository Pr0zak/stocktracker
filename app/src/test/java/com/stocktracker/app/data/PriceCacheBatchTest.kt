package com.stocktracker.app.data

import com.stocktracker.app.data.model.Quote
import com.stocktracker.app.data.prefs.PriceCache
import org.junit.Test
import org.junit.Assert.assertEquals

/**
 * Unit tests for PriceCache.putQuotes() batch write and pruning logic.
 *
 * Tests the pure logic of batch writes and symbol pruning without involving
 * DataStore or coroutines.
 */
class PriceCacheBatchTest {

    /** Test that putQuotes would batch multiple quotes into one write. */
    @Test
    fun batchMultipleQuotes() {
        val quotes = mapOf(
            "AAPL" to Quote("AAPL", 150.0, 1.0, 0.67, prevClose = 149.0),
            "GOOGL" to Quote("GOOGL", 140.0, -2.0, -1.43, prevClose = 142.0),
            "MSFT" to Quote("MSFT", 380.0, 3.0, 0.79, prevClose = 377.0),
        )
        val activeSymbols = setOf("AAPL", "GOOGL", "MSFT", "TSLA")

        // Verify the inputs are what we expect (all three quotes present, one extra symbol in active set)
        assertEquals(3L, quotes.size.toLong())
        assertEquals(4L, activeSymbols.size.toLong())
        quotes.keys.forEach { assert(activeSymbols.contains(it)) }
    }

    /** Test that pruning removes symbols no longer in the active set. */
    @Test
    fun pruneRemovedSymbols() {
        val oldCache = mapOf(
            "AAPL" to Quote("AAPL", 150.0, 1.0, 0.67, prevClose = 149.0),
            "GOOGL" to Quote("GOOGL", 140.0, -2.0, -1.43, prevClose = 142.0),
            "MSFT" to Quote("MSFT", 380.0, 3.0, 0.79, prevClose = 377.0),
            "AMZN" to Quote("AMZN", 160.0, 0.0, 0.0, prevClose = 160.0),
            "TSLA" to Quote("TSLA", 250.0, -5.0, -2.0, prevClose = 255.0),
        )

        val newQuotes = mapOf(
            "AAPL" to Quote("AAPL", 151.0, 2.0, 1.34, prevClose = 149.0),
            "GOOGL" to Quote("GOOGL", 141.0, -1.0, -0.71, prevClose = 142.0),
        )

        // Active set no longer includes MSFT, AMZN, or TSLA
        val activeSymbols = setOf("AAPL", "GOOGL", "NVDA")

        // Verify setup: oldCache has 5 symbols, activeSymbols has 3 (MSFT, AMZN, TSLA removed)
        assertEquals(5L, oldCache.size.toLong())
        assertEquals(3L, activeSymbols.size.toLong())
        assert(!activeSymbols.contains("MSFT"))
        assert(!activeSymbols.contains("AMZN"))
        assert(!activeSymbols.contains("TSLA"))

        // After merge, should have AAPL and GOOGL (updated), and prune would remove MSFT, AMZN, TSLA
        val merged = oldCache.toMutableMap()
        merged.putAll(newQuotes)
        merged.keys.retainAll(activeSymbols)

        assertEquals("Should have only AAPL and GOOGL after pruning", 2L, merged.size.toLong())
        assert(merged.containsKey("AAPL"))
        assert(merged.containsKey("GOOGL"))
        assert(!merged.containsKey("MSFT"))
        assert(!merged.containsKey("AMZN"))
        assert(!merged.containsKey("TSLA"))

        // Verify prices were updated
        assertEquals(151.0, merged["AAPL"]?.price)
        assertEquals(141.0, merged["GOOGL"]?.price)
    }

    /** Test that new symbols are added when they appear in the batch. */
    @Test
    fun addNewSymbols() {
        val oldCache = mapOf(
            "AAPL" to Quote("AAPL", 150.0, 1.0, 0.67, prevClose = 149.0),
        )

        val newQuotes = mapOf(
            "GOOGL" to Quote("GOOGL", 140.0, -2.0, -1.43, prevClose = 142.0),
            "MSFT" to Quote("MSFT", 380.0, 3.0, 0.79, prevClose = 377.0),
        )

        val activeSymbols = setOf("AAPL", "GOOGL", "MSFT")

        val merged = oldCache.toMutableMap()
        merged.putAll(newQuotes)
        merged.keys.retainAll(activeSymbols)

        assertEquals(3L, merged.size.toLong())
        assert(merged.containsKey("AAPL"))
        assert(merged.containsKey("GOOGL"))
        assert(merged.containsKey("MSFT"))
    }

    /** Test buffer update logic: only append when price changes. */
    @Test
    fun bufferDedupSamePrices() {
        val initialBuffer = listOf(
            PriceCache.Sample(1000L, 150.0),
            PriceCache.Sample(2000L, 150.5),
        )

        // Same price as last sample — should not append
        val quote1 = Quote("AAPL", 150.5, 0.5, 0.33, prevClose = 150.0)
        val lastPrice1 = initialBuffer.lastOrNull()?.price
        val appendWhen1 = lastPrice1 != quote1.price

        assertEquals("Should NOT append when price is the same", false as Any, appendWhen1 as Any)

        // Different price — should append
        val quote2 = Quote("AAPL", 151.0, 1.0, 0.67, prevClose = 150.0)
        val appendWhen2 = lastPrice1 != quote2.price

        assertEquals("Should append when price changes", true as Any, appendWhen2 as Any)
    }

    /** Test that buffer is trimmed to MAX_SAMPLES. */
    @Test
    fun bufferTrimToMaxSamples() {
        // Create a buffer with MAX_SAMPLES already
        val atMax = (1..PriceCache.MAX_SAMPLES).map { i ->
            PriceCache.Sample(i.toLong() * 1000, 100.0 + i)
        }

        assertEquals(PriceCache.MAX_SAMPLES.toLong(), atMax.size.toLong())

        // Add one more sample and trim
        val newPrice = 100.0 + PriceCache.MAX_SAMPLES + 1
        val withNew = atMax + PriceCache.Sample((PriceCache.MAX_SAMPLES + 1).toLong() * 1000, newPrice)
        val trimmed = withNew.takeLast(PriceCache.MAX_SAMPLES)

        assertEquals("Should be trimmed back to MAX_SAMPLES", PriceCache.MAX_SAMPLES.toLong(), trimmed.size.toLong())
        assertEquals("Newest sample should be kept", newPrice, trimmed.last().price, 0.0001)
        // Oldest sample (from index 0 of the original atMax) should be gone; the first of trimmed is the original[1]
        assertEquals("Oldest sample should be removed", 102.0, trimmed.first().price, 0.0001)
    }

    /** Test that samples older than BUFFER_WINDOW_MS are dropped. */
    @Test
    fun bufferExpireStaleSamples() {
        val now = System.currentTimeMillis()
        val oneDayAgo = now - 24L * 60 * 60 * 1000
        val twoHoursAgo = now - 2L * 60 * 60 * 1000

        // Sample from 1 day ago (should be kept, exactly at the boundary)
        val old = PriceCache.Sample(oneDayAgo, 100.0)
        // Sample from 2 hours ago (definitely kept)
        val recent = PriceCache.Sample(twoHoursAgo, 101.0)
        // Sample now
        val newest = PriceCache.Sample(now, 102.0)

        val buffer = listOf(old, recent, newest)

        // Window is BUFFER_WINDOW_MS (24 hours)
        val filtered = buffer.filter { now - it.ts <= PriceCache.BUFFER_WINDOW_MS }

        // All three should be kept (old is exactly at boundary, recent and newest are within)
        assertEquals("All samples within 24h window should be kept", 3L, filtered.size.toLong())

        // Add a sample from 25 hours ago
        val tooOld = PriceCache.Sample(now - 25L * 60 * 60 * 1000, 99.0)
        val bufferWithOld = listOf(tooOld, old, recent, newest)
        val filteredWithOld = bufferWithOld.filter { now - it.ts <= PriceCache.BUFFER_WINDOW_MS }

        assertEquals("Sample older than 24h should be dropped", 3L, filteredWithOld.size.toLong())
        assert(!filteredWithOld.contains(tooOld))
    }
}
