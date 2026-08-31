package com.stocktracker.app.ui

import com.stocktracker.app.ui.components.formatPaneValue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sub-pane readout's formatter. The load-bearing case is the first one: an indicator inside its
 * warm-up has no reading, and the pane must say so rather than print a number.
 *
 * The drawing itself is a Canvas call and is not reachable from a JVM test — these cover the text
 * that goes into it.
 */
class PaneValueFormatTest {

    @Test fun `a warm-up bar prints an em dash, not zero`() {
        assertEquals("—", formatPaneValue(null))
        // The distinction that matters: a real zero is a reading and prints as one.
        assertEquals("0.000", formatPaneValue(0.0))
    }

    @Test fun `non-finite values are absence, not numbers`() {
        assertEquals("—", formatPaneValue(Double.NaN))
        assertEquals("—", formatPaneValue(Double.POSITIVE_INFINITY))
        assertEquals("—", formatPaneValue(Double.NEGATIVE_INFINITY))
    }

    /** RSI and the stochastic run 0..100; a MACD line on a cheap stock lives in hundredths. */
    @Test fun `precision adapts to magnitude so both scales stay readable`() {
        assertEquals("100", formatPaneValue(100.0))          // %K pinned high
        assertEquals("42.4", formatPaneValue(42.37))         // RSI
        assertEquals("1.24", formatPaneValue(1.2351))        // MACD on a mid-price name
        assertEquals("0.004", formatPaneValue(0.0038))       // MACD on a cheap one
    }

    @Test fun `negative values keep their sign and their precision`() {
        assertEquals("-42.4", formatPaneValue(-42.37))
        assertEquals("-0.004", formatPaneValue(-0.0038))
    }

    /** Formatting is locale-independent — a comma decimal separator would misread as a thousands mark. */
    @Test fun `the decimal separator is a dot regardless of default locale`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("42.4", formatPaneValue(42.37))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }
}
