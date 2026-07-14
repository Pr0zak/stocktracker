package com.stocktracker.app.util

import com.stocktracker.app.data.model.ChartRange
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME_ONLY = DateTimeFormatter.ofPattern("h:mm a")
private val DATE_TIME = DateTimeFormatter.ofPattern("MMM d, h:mm a")
private val DATE_ONLY = DateTimeFormatter.ofPattern("MMM d, yyyy")

/** Formats a chart point's timestamp for the scrub readout, in the device's timezone. */
fun formatChartTimestamp(epochMs: Long, range: ChartRange, zone: ZoneId = ZoneId.systemDefault()): String {
    val zdt = Instant.ofEpochMilli(epochMs).atZone(zone)
    return when (range) {
        ChartRange.DAY -> zdt.format(TIME_ONLY)
        ChartRange.WEEK, ChartRange.MONTH, ChartRange.QUARTER -> zdt.format(DATE_TIME)
        ChartRange.YEAR, ChartRange.ALL -> zdt.format(DATE_ONLY)
    }
}
