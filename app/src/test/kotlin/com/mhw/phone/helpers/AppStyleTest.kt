package com.mhw.phone.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.goodwy.commons.helpers.FONT_SIZE_MEDIUM
import com.goodwy.commons.helpers.OVERFLOW_ICON_VERTICAL
import com.goodwy.commons.helpers.PREFS_KEY
import com.mhw.phone.R
import com.mhw.phone.extensions.config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppStyleTest {
    private lateinit var context: Context
    private val prefs get() = context.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // App.onCreate already ran both functions; start from clean prefs
        prefs.edit().clear().commit()
    }

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    private fun assertPalette(dark: Boolean) {
        val c = context.config
        val text = if (dark) com.goodwy.commons.R.color.theme_black_text_color else com.goodwy.commons.R.color.theme_light_text_color
        val bg = if (dark) com.goodwy.commons.R.color.theme_black_background_color else com.goodwy.commons.R.color.theme_light_background_color
        assertEquals(color(text), c.textColor)
        assertEquals(color(bg), c.backgroundColor)
        assertEquals(color(if (dark) R.color.brand_primary_dark_theme else R.color.default_primary_color), c.primaryColor)
        assertEquals(color(if (dark) R.color.brand_accent_dark_theme else R.color.brand_accent), c.accentColor)
        assertTrue(c.isUsingAccentColor)
        assertEquals(dark, isAppThemeDark)
    }

    @Test
    fun lightThemeWritesLightPalette() {
        context.config.appTheme = APP_THEME_LIGHT
        context.applyAppTheme()
        assertPalette(dark = false)
    }

    @Test
    fun darkThemeWritesDarkPalette() {
        context.config.appTheme = APP_THEME_DARK
        context.applyAppTheme()
        assertPalette(dark = true)
    }

    @Test
    fun systemThemeFollowsSystemUiMode() {
        // Robolectric's system resources are in day mode
        context.config.appTheme = APP_THEME_SYSTEM
        context.applyAppTheme()
        assertPalette(dark = false)
    }

    @Test
    fun switchingThemeBackAndForthRewritesColours() {
        context.config.appTheme = APP_THEME_DARK
        context.applyAppTheme()
        val darkBg = context.config.backgroundColor
        context.config.appTheme = APP_THEME_LIGHT
        context.applyAppTheme()
        assertNotEquals(darkBg, context.config.backgroundColor)
        assertPalette(dark = false)
    }

    @Test
    fun forcesMaterialYouAndAutoThemeOff() {
        val c = context.config
        c.isSystemThemeEnabled = true
        c.isAutoThemeEnabled = true
        context.applyAppTheme()
        assertFalse(c.isSystemThemeEnabled)
        assertFalse(c.isAutoThemeEnabled)
    }

    @Test
    fun importedConflictingColoursAreOverwritten() {
        val c = context.config
        c.appTheme = APP_THEME_LIGHT
        c.textColor = 0x123456
        c.backgroundColor = 0x654321
        c.primaryColor = 0x111111
        c.accentColor = 0x222222
        c.isUsingAccentColor = false
        context.applyAppTheme()
        assertPalette(dark = false)
    }

    @Test
    fun pinnedSettingsOverwriteImportedValues() {
        val c = context.config
        c.showTabs = 0
        c.bottomNavigationBar = false
        c.dialpadStyle = DIALPAD_ORIGINAL + 1
        c.toneVolume = 5
        c.dialpadSize = 5
        c.answerStyle = ANSWER_BUTTON
        c.callButtonStyle = IOS16 + 1
        c.openDialPadAtLaunch = false
        c.groupAllCalls = true
        c.queryLimitRecent = QUERY_LIMIT_MEDIUM_VALUE + 1
        c.onRecentClick = SWIPE_ACTION_CALL
        c.useColoredContacts = true
        context.applyPinnedSettings()
        assertEquals(ALL_TABS_MASK, c.showTabs)
        assertTrue(c.bottomNavigationBar)
        assertEquals(DIALPAD_ORIGINAL, c.dialpadStyle)
        assertEquals(80, c.toneVolume)
        assertEquals(80, c.dialpadSize)
        assertEquals(ANSWER_SLIDER_VERTICAL, c.answerStyle)
        assertEquals(IOS16, c.callButtonStyle)
        assertTrue(c.openDialPadAtLaunch)
        assertFalse(c.groupAllCalls)
        assertEquals(QUERY_LIMIT_MEDIUM_VALUE, c.queryLimitRecent)
        assertEquals(SWIPE_ACTION_OPEN, c.onRecentClick)
        assertFalse(c.useColoredContacts)
    }

    @Test
    fun pinnedSettingsAreIdempotent() {
        context.applyPinnedSettings()
        val first = prefs.all.toMap()
        context.applyPinnedSettings()
        assertEquals(first, prefs.all.toMap())
    }

    @Test
    fun pinnedSettingsDoNotWriteWhenAlreadyPinned() {
        context.applyPinnedSettings()
        var writes = 0
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> writes++ }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        context.applyPinnedSettings()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        assertEquals(0, writes)
    }

    @Test
    fun applyAppThemeIsIdempotent() {
        context.config.appTheme = APP_THEME_DARK
        context.applyAppTheme()
        val first = prefs.all.toMap()
        context.applyAppTheme()
        assertEquals(first, prefs.all.toMap())
    }

    // The approved decision table (docs/handoff/settings-simplify.md): every removed setting and its one value
    @Test
    @Suppress("LongMethod") // one assertion per row of the decision table
    fun pinsEveryRemovedSettingToItsApprovedValue() {
        context.applyPinnedSettings()
        val c = context.config
        val expected: List<Triple<String, Any, Any>> = listOf(
            Triple("topAppBarColorIcon", false, c.topAppBarColorIcon),
            Triple("topAppBarColorTitle", false, c.topAppBarColorTitle),
            Triple("overflowIcon", OVERFLOW_ICON_VERTICAL, c.overflowIcon),
            Triple("useColoredContacts", false, c.useColoredContacts),
            Triple("fontSize", FONT_SIZE_MEDIUM, c.fontSize),
            Triple("useEnglish", false, c.useEnglish),
            Triple("useSpeechToText", true, c.useSpeechToText),
            Triple("formatPhoneNumbers", true, c.formatPhoneNumbers),
            Triple("showTabs", ALL_TABS_MASK, c.showTabs),
            Triple("bottomNavigationBar", true, c.bottomNavigationBar),
            Triple("useIconTabs", false, c.useIconTabs),
            Triple("screenSlideAnimation", 1, c.screenSlideAnimation),
            Triple("openSearch", true, c.openSearch),
            Triple("closeSearch", false, c.closeSearch),
            Triple("showSearchBar", true, c.showSearchBar),
            Triple("changeColourTopBar", true, c.changeColourTopBar),
            Triple("onFavoriteClick", SWIPE_ACTION_OPEN, c.onFavoriteClick),
            Triple("onRecentClick", SWIPE_ACTION_OPEN, c.onRecentClick),
            Triple("onContactClick", SWIPE_ACTION_OPEN, c.onContactClick),
            Triple("useSwipeToAction", true, c.useSwipeToAction),
            Triple("swipeVibration", true, c.swipeVibration),
            Triple("swipeRipple", true, c.swipeRipple),
            Triple("skipDeleteConfirmation", false, c.skipDeleteConfirmation),
            Triple("openDialPadAtLaunch", true, c.openDialPadAtLaunch),
            Triple("hideDialpadLetters", false, c.hideDialpadLetters),
            Triple("hideDialpadNumbers", false, c.hideDialpadNumbers),
            Triple("searchContactsInDialpad", true, c.searchContactsInDialpad),
            Triple("showRecentCallsOnDialpad", true, c.showRecentCallsOnDialpad),
            Triple("dialpadStyle", DIALPAD_ORIGINAL, c.dialpadStyle),
            Triple("showVoicemailIcon", false, c.showVoicemailIcon),
            Triple("dialpadSecondaryTypeface", android.graphics.Typeface.NORMAL, c.dialpadSecondaryTypeface),
            Triple("dialpadHashtagLongClick", DIALPAD_LONG_CLICK_WAIT, c.dialpadHashtagLongClick),
            Triple("dialpadClearWhenStartCall", false, c.dialpadClearWhenStartCall),
            Triple("toneVolume", 80, c.toneVolume),
            Triple("dialpadSize", 80, c.dialpadSize),
            Triple("dialpadNumberFontSize", 80, c.dialpadNumberFontSize),
            Triple("callButtonPrimarySize", 100, c.callButtonPrimarySize),
            Triple("callButtonSecondarySize", 100, c.callButtonSecondarySize),
            Triple("dialpadBottomMargin", 0, c.dialpadBottomMargin),
            Triple("backgroundCallScreen", THEME_BACKGROUND, c.backgroundCallScreen),
            Triple("transparentCallScreen", false, c.transparentCallScreen),
            Triple("answerStyle", ANSWER_SLIDER_VERTICAL, c.answerStyle),
            Triple("disableSwipeToAnswer", true, c.disableSwipeToAnswer),
            Triple("callButtonStyle", IOS16, c.callButtonStyle),
            Triple("simDialogStyle", SIM_DIALOG_STYLE_LIST, c.simDialogStyle),
            Triple("keepCallsInPopUp", false, c.keepCallsInPopUp),
            Triple("turnOnSpeakerInPopup", false, c.turnOnSpeakerInPopup),
            Triple("backPressedEndCall", false, c.backPressedEndCall),
            Triple("callBlockButton", false, c.callBlockButton),
            Triple("blockCallFromAnotherApp", true, c.blockCallFromAnotherApp),
            Triple("groupSubsequentCalls", true, c.groupSubsequentCalls),
            Triple("groupAllCalls", false, c.groupAllCalls),
            Triple("queryLimitRecent", QUERY_LIMIT_MEDIUM_VALUE, c.queryLimitRecent),
            Triple("useDividers", true, c.useDividers),
            Triple("showContactThumbnails", true, c.showContactThumbnails),
            Triple("showPhoneNumbers", true, c.showPhoneNumbers),
            Triple("showNicknameInsteadNames", false, c.showNicknameInsteadNames),
            Triple("useRelativeDate", false, c.useRelativeDate)
        )
        expected.forEach { (name, value, actual) -> assertEquals(name, value, actual) }
    }
}
