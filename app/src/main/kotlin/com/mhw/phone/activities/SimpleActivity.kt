package com.mhw.phone.activities

import android.content.Context
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import com.goodwy.commons.activities.BaseSimpleActivity
import com.mhw.phone.R
import com.mhw.phone.extensions.AlwaysDarkScreen
import com.mhw.phone.extensions.applyLiquidBackdrop
import com.mhw.phone.extensions.isLiquidLightTheme
import com.mhw.phone.helpers.applyAppTheme

open class SimpleActivity : BaseSimpleActivity() {
    // Follow the app theme (not the system) for -night resources, so dark-theme variants of the glass drawables (e.g.
    // darker row cards) load; the conference screen is always dark. Set before attaching, AppCompat gives this activity its
    // own override configuration. Set in onCreate it rewrote the Resources shared with the other screens (the call
    // screen handles uiMode itself), so a list row built behind a call got the dark card under light text.
    override fun attachBaseContext(newBase: Context) {
        // Refresh the palette first: after a system light/dark switch it still holds the previous theme's colours
        newBase.applicationContext.applyAppTheme()
        val light = this !is AlwaysDarkScreen && newBase.applicationContext.isLiquidLightTheme()
        delegate.localNightMode = if (light) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        super.attachBaseContext(newBase)
    }

    override fun onResume() {
        super.onResume()
        // Runs after commons paints the theme background colour, so the backdrop wins. It goes on
        // the content view, not the window: glass panels capture views, and a window background
        // is never part of that capture (the glass would render dark).
        findViewById<View>(android.R.id.content).applyLiquidBackdrop()
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
