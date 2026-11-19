package com.goodwy.dialer.activities

import android.view.View
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.dialer.R
import com.goodwy.dialer.extensions.applyLiquidBackdrop

open class SimpleActivity : BaseSimpleActivity() {
    // The call screen keeps the user's call-background setting (photo / blurred avatar) as its
    // glass backdrop, so it opts out of the shared colour-field backdrop.
    protected open val usesLiquidBackdrop = true

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
