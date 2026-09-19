package com.stocktracker.app.data

import com.stocktracker.app.data.FidelityImportManager.AccountTaxGuess
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.Lot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MONEY-6: this is where a Fidelity CSV import lives or dies, per the task's own instructions —
 * a well-formed export, the non-position rows being skipped (cash sweep, pending activity, an
 * option contract, footer/disclaimer text), a malformed number, an empty file, and a symbol that
 * already exists on the watchlist.
 */
class FidelityImportManagerTest {

    private val header =
        "\"Account Number\",\"Account Name\",\"Symbol\",\"Description\",\"Quantity\",\"Last Price\"," +
            "\"Current Value\",\"Cost Basis Total\",\"Average Cost Basis\",\"Type\""

    private fun row(
        accountNumber: String,
        accountName: String,
        symbol: String,
        description: String,
        quantity: String,
        currentValue: String,
        costBasisTotal: String,
        avgCost: String,
        type: String = "Cash",
    ) = "\"$accountNumber\",\"$accountName\",\"$symbol\",\"$description\",\"$quantity\",\"\$1.00\"," +
        "\"$currentValue\",\"$costBasisTotal\",\"$avgCost\",\"$type\""

    private val wellFormedCsv = listOf(
        header,
        row("Z12345678", "ROTH IRA", "AAPL", "APPLE INC", "10", "\$1,500.00", "\$1,000.00", "\$100.00"),
        row("Z12345678", "ROTH IRA", "SPAXX**", "FIDELITY GOVERNMENT MONEY MARKET", "2500.5", "\$2,500.50", "--", "--"),
        row("Z12345678", "ROTH IRA", "Pending Activity", "Pending Activity", "--", "--", "--", "--"),
        row("X98765432", "INDIVIDUAL", "MSFT", "MICROSOFT CORP", "5", "\$1,500.00", "\$1,300.00", "\$260.00"),
        row("X98765432", "INDIVIDUAL", "-AAPL260117C400", "AAPL JAN 17 2026 \$400 CALL", "1", "\$500.00", "\$450.00", "\$450.00"),
        row("X98765432", "INDIVIDUAL", "VTSAX", "VANGUARD TOTAL STOCK MKT IDX FUND", "abc", "\$0.00", "--", "--"),
        "\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\",\"\"",
        "\"Date downloaded 09/17/2026 3:45 PM ET\"",
        "\"The data and information in this spreadsheet is provided by Fidelity Investments for personal use only.\"",
    ).joinToString("\n")

    // --- parseCsv: the well-formed case ------------------------------------------------------

    @Test
    fun `a well-formed export yields exactly the real positions`() {
        val parsed = FidelityImportManager.parseCsv(wellFormedCsv)
        assertEquals(setOf("AAPL", "MSFT"), parsed.positions.map { it.symbol }.toSet())

        val aapl = parsed.positions.single { it.symbol == "AAPL" }
        assertEquals(10.0, aapl.shares, 1e-9)
        assertEquals(100.0, aapl.costPerShare!!, 1e-9)
        assertEquals(AccountTaxGuess.TAX_ADVANTAGED, aapl.accountTaxGuess)
        assertTrue(aapl.accountLabel.contains("ROTH IRA"))

        val msft = parsed.positions.single { it.symbol == "MSFT" }
        assertEquals(5.0, msft.shares, 1e-9)
        assertEquals(260.0, msft.costPerShare!!, 1e-9)
        assertEquals(AccountTaxGuess.TAXABLE, msft.accountTaxGuess)
    }

    @Test
    fun `average cost basis is preferred over cost-total divided by shares`() {
        // A row where Cost Basis Total / Quantity would NOT equal Average Cost Basis — the broker's
        // own per-share figure must win, not a derived one that can differ by rounding.
        val csv = header + "\n" + row("A1", "INDIVIDUAL", "AAPL", "APPLE INC", "3", "\$300", "\$299.97", "\$100.50")
        val parsed = FidelityImportManager.parseCsv(csv)
        assertEquals(100.50, parsed.positions.single().costPerShare!!, 1e-9)
    }

    @Test
    fun `missing cost columns degrade to an unknown cost, not zero`() {
        val csv = "\"Symbol\",\"Description\",\"Quantity\"\n\"AAPL\",\"APPLE INC\",\"10\""
        val parsed = FidelityImportManager.parseCsv(csv)
        val pos = parsed.positions.single()
        assertEquals(10.0, pos.shares, 1e-9)
        assertNull("no cost column at all must mean unknown, not \$0", pos.costPerShare)
    }

    // --- non-position rows are skipped, each for the right reason -----------------------------

    @Test
    fun `cash sweeps are counted as cash, not as a holding`() {
        val parsed = FidelityImportManager.parseCsv(wellFormedCsv)
        assertTrue(parsed.positions.none { it.symbol.startsWith("SPAXX") })
        val cash = parsed.cash.single()
        assertEquals(2500.50, cash.amount!!, 1e-9)
        assertEquals(AccountTaxGuess.TAX_ADVANTAGED, cash.accountTaxGuess)
    }

    @Test
    fun `pending activity is skipped with its own reason`() {
        val parsed = FidelityImportManager.parseCsv(wellFormedCsv)
        val reason = parsed.skipped.single { it.reason.contains("pending activity") }
        assertTrue(reason.rawLine.contains("Pending Activity"))
    }

    @Test
    fun `an option contract is skipped, not imported as a security`() {
        val parsed = FidelityImportManager.parseCsv(wellFormedCsv)
        assertTrue(parsed.positions.none { it.symbol.contains("AAPL260117") })
        assertTrue(parsed.skipped.any { it.reason.contains("option") })
    }

    @Test
    fun `footer and disclaimer lines are skipped without crashing`() {
        val parsed = FidelityImportManager.parseCsv(wellFormedCsv)
        assertTrue(parsed.skipped.any { it.rawLine.contains("Date downloaded") })
        assertTrue(parsed.skipped.any { it.rawLine.contains("Fidelity Investments") })
    }

    // --- malformed numbers --------------------------------------------------------------------

    @Test
    fun `a malformed quantity is skipped with the raw text quoted back`() {
        val parsed = FidelityImportManager.parseCsv(wellFormedCsv)
        assertTrue(parsed.positions.none { it.symbol == "VTSAX" })
        val reason = parsed.skipped.single { it.rawLine.contains("VTSAX") }
        assertTrue(reason.reason.contains("couldn't read a share quantity"))
        assertTrue(reason.reason.contains("abc"))
    }

    @Test
    fun `parenthesized negative numbers and dollar formatting both parse`() {
        assertEquals(-12.34, FidelityImportManager.parseNumber("(\$12.34)")!!, 1e-9)
        assertEquals(1234.56, FidelityImportManager.parseNumber("\$1,234.56")!!, 1e-9)
        assertNull(FidelityImportManager.parseNumber("--"))
        assertNull(FidelityImportManager.parseNumber(""))
        assertNull(FidelityImportManager.parseNumber("N/A"))
        assertNull(FidelityImportManager.parseNumber(null))
    }

    @Test
    fun `quoted fields with embedded commas split correctly`() {
        val cells = FidelityImportManager.splitCsvLine("\"AAPL\",\"APPLE, INC.\",\"\$1,234.56\"")
        assertEquals(listOf("AAPL", "APPLE, INC.", "$1,234.56"), cells)
    }

    // --- empty / unrecognizable file -----------------------------------------------------------

    @Test
    fun `an empty file is refused, not silently imported as zero positions`() {
        val ex = runCatching { FidelityImportManager.parseCsv("") }.exceptionOrNull()
        assertTrue(ex != null)
        assertTrue(ex!!.message!!.contains("empty"))
    }

    @Test
    fun `blank-only text is refused the same way`() {
        val ex = runCatching { FidelityImportManager.parseCsv("   \n  \n") }.exceptionOrNull()
        assertTrue(ex != null)
    }

    @Test
    fun `a file with no Symbol column is refused rather than guessed at`() {
        val csv = "\"Ticker\",\"Shares\"\n\"AAPL\",\"10\""
        val ex = runCatching { FidelityImportManager.parseCsv(csv) }.exceptionOrNull()
        assertTrue(ex != null)
        assertTrue(ex!!.message!!.contains("Symbol"))
    }

    // --- buildPreview: new vs. replace vs. dated-lot loss --------------------------------------

    @Test
    fun `a brand-new symbol is flagged new with a single undated lot`() {
        val parsed = FidelityImportManager.parseCsv(wellFormedCsv)
        val preview = FidelityImportManager.buildPreview(parsed, current = emptyList(), currentCorrupted = false)
        val aapl = preview.rows.single { it.symbol == "AAPL" }
        assertTrue(aapl.isNew)
        assertEquals(1, aapl.lots.size)
        assertNull(aapl.lots.single().acquiredDateIso)
        assertEquals(2, preview.newCount)
        assertEquals(0, preview.replaceCount)
    }

    @Test
    fun `a symbol that already exists is flagged to replace, and dated-lot loss is called out`() {
        val existingAapl = Asset(
            symbol = "AAPL",
            type = AssetType.STOCK,
            displayName = "Apple Inc.",
            lots = listOf(Lot(shares = 5.0, costPerShare = 90.0, acquiredDateIso = "2020-01-01")),
        )
        val parsed = FidelityImportManager.parseCsv(wellFormedCsv)
        val preview = FidelityImportManager.buildPreview(
            parsed,
            current = listOf(existingAapl),
            currentCorrupted = false,
        )
        val aapl = preview.rows.single { it.symbol == "AAPL" }
        assertFalse(aapl.isNew)
        assertTrue("replacing a dated lot with an undated one must be flagged", aapl.willDiscardDatedLots)
        assertEquals(1, aapl.discardedDatedLotCount)
        assertEquals(1, preview.replaceCount)
    }

    @Test
    fun `an existing position that would be unchanged is not flagged as discarding history`() {
        // Same shares, same avg cost, same (null) date as what's already there — re-importing the
        // identical figures shouldn't cry wolf about losing history nothing actually changes.
        val existing = Asset(
            symbol = "MSFT",
            type = AssetType.STOCK,
            displayName = "Microsoft Corp",
            lots = listOf(Lot(shares = 5.0, costPerShare = 260.0, acquiredDateIso = null)),
        )
        val parsed = FidelityImportManager.parseCsv(wellFormedCsv)
        val preview = FidelityImportManager.buildPreview(parsed, current = listOf(existing), currentCorrupted = false)
        val msft = preview.rows.single { it.symbol == "MSFT" }
        assertFalse(msft.willDiscardDatedLots)
    }

    @Test
    fun `the same symbol across two accounts becomes two lots, never a blended average`() {
        val csv = header + "\n" +
            row("A1", "INDIVIDUAL", "AAPL", "APPLE INC", "10", "\$1,000", "\$1,000", "\$100.00") + "\n" +
            row("A2", "ROTH IRA", "AAPL", "APPLE INC", "5", "\$1,000", "\$1,000", "\$200.00")
        val parsed = FidelityImportManager.parseCsv(csv)
        val preview = FidelityImportManager.buildPreview(parsed, current = emptyList(), currentCorrupted = false)
        val aapl = preview.rows.single { it.symbol == "AAPL" }
        assertEquals(2, aapl.lots.size)
        assertEquals(setOf(100.0, 200.0), aapl.lots.mapNotNull { it.costPerShare }.toSet())
        assertEquals(2, aapl.accountLabels.size)
    }

    @Test
    fun `cash total sums across accounts and an unknown amount is not treated as zero`() {
        val cashRows = listOf(
            FidelityImportManager.CashRow("A", 100.0, AccountTaxGuess.TAXABLE),
            FidelityImportManager.CashRow("B", null, AccountTaxGuess.UNKNOWN),
            FidelityImportManager.CashRow("C", 50.0, AccountTaxGuess.TAX_ADVANTAGED),
        )
        val preview = FidelityImportManager.ImportPreview(
            rows = emptyList(),
            skipped = emptyList(),
            cash = cashRows,
            accountTaxGuesses = emptyMap(),
            currentWatchlistCorrupted = false,
        )
        assertEquals(150.0, preview.totalCash, 1e-9)
        assertEquals(1, preview.cashWithUnknownAmount)
    }

    @Test
    fun `an account name that matches no known pattern guesses unknown, not a default`() {
        val csv = header + "\n" + row("A1", "SOMETHING WEIRD 42", "AAPL", "APPLE INC", "1", "\$1", "\$1", "\$1.00")
        val parsed = FidelityImportManager.parseCsv(csv)
        assertEquals(AccountTaxGuess.UNKNOWN, parsed.positions.single().accountTaxGuess)
    }
}
