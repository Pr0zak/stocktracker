package com.stocktracker.app.data

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import com.stocktracker.app.data.model.Asset
import com.stocktracker.app.data.model.AssetType
import com.stocktracker.app.data.model.Lot
import com.stocktracker.app.data.prefs.dataStore
import com.stocktracker.app.data.remote.Http
import com.stocktracker.app.di.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.IOException

/**
 * MONEY-6: import a Fidelity "Positions" CSV export.
 *
 * Every holding in this app was, until now, typed in by hand — and every add-to-position was
 * arithmetic the user did in their own head first. A broker export is the structural fix: it
 * carries share counts, cost basis and account identity in one file, written by the broker's own
 * books rather than recalled from memory.
 *
 * A Fidelity Positions export is NOT a transaction history. It reports one row per (symbol,
 * account) holding with a running total quantity and a running average cost — never individual
 * purchase lots, never purchase dates. So every position this produces is exactly ONE [Lot] with
 * [Lot.acquiredDateIso] left null (see [Lot]'s own doc on what null means there): inventing a date
 * would be exactly the confident-looking wrong number this project refuses to print, and it would
 * silently corrupt a tax-aware rebalance that reads that date to tell short-term from long-term.
 *
 * Three things this file is careful about, mirroring [BackupManager] (the other place a single tap
 * can replace real user data):
 *  1. Parsing never guesses a row into existence. Cash sweeps, pending activity, option contracts,
 *     account-total/disclaimer footer lines, and unparseable rows are all told apart and reported
 *     as such (see [ParsedFile.skipped] / [ParsedFile.cash]), never silently turned into holdings.
 *  2. [buildPreview] is pure and produces concrete NEW-vs-REPLACE-vs-skipped counts BEFORE anything
 *     is written, so the confirmation dialog can say exactly what is about to happen.
 *  3. [commitImport] replaces an existing symbol's [Asset.lots] WHOLESALE — never merges old and new
 *     lots, never blends two cost bases into an invented average. That is the one honest meaning of
 *     "this broker now says you hold N shares at cost C"; a clever reconciliation would be a guess
 *     wearing the broker's authority.
 *
 * What this does NOT do: there is no per-account entity anywhere in this codebase yet (account
 * identity here is display-only — see [RawPosition.accountLabel] — and the taxable/tax-advantaged
 * read of an account name in [AccountTaxGuess] is never persisted). "Investable cash" is a single
 * global figure ([com.stocktracker.app.data.prefs.SettingsStore.investableCash]), not one per
 * account, so the best this import can honestly offer is the SUM of cash found across every account
 * in the file — see [ImportPreview.totalCash] and [commitImport]'s `applyCashTotal`.
 */
object FidelityImportManager {

    // ---------------------------------------------------------------------------------------------
    // Parsing — pure, no Android/DataStore involved, so this is where the hard cases get tested.
    // ---------------------------------------------------------------------------------------------

    /** Why a row from the export wasn't turned into a holding. [rawLine] is kept verbatim — a
     *  confused user can find the exact line in their own file rather than trust our summary of it. */
    data class SkippedRow(val rawLine: String, val reason: String)

    /**
     * A cash / money-market sweep line, kept apart from a position (MONEY-6 §1/§4) — a money-market
     * fund is where Fidelity parks uninvested cash, not a holding to track a price on.
     *
     * [amount] is null — never 0.0 — when the row didn't carry a value this parser could read. A
     * missing amount must not silently vanish from the cash total; see [ImportPreview.totalCash] and
     * [ImportPreview.cashWithUnknownAmount].
     */
    data class CashRow(val accountLabel: String, val amount: Double?, val accountTaxGuess: AccountTaxGuess)

    /**
     * A best-effort READ of Fidelity's free-text Account Name (e.g. "ROTH IRA", "INDIVIDUAL") — a
     * guess for display only. Nothing in this codebase persists a taxable/tax-advantaged flag yet
     * (that model work is happening elsewhere, concurrently); this exists so the import preview can
     * tell the user what it noticed rather than staying silent about information the file plainly
     * carries.
     */
    enum class AccountTaxGuess { TAXABLE, TAX_ADVANTAGED, UNKNOWN }

    /** One position row, already resolved to a single account. [ImportRow] is the per-symbol shape
     *  a UI actually renders — this is the intermediate, one-row-per-(symbol,account) form. */
    data class RawPosition(
        val symbol: String,
        val description: String,
        val accountLabel: String,
        val accountTaxGuess: AccountTaxGuess,
        val shares: Double,
        val costPerShare: Double?,
    )

    /** Output of [parseCsv]: everything found in the file, before comparing against what the app
     *  already tracks. */
    data class ParsedFile(
        val positions: List<RawPosition>,
        val skipped: List<SkippedRow>,
        val cash: List<CashRow>,
    )

    // Column names Fidelity has shipped, normalized (lowercase, non-alphanumerics stripped) so
    // "Cost Basis Total", "cost basis total" and a stray extra space all match the same alias.
    // Deliberately NOT a fixed column order or count: a real export carries a dozen+ columns this
    // app has no use for (Last Price, Today's Gain/Loss, Percent Of Account, ...) and skipping past
    // them by NAME rather than position is what keeps this working if Fidelity reorders them.
    private val HEADER_ALIASES: Map<String, List<String>> = mapOf(
        "symbol" to listOf("symbol"),
        "description" to listOf("description"),
        "quantity" to listOf("quantity", "qty"),
        "costbasistotal" to listOf("costbasistotal", "totalcostbasis"),
        "avgcost" to listOf(
            "averagecostbasis", "avgcostbasis", "costbasispershare", "averagecostbasispershare",
        ),
        "currentvalue" to listOf("currentvalue", "marketvalue"),
        "accountname" to listOf("accountname"),
        "accountnumber" to listOf("accountnumber", "account"),
        "type" to listOf("type", "securitytype"),
    )

    private val TAX_ADVANTAGED_MARKERS = listOf(
        "IRA", "401K", "401(K)", "403B", "403(B)", "ROTH", "SEP ", "SEP-", "HSA", "FSA", "529",
        "KEOGH", "TSP", "ANNUITY",
    )
    private val TAXABLE_MARKERS = listOf(
        "INDIVIDUAL", "JOINT", "BROKERAGE", "TRUST", "CUSTODIAL", "UGMA", "UTMA",
    )

    private fun normalizeHeader(s: String): String = s.trim().lowercase().replace(Regex("[^a-z0-9]"), "")

    /**
     * RFC4180-ish: quoted fields, embedded commas inside them, and a doubled `""` as an escaped
     * literal quote. Needed because Fidelity quotes any dollar figure at or above $1,000 — the comma
     * inside `"$1,234.56"` would otherwise read as a field separator and shift every column after it.
     */
    internal fun splitCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    sb.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> { fields.add(sb.toString()); sb.setLength(0) }
                    else -> sb.append(c)
                }
            }
            i++
        }
        fields.add(sb.toString())
        return fields
    }

    /**
     * "$1,234.56" -> 1234.56; "(12.34)" -> -12.34 (accounting notation for negative); "--", "" and
     * "n/a" -> null. Null here means "this file didn't say" — the same UNKNOWN [Lot.costPerShare]
     * means everywhere else, never coerced to 0.0.
     */
    internal fun parseNumber(raw: String?): Double? {
        if (raw == null) return null
        var s = raw.trim()
        if (s.isEmpty() || s == "--" || s.equals("n/a", ignoreCase = true)) return null
        var negative = false
        if (s.length > 2 && s.startsWith("(") && s.endsWith(")")) {
            negative = true
            s = s.substring(1, s.length - 1)
        }
        s = s.replace("$", "").replace(",", "").replace("%", "").trim()
        if (s.startsWith("+")) s = s.substring(1)
        val v = s.toDoubleOrNull() ?: return null
        return if (negative) -v else v
    }

    private fun guessTaxable(accountName: String?): AccountTaxGuess {
        val n = accountName?.uppercase() ?: return AccountTaxGuess.UNKNOWN
        return when {
            TAX_ADVANTAGED_MARKERS.any { n.contains(it) } -> AccountTaxGuess.TAX_ADVANTAGED
            TAXABLE_MARKERS.any { n.contains(it) } -> AccountTaxGuess.TAXABLE
            else -> AccountTaxGuess.UNKNOWN
        }
    }

    private fun accountLabel(name: String?, number: String?): String = when {
        !name.isNullOrBlank() && !number.isNullOrBlank() -> "$name (…${number.takeLast(4)})"
        !name.isNullOrBlank() -> name
        !number.isNullOrBlank() -> "Account …${number.takeLast(4)}"
        else -> "Unknown account"
    }

    /**
     * Deliberately ignores the "Type" column: on a real Fidelity export that column reports
     * Cash-vs-Margin for the ACCOUNT the position sits in, not the security's asset class — a
     * perfectly ordinary AAPL share in a cash (non-margin) brokerage account is reported with
     * Type="Cash" too. Keying cash-sweep detection off it would silently swallow real holdings.
     */
    private fun looksLikeCashSweep(symbol: String, description: String?): Boolean {
        if (symbol.equals("CASH", ignoreCase = true)) return true
        // Fidelity's core/sweep money-market funds are named like SPAXX**, FDRXX**, FCASH** — the
        // trailing "**" is the broker's own footnote marker for "this is where uninvested cash sits".
        if (symbol.endsWith("**")) return true
        val d = description?.uppercase().orEmpty()
        return d.contains("MONEY MARKET") || d.contains("GOVERNMENT MONEY")
    }

    private val OPTION_WORDS = Regex("""\b(CALL|PUT)\b""")
    private val OPTION_STRIKE = Regex("""\$?\d+(\.\d+)?""")

    private fun looksLikeOption(symbol: String, description: String?): Boolean {
        if (symbol.startsWith("-")) return true // Fidelity's OCC-style option symbol prefix
        val d = description?.uppercase().orEmpty()
        return OPTION_WORDS.containsMatchIn(d) && OPTION_STRIKE.containsMatchIn(d)
    }

    /**
     * Parse the raw text of a Fidelity "Positions" export.
     *
     * Never throws for a malformed ROW — a bad quantity, an unreadable cost, or a line that's really
     * disclaimer text degrades to a [SkippedRow] or a null field, never a crash and never an invented
     * number. It DOES throw [IOException] for a file that isn't recognizable as this export at all
     * (empty, or no "Symbol" column found in the first several lines) — there's nothing honest to
     * preview from that, so the caller should refuse rather than guess.
     */
    fun parseCsv(text: String): ParsedFile {
        if (text.isBlank()) throw IOException("That file is empty.")
        val lines = text.lines()

        // Fidelity sometimes writes a title/date line above the real header, so scan for it instead
        // of assuming line 0.
        var headerIndex = -1
        var headerMap: Map<String, Int> = emptyMap()
        for (i in lines.indices.take(10)) {
            val cells = splitCsvLine(lines[i]).map { normalizeHeader(it) }
            val symbolIdx = HEADER_ALIASES.getValue("symbol").firstNotNullOfOrNull { alias ->
                cells.indexOf(alias).takeIf { it >= 0 }
            }
            if (symbolIdx != null) {
                headerIndex = i
                headerMap = buildMap {
                    for ((key, aliases) in HEADER_ALIASES) {
                        val idx = aliases.firstNotNullOfOrNull { alias -> cells.indexOf(alias).takeIf { it >= 0 } }
                        if (idx != null) put(key, idx)
                    }
                }
                break
            }
        }
        if (headerIndex < 0) {
            throw IOException("That doesn't look like a Fidelity Positions export — no \"Symbol\" column found.")
        }

        fun cell(cells: List<String>, key: String): String? =
            headerMap[key]?.let { cells.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }

        val positions = mutableListOf<RawPosition>()
        val skipped = mutableListOf<SkippedRow>()
        val cash = mutableListOf<CashRow>()

        for (line in lines.drop(headerIndex + 1)) {
            if (line.isBlank()) continue
            val cells = splitCsvLine(line)
            val symbol = cell(cells, "symbol")
            if (symbol == null) {
                // The tail of a real export is an "Account Total" line (no symbol), a blank line,
                // then several sentences of legal disclaimer text — none of it a position, and none
                // of it alarming enough to report line-by-line as a "malformed" row.
                skipped.add(SkippedRow(line, "no ticker symbol (likely a total, footer, or disclaimer line)"))
                continue
            }
            val description = cell(cells, "description")
            val accountName = cell(cells, "accountname")
            val accountNumber = cell(cells, "accountnumber")
            val label = accountLabel(accountName, accountNumber)
            val taxGuess = guessTaxable(accountName)

            if (symbol.equals("Pending Activity", ignoreCase = true)) {
                skipped.add(SkippedRow(line, "pending activity, not a settled position"))
                continue
            }
            if (looksLikeCashSweep(symbol, description)) {
                val amount = parseNumber(cell(cells, "currentvalue")) ?: parseNumber(cell(cells, "quantity"))
                cash.add(CashRow(label, amount, taxGuess))
                continue
            }
            if (looksLikeOption(symbol, description)) {
                skipped.add(SkippedRow(line, "option contract — not supported by CSV import"))
                continue
            }
            val quantityRaw = cell(cells, "quantity")
            val shares = parseNumber(quantityRaw)
            if (shares == null) {
                skipped.add(SkippedRow(line, "couldn't read a share quantity ('${quantityRaw.orEmpty()}')"))
                continue
            }
            if (shares == 0.0) {
                skipped.add(SkippedRow(line, "zero shares"))
                continue
            }
            val costTotal = parseNumber(cell(cells, "costbasistotal"))
            val avgCostDirect = parseNumber(cell(cells, "avgcost"))
            // Prefer the broker's own per-share figure over deriving one — it's what the statement
            // actually shows the user, and dividing costTotal/shares can differ from it by rounding.
            val costPerShare = avgCostDirect ?: costTotal?.let { it / shares }
            positions.add(
                RawPosition(
                    symbol = symbol.uppercase(),
                    description = description ?: symbol,
                    accountLabel = label,
                    accountTaxGuess = taxGuess,
                    shares = shares,
                    costPerShare = costPerShare,
                ),
            )
        }
        return ParsedFile(positions, skipped, cash)
    }

    // ---------------------------------------------------------------------------------------------
    // Preview — what importing [ParsedFile] would do to the CURRENTLY tracked watchlist, in counts.
    // ---------------------------------------------------------------------------------------------

    /** One symbol's worth of the import — every CSV row for that symbol (there can be more than one
     *  when the same security is held across several accounts) folded into the [Lot]s it would
     *  produce, alongside whatever is already tracked for it (null = not tracked yet). */
    data class ImportRow(
        val symbol: String,
        val description: String,
        val accountLabels: List<String>,
        val lots: List<Lot>,
        val existingAsset: Asset?,
    ) {
        val isNew: Boolean get() = existingAsset == null
        private val previewAsset =
            Asset(symbol = symbol, type = AssetType.STOCK, displayName = description, lots = lots)
        val newShares: Double get() = previewAsset.shares ?: 0.0
        val newAvgCost: Double? get() = previewAsset.avgCost

        /** True when the existing tracked position has purchase-dated lots that this replacement
         *  would throw away — reuses [Asset.editWouldDiscardDatedLots], the same guard the manual
         *  edit-holdings dialog already applies (MONEY-2), so an import can't discard history through
         *  a door that dialog keeps shut. */
        val willDiscardDatedLots: Boolean
            get() = existingAsset?.editWouldDiscardDatedLots(previewAsset.shares, previewAsset.avgCost) == true
        val discardedDatedLotCount: Int get() = existingAsset?.datedLotCount() ?: 0
    }

    data class ImportPreview(
        val rows: List<ImportRow>,
        val skipped: List<SkippedRow>,
        val cash: List<CashRow>,
        /** Account label -> best-effort taxable/tax-advantaged read, for DISPLAY only — see
         *  [AccountTaxGuess]'s doc for why nothing here is persisted. */
        val accountTaxGuesses: Map<String, AccountTaxGuess>,
        /** True if the watchlist this import would merge into is CURRENTLY unreadable (corrupt) —
         *  mirrors [BackupManager.ReadResult.Ready.corruptedNow]. Importing still proceeds if the
         *  user confirms, but as a wholesale replacement of whatever might have been recoverable,
         *  not a merge — the confirmation copy must say that plainly. */
        val currentWatchlistCorrupted: Boolean,
    ) {
        val newCount: Int get() = rows.count { it.isNew }
        val replaceCount: Int get() = rows.count { !it.isNew }
        val totalCash: Double get() = cash.mapNotNull { it.amount }.sum()
        val cashWithUnknownAmount: Int get() = cash.count { it.amount == null }
    }

    /**
     * Pure: builds [ImportPreview] from an already-parsed file and the assets CURRENTLY tracked.
     * No auto-merge (MONEY-6): a symbol already on the watchlist is flagged to have its [Asset.lots]
     * REPLACED wholesale by [commitImport], never blended with what's already there — see this
     * object's class doc for why a clever reconciliation rule is worse than an honest replacement.
     */
    fun buildPreview(parsed: ParsedFile, current: List<Asset>, currentCorrupted: Boolean): ImportPreview {
        val bySymbol = parsed.positions.groupBy { it.symbol }
        val rows = bySymbol.map { (symbol, group) ->
            val lots = group.map { Lot(shares = it.shares, costPerShare = it.costPerShare, acquiredDateIso = null) }
            ImportRow(
                symbol = symbol,
                description = group.first().description,
                accountLabels = group.map { it.accountLabel }.distinct(),
                lots = lots,
                existingAsset = current.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) },
            )
        }.sortedBy { it.symbol }
        val taxGuesses = buildMap {
            parsed.positions.forEach { putIfAbsent(it.accountLabel, it.accountTaxGuess) }
            parsed.cash.forEach { putIfAbsent(it.accountLabel, it.accountTaxGuess) }
        }
        return ImportPreview(rows, parsed.skipped, parsed.cash, taxGuesses, currentCorrupted)
    }

    // ---------------------------------------------------------------------------------------------
    // File I/O + commit — the DataStore glue. Mirrors BackupManager's read/confirm/commit split: a
    // file pick only ever READS and parses (safe to cancel), nothing is written until the user has
    // seen [ImportPreview]'s concrete counts and explicitly confirmed.
    // ---------------------------------------------------------------------------------------------

    sealed interface ReadResult {
        data class Ready(val preview: ImportPreview) : ReadResult
        data class Failed(val message: String) : ReadResult
    }

    suspend fun readForImport(context: Context, uri: Uri): ReadResult = withContext(Dispatchers.IO) {
        val text = try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return@withContext ReadResult.Failed("Couldn't open the chosen file.")
        } catch (e: IOException) {
            return@withContext ReadResult.Failed("Couldn't read the chosen file: ${e.message ?: "unknown error"}.")
        }
        val parsed = try {
            parseCsv(text)
        } catch (e: IOException) {
            return@withContext ReadResult.Failed(e.message ?: "That file isn't a Fidelity Positions export.")
        }
        if (parsed.positions.isEmpty() && parsed.cash.isEmpty()) {
            return@withContext ReadResult.Failed(
                "No positions or cash were found in that file" +
                    if (parsed.skipped.isNotEmpty()) " (${parsed.skipped.size} rows were skipped)." else ".",
            )
        }
        val current = ServiceLocator.watchlistStore.snapshot()
        val corrupted = ServiceLocator.watchlistStore.corrupted.first()
        ReadResult.Ready(buildPreview(parsed, current, corrupted))
    }

    /** Snapshot needed to [undo] a commit — the exact pre-import bytes/value, same rigor as
     *  [BackupManager.BackupRawState] and for the same reason: a corrupt-but-possibly-recoverable
     *  watchlist must come back exactly as it was, not as whatever a decode-then-reencode produces. */
    data class ImportUndo(val assetsJsonBefore: String?, val investableCashBefore: Double?)

    sealed interface CommitResult {
        data class Success(val rowsImported: Int, val undo: ImportUndo) : CommitResult
        data class Failed(val message: String) : CommitResult
    }

    /**
     * Writes [preview]'s rows into the watchlist in ONE DataStore transaction: an existing symbol's
     * [Asset.lots] is REPLACED wholesale by the imported lots (all its other fields — alerts, groups,
     * favorite, displayName — are left untouched); a symbol not already tracked is appended as a new
     * [Asset] typed [AssetType.STOCK] (a Positions export never carries crypto).
     *
     * If [applyCashTotal], also sets the app's single investable-cash figure
     * ([com.stocktracker.app.data.prefs.SettingsStore.investableCash]) to [ImportPreview.totalCash].
     * There is no per-account cash store in this app (see class doc), so that sum is the honest
     * ceiling of what "set cash from this import" can mean today.
     */
    suspend fun commitImport(
        context: Context,
        preview: ImportPreview,
        applyCashTotal: Boolean,
    ): CommitResult = withContext(Dispatchers.IO) {
        val current = ServiceLocator.watchlistStore.snapshot()
        val byImportedSymbol = preview.rows.associateBy { it.symbol.uppercase() }
        val updatedExisting = current.map { asset ->
            byImportedSymbol[asset.symbol.uppercase()]?.let { row -> asset.copy(lots = row.lots) } ?: asset
        }
        val existingSymbols = current.map { it.symbol.uppercase() }.toSet()
        val newAssets = preview.rows.filter { it.symbol.uppercase() !in existingSymbols }.map { row ->
            Asset(
                symbol = row.symbol,
                type = AssetType.STOCK,
                displayName = row.description.ifBlank { row.symbol },
                lots = row.lots,
            )
        }
        val finalList = updatedExisting + newAssets

        val prefsBefore = context.dataStore.data.first()
        val assetsBefore = ServiceLocator.watchlistStore.rawValue(prefsBefore)
        val cashBefore = ServiceLocator.settingsStore.rawInvestableCash(prefsBefore)
        val newCashValue = preview.totalCash.coerceAtLeast(0.0)

        try {
            context.dataStore.edit { prefs ->
                ServiceLocator.watchlistStore.writeRaw(prefs, Http.json.encodeToString(finalList))
                if (applyCashTotal) {
                    ServiceLocator.settingsStore.writeRawInvestableCash(prefs, newCashValue)
                }
            }
            CommitResult.Success(preview.rows.size, ImportUndo(assetsBefore, cashBefore))
        } catch (e: IOException) {
            // DataStore.edit leaves the prior file untouched when the write itself fails, so nothing
            // actually changed here.
            CommitResult.Failed("Couldn't save the import: ${e.message ?: "unknown error"}.")
        }
    }

    /** Restores exactly what [commitImport] read immediately beforehand — including a value that was
     *  already corrupt then. Does not go through decode/encode, so it cannot "fix" or lose that
     *  corruption; it puts back the same bytes/value. */
    suspend fun undo(context: Context, undo: ImportUndo) = withContext(Dispatchers.IO) {
        context.dataStore.edit { prefs ->
            ServiceLocator.watchlistStore.writeRaw(prefs, undo.assetsJsonBefore)
            ServiceLocator.settingsStore.writeRawInvestableCash(prefs, undo.investableCashBefore)
        }
        Unit
    }
}
