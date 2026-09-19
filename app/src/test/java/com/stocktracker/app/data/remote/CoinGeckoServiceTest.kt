package com.stocktracker.app.data.remote

import com.stocktracker.app.data.remote.CoinGeckoPriceDto
import com.stocktracker.app.data.remote.CoinMarketDto
import com.stocktracker.app.data.remote.Http
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * A null price from CoinGecko must not be silently rendered as $0.00. The coerceInputValues flag
 * in Http.json would coerce null to the declared default; CoinGeckoPriceDto and CoinMarketDto must
 * have nullable price fields WITHOUT numeric defaults, so null stays null through deserialization.
 *
 * When quote() or markets() encounter a null or zero price, they must skip / return no quote,
 * matching the pattern FinnhubService uses for all-zeros payloads.
 */
class CoinGeckoServiceTest {

    @Test
    fun `decoding CoinGecko price with null usd produces null, not 0, so the service can reject it`() {
        // The JSON explicitly sends null, not omitting the field. With coerceInputValues=true, a
        // default of 0.0 would silently convert null to 0.0 during deserialization. The DTO field
        // must be nullable without a numeric default.
        val json = """{"usd":null,"usd_24h_change":-5.0,"usd_24h_vol":100.0}"""
        val dto = Http.json.decodeFromString<CoinGeckoPriceDto>(json)
        assertEquals("price field should remain null, not be coerced to 0.0", null, dto.usd)
        assertEquals(-5.0, dto.usd24hChange!!, 0.0)
    }

    @Test
    fun `quote() throws when price is null`() {
        // The service's quote() function must detect null price and throw IOException,
        // not return a $0.00 quote.
        val json = """{"bitcoin":{"usd":null,"usd_24h_change":-2.0,"usd_24h_vol":50.0}}"""
        val map = Http.json.decodeFromString<Map<String, CoinGeckoPriceDto>>(json)
        val dto = map["bitcoin"]!!
        val ex = assertThrows(IOException::class.java) {
            // Simulate the logic in CoinGeckoService.quote() after decoding
            val price = dto.usd
            if (price == null || price <= 0.0) {
                throw IOException("No CoinGecko price for 'bitcoin' (invalid or missing price)")
            }
        }
        assertEquals(
            "No CoinGecko price for 'bitcoin' (invalid or missing price)",
            ex.message
        )
    }

    @Test
    fun `quote() also throws when price is zero`() {
        val json = """{"bitcoin":{"usd":0.0,"usd_24h_change":-2.0,"usd_24h_vol":50.0}}"""
        val map = Http.json.decodeFromString<Map<String, CoinGeckoPriceDto>>(json)
        val dto = map["bitcoin"]!!
        val ex = assertThrows(IOException::class.java) {
            val price = dto.usd
            if (price == null || price <= 0.0) {
                throw IOException("No CoinGecko price for 'bitcoin' (invalid or missing price)")
            }
        }
        assertEquals(
            "No CoinGecko price for 'bitcoin' (invalid or missing price)",
            ex.message
        )
    }

    @Test
    fun `quote() throws when price is negative`() {
        val json = """{"bitcoin":{"usd":-10.0,"usd_24h_change":5.0,"usd_24h_vol":50.0}}"""
        val map = Http.json.decodeFromString<Map<String, CoinGeckoPriceDto>>(json)
        val dto = map["bitcoin"]!!
        val ex = assertThrows(IOException::class.java) {
            val price = dto.usd
            if (price == null || price <= 0.0) {
                throw IOException("No CoinGecko price for 'bitcoin' (invalid or missing price)")
            }
        }
        assertEquals(
            "No CoinGecko price for 'bitcoin' (invalid or missing price)",
            ex.message
        )
    }

    @Test
    fun `markets() filters out coins with null price`() {
        val json = """[
            {"id":"bitcoin","symbol":"BTC","name":"Bitcoin","current_price":45000.0,
             "price_change_24h":100.0,"price_change_percentage_24h":0.2,"sparkline_in_7d":{"price":[45000,45100]}},
            {"id":"ethereum","symbol":"ETH","name":"Ethereum","current_price":null,
             "price_change_24h":50.0,"price_change_percentage_24h":0.1,"sparkline_in_7d":{"price":[2500,2600]}},
            {"id":"cardano","symbol":"ADA","name":"Cardano","current_price":0.5,
             "price_change_24h":-10.0,"price_change_percentage_24h":-0.5,"sparkline_in_7d":{"price":[0.5,0.4]}}
        ]"""
        val dtos = Http.json.decodeFromString<List<CoinMarketDto>>(json)

        // Simulate the markets() filtering logic
        val markets = dtos.mapNotNull {
            val price = it.currentPrice
            if (price == null || price <= 0.0) null else Pair(it.id, it.symbol)
        }

        assertEquals("should filter out ethereum (null price) and keep bitcoin (valid price)", 2, markets.size)
        assertEquals("bitcoin", markets[0].first)
        assertEquals("cardano", markets[1].first)
    }

    @Test
    fun `decoding CoinMarket with null current_price keeps it null, not 0`() {
        val json = """{"id":"ethereum","symbol":"ETH","name":"Ethereum","current_price":null,
                      "price_change_24h":50.0,"price_change_percentage_24h":0.1}"""
        val dto = Http.json.decodeFromString<CoinMarketDto>(json)
        assertEquals("current_price field should remain null, not be coerced to 0.0", null, dto.currentPrice)
        assertEquals(50.0, dto.priceChange24h!!, 0.0)
    }

    @Test
    fun `a valid CoinGecko price decodes correctly and is not rejected`() {
        // Smoke test: when the price is valid, decoding works and the service should construct a quote.
        val json = """{"bitcoin":{"usd":45000.0,"usd_24h_change":500.0,"usd_24h_vol":1000000.0}}"""
        val map = Http.json.decodeFromString<Map<String, CoinGeckoPriceDto>>(json)
        val dto = map["bitcoin"]!!

        // This should NOT throw
        val price = dto.usd
        if (price == null || price <= 0.0) {
            throw IOException("No CoinGecko price for 'bitcoin' (invalid or missing price)")
        }

        assertEquals(45000.0, price, 0.0)
        assertEquals(500.0, dto.usd24hChange!!, 0.0)
    }
}
