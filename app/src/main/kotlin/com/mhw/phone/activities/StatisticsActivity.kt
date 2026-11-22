package com.mhw.phone.activities

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.provider.CallLog.Calls
import androidx.core.graphics.ColorUtils
import com.goodwy.commons.extensions.baseConfig
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
import com.goodwy.commons.models.contacts.Contact
import com.google.android.material.button.MaterialButton
import com.mhw.phone.R
import com.mhw.phone.databinding.ActivityStatisticsBinding
import com.mhw.phone.extensions.loadPrivateContacts
import com.mhw.phone.helpers.ContactsRepository
import com.mhw.phone.helpers.StatCall
import com.mhw.phone.helpers.blockedNumberKey
import com.mhw.phone.helpers.buildDashboard
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** A dashboard of the call log, worked out on the phone: talk time, indicators, charts and the people called most. */
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
        binding.apply {
            arrayOf(statsSummaryCard, statsActivityCard, statsMixCard, statsHourCard, statsWeekdayCard, statsTopCard).forEach {
                it.setCardBackgroundColor(Color.TRANSPARENT)
                it.setBackgroundResource(R.drawable.bg_glass_card)
            }
            updateTextColors(statsHolder)
            val primary = getProperPrimaryColor()
            arrayOf(statsTrend, statsActivityLabel, statsMixLabel, statsHourLabel, statsWeekdayLabel, statsTopLabel)
                .forEach { it.setTextColor(primary) }
        }
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
        val days = periodDays
        // Twice the period is read, so the period before it can be compared against
        val since = if (days == 0) 0L else System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days * 2L)
        ContactsRepository.ensureLoaded(this) { snapshot ->
            ensureBackgroundThread {
                val dashboard = buildDashboard(readCalls(since, snapshot), days, LocalDate.now())
                runOnUiThread {
                    if (generation == loadGeneration && !isDestroyed) showDashboard(binding, dashboard)
                }
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
        val zone = ZoneId.systemDefault()
        val contacts = HashMap<String, Contact?>() // one contact lookup per number, however long the log is
        val projection = arrayOf(Calls.NUMBER, Calls.TYPE, Calls.DURATION, Calls.DATE, Calls.CACHED_NAME)
        val selection = "${Calls.DATE} >= ?"
        contentResolver.query(Calls.CONTENT_URI, projection, selection, arrayOf(since.toString()), "${Calls.DATE} DESC")?.use { cursor ->
            while (cursor.moveToNext()) {
                val number = cursor.getString(0).orEmpty()
                if (number.isNotEmpty() && number.normalizePhoneNumber() in privateNumbers) continue
                val contact = contacts.getOrPut(number) { snapshot.find(number) }
                val time = Instant.ofEpochMilli(cursor.getLong(3)).atZone(zone)
                calls += StatCall(
                    key = contact?.let { "c${it.id}" } ?: "n${blockedNumberKey(number)}",
                    name = contact?.getNameToDisplay() ?: cursor.getString(4)?.takeIf { it.isNotBlank() }
                        ?: number.takeIf { blockedNumberKey(it).isNotEmpty() } ?: getString(R.string.unknown_caller),
                    type = cursor.getInt(1),
                    seconds = cursor.getInt(2),
                    hour = time.hour,
                    weekday = time.dayOfWeek.value % 7 + 1, // Calendar numbering: Sunday = 1
                    day = time.toLocalDate().toEpochDay(),
                    month = time.year * 12 + time.monthValue - 1,
                )
            }
        }
        return calls
    }
}
