package com.stocktracker.app.data.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MONEY-1: what selling some shares of a lotted holding would realise, tax-wise.
 *
 * [Asset.saleTaxNote] is the client-side counterpart to the backend's `sandbox_job.
 * annotate_holding_period` — computed from this app's own full [Lot] list (shares AND dates), which
 * is strictly more precise than anything sent over the wire, so the rebalance dialog's per-move
 * callout never depends on a round trip. The invariant pinned throughout: a lot with no recorded date
 * is UNKNOWN and must never be silently folded into either bucket.
 */
class SaleTaxNoteTest {

    private val today = LocalDate.of(2026, 9, 19)

    private fun stock(vararg lots: Lot) = Asset(
        symbol = "AAPL", type = AssetType.STOCK, displayName = "Apple Inc.", lots = lots.toList(),
    )

    private fun daysAgo(days: Long) = today.minusDays(days).toString()

    // -------------------------------------------------------------------------- single-lot cases

    @Test fun `a young single lot is short-term with a countdown`() {
        val asset = stock(Lot(shares = 10.0, costPerShare = 100.0, acquiredDateIso = daysAgo(4)))
        val note = asset.saleTaxNote(10.0, today)!!
        assertEquals(LotTaxStatus.SHORT_TERM, note.status)
        assertEquals(LONG_TERM_HOLDING_DAYS - 4, note.daysToLongTerm)
    }

    @Test fun `a seasoned single lot needs no warning at all`() {
        val asset = stock(Lot(shares = 10.0, costPerShare = 100.0, acquiredDateIso = daysAgo(400)))
        assertNull("a cleanly long-term sale has nothing to warn about", asset.saleTaxNote(10.0, today))
    }

    @Test fun `the boundary is more than one year, matching the backend exactly`() {
        fun status(days: Long) = stock(Lot(shares = 1.0, costPerShare = 1.0, acquiredDateIso = daysAgo(days)))
            .saleTaxNote(1.0, today)?.status
        assertEquals(LotTaxStatus.SHORT_TERM, status(365))   // "more than one year", not "one year"
        assertNull(status(366))                              // 366 days -> long-term -> nothing to say
    }

    // --------------------------------------------------------------------------- multi-lot / FIFO

    @Test fun `selling across both an old and a young lot is mixed`() {
        val asset = stock(
            Lot(shares = 5.0, costPerShare = 100.0, acquiredDateIso = daysAgo(400)),  // long-term
            Lot(shares = 5.0, costPerShare = 150.0, acquiredDateIso = daysAgo(10)),   // short-term
        )
        // FIFO consumes the old lot first (5) then 3 of the young lot -> touches BOTH.
        val note = asset.saleTaxNote(8.0, today)!!
        assertEquals(LotTaxStatus.MIXED, note.status)
        assertEquals(LONG_TERM_HOLDING_DAYS - 10, note.daysToLongTerm)
    }

    @Test fun `selling only within the old lot never touches the young one and needs no warning`() {
        val asset = stock(
            Lot(shares = 5.0, costPerShare = 100.0, acquiredDateIso = daysAgo(400)),
            Lot(shares = 5.0, costPerShare = 150.0, acquiredDateIso = daysAgo(10)),
        )
        assertNull(asset.saleTaxNote(4.0, today))
    }

    @Test fun `selling across two short-term lots is short-term, not mixed`() {
        // A long-term lot is, BY DEFINITION, always older than a short-term one, so FIFO always
        // drains it first -- "mixed" only happens when the sale reaches past it. Two short-term lots
        // of different ages is the case that proves multi-lot classification isn't ALWAYS "mixed".
        val asset = stock(
            Lot(shares = 5.0, costPerShare = 100.0, acquiredDateIso = daysAgo(200)),
            Lot(shares = 5.0, costPerShare = 150.0, acquiredDateIso = daysAgo(10)),
        )
        val note = asset.saleTaxNote(8.0, today)!!
        assertEquals(LotTaxStatus.SHORT_TERM, note.status)
        assertEquals(LONG_TERM_HOLDING_DAYS - 10, note.daysToLongTerm)
    }

    // ------------------------------------------------------------------- unknown dates: never guess

    @Test fun `a sale that touches an undated lot is unknown, never short or long`() {
        val asset = stock(
            Lot(shares = 5.0, costPerShare = 100.0, acquiredDateIso = null),          // migrated, no date
            Lot(shares = 5.0, costPerShare = 150.0, acquiredDateIso = daysAgo(10)),
        )
        // Sorting an undated lot LAST means this 3-share sale would come entirely from the DATED
        // young lot if dates were ignored -- but the undated one exists in the position, so a sale
        // large enough to need it must say UNKNOWN, not quietly skip past it.
        val note = asset.saleTaxNote(8.0, today)!!
        assertEquals(LotTaxStatus.UNKNOWN, note.status)
        assertNull("no countdown can be trusted once the sale is unknown", note.daysToLongTerm)
    }

    @Test fun `an undated lot sorts last, so a small sale of only the dated shares is not unknown`() {
        val asset = stock(
            Lot(shares = 5.0, costPerShare = 100.0, acquiredDateIso = null),
            Lot(shares = 5.0, costPerShare = 150.0, acquiredDateIso = daysAgo(10)),
        )
        val note = asset.saleTaxNote(3.0, today)!!
        assertEquals("the sale never reached the undated lot", LotTaxStatus.SHORT_TERM, note.status)
    }

    @Test fun `an unparseable date is treated exactly like a missing one`() {
        val asset = stock(Lot(shares = 5.0, costPerShare = 100.0, acquiredDateIso = "not-a-date"))
        assertEquals(LotTaxStatus.UNKNOWN, asset.saleTaxNote(5.0, today)!!.status)
    }

    // ---------------------------------------------------------------------------------- edge cases

    @Test fun `no lots means nothing to warn about`() {
        val asset = stock()
        assertNull(asset.saleTaxNote(5.0, today))
    }

    @Test fun `selling zero or negative shares is never a warning`() {
        val asset = stock(Lot(shares = 5.0, costPerShare = 100.0, acquiredDateIso = daysAgo(4)))
        assertNull(asset.saleTaxNote(0.0, today))
        assertNull(asset.saleTaxNote(-1.0, today))
    }

    // -------------------------------------------------------------------------------- display text

    @Test fun `display text pluralizes days correctly`() {
        val one = SaleTaxNote(LotTaxStatus.SHORT_TERM, daysToLongTerm = 1).toDisplayText()
        val many = SaleTaxNote(LotTaxStatus.SHORT_TERM, daysToLongTerm = 5).toDisplayText()
        assertTrue(one.contains("1 day") && !one.contains("1 days"))
        assertTrue(many.contains("5 days"))
    }

    @Test fun `unknown display text names the actual gap rather than a number`() {
        val text = SaleTaxNote(LotTaxStatus.UNKNOWN).toDisplayText()
        assertTrue(text.contains("unknown", ignoreCase = true))
    }
}
