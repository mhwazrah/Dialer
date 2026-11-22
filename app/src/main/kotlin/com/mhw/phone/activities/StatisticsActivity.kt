package com.mhw.phone.activities

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.provider.CallLog.Calls
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.beGoneIf
import com.goodwy.commons.extensions.beVisibleIf
import com.goodwy.commons.extensions.formatSecondsToShortTimeString
import com.goodwy.commons.extensions.getContrastColor
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.hasPermission
import com.goodwy.commons.extensions.normalizePhoneNumber
import com.goodwy.commons.extensions.updateTextColors
import com.goodwy.commons.extensions.viewBinding
import com.goodwy.commons.helpers.NavigationIcon
import com.goodwy.commons.helpers.PERMISSION_READ_CALL_LOG
import com.goodwy.commons.helpers.SMT_PRIVATE
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.views.MyTextView
import com.google.android.material.button.MaterialButton
import com.mhw.phone.R
import com.mhw.phone.databinding.ActivityStatisticsBinding
import com.mhw.phone.databinding.ItemStatTileBinding
import com.mhw.phone.extensions.loadPrivateContacts
import com.mhw.phone.helpers.CallStats
import com.mhw.phone.helpers.ContactsRepository
import com.mhw.phone.helpers.StatCall
import com.mhw.phone.helpers.blockedNumberKey
import com.mhw.phone.helpers.callStats
import java.text.NumberFormat
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Talk time, call mix, busiest hour and the people called most, worked out on the phone from the call log. */
class StatisticsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityStatisticsBinding::inflate)
    private var periodDays = 30 // 0 = all time
    private var loadGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomSystem = listOf(binding.statsScrollview))
        periodButtons().forEach { (button, days) ->
            button.setOnClickListener {
                periodDays = days
                updatePeriodButtons()
                load()
            }
        }
        load()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.statsAppbar, NavigationIcon.Arrow)
        binding.statsAppbar.setBackgroundColor(Color.TRANSPARENT)
        binding.statsToolbar.setBackgroundColor(Color.TRANSPARENT)
        arrayOf(binding.statsSummaryCard, binding.statsTopCard).forEach {
            it.setCardBackgroundColor(Color.TRANSPARENT)
            it.setBackgroundResource(R.drawable.bg_glass_card)
        }
        updateTextColors(binding.statsHolder)
        binding.statsTopLabel.setTextColor(getProperPrimaryColor())
        updatePeriodButtons()
    }

    private fun periodButtons(): List<Pair<MaterialButton, Int>> =
        listOf(binding.statsPeriodWeek to 7, binding.statsPeriodMonth to 30, binding.statsPeriodAll to 0)

    private fun updatePeriodButtons() {
        val primary = getProperPrimaryColor()
        val text = getProperTextColor()
        periodButtons().forEach { (button, days) ->
            val selected = days == periodDays
            button.setBackgroundColor(if (selected) primary else Color.TRANSPARENT)
            button.setTextColor(if (selected) primary.getContrastColor() else text)
            button.strokeColor = ColorStateList.valueOf(if (selected) Color.TRANSPARENT else ColorUtils.setAlphaComponent(text, 102))
        }
    }

    private fun load() {
        val generation = ++loadGeneration
        val since = if (periodDays == 0) 0L else System.currentTimeMillis() - TimeUnit.DAYS.toMillis(periodDays.toLong())
        ContactsRepository.ensureLoaded(this) { snapshot ->
            ensureBackgroundThread {
                val stats = callStats(readCalls(since, snapshot))
                runOnUiThread { if (generation == loadGeneration && !isDestroyed) show(stats) }
            }
        }
    }

    // Straight from the call log: the Recents list is grouped and capped
    @SuppressLint("MissingPermission")
    private fun readCalls(since: Long, snapshot: ContactsRepository.Snapshot): List<StatCall> {
        if (!hasPermission(PERMISSION_READ_CALL_LOG)) return emptyList()
        // Hidden contacts stay hidden here too, as in Recents
        val privateNumbers = if (SMT_PRIVATE in baseConfig.ignoredContactSources) {
            loadPrivateContacts(includeIgnored = true).flatMap { it.phoneNumbers }.mapTo(HashSet()) { it.normalizedNumber }
        } else {
            emptySet()
        }
        val calls = ArrayList<StatCall>()
        val calendar = Calendar.getInstance()
        val projection = arrayOf(Calls.NUMBER, Calls.TYPE, Calls.DURATION, Calls.DATE, Calls.CACHED_NAME)
        val selection = "${Calls.DATE} >= ?"
        contentResolver.query(Calls.CONTENT_URI, projection, selection, arrayOf(since.toString()), "${Calls.DATE} DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val number = cursor.getString(0).orEmpty()
                if (number.isNotEmpty() && number.normalizePhoneNumber() in privateNumbers) continue
                val contact = snapshot.find(number)
                calendar.timeInMillis = cursor.getLong(3)
                calls += StatCall(
                    key = contact?.let { "c${it.id}" } ?: "n${blockedNumberKey(number)}",
                    name = contact?.getNameToDisplay() ?: cursor.getString(4)?.takeIf { it.isNotBlank() }
                        ?: number.takeIf { blockedNumberKey(it).isNotEmpty() } ?: getString(R.string.unknown_caller),
                    type = cursor.getInt(1),
                    seconds = cursor.getInt(2),
                    hour = calendar.get(Calendar.HOUR_OF_DAY),
                )
            }
        }
        return calls
    }

    private fun show(stats: CallStats) {
        binding.apply {
            statsEmpty.beVisibleIf(stats.total == 0)
            arrayOf(statsSummaryCard, statsTopLabel, statsTopCard).forEach { it.beGoneIf(stats.total == 0) }
            statsTalkTime.text = formatSecondsToShortTimeString(stats.talkSeconds)
            tile(statsTotal, stats.total, R.string.stats_total_calls)
            tile(statsIncoming, stats.incoming, R.string.incoming)
            tile(statsOutgoing, stats.outgoing, R.string.outgoing)
            tile(statsMissed, stats.missed, R.string.missed)
            statsAverage.text = getString(R.string.stats_average_call, formatSecondsToShortTimeString(stats.averageSeconds))
            statsBusiestHour.beVisibleIf(stats.busiestHour != null)
            stats.busiestHour?.let { statsBusiestHour.text = getString(R.string.stats_busiest_hour, hourText(it)) }

            statsTopList.removeAllViews()
            stats.topCallers.forEach { caller ->
                val detail = resources.getQuantityString(R.plurals.stats_calls, caller.calls, caller.calls) +
                    " · " + formatSecondsToShortTimeString(caller.seconds)
                statsTopList.addView(topRow(caller.name, detail))
            }
            updateTextColors(statsHolder)
        }
    }

    // In the app's 12 / 24-hour setting, like the times in Recents
    private fun hourText(hour: Int): String {
        val pattern = if (baseConfig.use24HourFormat) "HH:mm" else "h a"
        val formatter = DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).withDecimalStyle(DecimalStyle.ofDefaultLocale())
        return LocalTime.of(hour, 0).format(formatter)
    }

    private fun tile(tile: ItemStatTileBinding, value: Int, label: Int) {
        tile.statValue.text = NumberFormat.getInstance().format(value)
        tile.statLabel.setText(label)
    }

    private fun topRow(name: String, detail: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        val padding = resources.getDimensionPixelSize(com.goodwy.commons.R.dimen.activity_margin)
        setPadding(padding, padding / 2, padding, padding / 2)
        addView(MyTextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text = name
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        })
        addView(MyTextView(context).apply {
            text = detail
            alpha = 0.6f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        })
    }
}
