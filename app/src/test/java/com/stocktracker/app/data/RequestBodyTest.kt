package com.stocktracker.app.data

import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.data.remote.SandboxSettingsPatch
import com.stocktracker.app.data.remote.SandboxTickRequest
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Request bodies must actually contain the values they were built with.
 *
 * `Http.json` leaves `encodeDefaults` at its default of FALSE, so kotlinx omits any field whose value
 * equals the class default. For response parsing that is harmless; for REQUEST bodies with non-null
 * defaults it silently drops the instruction. Every control affected by this looks like it worked —
 * the POST returns 200 with the unchanged settings — while doing nothing at all.
 */
class RequestBodyTest {

    @Test
    fun `force=true survives serialization`() {
        // "Run a decision cycle now" is the whole point of the button: without force the server
        // applies its normal cadence gate and the manual run silently does nothing.
        val body = Http.json.encodeToString(SandboxTickRequest(force = true, manual = true))
        assertTrue("force was dropped from the tick request: $body", body.contains("\"force\""))
    }

    @Test
    fun `force=false also survives`() {
        val body = Http.json.encodeToString(SandboxTickRequest(force = false, manual = true))
        assertTrue(body.contains("\"force\""))
    }

    @Test
    fun `switching a margin account back to cash actually sends account_type`() {
        // accountType's class default is "cash", so turning margin OFF used to serialize to {} —
        // an empty patch the server treats as a no-op, leaving the paper trader on margin.
        val body = Http.json.encodeToString(SandboxSettingsPatch(accountType = "cash"))
        assertTrue("account_type was dropped, so margin can never be turned off: $body",
            body.contains("account_type"))
    }

    @Test
    fun `switching wash-sale avoidance back on actually sends the flag`() {
        val body = Http.json.encodeToString(SandboxSettingsPatch(avoidWashSales = true))
        assertTrue("avoid_wash_sales was dropped, so it can never be re-enabled: $body",
            body.contains("avoid_wash_sales"))
    }

    @Test
    fun `clearing the birth date sends an empty string rather than dropping the field`() {
        // A null would be omitted from the patch entirely and the server would keep the old date --
        // "Clear" in the picker would appear to work and change nothing. The empty string is what
        // the server reads as "unset it", same as the retirement and exit dates beside it.
        val cleared = Http.json.encodeToString(SandboxSettingsPatch(birthDate = ""))
        assertTrue("birth_date was dropped, so the date can never be cleared: $cleared",
            cleared.contains("birth_date"))
        val set = Http.json.encodeToString(SandboxSettingsPatch(birthDate = "1979-08-20"))
        assertTrue(set.contains("1979-08-20"))
        // Sending a stale current_age alongside it would be ignored server-side, but it must not be
        // sent at all -- the date is the only input now.
        assertTrue("the age must not ride along with the date: $set", !set.contains("current_age"))
    }

    @Test
    fun `a patch still omits fields the caller did not set`() {
        // The whole point of a patch is partial update — this must NOT become "send everything".
        val body = Http.json.encodeToString(SandboxSettingsPatch(accountType = "margin"))
        assertTrue("a patch must not carry unrelated nulls: $body", !body.contains("retirement_date"))
        assertTrue(!body.contains("master_enabled"))
    }

    @Test
    fun `refresh survives serialization on both portfolio bodies`() {
        // encodeDefaults is false, so a defaulted field equal to its default is DROPPED. Both
        // refresh fields are declared without a default for exactly that reason — if either grows
        // one, "Refresh" silently stops bypassing the cache and re-serves an old plan as current.
        val rev = Http.json.encodeToString(
            com.stocktracker.app.data.remote.PortfolioReviewRequest(
                cash = 0.0, deep = false, refresh = false, holdings = emptyList()))
        assertTrue("refresh dropped from the review body: $rev", rev.contains("\"refresh\""))
        val reb = Http.json.encodeToString(
            com.stocktracker.app.data.remote.RebalanceRequestBody(
                cash = 0.0, deep = false, refresh = false, maxPositionPct = 25.0, holdings = emptyList()))
        assertTrue("refresh dropped from the rebalance body: $reb", reb.contains("\"refresh\""))
    }
}
