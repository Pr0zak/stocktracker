package com.stocktracker.app.util

import com.stocktracker.app.util.NumberInput.Parsed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The position editor writes straight to the store, so a field that reads as null erases
 * hand-entered shares and cost basis. These cases are the ones that used to do exactly that.
 */
class NumberInputTest {

    private fun value(s: String): Double? = (NumberInput.parse(s) as? Parsed.Value)?.number

    @Test
    fun `plain numbers parse`() {
        assertEquals(150.0, value("150")!!, 1e-9)
        assertEquals(0.5, value("0.5")!!, 1e-9)
        assertEquals(1234.56, value("1234.56")!!, 1e-9)
    }

    @Test
    fun `thousands separators no longer wipe the field`() {
        assertEquals(1000.0, value("1,000")!!, 1e-9)
        assertEquals(1234567.0, value("1,234,567")!!, 1e-9)
        assertEquals(1234.56, value("1,234.56")!!, 1e-9)
    }

    @Test
    fun `a decimal comma is understood`() {
        // The Decimal IME produces this across most of Europe; it used to read as null.
        assertEquals(0.5, value("0,5")!!, 1e-9)
        assertEquals(12.75, value("12,75")!!, 1e-9)
    }

    @Test
    fun `european grouping with a decimal comma`() {
        assertEquals(1234.56, value("1.234,56")!!, 1e-9)
    }

    @Test
    fun `currency symbols and stray spaces are tolerated`() {
        assertEquals(150.0, value("\$150")!!, 1e-9)
        assertEquals(150.0, value(" 150 ")!!, 1e-9)
        assertEquals(1500.0, value("\$1,500")!!, 1e-9)
    }

    @Test
    fun `empty is distinct from invalid`() {
        // Empty means "clear this field" and null IS the right thing to store.
        assertTrue(NumberInput.parse("") is Parsed.Empty)
        assertTrue(NumberInput.parse("   ") is Parsed.Empty)
        // Garbage must be refused, never written as null over real data.
        assertTrue(NumberInput.parse("abc") is Parsed.Invalid)
        assertTrue(NumberInput.parse("1.2.3.4") is Parsed.Invalid)
        assertTrue(NumberInput.parse("-") is Parsed.Invalid)
    }

    @Test
    fun `non-finite input is refused rather than stored`() {
        assertTrue(NumberInput.parse("NaN") is Parsed.Invalid)
        assertTrue(NumberInput.parse("Infinity") is Parsed.Invalid)
    }

    @Test
    fun `isInvalid only fires for non-empty garbage`() {
        assertTrue(NumberInput.isInvalid("1,2,3.4.5"))
        assertTrue(!NumberInput.isInvalid(""))
        assertTrue(!NumberInput.isInvalid("1,000"))
    }
}
