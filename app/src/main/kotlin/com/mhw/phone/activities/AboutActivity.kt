package com.mhw.phone.activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.core.net.toUri
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.extensions.updateTextColors
import com.goodwy.commons.extensions.viewBinding
import com.goodwy.commons.helpers.NavigationIcon
import com.mhw.phone.BuildConfig
import com.mhw.phone.R
import com.mhw.phone.databinding.ActivityAboutBinding

class AboutActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityAboutBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(aboutNestedScrollview))
            aboutVersion.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)
            aboutGithubHolder.setOnClickListener { openLink(R.string.about_github_url) }
            aboutXHolder.setOnClickListener { openLink(R.string.about_x_url) }
            aboutLinkedinHolder.setOnClickListener { openLink(R.string.about_linkedin_url) }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.aboutAppbar, NavigationIcon.Arrow)
        binding.aboutAppbar.setBackgroundColor(Color.TRANSPARENT)
        binding.aboutToolbar.setBackgroundColor(Color.TRANSPARENT)

        binding.apply {
            arrayOf(aboutBioCard, aboutPhilosophyCard, aboutLinksCard).forEach {
                it.setCardBackgroundColor(Color.TRANSPARENT)
                it.setBackgroundResource(R.drawable.bg_glass_card)
            }
            updateTextColors(aboutHolder)
            val primaryColor = getProperPrimaryColor()
            arrayOf(aboutPhilosophyLabel, aboutFollowLabel).forEach { it.setTextColor(primaryColor) }
        }
    }

    private fun openLink(urlRes: Int) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, getString(urlRes).toUri()))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.about_link_error)
        }
    }
}
