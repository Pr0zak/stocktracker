package com.stocktracker.app.data.model

import com.stocktracker.app.data.remote.SplitEvent
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MONEY-4: nothing on the holdings path adjusted for a stock split — the morning after a 10-for-1,
 * the Detail card showed roughly -90% and the rebalance payload carried a share count ten times too
 * small, while the user's real position was unchanged. [detectSplitAdjustments] finds which LOTS a
 * split applies to (only ones bought before its ex-date); [applySplitAdjustments] is the arithmetic,
 * applied only once the caller has the user's explicit confirmation — nothing here writes anywhere on
 * its own.
 */
class SplitAdjustmentTest {

    private fun date(y: Int, m: Int, d: Int) = LocalDate.of(y, m, d)
    private fun ms(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private fun isoOf(d: LocalDate) = d.toString()

    private fun lot(shares: Double, cost: Double?, date: LocalDate?) =
        Lot(shares = shares, costPerShare = cost, acquiredDateIso = date?.let(::isoOf))

    // ------------------------------------------------------------------------------ detection

    @Test fun `a lot bought before the split is affected`() {
        val bought = date(2023, 1, 1)
        val splitDate = date(2023, 6, 1)
        val lots = listOf(lot(10.0, 100.0, bought))
        val splits = listOf(SplitEvent(ms(splitDate), ratio = 4.0, label = "4:1"))
        val found = detectSplitAdjustments(lots, splits)
        assertEquals(1, found.size)
        assertEquals(0, found[0].lotIndex)
        assertEquals(4.0, found[0].ratio, 0.0001)
        assertEquals("4:1", found[0].label)
    }

    @Test fun `a lot bought AFTER the split is not affected -- the split boundary is respected`() {
        // The whole point of per-lot detection: a lot bought post-split already reflects the new
        // share count, and "adjusting" it again would double-count the split.
        val splitDate = date(2023, 6, 1)
        val boughtAfter = date(2023, 7, 1)
        val lots = listOf(lot(40.0, 25.0, boughtAfter))
        val splits = listOf(SplitEvent(ms(splitDate), ratio = 4.0, label = "4:1"))
        assertTrue(detectSplitAdjustments(lots, splits).isEmpty())
    }

    @Test fun `a multi-lot position is adjusted only on the lot side of the split boundary`() {
        // This is the case MONEY-4 exists for: one lot straddles the split date and the other
        // doesn't. Getting this wrong either double-adjusts the newer lot or misses the older one.
        val splitDate = date(2023, 6, 1)
        val before = lot(shares = 10.0, cost = 100.0, date = date(2023, 1, 1))   // pre-split -- affected
        val after = lot(shares = 40.0, cost = 26.0, date = date(2023, 7, 1))     // post-split -- untouched
        val lots = listOf(before, after)
        val splits = listOf(SplitEvent(ms(splitDate), ratio = 4.0, label = "4:1"))

        val found = detectSplitAdjustments(lots, splits)
        assertEquals(listOf(0), found.map { it.lotIndex })

        val adjusted = applySplitAdjustments(lots, found)
        assertEquals(40.0, adjusted[0].shares, 0.0001)     // 10 -> 40
        assertEquals(25.0, adjusted[0].costPerShare!!, 0.0001)  // 100 -> 25
        assertEquals(after, adjusted[1])                    // completely untouched
    }

    @Test fun `two splits since one lot's purchase compound into a single adjustment`() {
        val bought = date(2020, 1, 1)
        val split1 = date(2021, 1, 1)   // 2:1
        val split2 = date(2022, 1, 1)   // 3:1 -- combined 6:1
        val lots = listOf(lot(5.0, 300.0, bought))
        val splits = listOf(
            SplitEvent(ms(split1), ratio = 2.0, label = "2:1"),
            SplitEvent(ms(split2), ratio = 3.0, label = "3:1"),
        )
        val found = detectSplitAdjustments(lots, splits)
        assertEquals(1, found.size)
        assertEquals(6.0, found[0].ratio, 0.0001)
        assertTrue(found[0].label.contains("2:1"))
        assertTrue(found[0].label.contains("3:1"))

        val adjusted = applySplitAdjustments(lots, found)
        assertEquals(30.0, adjusted[0].shares, 0.0001)      // 5 * 6
        assertEquals(50.0, adjusted[0].costPerShare!!, 0.0001) // 300 / 6
    }

    @Test fun `an unknown acquisition date is never assumed pre- or post-split`() {
        // Never guess from silence: a migrated lot with no recorded date must not be adjusted, even
        // though a real split clearly happened at some point in the symbol's history.
        val splitDate = date(2023, 6, 1)
        val lots = listOf(lot(10.0, 100.0, null))
        val splits = listOf(SplitEvent(ms(splitDate), ratio = 4.0, label = "4:1"))
        assertTrue(detectSplitAdjustments(lots, splits).isEmpty())
    }

    @Test fun `an unparseable date is treated exactly like a missing one`() {
        val lots = listOf(Lot(shares = 10.0, costPerShare = 100.0, acquiredDateIso = "not-a-date"))
        val splits = listOf(SplitEvent(ms(date(2023, 6, 1)), ratio = 4.0, label = "4:1"))
        assertTrue(detectSplitAdjustments(lots, splits).isEmpty())
    }

    @Test fun `no splits means nothing detected`() {
        val lots = listOf(lot(10.0, 100.0, date(2020, 1, 1)))
        assertTrue(detectSplitAdjustments(lots, emptyList()).isEmpty())
    }

    @Test fun `a reverse split divides shares and multiplies cost`() {
        val bought = date(2023, 1, 1)
        val lots = listOf(lot(100.0, 10.0, bought))
        // 1-for-10 reverse split: ratio is 0.1 (new shares per old share).
        val splits = listOf(SplitEvent(ms(date(2023, 6, 1)), ratio = 0.1, label = "1:10"))
        val adjusted = applySplitAdjustments(lots, detectSplitAdjustments(lots, splits))
        assertEquals(10.0, adjusted[0].shares, 0.0001)
        assertEquals(100.0, adjusted[0].costPerShare!!, 0.0001)
    }

    // ------------------------------------------------------------------------------ arithmetic

    @Test fun `applying a split preserves total cost basis (shares times cost)`() {
        val lots = listOf(lot(10.0, 100.0, date(2023, 1, 1)))
        val basisBefore = lots[0].shares * lots[0].costPerShare!!
        val adjusted = applySplitAdjustments(
            lots, listOf(LotSplitAdjustment(lotIndex = 0, ratio = 4.0, label = "4:1")))
        val basisAfter = adjusted[0].shares * adjusted[0].costPerShare!!
        assertEquals(basisBefore, basisAfter, 0.0001)
    }

    @Test fun `a lot with no cost basis stays costless after adjustment, not zero`() {
        val lots = listOf(lot(10.0, null, date(2023, 1, 1)))
        val adjusted = applySplitAdjustments(
            lots, listOf(LotSplitAdjustment(lotIndex = 0, ratio = 4.0, label = "4:1")))
        assertEquals(40.0, adjusted[0].shares, 0.0001)
        org.junit.Assert.assertNull("an unknown cost must stay unknown, not become 0.0", adjusted[0].costPerShare)
    }

    @Test fun `applying a split never touches the acquisition date`() {
        val bought = date(2023, 1, 1)
        val lots = listOf(lot(10.0, 100.0, bought))
        val adjusted = applySplitAdjustments(
            lots, listOf(LotSplitAdjustment(lotIndex = 0, ratio = 4.0, label = "4:1")))
        assertEquals(isoOf(bought), adjusted[0].acquiredDateIso)
    }

    @Test fun `applying no adjustments returns the lots unchanged`() {
        val lots = listOf(lot(10.0, 100.0, date(2023, 1, 1)))
        assertEquals(lots, applySplitAdjustments(lots, emptyList()))
    }

    @Test fun `earliestKnownLotEpochMs ignores undated lots and picks the true earliest`() {
        val lots = listOf(
            lot(1.0, 1.0, null),
            lot(1.0, 1.0, date(2022, 6, 1)),
            lot(1.0, 1.0, date(2020, 1, 1)),
        )
        assertEquals(ms(date(2020, 1, 1)), earliestKnownLotEpochMs(lots))
    }

    @Test fun `earliestKnownLotEpochMs is null when every lot's date is unknown`() {
        val lots = listOf(lot(1.0, 1.0, null), lot(2.0, 2.0, null))
        assertEquals(null, earliestKnownLotEpochMs(lots))
    }
}
