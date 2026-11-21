package com.mhw.phone.helpers

import android.content.ComponentName
import android.content.Context
import android.telecom.PhoneAccountHandle
import androidx.test.core.app.ApplicationProvider
import com.goodwy.commons.helpers.PREFS_KEY
import com.mhw.phone.BuildConfig
import com.mhw.phone.R
import com.mhw.phone.extensions.config
import com.mhw.phone.models.RecentCall
import com.mhw.phone.models.SpeedDial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class ConfigTest {
    private lateinit var context: Context
    private val prefs get() = context.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)
    private lateinit var config: Config

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Drop the singleton so each test gets a Config bound to this test's application
        Config::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        config = context.config
        prefs.edit().clear().commit()
        context.getSharedPreferences(RECENTS_CACHE_FILE, Context.MODE_PRIVATE).edit().clear().commit()
        Locale.setDefault(Locale.US)
    }

    private fun handle(id: String = "sim1") = PhoneAccountHandle(ComponentName("com.example", "com.example.Svc"), id)

    private fun call(id: Int, number: String = "123") = RecentCall(
        id = id, phoneNumber = number, name = "n$id", photoUri = "", startTS = 1_700_000_000L + id, duration = 5, type = 1,
        simID = 1, simColor = 0, specificNumber = "", specificType = "", isUnknownNumber = false, isVoiceMail = false
    )

    @Test
    fun appThemeDefaultsToSystemAndRoundTrips() {
        assertEquals(APP_THEME_SYSTEM, config.appTheme)
        config.appTheme = APP_THEME_DARK
        assertEquals(APP_THEME_DARK, config.appTheme)
    }

    @Test
    fun quickAnswersDefaultToThreeStrings() {
        val expected = listOf(R.string.message_call_later, R.string.message_on_my_way, R.string.message_cant_talk_right_now)
            .map { context.getString(it) }
        assertEquals(expected, config.quickAnswers)
    }

    @Test
    fun quickAnswersRoundTripExactlyThreeLines() {
        config.quickAnswers = arrayListOf("a", "b", "c")
        assertEquals(arrayListOf("a", "b", "c"), config.quickAnswers)
        assertEquals("a\nb\nc", prefs.getString(QUICK_ANSWERS, null))
    }

    @Test
    fun quickAnswersNewlinesInsideAnswerBecomeSpaces() {
        config.quickAnswers = arrayListOf("one\ntwo", "b", "c")
        assertEquals(arrayListOf("one two", "b", "c"), config.quickAnswers)
    }

    @Test
    fun quickAnswersWithWrongLineCountFallBackToDefaults() {
        val defaults = config.quickAnswers
        prefs.edit().putString(QUICK_ANSWERS, "a\nb").commit()
        assertEquals(defaults, config.quickAnswers)
        prefs.edit().putString(QUICK_ANSWERS, "a\nb\nc\nd").commit()
        assertEquals(defaults, config.quickAnswers)
        prefs.edit().putString(QUICK_ANSWERS, "").commit()
        assertEquals(defaults, config.quickAnswers)
    }

    @Test
    fun quickAnswersKeepEmptyLinesWhenThreeLinesSaved() {
        config.quickAnswers = arrayListOf("", "b", "")
        assertEquals(arrayListOf("", "b", ""), config.quickAnswers)
    }

    @Test
    fun speedDialValuesDefaultToNineEmptySlots() {
        val values = config.getSpeedDialValues()
        assertEquals((1..9).toList(), values.map { it.id }.sorted())
        assertTrue(values.none { it.isValid() })
    }

    @Test
    fun speedDialValuesKeepSavedAndFillMissingSlots() {
        config.speedDial = """[{"a":2,"b":"555","c":"Mom"},{"id":5,"number":"777","displayName":"Dad"}]"""
        val values = config.getSpeedDialValues()
        assertEquals(9, values.size)
        assertEquals("Mom", values.first { it.id == 2 }.displayName)
        assertEquals("777", values.first { it.id == 5 }.number) // readable alias keys accepted
        assertEquals(2, values.count { it.isValid() })
        assertEquals(9, values.map { it.id }.toSet().size)
    }

    @Test
    fun speedDialValuesDoNotDuplicateFullList() {
        config.speedDial = com.google.gson.Gson().toJson((1..9).map { SpeedDial(it, "$it$it", "n$it") })
        val values = config.getSpeedDialValues()
        assertEquals(9, values.size)
        assertTrue(values.all { it.isValid() })
    }

    @Test
    fun speedDialValuesKeepOutOfRangeIds() {
        config.speedDial = """[{"a":12,"b":"1","c":"x"}]"""
        assertEquals(10, config.getSpeedDialValues().size) // unknown id kept, 1..9 added
    }

    @Test
    fun recentCallsCacheIsEmptyWhenNothingSaved() {
        assertEquals("", config.recentCallsCache)
        assertTrue(config.parseRecentCallsCache().isEmpty())
    }

    @Test
    fun recentCallsCacheRoundTrips() {
        config.saveRecentCallsCache(listOf(call(1, "111"), call(2, "222")))
        val parsed = config.parseRecentCallsCache()
        assertEquals(listOf(1, 2), parsed.map { it.id })
        assertEquals("222", parsed[1].phoneNumber)
    }

    @Test
    fun recentCallsCacheIsTruncatedToCacheSize() {
        config.saveRecentCallsCache((1..RECENT_CALL_CACHE_SIZE + 25).map { call(it) })
        val parsed = config.parseRecentCallsCache()
        assertEquals(RECENT_CALL_CACHE_SIZE, parsed.size)
        assertEquals(1, parsed.first().id)
    }

    @Test
    fun recentCallsCacheFromOtherVersionIsIgnored() {
        config.saveRecentCallsCache(listOf(call(1)))
        context.getSharedPreferences(RECENTS_CACHE_FILE, Context.MODE_PRIVATE).edit()
            .putInt(RECENT_CALL_CACHE_VERSION, BuildConfig.VERSION_CODE + 1).commit()
        assertEquals("", config.recentCallsCache)
        assertTrue(config.parseRecentCallsCache().isEmpty())
    }

    @Test
    fun recentCallsCacheStampsCurrentVersion() {
        config.saveRecentCallsCache(listOf(call(1)))
        val file = context.getSharedPreferences(RECENTS_CACHE_FILE, Context.MODE_PRIVATE)
        assertEquals(BuildConfig.VERSION_CODE, file.getInt(RECENT_CALL_CACHE_VERSION, -1))
    }

    @Test
    fun recentCallsCacheMigratesOutOfMainPrefs() {
        prefs.edit().putString(RECENT_CALL, "old").putInt(RECENT_CALL_CACHE_VERSION, 1).commit()
        assertEquals("", config.recentCallsCache) // touching the cache removes the legacy keys
        assertFalse(prefs.contains(RECENT_CALL))
        assertFalse(prefs.contains(RECENT_CALL_CACHE_VERSION))
    }

    @Test
    fun regionHintFallsBackToLocaleCountryUppercased() {
        Locale.setDefault(Locale("en", "GB"))
        assertEquals("GB", config.regionHint())
    }

    @Test
    fun customSimRoundTripsAndRemoves() {
        assertNull(config.getCustomSIM("+14155550123"))
        config.saveCustomSIM("+14155550123", handle("simA"))
        val got = config.getCustomSIM("+14155550123")
        assertNotNull(got)
        assertEquals("simA", got!!.id)
        assertEquals("com.example.Svc", got.componentName.className)
        config.removeCustomSIM("+14155550123")
        assertNull(config.getCustomSIM("+14155550123"))
    }

    @Test
    fun customSimKeyIsNormalizedAcrossNumberFormats() {
        config.saveCustomSIM("tel:+1%20415-555-0123", handle("simA"))
        assertEquals("simA", config.getCustomSIM("+14155550123")?.id)
        assertEquals("simA", config.getCustomSIM("(415) 555-0123")?.id) // region US from locale
    }

    @Test
    fun customSimDifferentNumbersAreIndependent() {
        config.saveCustomSIM("+14155550123", handle("simA"))
        config.saveCustomSIM("+14155550124", handle("simB"))
        assertEquals("simA", config.getCustomSIM("+14155550123")?.id)
        assertEquals("simB", config.getCustomSIM("+14155550124")?.id)
    }

    @Test
    fun legacyCustomSimKeysAreMigratedOnceToNormalizedKey() {
        config.saveCustomSIM("+14155550123", handle("simLegacy"))
        val json = prefs.getString(REMEMBER_SIM_PREFIX + "+14155550123", null)!!
        prefs.edit().clear().putString(REMEMBER_SIM_PREFIX + "415 555 0123", json).commit()

        assertEquals("simLegacy", config.getCustomSIM("+14155550123")?.id)
        assertFalse(prefs.contains(REMEMBER_SIM_PREFIX + "415 555 0123"))
        assertTrue(prefs.getBoolean(LEGACY_SIM_KEYS_MIGRATED, false))
    }

    @Test
    fun legacyMigrationIsSkippedAfterFlagSet() {
        prefs.edit().putBoolean(LEGACY_SIM_KEYS_MIGRATED, true)
            .putString(REMEMBER_SIM_PREFIX + "raw", "{}").commit()
        config.getCustomSIM("x")
        assertTrue(prefs.contains(REMEMBER_SIM_PREFIX + "raw")) // untouched
    }

    @Test
    fun legacyMigrationLeavesUnreadableValuesAlone() {
        prefs.edit().putString(REMEMBER_SIM_PREFIX + "raw", "not json").commit()
        config.getCustomSIM("x")
        assertEquals("not json", prefs.getString(REMEMBER_SIM_PREFIX + "raw", null))
        assertTrue(prefs.getBoolean(LEGACY_SIM_KEYS_MIGRATED, false))
    }

    @Test
    fun recentOutgoingNumbersDefaultAndRoundTrip() {
        assertEquals(hashSetOf("."), config.recentOutgoingNumbers)
        config.recentOutgoingNumbers = hashSetOf("1", "2")
        assertEquals(setOf("1", "2"), config.recentOutgoingNumbers)
    }
}
