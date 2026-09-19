package com.stocktracker.app.data

import com.stocktracker.app.data.remote.Health
import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.data.remote.PortfolioReviewResponse
import com.stocktracker.app.data.remote.SandboxState
import com.stocktracker.app.data.remote.ScanLatest
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CI-2 — the missing link between the app's JSON contract and the backend that serves it.
 *
 * The app declares ~432 `@SerialName` fields, decodes with `coerceInputValues = true`, and 86
 * numeric fields carry literal defaults — so a field the backend renames does not fail here, it
 * silently decodes as 0.0 and renders as a confident zero. On the backend, 74 of 75 routes return a
 * bare `dict`, so nothing there pins the shape either. A rename of `positions_value` or `avg_cost`
 * would ship green on both sides and show wrong numbers on the phone.
 *
 * These fixtures are NOT hand-written. Each one is the literal, actual JSON response of one of this
 * app's four highest-stakes routes (real money screens: the sandbox paper book, a whole-portfolio
 * review, the nightly dip scan, and backend health), captured by
 * stocktracker-signals/tests/test_contract_fixtures.py against the real FastAPI route (or, for the
 * response model itself, real pydantic models like `analyst.PortfolioReview`) with deterministic,
 * mocked inputs. Every assertion below states the SAME values that test asserts server-side — so a
 * rename on either side of the contract turns exactly one of these two test files red.
 *
 * Every assertion here checks an actual VALUE, never merely "decoding did not throw". Decoding never
 * throws on a rename — `coerceInputValues = true` guarantees that — so a test that stops at "no
 * exception" is the exact gap this file exists to close.
 *
 * HOW TO REGENERATE — do this whenever a pinned route's response shape changes on purpose:
 *
 *     cd /home/spider/stocktracker-signals
 *     .venv/bin/python -m pytest tests/test_contract_fixtures.py -q
 *
 * That writes health.json, sandbox_state.json, portfolio_review.json and scan_latest.json under
 * stocktracker-signals/tests/fixtures/contract/ — copy that whole directory's contents into this
 * repo's app/src/test/resources/contract/, overwriting the four files there. (Deliberately not
 * spelling that copy as a single glob in this comment: a slash immediately followed by a star opens
 * a nested Kotlin block comment and swallows the rest of this KDoc — which is exactly the syntax
 * error the first version of this file shipped with.)
 *
 * Then update the expected values below to match the new fixtures, and re-run both suites. Never
 * hand-edit a fixture file directly — see test_contract_fixtures.py's own docstring for why.
 */
class ContractFixtureDecodeTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("contract/$name")) {
            "missing test resource contract/$name — see this file's regeneration comment"
        }.bufferedReader().readText()

    // ---------------------------------------------------------------- GET /health

    @Test
    fun `health decodes the fields SignalsHealth's probe never looked at`() {
        val h = Http.json.decodeFromString<Health>(fixture("health.json"))
        assertEquals(true, h.ok)
        assertEquals(true, h.keyConfigured)
        assertEquals("claude-opus-4-8", h.deepModel)
        assertEquals("claude-haiku-4-5", h.scanModel)
        assertEquals("env", h.settingsSource)
    }

    // ---------------------------------------------------------------- GET /sandbox/state

    @Test
    fun `sandbox state decodes the real money fields at their real values`() {
        val s = Http.json.decodeFromString<SandboxState>(fixture("sandbox_state.json"))

        // Chosen server-side so no two of these coincide — a decoder reading the wrong key lands on
        // a value this test can tell is wrong, not on an accidental match.
        assertEquals(10000.0, s.cash, 1e-9)
        assertEquals(12000.0, s.equity, 1e-9)
        assertEquals(2000.0, s.positionsValue, 1e-9)
        assertEquals(10000.0, s.fundedTotal, 1e-9)
        assertEquals(20.0, s.totalReturnPct!!, 1e-9)
        assertEquals(83.3, s.cashPct!!, 1e-9)
        assertEquals(11000.0, s.benchmarkValue!!, 1e-9)
        assertEquals(9.09, s.vsBenchmarkPct!!, 1e-9)
        assertTrue("a mocked, live-priced position must not be reported as stale", s.staleMarks.isEmpty())

        val pos = s.positions.single()
        assertEquals("AAPL", pos.symbol)
        assertEquals(10.0, pos.shares, 1e-9)
        assertEquals(150.0, pos.avgCost, 1e-9)          // the exact field CI-2 named as at risk
        assertEquals(200.0, pos.price, 1e-9)
        assertEquals(2000.0, pos.value, 1e-9)
        assertEquals(33.33, pos.unrealizedPct!!, 1e-9)
    }

    // ---------------------------------------------------------------- POST /portfolio/review

    @Test
    fun `portfolio review decodes the book's real weights, not zeros`() {
        val r = Http.json.decodeFromString<PortfolioReviewResponse>(fixture("portfolio_review.json"))

        assertEquals(3000.0, r.portfolio.totalValue, 1e-9)
        assertEquals(33.3, r.portfolio.cashPct!!, 1e-9)
        assertEquals("claude-haiku-4-5", r.model)
        assertEquals(false, r.cached)

        val pos = r.portfolio.positions.single()
        assertEquals("AAPL", pos.symbol)
        assertEquals(2000.0, pos.value, 1e-9)
        assertEquals(66.7, pos.weightPct!!, 1e-9)
        assertEquals(25.0, pos.unrealizedGainPct!!, 1e-9)

        val action = r.review.actions.single()
        assertEquals("AAPL", action.symbol)
        assertEquals("hold", action.action)
        assertTrue(r.review.concentration.isNotEmpty())
    }

    // ---------------------------------------------------------------- GET /scan/latest

    @Test
    fun `scan latest decodes a qualified dip and a near-miss reject with their real numbers`() {
        val scan = Http.json.decodeFromString<ScanLatest>(fixture("scan_latest.json"))

        assertEquals(true, scan.scanAvailable)
        assertTrue(scan.hasScan)
        assertEquals(3, scan.dipCounts?.scanned)
        assertEquals(1, scan.dipCounts?.qualified)
        assertEquals(1, scan.dipCounts?.nearMiss)
        assertEquals(1, scan.dipCounts?.nowhereNear)
        assertEquals(0, scan.dipCounts?.unmeasured)

        val aapl = scan.results!!.single { it.symbol == "AAPL" }
        assertEquals("pullback_10", aapl.dip)
        assertEquals(true, aapl.dipMeasured)
        assertEquals(true, aapl.flipped)
        assertEquals(-12.0, aapl.pctOffRecentHigh!!, 1e-9)

        val nearMiss = scan.dipRejects?.nearMiss?.single()
        assertEquals("MSFT", nearMiss?.symbol)
        assertEquals(0.8, nearMiss!!.gapPp!!, 1e-9)

        val alert = scan.dipAlerts!!.single()
        assertEquals("AAPL", alert.symbol)
        assertEquals("pullback_10", alert.dip)
    }
}
