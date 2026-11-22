package com.mhw.phone.activities

import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.beGoneIf
import com.goodwy.commons.extensions.beVisibleIf
import com.goodwy.commons.extensions.formatSecondsToShortTimeString
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.updateTextColors
import com.mhw.phone.R
import com.mhw.phone.databinding.ActivityStatisticsBinding
import com.mhw.phone.databinding.ItemStatLegendBinding
import com.mhw.phone.databinding.ItemStatPersonBinding
import com.mhw.phone.databinding.ItemStatTileBinding
import com.mhw.phone.helpers.CallStats
import com.mhw.phone.helpers.Dashboard
import com.mhw.phone.helpers.percentChange
import java.text.DateFormatSymbols
import java.text.NumberFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

// The statistics screen's cards, filled from one Dashboard. Kept apart from the activity, which only loads the data.

private const val DAY_LABEL_EVERY = 5
private const val HOUR_LABEL_EVERY = 6

internal fun StatisticsActivity.showDashboard(binding: ActivityStatisticsBinding, dashboard: Dashboard) {
    val empty = dashboard.stats.total == 0
    binding.statsEmpty.beVisibleIf(empty)
    binding.statsContent.beGoneIf(empty)
    if (empty) return
    showSummary(binding, dashboard)
    showActivity(binding, dashboard)
    showMix(binding, dashboard.stats)
    showRhythm(binding, dashboard.stats)
    showPeople(binding, dashboard.stats)
    // The new rows take the theme's text colour; the trend keeps its accent, so it is set after
    updateTextColors(binding.statsHolder)
    binding.statsTrend.setTextColor(getProperPrimaryColor())
}

private fun StatisticsActivity.showSummary(binding: ActivityStatisticsBinding, dashboard: Dashboard) {
    val stats = dashboard.stats
    val numbers = NumberFormat.getInstance()
    binding.statsTalkTime.text = formatSecondsToShortTimeString(stats.talkSeconds)
    val change = dashboard.previous?.let { percentChange(stats.talkSeconds, it.talkSeconds) }
    binding.statsTrend.beVisibleIf(change != null)
    if (change != null) {
        val arrow = if (change >= 0) "▲" else "▼"
        val amount = NumberFormat.getPercentInstance().format(abs(change) / 100.0)
        binding.statsTrend.text = getString(R.string.stats_vs_previous, "$arrow $amount")
    }
    binding.statsTalkSplit.text = getString(
        R.string.stats_talk_split,
        formatSecondsToShortTimeString(stats.incomingSeconds),
        formatSecondsToShortTimeString(stats.outgoingSeconds)
    )
    val percent = NumberFormat.getPercentInstance()
    tile(binding.statsAnswerRate, stats.answerRate?.let { percent.format(it / 100.0) } ?: "–", R.string.stats_answer_rate)
    tile(binding.statsLongest, formatSecondsToShortTimeString(stats.longestSeconds), R.string.stats_longest_call)
    val perDay = NumberFormat.getInstance().apply { maximumFractionDigits = 1 }.format(dashboard.callsPerDay)
    tile(binding.statsPerDay, perDay, R.string.stats_calls_per_day)
    tile(binding.statsPeople, numbers.format(stats.people), R.string.stats_people)
}

private fun tile(tile: ItemStatTileBinding, value: String, label: Int) {
    tile.statValue.text = value
    tile.statLabel.setText(label)
}

/** Calls per day for 7 or 30 days, per month for all time. */
private fun StatisticsActivity.showActivity(binding: ActivityStatisticsBinding, dashboard: Dashboard) {
    val series = dashboard.series
    val labels = if (dashboard.periodDays == 0) {
        val months = DateFormatSymbols.getInstance().shortMonths
        series.indices.map { index ->
            val month = dashboard.today.minusMonths((series.size - 1 - index).toLong()).monthValue - 1
            if ((series.size - 1 - index) % 2 == 0) months[month] else ""
        }
    } else {
        val weekdays = DateFormatSymbols.getInstance().shortWeekdays
        series.indices.map { index ->
            val date = dashboard.today.minusDays((series.size - 1 - index).toLong())
            when {
                dashboard.periodDays <= 7 -> weekdays[date.dayOfWeek.value % 7 + 1]
                (series.size - 1 - index) % DAY_LABEL_EVERY == 0 -> NumberFormat.getInstance().format(date.dayOfMonth)
                else -> ""
            }
        }
    }
    val calls = resources.getQuantityString(R.plurals.stats_calls, dashboard.stats.total, dashboard.stats.total)
    val perDay = NumberFormat.getInstance().apply { maximumFractionDigits = 1 }.format(dashboard.callsPerDay)
    binding.statsActivitySummary.text = getString(R.string.stats_two_parts, calls, getString(R.string.stats_per_day_average, perDay))
    binding.statsActivityChart.setData(series, labels, getProperPrimaryColor(), getProperTextColor())
    binding.statsActivityChart.contentDescription = binding.statsActivitySummary.text
}

private fun StatisticsActivity.showMix(binding: ActivityStatisticsBinding, stats: CallStats) {
    val parts = listOf(
        Triple(R.string.incoming, stats.incoming, getProperPrimaryColor()),
        Triple(R.string.outgoing, stats.outgoing, ContextCompat.getColor(this, R.color.green_call)),
        Triple(R.string.missed, stats.missed, ContextCompat.getColor(this, R.color.red_call)),
        Triple(R.string.rejected, stats.rejected, ContextCompat.getColor(this, R.color.stats_rejected)),
    )
    binding.statsMixBar.setParts(parts.map { it.second to it.third })
    binding.statsMixLegend.removeAllViews()
    val percent = NumberFormat.getPercentInstance()
    val numbers = NumberFormat.getInstance()
    parts.forEach { (label, count, color) ->
        val row = ItemStatLegendBinding.inflate(layoutInflater, binding.statsMixLegend, false)
        row.legendDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        row.legendLabel.setText(label)
        row.legendValue.text = getString(R.string.stats_two_parts, numbers.format(count), percent.format(count.toDouble() / stats.total))
        binding.statsMixLegend.addView(row.root)
    }
    binding.statsMixBar.contentDescription = parts.joinToString { getString(it.first) + " " + numbers.format(it.second) }
    binding.statsAverage.text = getString(R.string.stats_average_call, formatSecondsToShortTimeString(stats.averageSeconds))
}

/** When calls happen: by hour of the day and by weekday (in the locale's week order). */
private fun StatisticsActivity.showRhythm(binding: ActivityStatisticsBinding, stats: CallStats) {
    val primary = getProperPrimaryColor()
    val text = getProperTextColor()
    val hourLabels = (0 until 24).map { if (it % HOUR_LABEL_EVERY == 0) hourLabel(it) else "" }
    binding.statsHourChart.setData(stats.byHour, hourLabels, primary, text)
    binding.statsBusiestHour.beVisibleIf(stats.busiestHour != null)
    stats.busiestHour?.let { binding.statsBusiestHour.text = getString(R.string.stats_busiest_hour, hourLabel(it)) }
    binding.statsHourChart.contentDescription = binding.statsBusiestHour.text

    val firstDay = Calendar.getInstance().firstDayOfWeek
    val order = (0 until 7).map { (firstDay - 1 + it) % 7 + 1 } // Calendar.DAY_OF_WEEK values
    val short = DateFormatSymbols.getInstance().shortWeekdays
    binding.statsWeekdayChart.setData(order.map { stats.byWeekday[it - 1] }, order.map { short[it] }, primary, text)
    binding.statsBusiestDay.beVisibleIf(stats.busiestWeekday != null)
    stats.busiestWeekday?.let {
        binding.statsBusiestDay.text = getString(R.string.stats_busiest_day, DateFormatSymbols.getInstance().weekdays[it])
    }
    binding.statsWeekdayChart.contentDescription = binding.statsBusiestDay.text
}

// In the app's 12 / 24-hour setting, like the times in Recents
private fun StatisticsActivity.hourLabel(hour: Int): String {
    val pattern = if (baseConfig.use24HourFormat) "HH:mm" else "h a"
    val formatter = DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).withDecimalStyle(DecimalStyle.ofDefaultLocale())
    return LocalTime.of(hour, 0).format(formatter)
}

/** The most called people, each with a bar showing their calls against the top person's. */
private fun StatisticsActivity.showPeople(binding: ActivityStatisticsBinding, stats: CallStats) {
    binding.statsTopList.removeAllViews()
    val most = stats.topCallers.maxOfOrNull { it.calls }?.coerceAtLeast(1) ?: 1
    val primary = getProperPrimaryColor()
    stats.topCallers.forEach { caller ->
        val row = ItemStatPersonBinding.inflate(layoutInflater, binding.statsTopList, false)
        row.personName.text = caller.name
        val calls = resources.getQuantityString(R.plurals.stats_calls, caller.calls, caller.calls)
        row.personDetail.text = getString(R.string.stats_two_parts, calls, formatSecondsToShortTimeString(caller.seconds))
        row.personShare.setIndicatorColor(primary)
        row.personShare.trackColor = androidx.core.graphics.ColorUtils.setAlphaComponent(primary, 0x26)
        row.personShare.progress = caller.calls * 100 / most
        binding.statsTopList.addView(row.root)
    }
}
