package com.stocktracker.app.notify

import com.stocktracker.app.data.model.CallPosition
import com.stocktracker.app.data.model.PositionSide
import kotlin.math.roundToInt

/**
 * One exit nudge about a tracked option position (OC-4): a short title, a plain-language message, and a
 * STABLE dedupe key (`positionId:TYPE`) so the periodic worker fires it once per crossing.
 */
data class CallExitAlert(
    val positionId: String,
    val type: Type,
    val title: String,
    val message: String,
) {
    enum class Type { TAKE_PROFIT, STOP, TIME_STOP, EXPIRY }

    /** Stable across worker runs — same position + same rule → same key → notify-once. */
    val key: String get() = keyFor(positionId, type)

    companion object {
        fun keyFor(positionId: String, type: Type): String = "$positionId:${type.name}"
    }
}

/**
 * PURE, unit-testable decision logic for OC-4 — tells the user WHEN to close (or handle expiry) a
 * manually-tracked option position from OC-3. No I/O and no Android deps, so it can be tested directly.
 *
 * This is DECISION SUPPORT, not investment advice, and it always frames closing the option over letting
 * it settle automatically on Fidelity. LLM-free — unaffected by the AI kill-switch.
 *
 * SHORT POSITIONS (MONEY-3) get the DTE-based rules (time-stop, expiry) with side-appropriate wording,
 * but NOT the P/L-based take-profit/stop rules. [DEFAULT_TAKE_PROFIT_PCT]/[DEFAULT_STOP_PCT] apply
 * unconditionally when a position doesn't set its own — there is no "unset" to fall through to null —
 * and computing them against a short's premium with the long-side sign convention would fire a
 * take-profit alert exactly when the seller is losing money. [com.stocktracker.app.data.model.RiskMultiple]
 * draws the identical line for the same reason (see its class doc): a short is not silently
 * sign-flipped, it is left out, until this app tracks a real stop/target convention for a credit trade.
 */
object CallExitRules {

    const val DEFAULT_TAKE_PROFIT_PCT = 80.0
    const val DEFAULT_STOP_PCT = 50.0

    /** Theta accelerates inside ~3 weeks — nudge to sell or roll. */
    const val TIME_STOP_DTE = 21

    /** Final-days window — sell-to-close vs. auto-exercise / worthless expiry. */
    const val EXPIRY_DTE = 3

    /**
     * Evaluate every exit rule for one position and return the alerts that currently apply.
     *
     * @param position                the tracked call.
     * @param currentPremiumPerShare  live premium/share from /option_quote; null (unpriced / market
     *                                closed) skips the P/L rules but the DTE rules still run.
     * @param spot                    underlying's last price; used only to INFER moneyness when
     *                                [inTheMoney] is null.
     * @param inTheMoney              server (or caller-inferred) ITM flag; null = unknown.
     * @param nowEpochMs              "now" for DTE math — injected so tests are deterministic.
     */
    fun evaluate(
        position: CallPosition,
        currentPremiumPerShare: Double?,
        spot: Double?,
        inTheMoney: Boolean?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): List<CallExitAlert> {
        val alerts = mutableListOf<CallExitAlert>()
        val isPut = position.type.equals("put", ignoreCase = true)
        val isShort = position.side == PositionSide.SHORT
        val label = "${position.symbol.uppercase()} \$${fmtStrike(position.strike)}${if (isPut) "P" else "C"}"

        // Ceiling — see CallsViewModel.dte. Flooring fired the expiry warning a day early.
        val dte = kotlin.math.ceil(
            (position.expiryTs * 1000L - nowEpochMs) / 86_400_000.0,
        ).toInt().coerceAtLeast(0)

        // --- P/L rules (need a live premium). LONG ONLY — see the class doc for why a SHORT is left
        //     out here rather than sign-flipped: DEFAULT_TAKE_PROFIT_PCT/DEFAULT_STOP_PCT apply even
        //     when the position never set its own, and there is no side-aware convention for those
        //     defaults yet to flip to. ---
        val plPct = currentPremiumPerShare?.takeIf { !isShort }?.let {
            if (position.fillPrice != 0.0) (it - position.fillPrice) / position.fillPrice * 100.0 else null
        }
        if (plPct != null) {
            val takeProfit = position.takeProfitPct ?: DEFAULT_TAKE_PROFIT_PCT
            val stop = position.stopPct ?: DEFAULT_STOP_PCT
            if (plPct >= takeProfit) {
                val x = plPct.roundToInt()
                alerts += CallExitAlert(
                    position.id, CallExitAlert.Type.TAKE_PROFIT,
                    "$label at your take-profit",
                    "Your $label is +$x% — at your take-profit. Consider selling to close. Not investment advice.",
                )
            } else if (plPct <= -stop) {
                val x = (-plPct).roundToInt()
                alerts += CallExitAlert(
                    position.id, CallExitAlert.Type.STOP,
                    "$label at your stop",
                    "Your $label is −$x% — at your stop. Consider selling to close. Not investment advice.",
                )
            }
        }

        // --- Time rules (need only DTE; run for both sides). Expiry supersedes the softer time-stop
        //     so ≤3 DTE yields ONE time alert, not two. ---
        if (dte <= EXPIRY_DTE) {
            val itm = inTheMoney ?: spot?.let { s -> if (isPut) s <= position.strike else s >= position.strike }
            val message = when {
                isShort && itm == true -> "Your $label expires in ${dte}d and is in-the-money — you may be " +
                    "assigned. Consider buying to close if you don't want that. Not investment advice."
                isShort && itm == false -> "Your $label expires in ${dte}d out-of-the-money — likely to expire " +
                    "worthless, which means you keep the full premium. Not investment advice."
                isShort -> "Your $label expires in ${dte}d — buy to close or let it ride to expiry. Not investment advice."
                itm == true -> "Your $label expires in ${dte}d and is in-the-money — sell to capture value, " +
                    "or it auto-exercises on Fidelity. Not investment advice."
                itm == false -> "Your $label expires in ${dte}d out-of-the-money — likely to expire worthless. " +
                    "Consider closing. Not investment advice."
                else -> "Your $label expires in ${dte}d — sell to close or roll before expiry. Not investment advice."
            }
            alerts += CallExitAlert(position.id, CallExitAlert.Type.EXPIRY, "$label expires in ${dte}d", message)
        } else if (dte <= TIME_STOP_DTE) {
            val verb = if (isShort) "Buy to close or let it ride" else "Sell or roll"
            alerts += CallExitAlert(
                position.id, CallExitAlert.Type.TIME_STOP,
                "$label — ${dte}d left",
                "Your $label has ${dte}d left — time decay speeds up now. $verb? Not investment advice.",
            )
        }

        return alerts
    }

    /** 100.0 → "100", 12.5 → "12.5" (no trailing ".0" on whole strikes). */
    private fun fmtStrike(strike: Double): String =
        if (strike % 1.0 == 0.0) strike.toInt().toString() else strike.toString()
}
