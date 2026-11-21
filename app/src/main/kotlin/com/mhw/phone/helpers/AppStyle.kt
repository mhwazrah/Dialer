package com.mhw.phone.helpers

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.core.content.ContextCompat
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.helpers.FONT_SIZE_MEDIUM
import com.goodwy.commons.helpers.OVERFLOW_ICON_VERTICAL
import com.goodwy.commons.helpers.PREFS_KEY
import com.mhw.phone.R
import com.mhw.phone.extensions.config

/** The resolved app theme, for code without a themed context (the dialog background, GlassDialogDrawable). */
@Volatile
var isAppThemeDark = false
    private set

/**
 * MHW Phone's fixed look: the only choice left to the user is [Config.appTheme] (system / light / dark).
 * Resolves it into the commons colours every screen reads. Call before anything reads a colour.
 */
fun Context.applyAppTheme() {
    val c = config
    val dark = when (c.appTheme) {
        APP_THEME_LIGHT -> false
        APP_THEME_DARK -> true
        // System, not this context: activities force their own night mode (SimpleActivity.localNightMode)
        else -> Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }
    isAppThemeDark = dark
    // Material You off; "follow system" is resolved here, not by commons' auto theme
    if (c.isSystemThemeEnabled) c.isSystemThemeEnabled = false
    if (c.isAutoThemeEnabled) c.isAutoThemeEnabled = false
    // Same palettes commons' auto theme uses
    val palette = if (dark) {
        com.goodwy.commons.R.color.theme_black_text_color to com.goodwy.commons.R.color.theme_black_background_color
    } else {
        com.goodwy.commons.R.color.theme_light_text_color to com.goodwy.commons.R.color.theme_light_background_color
    }
    pin(c.textColor, ContextCompat.getColor(this, palette.first)) { c.textColor = it }
    pin(c.backgroundColor, ContextCompat.getColor(this, palette.second)) { c.backgroundColor = it }
    val primary = if (dark) R.color.brand_primary_dark_theme else R.color.default_primary_color
    pin(c.primaryColor, ContextCompat.getColor(this, primary)) { c.primaryColor = it }
    pin(c.isUsingAccentColor, true) { c.isUsingAccentColor = it }
    val accent = if (dark) R.color.brand_accent_dark_theme else R.color.brand_accent
    pin(c.accentColor, ContextCompat.getColor(this, accent)) { c.accentColor = it }
}

/**
 * Every setting MHW Phone removed from the Settings screen, pinned to its one value. Runs on app start, so a
 * value from an older build or an imported settings file never sticks. Each write only happens when different.
 */
fun Context.applyPinnedSettings() {
    val c = config

    // Appearance
    pin(c.topAppBarColorIcon, false) { c.topAppBarColorIcon = it }
    pin(c.topAppBarColorTitle, false) { c.topAppBarColorTitle = it }
    pin(c.overflowIcon, OVERFLOW_ICON_VERTICAL) { c.overflowIcon = it }
    pin(c.useColoredContacts, false) { c.useColoredContacts = it }
    pin(c.fontSize, FONT_SIZE_MEDIUM) { c.fontSize = it }
    pin(c.useEnglish, false) { c.useEnglish = it }
    pin(c.useSpeechToText, true) { c.useSpeechToText = it }
    pin(c.formatPhoneNumbers, true) { c.formatPhoneNumbers = it }
    // Tabs and top bar
    pin(c.showTabs, ALL_TABS_MASK) { c.showTabs = it }
    pin(c.bottomNavigationBar, true) { c.bottomNavigationBar = it }
    pin(c.useIconTabs, false) { c.useIconTabs = it }
    pin(c.screenSlideAnimation, 1) { c.screenSlideAnimation = it } // zoom out
    pin(c.openSearch, true) { c.openSearch = it }
    pin(c.closeSearch, false) { c.closeSearch = it }
    pin(c.showSearchBar, true) { c.showSearchBar = it }
    pin(c.changeColourTopBar, true) { c.changeColourTopBar = it }
    // Taps and swipes
    pin(c.onFavoriteClick, SWIPE_ACTION_OPEN) { c.onFavoriteClick = it }
    pin(c.onRecentClick, SWIPE_ACTION_OPEN) { c.onRecentClick = it }
    pin(c.onContactClick, SWIPE_ACTION_OPEN) { c.onContactClick = it }
    pin(c.useSwipeToAction, true) { c.useSwipeToAction = it }
    pin(c.swipeToActionWidth, 3) { c.swipeToActionWidth = it } // a swipe acts after 1/3 of the screen width
    pin(c.swipeVibration, true) { c.swipeVibration = it }
    pin(c.swipeRipple, true) { c.swipeRipple = it }
    pin(c.skipDeleteConfirmation, false) { c.skipDeleteConfirmation = it }
    // Dialpad
    pin(c.openDialPadAtLaunch, true) { c.openDialPadAtLaunch = it }
    pin(c.hideDialpadLetters, false) { c.hideDialpadLetters = it }
    pin(c.hideDialpadNumbers, false) { c.hideDialpadNumbers = it }
    pin(c.searchContactsInDialpad, true) { c.searchContactsInDialpad = it }
    pin(c.showRecentCallsOnDialpad, true) { c.showRecentCallsOnDialpad = it }
    pin(c.dialpadStyle, DIALPAD_ORIGINAL) { c.dialpadStyle = it }
    pin(c.showVoicemailIcon, false) { c.showVoicemailIcon = it }
    pin(c.dialpadSecondaryTypeface, android.graphics.Typeface.NORMAL) { c.dialpadSecondaryTypeface = it }
    pin(c.dialpadHashtagLongClick, DIALPAD_LONG_CLICK_WAIT) { c.dialpadHashtagLongClick = it }
    pin(c.dialpadClearWhenStartCall, false) { c.dialpadClearWhenStartCall = it }
    pin(c.toneVolume, 80) { c.toneVolume = it }
    pin(c.dialpadSize, 80) { c.dialpadSize = it }
    pin(c.dialpadNumberFontSize, 80) { c.dialpadNumberFontSize = it }
    pin(c.callButtonPrimarySize, 100) { c.callButtonPrimarySize = it }
    pin(c.callButtonSecondarySize, 100) { c.callButtonSecondarySize = it }
    pin(c.dialpadBottomMargin, 0) { c.dialpadBottomMargin = it }
    // Call screen
    pin(c.backgroundCallScreen, THEME_BACKGROUND) { c.backgroundCallScreen = it }
    pin(c.transparentCallScreen, false) { c.transparentCallScreen = it }
    pin(c.answerStyle, ANSWER_SLIDER_VERTICAL) { c.answerStyle = it }
    pin(c.disableSwipeToAnswer, true) { c.disableSwipeToAnswer = it }
    pin(c.callButtonStyle, IOS16) { c.callButtonStyle = it }
    pin(c.simDialogStyle, SIM_DIALOG_STYLE_LIST) { c.simDialogStyle = it }
    pin(c.keepCallsInPopUp, false) { c.keepCallsInPopUp = it }
    pin(c.turnOnSpeakerInPopup, false) { c.turnOnSpeakerInPopup = it }
    pin(c.backPressedEndCall, false) { c.backPressedEndCall = it }
    pin(c.callBlockButton, false) { c.callBlockButton = it }
    pin(c.blockCallFromAnotherApp, true) { c.blockCallFromAnotherApp = it }
    // Lists and recents
    pin(c.groupSubsequentCalls, true) { c.groupSubsequentCalls = it }
    pin(c.groupAllCalls, false) { c.groupAllCalls = it }
    pin(c.queryLimitRecent, QUERY_LIMIT_MEDIUM_VALUE) { c.queryLimitRecent = it }
    pin(c.useDividers, true) { c.useDividers = it }
    pin(c.showContactThumbnails, true) { c.showContactThumbnails = it }
    pin(c.showPhoneNumbers, true) { c.showPhoneNumbers = it }
    pin(c.showNicknameInsteadNames, false) { c.showNicknameInsteadNames = it }
    pin(c.useRelativeDate, false) { c.useRelativeDate = it }

    // Defaults the user can still change: written only when never set, since commons' own default (off) can't be changed
    val prefs = getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)
    if (!prefs.contains(BLOCKING_ENABLED_KEY)) baseConfig.blockingEnabled = true
}

private const val BLOCKING_ENABLED_KEY = "blocking_enabled" // commons BaseConfig.blockingEnabled

// Every write is a SharedPreferences commit, so only write what differs
private inline fun pin(current: Boolean, value: Boolean, set: (Boolean) -> Unit) { if (current != value) set(value) }
private inline fun pin(current: Int, value: Int, set: (Int) -> Unit) { if (current != value) set(value) }
