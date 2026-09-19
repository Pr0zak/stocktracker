package com.stocktracker.shared

import kotlinx.serialization.Serializable

/**
 * Snapshot the portfolio widget (and now the Wear tile/complication, WGT-7) renders: total value +
 * day change across all held positions.
 *
 * Moved here from `com.stocktracker.app.widget.PortfolioWidgetState` -- no Android dependency, so
 * the relocation costs the phone app nothing. `:app` keeps
 * `typealias PortfolioSummary = com.stocktracker.shared.PortfolioSummary` at the original location.
 */
@Serializable
data class PortfolioSummary(
    val totalValue: Double = 0.0,
    val dayChange: Double = 0.0,
    val dayChangePercent: Double = 0.0,
    val holdingCount: Int = 0,
    /** Holdings excluded because no quote could be fetched. Non-zero means [totalValue] covers only
     *  part of the portfolio — the widget has to say so rather than show a confidently short number. */
    val missingCount: Int = 0,
) {
    val isPartial: Boolean get() = missingCount > 0
    val isUp: Boolean get() = dayChange >= 0.0
}
