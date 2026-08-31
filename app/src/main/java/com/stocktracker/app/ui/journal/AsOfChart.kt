package com.stocktracker.app.ui.journal

import com.stocktracker.app.data.model.PricePoint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The chart as it looked on the day a verdict was given.
 *
 * NOT called "replay". That word is already taken in this codebase for the backend's mechanical
 * plan replay (`POST /journal/replay`, `JournalReplay`), which measures what the plan WOULD have
 * done — a different thing from showing what you WERE LOOKING AT. Two meanings under one name in
 * one screen is how a reader ends up believing the picture is the measurement.
 *
 * The scoring half of the original idea is deliberately absent. Grading your own calls against the
 * engine's from a dozen journal entries is under this repo's own written floor for a rate
 * (`PairedStat` puts it at 20-30), and the sample is not blind: you know the symbol and you know the
 * era. `JournalComparison` already pairs your real R against the mechanical R over a shared
 * population, which is the honest version of that question.
 */

/** New York, because the bar dates are US session dates and the phone's zone is not. */
private val MARKET_ZONE: ZoneId = ZoneId.of("America/New_York")

/**
 * The index of the last bar at or before [dateIso], or null when the series cannot answer.
 *
 * Null has two distinct causes and both matter: the date is unparseable, or it falls before the
 * first bar we hold. The second is the one worth surfacing — "we only have history back to 2024" is
 * a different statement from "nothing traded", and truncating to an empty chart would say the
 * second.
 *
 * A verdict given on a weekend or a holiday resolves to the previous trading day, which is the last
 * chart the user could actually have been looking at.
 */
fun asOfIndex(points: List<PricePoint>, dateIso: String): Int? {
    if (points.isEmpty()) return null
    val target = runCatching { LocalDate.parse(dateIso) }.getOrNull() ?: return null

    fun dayOf(p: PricePoint): LocalDate =
        Instant.ofEpochMilli(p.epochMs).atZone(MARKET_ZONE).toLocalDate()

    if (dayOf(points.first()).isAfter(target)) return null  // the verdict predates our history

    var idx = -1
    for (i in points.indices) {
        if (dayOf(points[i]).isAfter(target)) break
        idx = i
    }
    return idx.takeIf { it >= 0 }
}

/**
 * The bars to draw for a cursor position.
 *
 * The series is TRUNCATED rather than the renderer being asked to stop early. Clamping an index
 * inside the chart would leave the future bars in scope for the y-scale, the volume maximum, the
 * high/low markers, the sub-pane autoscales, the x-axis ticks and the scrub — six separate places
 * that would each have to be got right and stay right. A shorter list cannot leak what it does not
 * contain.
 */
fun barsThrough(points: List<PricePoint>, index: Int): List<PricePoint> =
    if (points.isEmpty()) emptyList() else points.take((index + 1).coerceIn(1, points.size))

/**
 * How far a cursor can be stepped forward from [start], capped at [maxDays] trading bars.
 *
 * Capped because the question this answers is "what happened next", and next has a horizon. An
 * uncapped stepper walks to today and quietly becomes the ordinary chart, at which point the screen
 * is no longer showing anything the detail screen does not.
 */
fun stepLimit(points: List<PricePoint>, start: Int, maxDays: Int = 60): Int =
    (start + maxDays).coerceAtMost(points.lastIndex).coerceAtLeast(start)
