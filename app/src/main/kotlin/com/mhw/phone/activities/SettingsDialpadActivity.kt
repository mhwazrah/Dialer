package com.mhw.phone.activities

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.view.updateLayoutParams
import com.behaviorule.arturdumchev.library.pixels
import com.behaviorule.arturdumchev.library.setHeight
import com.goodwy.commons.dialogs.RadioGroupDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.RadioItem
import com.mhw.phone.BuildConfig
import com.mhw.phone.R
import com.mhw.phone.databinding.ActivitySettingsDialpadBinding
import com.mhw.phone.extensions.*
import com.mhw.phone.helpers.*
import com.mhw.phone.models.RecentCall
import com.mhw.phone.helpers.gson
import java.util.*

class SettingsDialpadActivity : SimpleActivity() {

    private val binding by viewBinding(ActivitySettingsDialpadBinding::inflate)

    private val productIdX1 = BuildConfig.PRODUCT_ID_X1
    private val productIdX2 = BuildConfig.PRODUCT_ID_X2
    private val productIdX3 = BuildConfig.PRODUCT_ID_X3
    private val subscriptionIdX1 = BuildConfig.SUBSCRIPTION_ID_X1
    private val subscriptionIdX2 = BuildConfig.SUBSCRIPTION_ID_X2
    private val subscriptionIdX3 = BuildConfig.SUBSCRIPTION_ID_X3
    private val subscriptionYearIdX1 = BuildConfig.SUBSCRIPTION_YEAR_ID_X1
    private val subscriptionYearIdX2 = BuildConfig.SUBSCRIPTION_YEAR_ID_X2
    private val subscriptionYearIdX3 = BuildConfig.SUBSCRIPTION_YEAR_ID_X3

    private val hideDialpadHandler = Handler(Looper.getMainLooper())


    @SuppressLint("MissingSuperCall", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        boundStateKey = externalStateKey()

        binding.apply {
            setupEdgeToEdge(
                padBottomSystem = listOf(
                    dialpadClearWrapper.dialpadGridLayout,
                    dialpadRoundWrapper.dialpadIosWrapper,
                    dialpadRectWrapper.dialpadGridWrapper
                ),
            )
        }

        updateDialpadSize()
        updateDialpadNumberFontSize()
        updateDialpadBottomMargin()

        if (config.dialpadStyle == DIALPAD_GRID || config.dialpadStyle == DIALPAD_ORIGINAL) updateCallButtonSize()

        if (config.hideDialpadNumbers) {
            binding.dialpadClearWrapper.apply {
                dialpad1Holder.isVisible = false
                dialpad2Holder.isVisible = false
                dialpad3Holder.isVisible = false
                dialpad4Holder.isVisible = false
                dialpad5Holder.isVisible = false
                dialpad6Holder.isVisible = false
                dialpad7Holder.isVisible = false
                dialpad8Holder.isVisible = false
                dialpad9Holder.isVisible = false
                //dialpadPlusHolder.isVisible = true
                dialpad0Holder.visibility = View.INVISIBLE
            }

            binding.dialpadRoundWrapper.apply {
                dialpad1IosHolder.isVisible = false
                dialpad2IosHolder.isVisible = false
                dialpad3IosHolder.isVisible = false
                dialpad4IosHolder.isVisible = false
                dialpad5IosHolder.isVisible = false
                dialpad6IosHolder.isVisible = false
                dialpad7IosHolder.isVisible = false
                dialpad8IosHolder.isVisible = false
                dialpad9IosHolder.isVisible = false
                //dialpadPlusIos.isVisible = true
                dialpad0IosHolder.visibility = View.INVISIBLE
            }
            binding.dialpadRectWrapper.apply {
                dialpad1Holder.isVisible = false
                dialpad2Holder.isVisible = false
                dialpad3Holder.isVisible = false
                dialpad4Holder.isVisible = false
                dialpad5Holder.isVisible = false
                dialpad6Holder.isVisible = false
                dialpad7Holder.isVisible = false
                dialpad8Holder.isVisible = false
                dialpad9Holder.isVisible = false
                //dialpadPlusHolder.isVisible = true
                dialpad0Holder.visibility = View.INVISIBLE
            }
        }

        bindSettings()
    }

    private var hasMultipleSims = false

    // Colours, pro state and SIM count are the only inputs that change while this screen is away:
    // the rows are rebound only when they changed.
    private var boundStateKey = ""

    private fun externalStateKey(): String {
        hasMultipleSims = areMultipleSIMsAvailable()
        return "${getProperTextColor()}|${getProperPrimaryColor()}|${getProperBackgroundColor()}|${checkPro()}|$hasMultipleSims"
    }

    @SuppressLint("MissingSuperCall")
    override fun onResume() {
        super.onResume()
        binding.apply {
            setupTopAppBar(dialpadAppbar, NavigationIcon.Arrow)
            dialpadAppbar.setBackgroundColor(Color.TRANSPARENT)
            dialpadToolbar.setBackgroundColor(Color.TRANSPARENT)
        }

        val stateKey = externalStateKey()
        if (stateKey == boundStateKey) return
        boundStateKey = stateKey
        bindSettings()
    }

    private fun bindSettings() {
        val properTextColor = getProperTextColor()

        binding.apply {
            dialpadSettingsCardHolder.setCardBackgroundColor(Color.TRANSPARENT)
            dialpadSettingsCardHolder.setBackgroundResource(R.drawable.bg_glass_card)

            initStyle()
            updateTextColors(dialpadSettingsHolder)

            arrayOf(
                dialpadClearWrapper.dialpadAsterisk, dialpadClearWrapper.dialpadHashtag,
                dialpadRoundWrapper.dialpadAsteriskIos, dialpadRoundWrapper.dialpadHashtagIos,
                dialpadClearWrapper.dialpadVoicemail, dialpadRoundWrapper.dialpadVoicemail
            ).forEach {
                it.applyColorFilter(properTextColor)
            }

            settingsGeneralLabel.setTextColor(getProperPrimaryColor())
        }

        setupPrimarySimCard()
        setupDialpadSecondaryLanguage()
    }


    private fun initStyle() {
        binding.apply {
            when (config.dialpadStyle) {
                DIALPAD_IOS -> {
                    val properBackgroundColor =
                        if (isDynamicTheme() && !isSystemInDarkMode()) getSurfaceColor() else getProperBackgroundColor()

                    dialpadRoundWrapper.apply {
                        dialpadVoicemail.beVisibleIf(config.showVoicemailIcon && !config.hideDialpadLetters)
                        dialpadIosHolder.setBackgroundColor(Color.TRANSPARENT)

                        dialpadCallButtonIosHolder.background.applyColorFilter(config.simIconsColors[1])
                        arrayOf(
                            dialpad0IosHolder, dialpad1IosHolder, dialpad2IosHolder, dialpad3IosHolder, dialpad4IosHolder,
                            dialpad5IosHolder, dialpad6IosHolder, dialpad7IosHolder, dialpad8IosHolder, dialpad9IosHolder,
                            dialpadAsteriskIosHolder, dialpadHashtagIosHolder
                        ).forEach {
                            it.foreground.applyColorFilter(Color.GRAY)
                            it.foreground.alpha = 60
                        }

                        dialpadBottomMargin.apply {
                            setBackgroundColor(Color.TRANSPARENT)
                            setHeight(100)
                        }
                    }
                    initLettersIos()
                }

                DIALPAD_CONCEPT -> {
                    val properBackgroundColor =
                        if (isDynamicTheme() && !isSystemInDarkMode()) getSurfaceColor() else getProperBackgroundColor()

                    dialpadRectWrapper.apply {
                        dialpadVoicemail.beVisibleIf(config.showVoicemailIcon && !config.hideDialpadLetters)
                        dialpadGridHolder.setBackgroundColor(Color.TRANSPARENT)

                        arrayOf(
                            dividerHorizontalZero, dividerHorizontalOne, dividerHorizontalTwo, dividerHorizontalThree,
                            dividerHorizontalFour, dividerVerticalOne, dividerVerticalTwo, dividerVerticalStart, dividerVerticalEnd
                        ).forEach {
                            it.beInvisible()
                        }

                        dialpadBottomMargin.apply {
                            setBackgroundColor(Color.TRANSPARENT)
                            setHeight(100)
                        }
                    }
                    initLettersConcept()
                }

                DIALPAD_GRID -> {
                    val surfaceColor =
                        if (isDynamicTheme() && !isSystemInDarkMode()) getProperBackgroundColor() else getSurfaceColor()

                    dialpadClearWrapper.apply {
                        dialpadVoicemail.beVisibleIf(config.showVoicemailIcon && !config.hideDialpadLetters)
                        dialpadGridHolder.setBackgroundColor(Color.TRANSPARENT)

                        arrayOf(
                            dividerHorizontalZero, dividerHorizontalOne, dividerHorizontalTwo, dividerHorizontalThree,
                            dividerHorizontalFour, dividerVerticalOne, dividerVerticalTwo, dividerVerticalStart, dividerVerticalEnd
                        ).forEach {
                            it.beInvisible()
                        }

                        dialpadBottomMargin.apply {
                            setBackgroundColor(Color.TRANSPARENT)
                            setHeight(100)
                        }
                    }

                    initLetters()
                }

                else -> {
                    val surfaceColor =
                        if (isDynamicTheme() && !isSystemInDarkMode()) getProperBackgroundColor() else getSurfaceColor()

                    dialpadClearWrapper.apply {
                        dialpadVoicemail.beVisibleIf(config.showVoicemailIcon && !config.hideDialpadLetters)
                        dialpadGridHolder.setBackgroundColor(Color.TRANSPARENT)

                        arrayOf(
                            dividerHorizontalZero, dividerHorizontalOne, dividerHorizontalTwo, dividerHorizontalThree,
                            dividerHorizontalFour, dividerVerticalOne, dividerVerticalTwo, dividerVerticalStart, dividerVerticalEnd
                        ).forEach {
                            it.beInvisible()
                        }

                        dialpadBottomMargin.apply {
                            setBackgroundColor(Color.TRANSPARENT)
                            setHeight(100)
                        }
                    }
                    initLetters()
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initLettersConcept() {
        val areMultipleSIMsAvailable = hasMultipleSims
        val baseColor = baseConfig.backgroundColor
        val buttonColor = when {
            isDynamicTheme() -> resources.getColor(R.color.you_status_bar_color, theme)
            baseColor == Color.WHITE -> resources.getColor(R.color.dark_grey, theme)
            baseColor == Color.BLACK -> resources.getColor(R.color.bottom_tabs_black_background, theme)
            else -> baseConfig.backgroundColor.lightenColor(4)
        }
        val textColor = buttonColor.getContrastColor()
        binding.dialpadRectWrapper.apply {
            if (config.hideDialpadLetters) {
                arrayOf(
                    dialpad1Letters, dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters,
                    dialpad6Letters, dialpad7Letters, dialpad8Letters, dialpad9Letters
                ).forEach {
                    it.beGone()
                }
            } else {
                val typeface = FontHelper.getTypeface(this@SettingsDialpadActivity)

                dialpad1Letters.apply {
                    beInvisible()
                    setTypeface(typeface, config.dialpadSecondaryTypeface)
                }
                arrayOf(
                    dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters, dialpad6Letters,
                    dialpad7Letters, dialpad8Letters, dialpad9Letters
                ).forEach {
                    it.beVisible()
                    it.setTypeface(typeface, config.dialpadSecondaryTypeface)
                }

                DialpadT9.ensureInitialized(this@SettingsDialpadActivity)
                val langPref = config.dialpadSecondaryLanguage
                val langLocale = Locale.getDefault().language
                val isAutoLang = DialpadT9.getSupportedSecondaryLanguages().contains(langLocale) && langPref == LANGUAGE_SYSTEM
                if (langPref!! != LANGUAGE_NONE && langPref != LANGUAGE_SYSTEM || isAutoLang) {
                    val lang = if (isAutoLang) langLocale else langPref
                    val fontSize = getTextSize() - 16f
                    dialpad1Letters.text = DialpadT9.getLettersForNumber(2, lang) + "\nABC"
                    dialpad2Letters.text = DialpadT9.getLettersForNumber(2, lang) + "\nABC"
                    dialpad3Letters.text = DialpadT9.getLettersForNumber(3, lang) + "\nDEF"
                    dialpad4Letters.text = DialpadT9.getLettersForNumber(4, lang) + "\nGHI"
                    dialpad5Letters.text = DialpadT9.getLettersForNumber(5, lang) + "\nJKL"
                    dialpad6Letters.text = DialpadT9.getLettersForNumber(6, lang) + "\nMNO"
                    dialpad7Letters.text = DialpadT9.getLettersForNumber(7, lang) + "\nPQRS"
                    dialpad8Letters.text = DialpadT9.getLettersForNumber(8, lang) + "\nTUV"
                    dialpad9Letters.text = DialpadT9.getLettersForNumber(9, lang) + "\nWXYZ"

                    arrayOf(
                        dialpad1Letters, dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters,
                        dialpad6Letters, dialpad7Letters, dialpad8Letters, dialpad9Letters
                    ).forEach {
                        it.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
                    }
                } else {
                    dialpad1Letters.text = "ABC"
                    dialpad2Letters.text = "ABC"
                    dialpad3Letters.text = "DEF"
                    dialpad4Letters.text = "GHI"
                    dialpad5Letters.text = "JKL"
                    dialpad6Letters.text = "MNO"
                    dialpad7Letters.text = "PQRS"
                    dialpad8Letters.text = "TUV"
                    dialpad9Letters.text = "WXYZ"

                    val fontSize = getTextSize() - 8f
                    arrayOf(
                        dialpad1Letters, dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters,
                        dialpad6Letters, dialpad7Letters, dialpad8Letters, dialpad9Letters
                    ).forEach {
                        it.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
                    }
                }
            }

            arrayOf(
                dialpad1, dialpad2, dialpad3, dialpad4, dialpad5,
                dialpad6, dialpad7, dialpad8, dialpad9, dialpad0,
                dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters, dialpad6Letters,
                dialpad7Letters, dialpad8Letters, dialpad9Letters, dialpadPlus
            ).forEach {
                it.setTextColor(textColor)
            }

            arrayOf(
                dialpad0Holder,
                dialpad1Holder,
                dialpad2Holder,
                dialpad3Holder,
                dialpad4Holder,
                dialpad5Holder,
                dialpad6Holder,
                dialpad7Holder,
                dialpad8Holder,
                dialpad9Holder,
                dialpadAsteriskHolder,
                dialpadHashtagHolder
            ).forEach {
                it.background = ResourcesCompat.getDrawable(resources, R.drawable.button_dialpad_background, theme)
                it.background.applyColorFilter(buttonColor)
                it.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    val margin = pixels(R.dimen.one_dp).toInt()
                    setMargins(margin, margin, margin, margin)
                }
            }

            //reduce the size of unnecessary buttons so that they don't look bigger than the others
            arrayOf(
                dialpadDownHolder,
                dialpadCallButtonHolder,
                dialpadClearCharHolder
            ).forEach {
                it.background = ResourcesCompat.getDrawable(resources, R.drawable.button_dialpad_background, theme)
                it.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    val margin = pixels(R.dimen.one_dp).toInt()
                    val marginBottom = pixels(R.dimen.tiny_margin).toInt()
                    setMargins(margin, margin, margin, marginBottom)
                }
            }

            dialpadGridWrapper.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                val margin = pixels(R.dimen.tiny_margin).toInt()
                setMargins(margin, margin, margin, margin)
            }

            arrayOf(
                binding.dialpadRectWrapper.dialpadAsterisk, binding.dialpadRectWrapper.dialpadHashtag,
                binding.dialpadRectWrapper.dialpadVoicemail
            ).forEach {
                it.applyColorFilter(textColor)
            }

            val simOnePrimary = config.currentSIMCardIndex == 0
            val simTwoColor = if (areMultipleSIMsAvailable) {
                if (simOnePrimary) config.simIconsColors[2] else config.simIconsColors[1]
            } else getProperPrimaryColor()
            dialpadDownHolder.background.applyColorFilter(simTwoColor)
            val drawableSecondary = if (simOnePrimary) R.drawable.ic_phone_two_vector else R.drawable.ic_phone_one_vector
            val dialpadIconColor = if (simTwoColor == Color.WHITE) simTwoColor.getContrastColor() else textColor
            val downIcon = if (areMultipleSIMsAvailable) resources.getColoredDrawableWithColor(this@SettingsDialpadActivity, drawableSecondary, dialpadIconColor)
            else resources.getColoredDrawableWithColor(this@SettingsDialpadActivity, R.drawable.ic_dialpad_vector, dialpadIconColor)
            dialpadDown.setImageDrawable(downIcon)

            val simOneColor = if (simOnePrimary) config.simIconsColors[1] else config.simIconsColors[2]
            val drawablePrimary = if (simOnePrimary) R.drawable.ic_phone_one_vector else R.drawable.ic_phone_two_vector
            val callIconId = if (areMultipleSIMsAvailable) drawablePrimary else R.drawable.ic_phone_vector
            val callIconColor = if (simOneColor == Color.WHITE) simOneColor.getContrastColor() else textColor
            val callIcon = resources.getColoredDrawableWithColor(this@SettingsDialpadActivity, callIconId, callIconColor)
            dialpadCallIcon.setImageDrawable(callIcon)
            dialpadCallButtonHolder.background.applyColorFilter(simOneColor)

            dialpadClearCharHolder.beVisible()
            dialpadClearCharHolder.background.applyColorFilter(getColor(R.color.red_call))
            dialpadClearChar.alpha = 1f
            dialpadClearChar.applyColorFilter(textColor)
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initLetters() {
        binding.dialpadClearWrapper.apply {
            if (config.hideDialpadLetters) {
                arrayOf(
                    dialpad1Letters, dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters,
                    dialpad6Letters, dialpad7Letters, dialpad8Letters, dialpad9Letters
                ).forEach {
                    it.beGone()
                }
            } else {
                val typeface = FontHelper.getTypeface(this@SettingsDialpadActivity)

                dialpad1Letters.apply {
                    beInvisible()
                    setTypeface(typeface, config.dialpadSecondaryTypeface)
                }
                arrayOf(
                    dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters,
                    dialpad6Letters, dialpad7Letters, dialpad8Letters, dialpad9Letters
                ).forEach {
                    it.beVisible()
                    it.setTypeface(typeface, config.dialpadSecondaryTypeface)
                }

                DialpadT9.ensureInitialized(this@SettingsDialpadActivity)
                val langPref = config.dialpadSecondaryLanguage
                val langLocale = Locale.getDefault().language
                val isAutoLang = DialpadT9.getSupportedSecondaryLanguages().contains(langLocale) && langPref == LANGUAGE_SYSTEM
                if (langPref!! != LANGUAGE_NONE && langPref != LANGUAGE_SYSTEM || isAutoLang) {
                    val lang = if (isAutoLang) langLocale else langPref
                    dialpad1Letters.text = DialpadT9.getLettersForNumber(2, lang) + "\nABC"
                    dialpad2Letters.text = DialpadT9.getLettersForNumber(2, lang) + "\nABC"
                    dialpad3Letters.text = DialpadT9.getLettersForNumber(3, lang) + "\nDEF"
                    dialpad4Letters.text = DialpadT9.getLettersForNumber(4, lang) + "\nGHI"
                    dialpad5Letters.text = DialpadT9.getLettersForNumber(5, lang) + "\nJKL"
                    dialpad6Letters.text = DialpadT9.getLettersForNumber(6, lang) + "\nMNO"
                    dialpad7Letters.text = DialpadT9.getLettersForNumber(7, lang) + "\nPQRS"
                    dialpad8Letters.text = DialpadT9.getLettersForNumber(8, lang) + "\nTUV"
                    dialpad9Letters.text = DialpadT9.getLettersForNumber(9, lang) + "\nWXYZ"

                    val fontSizeRu = getTextSize() - 16f
                    arrayOf(
                        dialpad1Letters, dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters,
                        dialpad6Letters, dialpad7Letters, dialpad8Letters, dialpad9Letters
                    ).forEach {
                        it.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizeRu)
                        it.applyDialpadLetterRows(twoRows = true)
                    }
                } else {
                    dialpad1Letters.text = "ABC"
                    dialpad2Letters.text = "ABC"
                    dialpad3Letters.text = "DEF"
                    dialpad4Letters.text = "GHI"
                    dialpad5Letters.text = "JKL"
                    dialpad6Letters.text = "MNO"
                    dialpad7Letters.text = "PQRS"
                    dialpad8Letters.text = "TUV"
                    dialpad9Letters.text = "WXYZ"

                    val fontSize = getTextSize() - 8f
                    arrayOf(
                        dialpad1Letters, dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters,
                        dialpad6Letters, dialpad7Letters, dialpad8Letters, dialpad9Letters
                    ).forEach {
                        it.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
                        it.applyDialpadLetterRows(twoRows = false)
                    }
                }
            }

            val areMultipleSIMsAvailable = hasMultipleSims
            val simOnePrimary = config.currentSIMCardIndex == 0
            if (areMultipleSIMsAvailable) {
                dialpadCallTwoButton.beVisible()
                val simTwoColor = if (simOnePrimary) config.simIconsColors[2] else config.simIconsColors[1]
                val drawableSecondary = if (simOnePrimary) R.drawable.ic_phone_two_vector else R.drawable.ic_phone_one_vector
                val callIcon = resources.getColoredDrawableWithColor(this@SettingsDialpadActivity, drawableSecondary, simTwoColor.getContrastColor())
                dialpadCallTwoButton.setImageDrawable(callIcon)
                dialpadCallTwoButton.background.applyColorFilter(simTwoColor)
                dialpadCallTwoButton.beVisible()
            } else {
                dialpadCallTwoButton.beGone()
            }

            val simOneColor = if (simOnePrimary) config.simIconsColors[1] else config.simIconsColors[2]
            val drawablePrimary = if (simOnePrimary) R.drawable.ic_phone_one_vector else R.drawable.ic_phone_two_vector
            val callIconId = if (areMultipleSIMsAvailable) drawablePrimary else R.drawable.ic_phone_vector
            val callIcon = resources.getColoredDrawableWithColor(this@SettingsDialpadActivity, callIconId, simOneColor.getContrastColor())
            dialpadCallButton.setImageDrawable(callIcon)
            dialpadCallButton.background.applyColorFilter(simOneColor)

            dialpadClearCharHolder.beVisibleIf(areMultipleSIMsAvailable)
            dialpadClearChar.applyColorFilter(Color.GRAY)
            dialpadClearChar.alpha = 0.4f
            dialpadClearCharX.applyColorFilter(getProperTextColor())
        }
    }

    @SuppressLint("SetTextI18n")
    private fun initLettersIos() {
        binding.dialpadRoundWrapper.apply {
            if (config.hideDialpadLetters) {
                arrayOf(
                    dialpad2IosLetters, dialpad3IosLetters, dialpad4IosLetters, dialpad5IosLetters,
                    dialpad6IosLetters, dialpad7IosLetters, dialpad8IosLetters, dialpad9IosLetters,
                    dialpad1IosLetters
                ).forEach {
                    it.beGone()
                }
            } else {
                val typeface = FontHelper.getTypeface(this@SettingsDialpadActivity)

                dialpad1IosLetters.apply {
                    beInvisible()
                    setTypeface(typeface, config.dialpadSecondaryTypeface)
                }
                arrayOf(
                    dialpad2IosLetters, dialpad3IosLetters, dialpad4IosLetters, dialpad5IosLetters,
                    dialpad6IosLetters, dialpad7IosLetters, dialpad8IosLetters, dialpad9IosLetters
                ).forEach {
                    it.beVisible()
                    it.setTypeface(typeface, config.dialpadSecondaryTypeface)
                }


                DialpadT9.ensureInitialized(this@SettingsDialpadActivity)
                val langPref = config.dialpadSecondaryLanguage
                val langLocale = Locale.getDefault().language
                val isAutoLang = DialpadT9.getSupportedSecondaryLanguages().contains(langLocale) && langPref == LANGUAGE_SYSTEM
                if (langPref!! != LANGUAGE_NONE && langPref != LANGUAGE_SYSTEM || isAutoLang) {
                    val lang = if (isAutoLang) langLocale else langPref
                    dialpad1IosLetters.text = DialpadT9.getLettersForNumber(2, lang) + "\nABC"
                    dialpad2IosLetters.text = DialpadT9.getLettersForNumber(2, lang) + "\nABC"
                    dialpad3IosLetters.text = DialpadT9.getLettersForNumber(3, lang) + "\nDEF"
                    dialpad4IosLetters.text = DialpadT9.getLettersForNumber(4, lang) + "\nGHI"
                    dialpad5IosLetters.text = DialpadT9.getLettersForNumber(5, lang) + "\nJKL"
                    dialpad6IosLetters.text = DialpadT9.getLettersForNumber(6, lang) + "\nMNO"
                    dialpad7IosLetters.text = DialpadT9.getLettersForNumber(7, lang) + "\nPQRS"
                    dialpad8IosLetters.text = DialpadT9.getLettersForNumber(8, lang) + "\nTUV"
                    dialpad9IosLetters.text = DialpadT9.getLettersForNumber(9, lang) + "\nWXYZ"

                    val fontSizeRu = getTextSize() - 16f
                    arrayOf(
                        dialpad1IosLetters, dialpad2IosLetters, dialpad3IosLetters, dialpad4IosLetters, dialpad5IosLetters,
                        dialpad6IosLetters, dialpad7IosLetters, dialpad8IosLetters, dialpad9IosLetters
                    ).forEach {
                        it.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSizeRu)
                    }
                } else {
                    dialpad1IosLetters.text = "ABC"
                    dialpad2IosLetters.text = "ABC"
                    dialpad3IosLetters.text = "DEF"
                    dialpad4IosLetters.text = "GHI"
                    dialpad5IosLetters.text = "JKL"
                    dialpad6IosLetters.text = "MNO"
                    dialpad7IosLetters.text = "PQRS"
                    dialpad8IosLetters.text = "TUV"
                    dialpad9IosLetters.text = "WXYZ"

                    val fontSize = getTextSize() - 8f
                    arrayOf(
                        dialpad1IosLetters, dialpad2IosLetters, dialpad3IosLetters, dialpad4IosLetters, dialpad5IosLetters,
                        dialpad6IosLetters, dialpad7IosLetters, dialpad8IosLetters, dialpad9IosLetters
                    ).forEach {
                        it.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
                    }
                }
            }

            val areMultipleSIMsAvailable = hasMultipleSims
            val getProperTextColor = getProperTextColor()
            if (areMultipleSIMsAvailable) {
                dialpadSimIosHolder.beVisible()
                dialpadSimIos.background.applyColorFilter(Color.GRAY)
                dialpadSimIos.background.alpha = 60
                dialpadSimIos.applyColorFilter(getProperTextColor)
                updateCallButton()
            } else {
                dialpadSimIosHolder.beGone()
                val color = config.simIconsColors[1]
                val callIcon = resources.getColoredDrawableWithColor(this@SettingsDialpadActivity, R.drawable.ic_phone_vector, color.getContrastColor())
                dialpadCallButtonIosIcon.setImageDrawable(callIcon)
                dialpadCallButtonIosHolder.background.applyColorFilter(color)
            }

            dialpadClearCharIos.applyColorFilter(Color.GRAY)
            dialpadClearCharIos.alpha = 0.235f
            dialpadClearCharXIos.applyColorFilter(getProperTextColor)
            dialpadClearCharIosHolder.beVisibleIf(areMultipleSIMsAvailable)
        }
    }

    private fun updateCallButton() {
        val oneSim = config.currentSIMCardIndex == 0
        val simColor = if (oneSim) config.simIconsColors[1] else config.simIconsColors[2]
        val callIconId = if (oneSim) R.drawable.ic_phone_one_vector else R.drawable.ic_phone_two_vector
        val callIcon = resources.getColoredDrawableWithColor(this@SettingsDialpadActivity, callIconId, simColor.getContrastColor())
        binding.dialpadRoundWrapper.dialpadCallButtonIosIcon.setImageDrawable(callIcon)
        binding.dialpadRoundWrapper.dialpadCallButtonIosHolder.background.applyColorFilter(simColor)
    }

    private fun updateDialpadNumberFontSize(percent: Int = config.dialpadNumberFontSize) {
        applyDialpadNumberFontSize(binding.dialpadClearWrapper, binding.dialpadRectWrapper, binding.dialpadRoundWrapper, percent)
    }

    private fun updateDialpadSize(percent: Int = config.dialpadSize) {
        val view = when (config.dialpadStyle) {
            DIALPAD_IOS -> binding.dialpadRoundWrapper.dialpadIosWrapper
            DIALPAD_CONCEPT -> binding.dialpadRectWrapper.dialpadGridWrapper
            else -> binding.dialpadClearWrapper.dialpadGridWrapper
        }
        val dimens = when (config.dialpadStyle) {
            DIALPAD_IOS -> pixels(R.dimen.dialpad_ios_height)
            DIALPAD_CONCEPT -> pixels(R.dimen.dialpad_grid_height)
            else -> pixels(R.dimen.dialpad_pill_grid_height)
        }
        view.setHeight((dimens * (percent / 100f)).toInt())
    }

    private fun updateDialpadBottomMargin(margin: Int = config.dialpadBottomMargin) {
        val marginView = when (config.dialpadStyle) {
            DIALPAD_IOS -> binding.dialpadRoundWrapper.dialpadBottomMargin
            DIALPAD_CONCEPT -> binding.dialpadRectWrapper.dialpadBottomMargin
            else -> binding.dialpadClearWrapper.dialpadBottomMargin
        }
        val start =
            if (config.dialpadStyle == DIALPAD_IOS) pixels(R.dimen.dialpad_margin_bottom_ios)
            else pixels(R.dimen.zero)
        marginView.setHeight((start + margin).toInt())
    }

    private fun updateCallButtonSize() {
        val size = config.callButtonPrimarySize
        val view = binding.dialpadClearWrapper.dialpadCallButton
        val dimens = pixels(R.dimen.dialpad_phone_button_size)
        view.setHeightAndWidth((dimens * (size / 100f)).toInt())
        view.setPadding((dimens * 0.1765 * (size / 100f)).toInt())

        if (hasMultipleSims) {
            val sizeSecondary = config.callButtonSecondarySize
            val viewSecondary = binding.dialpadClearWrapper.dialpadCallTwoButton
            val dimensSecondary = pixels(R.dimen.dialpad_button_size_small)
            viewSecondary.setHeightAndWidth((dimensSecondary * (sizeSecondary / 100f)).toInt())
            viewSecondary.setPadding((dimens * 0.1765 * (sizeSecondary / 100f)).toInt())
        }
    }

    private fun showDialpad() {
        val view = when (config.dialpadStyle) {
            DIALPAD_IOS -> binding.dialpadRoundWrapper.root
            DIALPAD_CONCEPT -> binding.dialpadRectWrapper.root
            else -> binding.dialpadClearWrapper.root
        }
        view.beVisible()
        hideDialpadHandler.removeCallbacks(updateHideDialpadTask)
        hideDialpadHandler.postDelayed(updateHideDialpadTask, 2000)
    }

    private val updateHideDialpadTask = Runnable {
        val view = when (config.dialpadStyle) {
            DIALPAD_IOS -> binding.dialpadRoundWrapper.root
            DIALPAD_CONCEPT -> binding.dialpadRectWrapper.root
            else -> binding.dialpadClearWrapper.root
        }
        view.beGone()
    }

    private fun setupPrimarySimCard() {
        val simList = getAvailableSIMCardLabels()
        if (simList.size > 1) {
            binding.settingsPrimarySimCardHolder.beVisibleIf(hasMultipleSims)
            binding.settingsPrimarySimCard.text = if (config.currentSIMCardIndex == 0) simList[0].label else simList[1].label
            binding.settingsPrimarySimCardHolder.setOnClickListener {
                val items = arrayListOf(
                    RadioItem(0, simList[0].label),
                    RadioItem(1, simList[1].label),
                )

                RadioGroupDialog(this@SettingsDialpadActivity, items, config.currentSIMCardIndex, R.string.primary_sim_card) {
                    config.currentSIMCardIndex = it as Int
                    binding.settingsPrimarySimCard.text = if (config.currentSIMCardIndex == 0) simList[0].label else simList[1].label
                    initStyle()
                    showDialpad()
                }
            }
        } else binding.settingsPrimarySimCardHolder.beGone()
    }

    private fun getLanguageName(lang: String?): String? {
        return when (lang) {
            LANGUAGE_NONE -> getString(R.string.none)
            LANGUAGE_SYSTEM -> getString(R.string.auto_theme)
            else -> {
                val currentLocale = Locale.getDefault()
                val locale = Locale(lang!!)
                locale.getDisplayLanguage(currentLocale)
            }
        }
    }

    private fun setupDialpadSecondaryLanguage() {
        binding.settingsDialpadSecondaryLanguage.text = getLanguageName(config.dialpadSecondaryLanguage)
        binding.settingsDialpadSecondaryLanguageHolder.setOnClickListener {
            val items: ArrayList<RadioItem> = arrayListOf(
                RadioItem(SECONDARY_LANGUAGE_NONE_ID, getString(R.string.none)),
                RadioItem(SECONDARY_LANGUAGE_SYSTEM_ID, getString(R.string.auto_theme))
            )
            val supportedLanguages = DialpadT9.getSupportedSecondaryLanguages()
            for (i in supportedLanguages.indices) {
                items.add(RadioItem(i, getLanguageName(supportedLanguages[i])!!))
            }
            val checkedItemId =
                if (config.dialpadSecondaryLanguage == LANGUAGE_SYSTEM) SECONDARY_LANGUAGE_SYSTEM_ID
                else supportedLanguages.indexOf(config.dialpadSecondaryLanguage)

            RadioGroupDialog(
                this@SettingsDialpadActivity,
                items,
                checkedItemId,
                R.string.secondary_dialpad_language,
                defaultItemId = SECONDARY_LANGUAGE_SYSTEM_ID
            ) {
                val index = it as Int
                if (index == -2) {
                    config.dialpadSecondaryLanguage = LANGUAGE_SYSTEM
                } else if (index == -1 || index >= supportedLanguages.size) {
                    config.dialpadSecondaryLanguage = LANGUAGE_NONE
                } else {
                    config.dialpadSecondaryLanguage = supportedLanguages[it]
                }
                binding.settingsDialpadSecondaryLanguage.text = getLanguageName(config.dialpadSecondaryLanguage)
                initStyle()
                showDialpad()
                config.needRestart = true
            }
        }
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        try {
            super.onRestoreInstanceState(savedInstanceState)
        } catch (_: ClassCastException) {
            // Ignored error restoring ProgressBar state when changing system theme
            Log.w("ProgressBarFix", "Ignoring ProgressBar state restoration error")
        }
    }

    private fun checkPro(collection: Boolean = resources.getBoolean(R.bool.show_collection)) =
        if (collection) isOrWasThankYouInstalled(false) || isPro() || isCollection()
        else isOrWasThankYouInstalled(false) || isPro()
}
