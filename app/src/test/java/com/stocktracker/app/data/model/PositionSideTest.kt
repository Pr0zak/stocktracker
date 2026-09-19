package com.stocktracker.app.data.model

import com.stocktracker.app.data.remote.Http
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MONEY-3: a [CallPosition] now has a [PositionSide] (LONG/SHORT), covered-call eligibility must
 * exclude shares already promised away by an OPEN short call, and assignment must turn a SHORT
 * position into a real lot on the watchlist holding in both directions (put: acquire; call: dispose).
 *
 * This pins three things: every [CallPosition] stored before MONEY-3 migrates to LONG (not an
 * ambiguous default — see [PositionSide]'s doc), [sharesCommittedToShortCalls] is the exact rule the
 * eligibility gate in DetailScreen reads, and [CallPosition.asAssigned] produces the right outcome and
 * shares for both a short put and a short call.
 */
class PositionSideTest {

    private fun position(
        symbol: String = "aapl",
        side: PositionSide = PositionSide.LONG,
        type: String = "call",
        contracts: Int = 1,
        strike: Double = 100.0,
        fillPrice: Double = 2.50,
    ) = CallPosition(
        symbol = symbol,
        type = type,
        strike = strike,
        expiryIso = "2026-10-16",
        expiryTs = 1_792_281_600L,
        contracts = contracts,
        fillPrice = fillPrice,
        openDateIso = "2026-09-01",
        side = side,
    )

    // -------------------------------------------------------------- migration: every stored position was LONG

    @Test fun `a CallPosition persisted before MONEY-3, with no side key at all, decodes as LONG`() {
        val legacy = """
            {"symbol":"NVDA","type":"call","strike":120.0,"expiryIso":"2026-10-16","expiryTs":1792281600,
             "contracts":1,"fillPrice":4.25,"openDateIso":"2026-09-01"}
        """.trimIndent()
        val position = Http.json.decodeFromString<CallPosition>(legacy)
        assertEquals(
            "every CallPosition on disk before MONEY-3 was a bought call and must migrate to LONG",
            PositionSide.LONG,
            position.side,
        )
    }

    @Test fun `a list of legacy positions with no side key all migrate to LONG, not dropped or defaulted oddly`() {
        val legacy = """
            [{"symbol":"AAPL","strike":200.0,"expiryIso":"2026-11-20","expiryTs":1795564800,
              "contracts":2,"fillPrice":3.10,"openDateIso":"2026-08-15"},
             {"symbol":"MSFT","strike":410.0,"expiryIso":"2026-12-18","expiryTs":1798070400,
              "contracts":1,"fillPrice":6.00,"openDateIso":"2026-09-01"}]
        """.trimIndent()
        val positions = Http.json.decodeFromString<List<CallPosition>>(legacy)
        assertEquals(2, positions.size)
        assertTrue(positions.all { it.side == PositionSide.LONG })
    }

    @Test fun `a SHORT position round-trips through encode-decode without collapsing to LONG`() {
        val short = position(side = PositionSide.SHORT, type = "put")
        val restored = Http.json.decodeFromString<CallPosition>(Http.json.encodeToString(short))
        assertEquals(PositionSide.SHORT, restored.side)
        assertEquals(short, restored)
    }

    // -------------------------------------------------------------- covered-call eligibility exclusion

    @Test fun `an open short call commits 100 shares per contract, excluded from eligibility`() {
        val open = listOf(position(symbol = "AAPL", side = PositionSide.SHORT, type = "call", contracts = 2))
        assertEquals(200, open.sharesCommittedToShortCalls("AAPL"))
    }

    @Test fun `a LONG call commits nothing -- only a SHORT call promises shares away`() {
        val open = listOf(position(symbol = "AAPL", side = PositionSide.LONG, type = "call", contracts = 3))
        assertEquals(0, open.sharesCommittedToShortCalls("AAPL"))
    }

    @Test fun `a SHORT put commits nothing -- it's the buy side of the wheel, not a promise to sell shares`() {
        val open = listOf(position(symbol = "AAPL", side = PositionSide.SHORT, type = "put", contracts = 5))
        assertEquals(0, open.sharesCommittedToShortCalls("AAPL"))
    }

    @Test fun `only the matching symbol's short calls count, case-insensitively`() {
        val open = listOf(
            position(symbol = "aapl", side = PositionSide.SHORT, type = "call", contracts = 1),
            position(symbol = "MSFT", side = PositionSide.SHORT, type = "call", contracts = 4),
        )
        assertEquals(100, open.sharesCommittedToShortCalls("AAPL"))
        assertEquals(400, open.sharesCommittedToShortCalls("msft"))
        assertEquals(0, open.sharesCommittedToShortCalls("TSLA"))
    }

    @Test fun `selling a second covered call after the first is already open is exactly what this prevents`() {
        // The MONEY-3 incident: 100 shares held, one covered call already sold against them. A second
        // "Sell covered calls" suggestion must not be offered, because heldShares - committed = 0.
        val heldShares = 100
        val open = listOf(position(symbol = "AAPL", side = PositionSide.SHORT, type = "call", contracts = 1))
        val eligible = heldShares - open.sharesCommittedToShortCalls("AAPL")
        assertEquals(0, eligible)
        assertTrue("0 free shares must not clear the >= 100 eligibility bar", eligible < 100)
    }

    @Test fun `an empty book commits nothing`() {
        assertEquals(0, emptyList<CallPosition>().sharesCommittedToShortCalls("AAPL"))
    }

    // -------------------------------------------------------------- assignment arithmetic, both directions

    @Test fun `a short put assigned buys shares at strike minus the premium collected`() {
        val p = position(side = PositionSide.SHORT, type = "put", contracts = 2, strike = 50.0, fillPrice = 1.25)
        val closed = p.asAssigned("2026-09-19")

        assertEquals(CallOutcome.ASSIGNED, closed.outcome)
        assertEquals(200, closed.exercisedShares) // 100 * contracts
        assertNull("no separate option-leg P/L -- the value rolls into the shares", closed.realizedPnl)
        assertNull(closed.realizedPnlPct)
        assertTrue(closed.notes!!.contains("48.75")) // strike 50.00 - premium 1.25 = 48.75/share
        assertTrue(closed.notes!!.contains("Assigned"))
    }

    @Test fun `a short call assigned sells shares away at the strike, not the premium`() {
        val p = position(side = PositionSide.SHORT, type = "call", contracts = 1, strike = 60.0, fillPrice = 2.00)
        val closed = p.asAssigned("2026-09-19")

        assertEquals(CallOutcome.ASSIGNED, closed.outcome)
        assertEquals(100, closed.exercisedShares)
        assertNull(closed.realizedPnl)
        assertNull(closed.realizedPnlPct)
        assertTrue(closed.notes!!.contains("called away"))
        assertTrue(closed.notes!!.contains("60")) // the strike, not the premium
    }

    @Test fun `the lot markAssigned would append matches the arithmetic in the assignment note, in both directions`() {
        // Pins the exact Lot shapes CallsViewModel.markAssigned constructs (mirrored here since that
        // function is coroutine/DataStore glue with no branching logic of its own to unit-test
        // directly -- same reasoning BackupImportTest gives for BackupManager.commitImport).
        val shortPut = position(side = PositionSide.SHORT, type = "put", contracts = 1, strike = 50.0, fillPrice = 1.25)
        val putLot = Lot(shares = 100.0 * shortPut.contracts, costPerShare = shortPut.strike - shortPut.fillPrice)
        assertEquals(100.0, putLot.shares, 0.0001)
        assertEquals(48.75, putLot.costPerShare!!, 0.0001)

        val shortCall = position(side = PositionSide.SHORT, type = "call", contracts = 1, strike = 60.0, fillPrice = 2.00)
        val callLot = Lot(shares = -100.0 * shortCall.contracts, costPerShare = shortCall.strike)
        assertEquals(-100.0, callLot.shares, 0.0001)
        assertEquals(60.0, callLot.costPerShare!!, 0.0001)

        // Folded onto an existing 100-share, $50-avg-cost holding: the put assignment grows the
        // position to 200 sh blended at ((100*50 + 100*48.75) / 200); the call assignment instead
        // shrinks it to 0 sh, avgCost unknown (no position).
        val holding = Asset(symbol = "AAPL", type = AssetType.STOCK, displayName = "Apple Inc.",
            lots = listOf(Lot(shares = 100.0, costPerShare = 50.0)))

        val afterPutAssignment = holding.copy(lots = holding.lots + putLot)
        assertEquals(200.0, afterPutAssignment.shares!!, 0.0001)
        assertEquals((100 * 50.0 + 100 * 48.75) / 200.0, afterPutAssignment.avgCost!!, 0.0001)

        val afterCallAssignment = holding.copy(lots = holding.lots + callLot)
        assertEquals(0.0, afterCallAssignment.shares!!, 0.0001)
        assertNull(afterCallAssignment.avgCost)
    }

    // -------------------------------------------------------------- breakeven is type-, not side-aware

    @Test fun `breakeven is the same price on either side of a call -- only the option type flips the sign`() {
        val longCall = position(side = PositionSide.LONG, type = "call", strike = 100.0, fillPrice = 5.0)
        val shortCall = position(side = PositionSide.SHORT, type = "call", strike = 100.0, fillPrice = 5.0)
        assertEquals(105.0, longCall.breakeven, 0.0001)
        assertEquals(105.0, shortCall.breakeven, 0.0001)
    }

    @Test fun `a put's breakeven sits below the strike, regardless of side`() {
        val shortPut = position(side = PositionSide.SHORT, type = "put", strike = 100.0, fillPrice = 5.0)
        assertEquals(95.0, shortPut.breakeven, 0.0001)
    }
}
