package com.stocktracker.app.util

/**
 * Parsing for hand-typed money and quantity fields.
 *
 * `String.toDoubleOrNull()` is locale-independent and unforgiving: "1,000", "0,5" (the decimal
 * separator across most of Europe, which a `KeyboardType.Decimal` IME will happily produce), "$150"
 * and a stray trailing space all return null. In the position editor that null was written straight
 * to the store, so a comma silently erased shares AND cost basis — hand-entered data that exists
 * nowhere else in the app.
 *
 * The distinction that matters is EMPTY vs UNPARSEABLE. Empty is a deliberate "clear this"; garbage
 * is a mistake, and the caller must be able to refuse it rather than persist a null.
 */
object NumberInput {

    /** Result of reading one field. */
    sealed interface Parsed {
        /** The field was blank — the user is clearing it, and null is the correct value to store. */
        data object Empty : Parsed

        /** A usable number. */
        data class Value(val number: Double) : Parsed

        /** Non-empty but not a number — never write this, ask the user to fix it. */
        data object Invalid : Parsed
    }

    fun parse(raw: String): Parsed {
        val t = raw.trim()
            .removePrefix("$").removePrefix("£").removePrefix("€")
            .replace(" ", "")      // non-breaking space, common from IME autocorrect
            .replace(" ", "")
            .replace("_", "")
        if (t.isEmpty()) return Parsed.Empty

        val normalized = when {
            // "1.234,56" — European: dot groups, comma decimals.
            t.contains('.') && t.contains(',') && t.lastIndexOf(',') > t.lastIndexOf('.') ->
                t.replace(".", "").replace(',', '.')
            // "1,234.56" — Anglo: comma groups, dot decimals.
            t.contains('.') && t.contains(',') -> t.replace(",", "")
            // A single comma that ISN'T a thousands group ("0,5") is a decimal separator.
            t.count { it == ',' } == 1 && t.substringAfter(',').length != 3 -> t.replace(',', '.')
            // Anything else with commas is grouping: "1,000", "1,234,567".
            else -> t.replace(",", "")
        }
        val v = normalized.toDoubleOrNull()
        return when {
            v == null || v.isNaN() || v.isInfinite() -> Parsed.Invalid
            else -> Parsed.Value(v)
        }
    }

    /** Convenience for read-only display paths: the number, or null for empty AND invalid alike. */
    fun parseOrNull(raw: String): Double? = (parse(raw) as? Parsed.Value)?.number

    /** True when the field holds something the user clearly meant as a number but we can't read. */
    fun isInvalid(raw: String): Boolean = parse(raw) is Parsed.Invalid
}
