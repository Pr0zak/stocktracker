package com.stocktracker.app.ui.report

import com.stocktracker.app.data.remote.Report
import com.stocktracker.app.data.remote.ReportSummary
import java.util.Locale
import kotlin.math.abs

/**
 * The report's words, kept pure so every sentence the user reads can be tested.
 *
 * Plain language throughout (the user asked for it): "Your week", "the S&P", "▲ 1.28%" — no
 * "alpha", no "breadth", no basis points. A missing number is "—", never "0.00%".
 */
object ReportRead {

    /** "▲ 1.28%" / "▼ 0.33%", or "—" when unknown. The arrow carries direction, not colour alone. */
    fun pct(p: Double?): String =
        if (p == null || !p.isFinite()) "—" else (if (p >= 0) "▲ " else "▼ ") + String.format(Locale.US, "%.2f%%", abs(p))

    /** "+$245.04" / "−$37.75", or "—". */
    fun usd(v: Double?): String {
        if (v == null || !v.isFinite()) return "—"
        val sign = if (v >= 0) "+" else "−"
        return sign + "$" + String.format(Locale.US, "%,.2f", abs(v))
    }

    /** "$19,438.17", or "—". */
    fun money(v: Double?): String =
        if (v == null || !v.isFinite()) "—" else "$" + String.format(Locale.US, "%,.2f", v)

    /**
     * "▲ 0.3 pts vs S&P" / "▼ 1.6 pts behind S&P" — a difference of two returns, in points, never a
     * percent of a percent. Under half a tenth either way it is "Even with the S&P": "▼ 0.0 pts
     * behind" claims a direction the number does not have.
     */
    fun vsSp(pts: Double?): String? {
        if (pts == null || !pts.isFinite()) return null
        if (abs(pts) < 0.05) return "Even with the S&P"
        val r = String.format(Locale.US, "%.1f", abs(pts))
        return if (pts >= 0) "▲ $r pts vs S&P" else "▼ $r pts behind S&P"
    }

    /**
     * The next report of [kind] that is not out yet: the last trading day of this week (or month),
     * or of the next one when this one's report already exists or its last session has passed.
     */
    fun nextReportEnd(
        kind: String,
        today: java.time.LocalDate,
        isHoliday: (java.time.LocalDate) -> Boolean,
        existingIds: Set<String>,
    ): java.time.LocalDate {
        fun trading(d: java.time.LocalDate) =
            d.dayOfWeek != java.time.DayOfWeek.SATURDAY && d.dayOfWeek != java.time.DayOfWeek.SUNDAY && !isHoliday(d)
        var anchor = today
        repeat(24) {
            val first = if (isMonth(kind)) anchor.withDayOfMonth(1) else anchor.with(java.time.DayOfWeek.MONDAY)
            val last = if (isMonth(kind)) first.withDayOfMonth(first.lengthOfMonth()) else first.plusDays(4)
            var end = last
            while (end >= first && !trading(end)) end = end.minusDays(1)
            if (end >= first && end >= today && "$kind-$end" !in existingIds) return end
            anchor = if (isMonth(kind)) first.plusMonths(1) else first.plusWeeks(1)
        }
        return anchor
    }

    /** "Thu Sep 24" from an ISO date; the input unchanged if it does not parse. */
    fun shortDate(iso: String?): String? = iso?.let {
        runCatching {
            java.time.LocalDate.parse(it).format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d", Locale.US))
        }.getOrDefault(it)
    }

    /** The Daily Pick tile's line: how many days it ran, and why nothing was picked. */
    fun pickLine(runs: Int?, sessions: Int?, reason: String?): String {
        val days = when {
            runs != null && sessions != null && runs < sessions -> "Ran $runs of $sessions days."
            sessions != null -> "0 of $sessions days."
            else -> ""
        }
        return listOf(days, reason.orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * Whether a newly seen report is still news. Judged by when its PERIOD ended, not when it was
     * built: a month rebuilt weeks later (a catch-up, a first install) is history, and announcing it
     * would read as this month's report.
     */
    fun isNews(endIso: String?, today: java.time.LocalDate, kind: String?): Boolean {
        val end = runCatching { java.time.LocalDate.parse(endIso) }.getOrNull() ?: return false
        val days = java.time.temporal.ChronoUnit.DAYS.between(end, today)
        return days in 0..(if (isMonth(kind)) 5 else 4)
    }

    fun isMonth(kind: String?): Boolean = kind == "month"

    /** "Week in review" / "Month in review". */
    fun title(kind: String?): String = if (isMonth(kind)) "Month in review" else "Week in review"

    /** "WEEK OF SEP 21" or "SEPTEMBER 2026" — the hero card's overline. */
    fun overline(kind: String?, label: String?): String {
        val l = label?.trim().orEmpty()
        if (l.isEmpty()) return title(kind).uppercase(Locale.US)
        if (isMonth(kind)) return l.uppercase(Locale.US)
        return "WEEK OF " + l.substringBefore(" –").trim().uppercase(Locale.US)
    }

    /** "Fri Sep 25 close". */
    fun closeLabel(endIso: String?): String? = runCatching {
        val d = java.time.LocalDate.parse(endIso)
        d.format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)) + " close"
    }.getOrNull()

    /**
     * The notification's title. Leads with the user's own result when the phone could price it — the
     * report exists so the user can see all three, and theirs is the one they care about first.
     */
    fun notificationTitle(kind: String?, label: String?, youPct: Double?, spPct: Double?): String {
        val sp = "S&P ${pct(spPct)}"
        val lead = if (isMonth(kind)) (label?.substringBefore(" ")?.ifBlank { null } ?: "Your month") else "Your week"
        return if (youPct != null && youPct.isFinite()) "$lead: you ${pct(youPct)}, $sp"
        else if (isMonth(kind)) "$lead in review: $sp" else "Week in review: $sp"
    }

    /** The notification's body: the sandbox in one clause, then the report's own headline. */
    fun notificationBody(s: ReportSummary): String {
        val ai = s.sandboxPct?.takeIf { it.isFinite() }?.let { "AI sandbox ${pct(it)}. " }.orEmpty()
        return ai + (s.headline ?: "Tap to read the report.")
    }

    /** A plain one-line summary a user might paste into a message. No holdings detail beyond the %. */
    fun shareText(r: Report, youPct: Double?): String = buildString {
        append(title(r.kind)).append(" · ").append(r.label.orEmpty()).append('\n')
        r.headline?.let { append(it).append('\n') }
        append("S&P 500 ").append(pct(r.sp500Pct))
        youPct?.let { append(" · You ").append(pct(it)) }
        r.sandbox?.main?.changePct?.let { append(" · AI sandbox ").append(pct(it)) }
        val s = r.market?.stocks
        s?.best?.firstOrNull()?.let { b ->
            append('\n').append("Top stock: ").append(b.symbol).append(' ').append(pct(b.pct))
            s.worst.firstOrNull()?.let { w -> append(" · Bottom: ").append(w.symbol).append(' ').append(pct(w.pct)) }
        }
        val e = r.market?.etfs
        e?.best?.firstOrNull()?.let { b ->
            append('\n').append("Top fund: ").append(b.symbol).append(" (").append(b.name).append(") ").append(pct(b.pct))
            e.worst.firstOrNull()?.let { w -> append(" · Bottom: ").append(w.symbol).append(' ').append(pct(w.pct)) }
        }
    }

    /** "Sold at a loss after 11 days" for a flagged sale; null otherwise. */
    fun fillFlag(flag: String?, heldDays: Int?): String? =
        if (flag == "quick_loss") "Sold at a loss after ${heldDays ?: "a few"} days" else null

    /** "38%" of big companies rose — the share as a whole percent, or null when nothing was measured. */
    fun upShare(up: Int?, measured: Int?): Int? =
        if (up == null || measured == null || measured <= 0) null else Math.round(up * 100.0 / measured).toInt()
}
