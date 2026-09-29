package com.stocktracker.app.data.remote

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceServerTest {
    @After fun reset() { PriceServer.baseUrl = "" }

    @Test fun `no service configured means ask the source directly`() = runBlocking {
        PriceServer.baseUrl = ""
        assertFalse(PriceServer.available())
        assertNull(PriceServer.yahoo("v8/finance/chart/AAPL?range=1d&interval=1m"))
    }

    @Test fun `an unreachable service answers null fast and is skipped for a while`() = runBlocking {
        PriceServer.baseUrl = "http://127.0.0.1:1"
        val t0 = System.currentTimeMillis()
        assertNull(PriceServer.yahoo("v8/finance/chart/AAPL?range=1d&interval=1m"))
        assertTrue("fell back within the connect timeout", System.currentTimeMillis() - t0 < 5_000)
        assertFalse("the next call skips the service", PriceServer.available())
    }
}
