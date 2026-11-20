package com.mhw.phone.activities

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.extensions.baseConfig
import com.mhw.phone.R
import com.mhw.phone.extensions.applyLiquidBackdrop
import com.mhw.phone.extensions.isLiquidLightTheme

open class SimpleActivity : BaseSimpleActivity() {
    // The call screen keeps the user's call-background setting (photo / blurred avatar) as its
    // glass backdrop, so it opts out of the shared colour-field backdrop.
    protected open val usesLiquidBackdrop = true

    override fun onCreate(savedInstanceState: Bundle?) {
        syncBrandPrimaryWithTheme()
        // Follow the app theme (not the system) for -night resources, so dark-theme variants of
        // the glass drawables (e.g. darker row cards) load. Set before super: no recreation.
        delegate.localNightMode =
            if (isLiquidLightTheme()) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        super.onCreate(savedInstanceState)
    }

    // The brand primary is a pair: navy on light themes, a light navy-blue on dark ones (navy
    // all but disappears on the dark backdrop). Only the brand defaults are swapped, so a colour
    // the user picked in Customize colors is never touched.
    private fun syncBrandPrimaryWithTheme() {
        val navy = ContextCompat.getColor(this, R.color.default_primary_color)
        val lightNavy = ContextCompat.getColor(this, R.color.brand_primary_dark_theme)
        val current = baseConfig.primaryColor
        val isLight = isLiquidLightTheme()
        if (!isLight && current == navy) baseConfig.primaryColor = lightNavy
        else if (isLight && current == lightNavy) baseConfig.primaryColor = navy
    }

    override fun onResume() {
        super.onResume()
        // Runs after commons paints the theme background colour, so the backdrop wins. It goes on
        // the content view, not the window: glass panels capture views, and a window background
        // is never part of that capture (the glass would render dark).
        if (usesLiquidBackdrop) findViewById<View>(android.R.id.content).applyLiquidBackdrop()
    }

    override fun getAppIconIDs() = arrayListOf(
        R.mipmap.ic_launcher,
        R.mipmap.ic_launcher_one,
        R.mipmap.ic_launcher_two,
        R.mipmap.ic_launcher_three,
        R.mipmap.ic_launcher_four,
        R.mipmap.ic_launcher_five,
        R.mipmap.ic_launcher_six,
        R.mipmap.ic_launcher_seven,
        R.mipmap.ic_launcher_eight,
        R.mipmap.ic_launcher_nine,
        R.mipmap.ic_launcher_ten,
        R.mipmap.ic_launcher_eleven
    )

    override fun getAppLauncherName() = getString(R.string.app_launcher_name)

    override fun getRepositoryName() = "Dialer"
}
