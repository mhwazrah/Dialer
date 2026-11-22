package com.mhw.phone.helpers

import android.provider.CallLog.Calls
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * One call-log row as the statistics see it. [key] is the person: a contact id, else the number's last nine digits.
 * The calendar fields are worked out by the caller in the phone's time zone: [hour] 0–23, [weekday] as
 * Calendar.DAY_OF_WEEK (1 = Sunday), [day] the local epoch day and [month] as year * 12 + month (0-based).
 */
data class StatCall(
    val key: String,
    val name: String,
    val type: Int,
    val seconds: Int,
    val hour: Int,
    val weekday: Int = 1,
    val day: Long = 0,
    val month: Int = 0,
)

data class TopCaller(val name: String, val calls: Int, val seconds: Int)

data class CallStats(
    val total: Int,
    val incoming: Int,
    val outgoing: Int,
    val missed: Int,
    val rejected: Int,
    val talkSeconds: Int,
    val incomingSeconds: Int,
    val outgoingSeconds: Int,
    val averageSeconds: Int,
    val longestSeconds: Int,
    val people: Int,
    /** Answered share of incoming calls, 0–100; null without incoming calls. */
    val answerRate: Int?,
    val topCallers: List<TopCaller>,
    val busiestHour: Int?,
    /** Busiest Calendar.DAY_OF_WEEK. */
    val busiestWeekday: Int?,
    /** Calls per hour of the day, 24 entries. */
    val byHour: List<Int>,
    /** Calls per weekday, indexed by Calendar.DAY_OF_WEEK - 1 (Sunday first). */
    val byWeekday: List<Int>,
)

private const val TOP_CALLERS = 5
private const val HOURS = 24
private const val WEEKDAYS = 7

/** What a call counts as. Every counted call lands in exactly one, so the four add up to the total. */
private enum class Kind { INCOMING, OUTGOING, MISSED, REJECTED }

private fun kindOf(type: Int) = when (type) {
    Calls.INCOMING_TYPE, Calls.ANSWERED_EXTERNALLY_TYPE -> Kind.INCOMING
    Calls.OUTGOING_TYPE -> Kind.OUTGOING
    Calls.REJECTED_TYPE -> Kind.REJECTED
    else -> Kind.MISSED // missed, voicemail and anything unknown
}

/** Blocked calls are left out entirely; talk time counts answered calls only. */
fun callStats(calls: List<StatCall>): CallStats {
    val counted = calls.filter { it.type != Calls.BLOCKED_TYPE }
    val kinds = counted.groupingBy { kindOf(it.type) }.eachCount()
    val talked = counted.filter { kindOf(it.type).let { k -> k == Kind.INCOMING || k == Kind.OUTGOING } && it.seconds > 0 }
    val talkSeconds = talked.sumOf { it.seconds }
    val incoming = kinds[Kind.INCOMING] ?: 0
    val byHour = counted.histogram(HOURS) { it.hour }
    val byWeekday = counted.histogram(WEEKDAYS) { it.weekday - 1 }
    return CallStats(
        total = counted.size,
        incoming = incoming,
        outgoing = kinds[Kind.OUTGOING] ?: 0,
        missed = kinds[Kind.MISSED] ?: 0,
        rejected = kinds[Kind.REJECTED] ?: 0,
        talkSeconds = talkSeconds,
        incomingSeconds = talked.filter { kindOf(it.type) == Kind.INCOMING }.sumOf { it.seconds },
        outgoingSeconds = talked.filter { kindOf(it.type) == Kind.OUTGOING }.sumOf { it.seconds },
        averageSeconds = if (talked.isEmpty()) 0 else talkSeconds / talked.size,
        longestSeconds = talked.maxOfOrNull { it.seconds } ?: 0,
        people = counted.distinctBy { it.key }.size,
        answerRate = answerRate(kinds),
        topCallers = topCallers(counted),
        busiestHour = byHour.busiestIndex(),
        busiestWeekday = byWeekday.busiestIndex()?.plus(1),
        byHour = byHour,
        byWeekday = byWeekday,
    )
}

private fun List<StatCall>.histogram(size: Int, slot: (StatCall) -> Int) =
    MutableList(size) { 0 }.apply { this@histogram.forEach { this[slot(it).coerceIn(0, size - 1)]++ } }

private fun answerRate(kinds: Map<Kind, Int>): Int? {
    val answered = kinds[Kind.INCOMING] ?: 0
    val all = answered + (kinds[Kind.MISSED] ?: 0) + (kinds[Kind.REJECTED] ?: 0)
    return if (all == 0) null else (answered * 100.0 / all).roundToInt()
}

private fun topCallers(counted: List<StatCall>) = counted.groupBy { it.key }.values
    .map { personCalls -> TopCaller(personCalls.first().name, personCalls.size, personCalls.sumOf { it.seconds }) }
    .sortedWith(compareByDescending<TopCaller> { it.calls }.thenByDescending { it.seconds })
    .take(TOP_CALLERS)

private fun List<Int>.busiestIndex(): Int? = indices.maxByOrNull { this[it] }?.takeIf { this[it] > 0 }

/** Calls per local day for the [days] days ending on [today], oldest first; days without calls are 0. */
fun dailySeries(calls: List<StatCall>, today: Long, days: Int): List<Int> {
    val counts = calls.filter { it.type != Calls.BLOCKED_TYPE }.groupingBy { it.day }.eachCount()
    return (days - 1 downTo 0).map { counts[today - it] ?: 0 }
}

/** Calls per month for the [months] months ending with [thisMonth] (year * 12 + month), oldest first. */
fun monthlySeries(calls: List<StatCall>, thisMonth: Int, months: Int): List<Int> {
    val counts = calls.filter { it.type != Calls.BLOCKED_TYPE }.groupingBy { it.month }.eachCount()
    return (months - 1 downTo 0).map { counts[thisMonth - it] ?: 0 }
}

/** Change from [previous] to [current] in percent, or null when there is nothing to compare against. */
fun percentChange(current: Int, previous: Int): Int? =
    if (previous <= 0) null else ((current - previous) * 100.0 / previous).roundToInt()

/** Everything the dashboard shows for one period: [periodDays] 0 means all time, charted by month. */
data class Dashboard(
    val stats: CallStats,
    /** The period just before, for the change shown under talk time; null for all time. */
    val previous: CallStats?,
    /** Calls per day (oldest first) for a period, per month over the last year for all time. */
    val series: List<Int>,
    val periodDays: Int,
    val today: LocalDate,
    val callsPerDay: Double,
)

private const val ALL_TIME_MONTHS = 12

/** [calls] cover twice the period (or everything for all time), so the previous period can be compared. */
fun buildDashboard(calls: List<StatCall>, periodDays: Int, today: LocalDate): Dashboard {
    val todayIndex = today.toEpochDay()
    val current = if (periodDays == 0) calls else calls.filter { it.day > todayIndex - periodDays }
    val previous = if (periodDays == 0) null else calls.filter { it.day in (todayIndex - 2 * periodDays + 1)..(todayIndex - periodDays) }
    val stats = callStats(current)
    val series = if (periodDays == 0) {
        monthlySeries(current, today.year * 12 + today.monthValue - 1, ALL_TIME_MONTHS)
    } else {
        dailySeries(current, todayIndex, periodDays)
    }
    val firstDay = current.minOfOrNull { it.day } ?: todayIndex
    val days = if (periodDays == 0) todayIndex - firstDay + 1 else periodDays.toLong()
    return Dashboard(stats, previous?.let(::callStats), series, periodDays, today, stats.total.toDouble() / days.coerceAtLeast(1))
}
