package com.goodwy.dialer.activities

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog.Calls
import android.telephony.PhoneNumberFormattingTextWatcher
import android.telephony.TelephonyManager
import android.util.TypedValue
import android.view.*
import android.view.View.OnFocusChangeListener
import android.widget.FrameLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import com.behaviorule.arturdumchev.library.pixels
import com.behaviorule.arturdumchev.library.setHeight
import com.goodwy.commons.dialogs.CallConfirmationDialog
import com.goodwy.commons.dialogs.ConfirmationAdvancedDialog
import com.goodwy.commons.dialogs.ConfirmationDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.dialer.BuildConfig
import com.goodwy.dialer.R
import com.goodwy.dialer.adapters.ContactsAdapter
import com.goodwy.dialer.adapters.RecentCallsAdapter
import com.goodwy.dialer.databinding.ActivityDialpadBinding
import com.goodwy.dialer.extensions.*
import com.goodwy.dialer.helpers.*
import com.goodwy.dialer.models.RecentCall
import com.goodwy.dialer.models.SpeedDial
import com.goodwy.dialer.helpers.gson
import io.reactivex.rxjava3.disposables.CompositeDisposable
import com.mikhaellopez.rxanimation.RxAnimation
import com.mikhaellopez.rxanimation.shake
import me.grantland.widget.AutofitHelper
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import androidx.core.view.isGone
import com.goodwy.dialer.models.Events
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class DialpadActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityDialpadBinding::inflate)

    // The listed contacts and the snapshot whose search keys cover them, swapped together.
    private class ContactSource(val contacts: List<Contact>, val snapshot: ContactsRepository.Snapshot)
    private class SearchResult(val contacts: ArrayList<Contact>?, val recents: List<RecentCall>?)

    @Volatile private var contactSource = ContactSource(emptyList(), ContactsRepository.snapshot)
    @Volatile private var allRecentCalls = listOf<RecentCall>()
    private val searchExecutor = Executors.newSingleThreadExecutor()
    private val searchHandler = Handler(Looper.getMainLooper())
    private val searchRunnable = Runnable { runSearch() }
    @Volatile private var searchGeneration = 0 // written on the main thread only
    // Only touched on the search thread.
    private val collator by lazy { Collator.getInstance(sysLocale()) }
    private var callCountsSource: List<RecentCall>? = null
    private var callCounts: Map<String, Int> = emptyMap()
    private var seenRepositoryVersion = ContactsRepository.version
    private var contactsLoadedOnce = false
    // Contacts added or edited while this screen is open (e.g. from the "add number" flow) show up in the search.
    private val repositoryListener: () -> Unit = {
        if (!isDestroyed && !isFinishing) loadContacts()
    }
    private var speedDialValues = ArrayList<SpeedDial>()
    private var toneGeneratorHelper: ToneGeneratorHelper? = null
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val pressedKeys = mutableSetOf<Char>()
    private var hasBeenScrolled = false
    private var clearOnReturn = false
    private val animationDisposables = CompositeDisposable()
    private var storedDialpadStyle = 0
    private var storedBackgroundColor = 0
    private var storedDisplaySettings = ""
    private var hasMultipleSims = false
    private var storedToneVolume = 0
    private var recentsAdapter: RecentCallsAdapter? = null
    private var recentsHelper = RecentsHelper(this)
    private var isTalkBackOn = false
    private var initSearch = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        val numberInRow = config.dialpadStyle != DIALPAD_IOS && config.dialpadStyle != DIALPAD_CONCEPT
        if (numberInRow) moveNumberInputIntoKeypadRow()

        binding.apply {
            setupEdgeToEdge(
                padBottomSystem = listOf(
                    dialpadClearWrapper.dialpadGridLayout,
                    dialpadRoundWrapper.dialpadIosWrapper,
                    dialpadRectWrapper.dialpadGridWrapper
                ),
                moveTopSystem = if (numberInRow) emptyList() else listOf(dialpadInput),
                moveBottomSystem = listOf(dialpadRoundWrapperUp),
//                padBottomImeAndSystem = listOf(dialpadList, dialpadHolder)
            )
        }

        EventBus.getDefault().register(this)

        if (checkAppSideloading()) {
            return
        }

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


        toneGeneratorHelper = ToneGeneratorHelper(this, DIALPAD_TONE_LENGTH_MS)
        binding.dialpadList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (!hasBeenScrolled) {
                    hasBeenScrolled = true
                    slideDown(dialpadView())
                }
            }
        })
        binding.dialpadRecentsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (!hasBeenScrolled) {
                    hasBeenScrolled = true
                    slideDown(dialpadView())
                }
            }
        })

        binding.dialpadInput.apply {
            if (config.formatPhoneNumbers) {
                @Suppress("DEPRECATION")
                addTextChangedListener(PhoneNumberFormattingTextWatcher(Locale.getDefault().country))
            }
            onTextChangeListener { dialpadValueChanged(it) }
            requestFocus()
            AutofitHelper.create(this@apply)
            disableKeyboard()
        }

        binding.dialpadRoundWrapperUp.setOnClickListener { dialpadHide() }
        val view = dialpadView()
        binding.dialpadInput.setOnClickListener {
            if (view.isGone) dialpadHide()
        }
        binding.dialpadInput.onFocusChangeListener = OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (view.isGone) dialpadHide()
            }
        }

        loadContacts()
        storedDialpadStyle = config.dialpadStyle
        storedToneVolume = config.toneVolume
        storedBackgroundColor = getProperBackgroundColor()
        storedDisplaySettings = displaySettingsKey()
    }

    @SuppressLint("UnsafeIntentLaunch")
    override fun onResume() {
        super.onResume()
        if (storedDialpadStyle != config.dialpadStyle || config.needRestart || storedBackgroundColor != getProperBackgroundColor() ||
            storedDisplaySettings != displaySettingsKey()
        ) {
            finish()
            startActivity(intent)
            return
        }
        hasMultipleSims = areMultipleSIMsAvailable()

        if (storedToneVolume != config.toneVolume) {
            toneGeneratorHelper?.release()
            toneGeneratorHelper = ToneGeneratorHelper(this, DIALPAD_TONE_LENGTH_MS)
        }
        if (clearOnReturn) {
            clearOnReturn = false
            clearInput()
        }
        isTalkBackOn = isTalkBackOn()

        ContactsRepository.addListener(repositoryListener)
        if (contactsLoadedOnce && seenRepositoryVersion != ContactsRepository.version) loadContacts()


        binding.dialpadCoordinator.applyLiquidBackdrop()
        speedDialValues = config.getSpeedDialValues()
        initStyle()
        updateDialpadSize()
        updateDialpadNumberFontSize()
        if (config.dialpadStyle == DIALPAD_GRID || config.dialpadStyle == DIALPAD_ORIGINAL) updateCallButtonSize()
        setupOptionsMenu()
        refreshMenuItems()
        updateTextColors(binding.dialpadCoordinator)

        val properTextColor = getProperTextColor()
        val properBackgroundColor =
            if (isDynamicTheme() && !isSystemInDarkMode()) getSurfaceColor() else getProperBackgroundColor()
        val properPrimaryColor = getProperPrimaryColor()
        setupTopAppBar(
            topAppBar = binding.dialpadAppbar,
            navigationIcon = NavigationIcon.Arrow,
            navigationClick = false
        )
        // Clear the bar after setup: passing a transparent topBarColor makes commons pick light
        // status-bar icons, which vanish on the light backdrop.
        binding.dialpadAppbar.setBackgroundColor(Color.TRANSPARENT)
        binding.dialpadRecentsList.setBackgroundColor(Color.TRANSPARENT)
        binding.dialpadList.setBackgroundColor(Color.TRANSPARENT)
        binding.dialpadToolbar.setBackgroundColor(Color.TRANSPARENT)

        binding.dialpadRectWrapper.root.applyGlassSurface()
        binding.dialpadAddNumber.setTextColor(properPrimaryColor)

        binding.letterFastscroller.textColor = properTextColor.getColorStateList()
        binding.letterFastscroller.pressedTextColor = properPrimaryColor
        binding.letterFastscrollerThumb.setupWithFastScroller(binding.letterFastscroller)
        binding.letterFastscrollerThumb.textColor = properPrimaryColor.getContrastColor()
        binding.letterFastscrollerThumb.thumbColor = properPrimaryColor.getColorStateList()

        invalidateOptionsMenu()

        if (config.showRecentCallsOnDialpad) refreshItems()

        if (binding.dialpadInput.value.isEmpty()) {
            binding.dialpadRecentsList.beVisibleIf(
                binding.dialpadInput.value.isEmpty() && config.showRecentCallsOnDialpad
            )
        }
    }

    override fun onPause() {
        super.onPause()
        ContactsRepository.removeListener(repositoryListener)
        longPressHandler.removeCallbacksAndMessages(null)
        storedDialpadStyle = config.dialpadStyle
        storedToneVolume = config.toneVolume
        storedBackgroundColor = getProperBackgroundColor()
        storedDisplaySettings = displaySettingsKey()
        config.needRestart = false
    }

    private fun initStyle() {
        DialpadT9.ensureInitialized(this)
        when (config.dialpadStyle) {
            DIALPAD_IOS -> {
                val properBackgroundColor =
                    if (isDynamicTheme() && !isSystemInDarkMode()) getSurfaceColor() else getProperBackgroundColor()

                binding.dialpadClearWrapper.root.beGone()
                binding.dialpadRectWrapper.root.beGone()
                binding.dialpadRoundWrapper.apply {
                    dialpadVoicemail.beVisibleIf(config.showVoicemailIcon && !config.hideDialpadLetters)
                    dialpadIosHolder.beVisible()
                    dialpadIosHolder.setBackgroundColor(Color.TRANSPARENT)
                    root.applyGlassSurface()

                    arrayOf(
                        dialpad0IosHolder, dialpad1IosHolder, dialpad2IosHolder, dialpad3IosHolder, dialpad4IosHolder,
                        dialpad5IosHolder, dialpad6IosHolder, dialpad7IosHolder, dialpad8IosHolder, dialpad9IosHolder,
                        dialpadAsteriskIosHolder, dialpadHashtagIosHolder
                    ).forEach {
                        it.foreground.applyColorFilter(Color.GRAY)
                        it.foreground.alpha = 60
                    }

                    val properTextColor = getProperTextColor()
                    arrayOf(dialpadAsteriskIos, dialpadHashtagIos, dialpadVoicemail
                    ).forEach {
                        it.applyColorFilter(properTextColor)
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

                binding.dialpadRectWrapper.apply {
                    dialpadVoicemail.beVisibleIf(config.showVoicemailIcon && !config.hideDialpadLetters)
                    dialpadGridWrapper.setBackgroundColor(Color.TRANSPARENT)

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
                binding.dialpadRoundWrapper.root.beGone()
                binding.dialpadClearWrapper.root.beGone()
                initLettersConcept()
            }

            DIALPAD_GRID -> {
                val surfaceColor =
                    if (isDynamicTheme() && !isSystemInDarkMode()) getProperBackgroundColor() else getSurfaceColor()

                binding.dialpadRoundWrapper.root.beGone()
                binding.dialpadRectWrapper.root.beGone()
                binding.dialpadClearWrapper.apply {
                    dialpadVoicemail.beVisibleIf(config.showVoicemailIcon && !config.hideDialpadLetters)
                    dialpadGridHolder.beVisible()
                    dialpadGridHolder.setBackgroundColor(Color.TRANSPARENT)
                    root.applyGlassSurface()

                    if (isPiePlus()) {
                        val textColor = getProperTextColor()
                        dialpadGridHolder.outlineAmbientShadowColor = textColor
                        dialpadGridHolder.outlineSpotShadowColor = textColor
                    }

                    arrayOf(
                        dividerHorizontalZero, dividerHorizontalOne, dividerHorizontalTwo, dividerHorizontalThree,
                        dividerHorizontalFour, dividerVerticalOne, dividerVerticalTwo, dividerVerticalStart, dividerVerticalEnd
                    ).forEach {
                        it.beInvisible()
                    }

                    val properTextColor = getProperTextColor()
                    arrayOf(dialpadAsterisk, dialpadHashtag, dialpadVoicemail
                    ).forEach {
                        it.applyColorFilter(properTextColor)
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

                binding.dialpadRoundWrapper.root.beGone()
                binding.dialpadRectWrapper.root.beGone()
                binding.dialpadClearWrapper.apply {
                    dialpadVoicemail.beVisibleIf(config.showVoicemailIcon && !config.hideDialpadLetters)
                    dialpadGridHolder.beVisible()
                    root.applyGlassSurface()
                    dialpadNumberUnderline.setBackgroundColor(getProperPrimaryColor())

                    if (isPiePlus()) {
                        val textColor = getProperTextColor()
                        dialpadGridHolder.outlineAmbientShadowColor = textColor
                        dialpadGridHolder.outlineSpotShadowColor = textColor
                    }

                    arrayOf(
                        dividerHorizontalZero, dividerHorizontalOne, dividerHorizontalTwo, dividerHorizontalThree,
                        dividerHorizontalFour, dividerVerticalOne, dividerVerticalTwo, dividerVerticalStart, dividerVerticalEnd
                    ).forEach {
                        it.beInvisible()
                    }

                    val properTextColor = getProperTextColor()
                    arrayOf(dialpadAsterisk, dialpadHashtag, dialpadVoicemail
                    ).forEach {
                        it.applyColorFilter(properTextColor)
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

    @SuppressLint("SetTextI18n")
    private fun initLettersConcept() {
        val areMultipleSIMsAvailable = hasMultipleSims
        val baseColor = baseConfig.backgroundColor
        val buttonsColor = when {
            isDynamicTheme() -> resources.getColor(R.color.you_status_bar_color, theme)
            baseColor == Color.WHITE -> resources.getColor(R.color.dark_grey, theme)
            baseColor == Color.BLACK -> resources.getColor(R.color.bottom_tabs_black_background, theme)
            else -> baseConfig.backgroundColor.lightenColor(4)
        }
        val textColor = buttonsColor.getContrastColor()
        binding.dialpadRectWrapper.apply {
            if (config.hideDialpadLetters) {
                arrayOf(
                    dialpad1Letters, dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters,
                    dialpad6Letters, dialpad7Letters, dialpad8Letters, dialpad9Letters
                ).forEach {
                    it.beGone()
                }
            } else {
                val typeface = FontHelper.getTypeface(this@DialpadActivity)

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

                val langPref = config.dialpadSecondaryLanguage
                val langLocale = Locale.getDefault().language
                val isAutoLang = DialpadT9.getSupportedSecondaryLanguages().contains(langLocale) && langPref == LANGUAGE_SYSTEM
                if (langPref != LANGUAGE_NONE && langPref != LANGUAGE_SYSTEM || isAutoLang) {
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
                    }
                } else {
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
                it.background.applyColorFilter(buttonsColor)
                it.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    val margin = pixels(R.dimen.one_dp).toInt()
                    setMargins(margin, margin, margin, margin)
                }
            }

            val textColor = buttonsColor.getContrastColor()
            arrayOf(
                dialpadAsterisk, dialpadHashtag, dialpadVoicemail
            ).forEach {
                it.applyColorFilter(textColor)
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

            val simOnePrimary = config.currentSIMCardIndex == 0
            val simTwoColor = if (areMultipleSIMsAvailable) {
                if (simOnePrimary) config.simIconsColors[2] else config.simIconsColors[1]
            } else getProperPrimaryColor()
            val drawableSecondary = if (simOnePrimary) R.drawable.ic_phone_two_vector else R.drawable.ic_phone_one_vector
            val dialpadIconColor = if (simTwoColor == Color.WHITE) simTwoColor.getContrastColor() else textColor
            val downIcon = if (areMultipleSIMsAvailable) resources.getColoredDrawableWithColor(this@DialpadActivity, drawableSecondary, dialpadIconColor)
            else resources.getColoredDrawableWithColor(this@DialpadActivity, R.drawable.ic_dialpad_vector, dialpadIconColor)
            dialpadDown.setImageDrawable(downIcon)
            dialpadDownHolder.apply {
                background.applyColorFilter(simTwoColor)
                setOnClickListener {
                    if (areMultipleSIMsAvailable) {initCall(binding.dialpadInput.value, handleIndex = if (simOnePrimary) 1 else 0)} else dialpadHide()
                    maybePerformDialpadHapticFeedback(dialpadDownHolder)
                }
                contentDescription = getString(
                    if (areMultipleSIMsAvailable) {
                        if (simOnePrimary) R.string.call_from_sim_2 else R.string.call_from_sim_1
                    } else {
                        val view = dialpadView()
                        if (view.isVisible) R.string.hide_dialpad else R.string.show_dialpad
                    }
                )
            }

            val simOneColor = if (simOnePrimary) config.simIconsColors[1] else config.simIconsColors[2]
            val drawablePrimary = if (simOnePrimary) R.drawable.ic_phone_one_vector else R.drawable.ic_phone_two_vector
            val callIconId = if (areMultipleSIMsAvailable) drawablePrimary else R.drawable.ic_phone_vector
            val callIconColor = if (simOneColor == Color.WHITE) simOneColor.getContrastColor() else textColor
            val callIcon = resources.getColoredDrawableWithColor(this@DialpadActivity, callIconId, callIconColor)
            dialpadCallIcon.setImageDrawable(callIcon)
            dialpadCallButtonHolder.apply {
                background.applyColorFilter(simOneColor)
                setOnClickListener {
                    initCall(binding.dialpadInput.value, handleIndex = if (simOnePrimary || !areMultipleSIMsAvailable) 0 else 1)
                    maybePerformDialpadHapticFeedback(this)
                }
                setOnLongClickListener {
                    if (binding.dialpadInput.value.isEmpty()) {
                        val text = getTextFromClipboard()
                        binding.dialpadInput.setText(text)
                        if (text != null && text != "") {
                            binding.dialpadInput.setSelection(text.length)
                            binding.dialpadInput.requestFocusFromTouch()
                        }; true
                    } else {
                        copyNumber(); true
                    }
                }
                contentDescription = getString(
                    if (areMultipleSIMsAvailable) {
                        if (simOnePrimary) R.string.call_from_sim_1 else R.string.call_from_sim_2
                    } else R.string.call
                )
            }

            dialpadClearCharHolder.beVisible()
            dialpadClearCharHolder.background.applyColorFilter(getColor(R.color.red_call))
            dialpadClearCharHolder.setOnClickListener { clearChar(it) }
            dialpadClearCharHolder.setOnLongClickListener { clearInput(); true }
            dialpadClearChar.alpha = 1f
            dialpadClearChar.applyColorFilter(textColor)

            //dialpadHolder.setOnClickListener { binding.dialpadInput.setText(getTextFromClipboard()); true }
            setupCharClick(dialpad1Holder, '1')
            setupCharClick(dialpad2Holder, '2')
            setupCharClick(dialpad3Holder, '3')
            setupCharClick(dialpad4Holder, '4')
            setupCharClick(dialpad5Holder, '5')
            setupCharClick(dialpad6Holder, '6')
            setupCharClick(dialpad7Holder, '7')
            setupCharClick(dialpad8Holder, '8')
            setupCharClick(dialpad9Holder, '9')
            setupCharClick(dialpad0Holder, '0')
            //setupCharClick(dialpadPlusHolder, '+', longClickable = false)
            setupCharClick(dialpadAsteriskHolder, '*')
            setupCharClick(dialpadHashtagHolder, '#')
            dialpadGridHolder.setOnClickListener { } //Do not press between the buttons
        }
        binding.dialpadAddNumber.setOnClickListener { addNumberToContact() }

        binding.dialpadRoundWrapperUp.background.applyColorFilter(config.simIconsColors[1])
        binding.dialpadRoundWrapperUp.setColorFilter(textColor)
    }

    @SuppressLint("SetTextI18n")
    private fun initLettersIos() {
        val areMultipleSIMsAvailable = hasMultipleSims
        val getProperTextColor = getProperTextColor()
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
                val typeface = FontHelper.getTypeface(this@DialpadActivity)

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

                val langPref = config.dialpadSecondaryLanguage
                val langLocale = Locale.getDefault().language
                val isAutoLang = DialpadT9.getSupportedSecondaryLanguages().contains(langLocale) && langPref == LANGUAGE_SYSTEM
                if (langPref != LANGUAGE_NONE && langPref != LANGUAGE_SYSTEM || isAutoLang) {
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
                    val fontSize = getTextSize() - 8f
                    arrayOf(
                        dialpad1IosLetters, dialpad2IosLetters, dialpad3IosLetters, dialpad4IosLetters, dialpad5IosLetters,
                        dialpad6IosLetters, dialpad7IosLetters, dialpad8IosLetters, dialpad9IosLetters
                    ).forEach {
                        it.setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
                    }
                }
            }

            if (areMultipleSIMsAvailable) {
                dialpadSimIosHolder.beVisible()
                dialpadSimIos.background.applyColorFilter(Color.GRAY)
                dialpadSimIos.background.alpha = 60
                dialpadSimIos.applyColorFilter(getProperTextColor)
                dialpadSimIosHolder.setOnClickListener {
                    if (config.currentSIMCardIndex == 0) config.currentSIMCardIndex = 1 else config.currentSIMCardIndex = 0
                    updateCallButtonIos()
                    maybePerformDialpadHapticFeedback(dialpadSimIosHolder)
                    RxAnimation.from(dialpadCallButtonIosHolder)
                        .shake()
                        .subscribe().also(animationDisposables::add)
                }
                updateCallButtonIos()
                dialpadCallButtonIosHolder.setOnClickListener {
                    initCall(binding.dialpadInput.value, config.currentSIMCardIndex)
                    maybePerformDialpadHapticFeedback(dialpadCallButtonIosHolder)
                }
                dialpadCallButtonIosHolder.contentDescription = getString(if (config.currentSIMCardIndex == 0) R.string.call_from_sim_1 else R.string.call_from_sim_2 )
            } else {
                dialpadSimIosHolder.beGone()
                val color = config.simIconsColors[1]
                val callIcon = resources.getColoredDrawableWithColor(this@DialpadActivity, R.drawable.ic_phone_vector, color.getContrastColor())
                dialpadCallButtonIosIcon.setImageDrawable(callIcon)
                dialpadCallButtonIosHolder.background.applyColorFilter(color)
                dialpadCallButtonIosHolder.setOnClickListener {
                    initCall(binding.dialpadInput.value, 0)
                    maybePerformDialpadHapticFeedback(dialpadCallButtonIosHolder)
                }
                dialpadCallButtonIosHolder.contentDescription = getString(R.string.call)
            }

            dialpadCallButtonIosHolder.setOnLongClickListener {
                if (binding.dialpadInput.value.isEmpty()) {
                    val text = getTextFromClipboard()
                    binding.dialpadInput.setText(text)
                    if (text != null && text != "") {
                        binding.dialpadInput.setSelection(text.length)
                        binding.dialpadInput.requestFocusFromTouch()
                    }; true
                } else {
                    copyNumber(); true
                }
            }

            dialpadClearCharIos.applyColorFilter(Color.GRAY)
            dialpadClearCharIos.alpha = 0.235f
            dialpadClearCharXIos.applyColorFilter(getProperTextColor)
            dialpadClearCharIosHolder.beVisibleIf(binding.dialpadInput.value.isNotEmpty() || areMultipleSIMsAvailable)
            dialpadClearCharIosHolder.setOnClickListener { clearChar(it) }
            dialpadClearCharIosHolder.setOnLongClickListener { clearInput(); true }

            //dialpadHolder.setOnClickListener { binding.dialpadInput.setText(getTextFromClipboard()); true }
            setupCharClick(dialpad1IosHolder, '1')
            setupCharClick(dialpad2IosHolder, '2')
            setupCharClick(dialpad3IosHolder, '3')
            setupCharClick(dialpad4IosHolder, '4')
            setupCharClick(dialpad5IosHolder, '5')
            setupCharClick(dialpad6IosHolder, '6')
            setupCharClick(dialpad7IosHolder, '7')
            setupCharClick(dialpad8IosHolder, '8')
            setupCharClick(dialpad9IosHolder, '9')
            setupCharClick(dialpad0IosHolder, '0')
            //setupCharClick(dialpadPlusIosHolder, '+', longClickable = false)
            setupCharClick(dialpadAsteriskIosHolder, '*')
            setupCharClick(dialpadHashtagIosHolder, '#')
            dialpadIosHolder.setOnClickListener { } //Do not press between the buttons
        }
        binding.dialpadAddNumber.setOnClickListener { addNumberToContact() }

        val simOneColor = config.simIconsColors[1]
        binding.dialpadRoundWrapperUp.background.applyColorFilter(simOneColor)
        binding.dialpadRoundWrapperUp.setColorFilter(simOneColor.getContrastColor())
    }

    private fun updateCallButtonIos() {
        val oneSim = config.currentSIMCardIndex == 0
        val simColor = if (oneSim) config.simIconsColors[1] else config.simIconsColors[2]
        val callIconId = if (oneSim) R.drawable.ic_phone_one_vector else R.drawable.ic_phone_two_vector
        val callIcon = resources.getColoredDrawableWithColor(this@DialpadActivity, callIconId, simColor.getContrastColor())
        binding.dialpadRoundWrapper.dialpadCallButtonIosIcon.setImageDrawable(callIcon)
        binding.dialpadRoundWrapper.dialpadCallButtonIosHolder.background.applyColorFilter(simColor)
    }

    @SuppressLint("SetTextI18n")
    private fun initLetters() {
        val areMultipleSIMsAvailable = hasMultipleSims
        val getProperTextColor = getProperTextColor()
        binding.dialpadClearWrapper.apply {
            if (config.hideDialpadLetters) {
                arrayOf(
                    dialpad1Letters, dialpad2Letters, dialpad3Letters, dialpad4Letters, dialpad5Letters,
                    dialpad6Letters, dialpad7Letters, dialpad8Letters, dialpad9Letters
                ).forEach {
                    it.beGone()
                }
            } else {
                val typeface = FontHelper.getTypeface(this@DialpadActivity)

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

                val langPref = config.dialpadSecondaryLanguage
                val langLocale = Locale.getDefault().language
                val isAutoLang = DialpadT9.getSupportedSecondaryLanguages().contains(langLocale) && langPref == LANGUAGE_SYSTEM
                if (langPref != LANGUAGE_NONE && langPref != LANGUAGE_SYSTEM || isAutoLang) {
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

            val simOnePrimary = config.currentSIMCardIndex == 0
            dialpadCallTwoButton.apply {
                if (areMultipleSIMsAvailable) {
                    beVisible()
                    val simTwoColor = if (simOnePrimary) config.simIconsColors[2] else config.simIconsColors[1]
                    val drawableSecondary = if (simOnePrimary) R.drawable.ic_phone_two_vector else R.drawable.ic_phone_one_vector
                    val callIcon = resources.getColoredDrawableWithColor(this@DialpadActivity, drawableSecondary, simTwoColor.getContrastColor())
                    setImageDrawable(callIcon)
                    background.applyColorFilter(simTwoColor)
                    beVisible()
                    setOnClickListener {
                        initCall(binding.dialpadInput.value, handleIndex = if (simOnePrimary) 1 else 0)
                        maybePerformDialpadHapticFeedback(this)
                    }
                    contentDescription = getString(if (simOnePrimary) R.string.call_from_sim_2 else R.string.call_from_sim_1 )
                } else {
                    beGone()
                } }

            val simOneColor = if (simOnePrimary) config.simIconsColors[1] else config.simIconsColors[2]
            val drawablePrimary = if (simOnePrimary) R.drawable.ic_phone_one_vector else R.drawable.ic_phone_two_vector
            val callIconId = if (areMultipleSIMsAvailable) drawablePrimary else R.drawable.ic_phone_vector
            val callIcon = resources.getColoredDrawableWithColor(this@DialpadActivity, callIconId, simOneColor.getContrastColor())
            dialpadCallButton.apply {
                setImageDrawable(callIcon)
                background.applyColorFilter(simOneColor)
                setOnClickListener {
                    initCall(binding.dialpadInput.value, handleIndex = if (simOnePrimary || !areMultipleSIMsAvailable) 0 else 1)
                    maybePerformDialpadHapticFeedback(this)
                }
                setOnLongClickListener {
                    if (binding.dialpadInput.value.isEmpty()) {
                        val text = getTextFromClipboard()
                        binding.dialpadInput.setText(text)
                        if (text != null && text != "") {
                            binding.dialpadInput.setSelection(text.length)
                            binding.dialpadInput.requestFocusFromTouch()
                        }; true
                    } else {
                        copyNumber(); true
                    }
                }
                contentDescription = getString(
                    if (areMultipleSIMsAvailable) {
                        if (simOnePrimary) R.string.call_from_sim_1 else R.string.call_from_sim_2
                    } else R.string.call
                )
            }

            dialpadClearCharHolder.beVisibleIf(binding.dialpadInput.value.isNotEmpty() || areMultipleSIMsAvailable)
            dialpadClearChar.applyColorFilter(Color.GRAY)
            dialpadClearChar.alpha = 0.4f
            dialpadClearCharX.applyColorFilter(getProperTextColor)
            dialpadClearCharHolder.setOnClickListener { clearChar(it) }
            dialpadClearCharHolder.setOnLongClickListener { clearInput(); true }

            //dialpadHolder.setOnClickListener { binding.dialpadInput.setText(getTextFromClipboard()); true }
            setupCharClick(dialpad1Holder, '1')
            setupCharClick(dialpad2Holder, '2')
            setupCharClick(dialpad3Holder, '3')
            setupCharClick(dialpad4Holder, '4')
            setupCharClick(dialpad5Holder, '5')
            setupCharClick(dialpad6Holder, '6')
            setupCharClick(dialpad7Holder, '7')
            setupCharClick(dialpad8Holder, '8')
            setupCharClick(dialpad9Holder, '9')
            setupCharClick(dialpad0Holder, '0')
            //setupCharClick(dialpadPlusHolder, '+', longClickable = false)
            setupCharClick(dialpadAsteriskHolder, '*')
            setupCharClick(dialpadHashtagHolder, '#')
            dialpadGridHolder.setOnClickListener { } //Do not press between the buttons
        }
        binding.dialpadAddNumber.setOnClickListener { addNumberToContact() }

        val simOneColor = config.simIconsColors[1]
        binding.dialpadRoundWrapperUp.background.applyColorFilter(simOneColor)
        binding.dialpadRoundWrapperUp.setColorFilter(simOneColor.getContrastColor())
    }

    private fun dialpadView() = when (config.dialpadStyle) {
        DIALPAD_IOS -> binding.dialpadRoundWrapper.root
        DIALPAD_CONCEPT -> binding.dialpadRectWrapper.root
        else -> binding.dialpadClearWrapper.root
    }

    private fun updateDialpadNumberFontSize() {
        applyDialpadNumberFontSize(binding.dialpadClearWrapper, binding.dialpadRectWrapper, binding.dialpadRoundWrapper, config.dialpadNumberFontSize)
    }

    private fun updateDialpadSize() {
        val size = config.dialpadSize
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
        view.setHeight((dimens * (size / 100f)).toInt())

        val margin = config.dialpadBottomMargin
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

    // The original-style keypad shows the typed number in a row above the keys instead of under the toolbar.
    private fun moveNumberInputIntoKeypadRow() {
        val input = binding.dialpadInput
        (input.parent as ViewGroup).removeView(input)
        // Keep the field clear of the backspace at the row's end (it would otherwise take its
        // touches); mirror the inset on the start side so the number stays centred.
        val sideMargin = pixels(R.dimen.dialpad_number_side_margin).toInt() +
            binding.dialpadClearWrapper.dialpadClearCharHolder.layoutParams.width.coerceAtLeast(0)
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        params.setMargins(sideMargin, 0, sideMargin, 0)
        binding.dialpadClearWrapper.dialpadNumberRow.addView(input, 0, params)
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

    private fun refreshMenuItems() {
        binding.dialpadToolbar.menu.apply {
            findItem(R.id.copy_number).isVisible = binding.dialpadInput.value.isNotEmpty()
            findItem(R.id.web_search).isVisible = binding.dialpadInput.value.isNotEmpty()
            findItem(R.id.cab_call_anonymously).isVisible = binding.dialpadInput.value.isNotEmpty()
            findItem(R.id.clear_call_history).isVisible = config.showRecentCallsOnDialpad
            findItem(R.id.show_blocked_numbers).isVisible = config.showRecentCallsOnDialpad
            findItem(R.id.show_blocked_numbers).title =
                if (config.showBlockedNumbers) getString(R.string.hide_blocked_numbers) else getString(R.string.show_blocked_numbers)
        }
    }

    private fun setupOptionsMenu() {
        binding.dialpadToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.paste_number -> {
                    val text = getTextFromClipboard()
                    binding.dialpadInput.setText(text)
                    if (text != null && text != "") {
                        binding.dialpadInput.setSelection(text.length)
                        binding.dialpadInput.requestFocusFromTouch()
                    }
                }
                R.id.copy_number -> copyNumber()
                R.id.web_search -> webSearch()
                R.id.cab_call_anonymously -> initCallAnonymous()
                R.id.show_blocked_numbers -> showBlockedNumbers()
                R.id.clear_call_history -> clearCallHistory()
                R.id.settings_dialpad -> startActivity(Intent(applicationContext, SettingsDialpadActivity::class.java))
                R.id.settings -> startActivity(Intent(applicationContext, SettingsActivity::class.java))
                //R.id.add_number_to_contact -> addNumberToContact()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
        binding.dialpadToolbar.setNavigationOnClickListener {
            onBackPressed()
        }
    }

    override fun onBackPressedCompat(): Boolean {
        return when (CallManager.getPhoneState()) {
            NoCall -> {
                if (intent.getBooleanExtra(SHOW_RECENT_CALLS_ON_DIALPAD, false)) finishAffinity()
                else finish()
                true
            }
            else -> {
                startActivity(Intent(this, CallActivity::class.java))
                true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        longPressHandler.removeCallbacksAndMessages(null)
        searchHandler.removeCallbacksAndMessages(null)
        searchExecutor.shutdownNow()
        animationDisposables.dispose()
        toneGeneratorHelper?.release()
        toneGeneratorHelper = null
        EventBus.getDefault().unregister(this)
    }

    private fun copyNumber() {
        val clip = binding.dialpadInput.value
        copyToClipboard(clip)
    }

    private fun webSearch() {
        val text = binding.dialpadInput.value
        launchInternetSearch(text)
    }

    // A second tel: intent while the dialpad is open must refill the input too
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkDialIntent()
    }

    private fun checkDialIntent(): Boolean {
        val data = intent.data
        return if (
            (intent.action == Intent.ACTION_DIAL || intent.action == Intent.ACTION_VIEW)
            && data?.scheme == "tel"
        ) {
            // tel:+1555;ext=1 / tel:555?x: keep the dialable part only
            val number = data.schemeSpecificPart.orEmpty().substringBefore(';').substringBefore('?')
            binding.dialpadInput.setText(number)
            binding.dialpadInput.setSelection(number.length)
            true
        } else {
            false
        }
    }

    private fun addNumberToContact() {
        startAddNumberToContact(binding.dialpadInput.value)
    }

    private fun dialpadPressed(char: Char, view: View?) {
        binding.dialpadInput.addCharacter(char)
        maybePerformDialpadHapticFeedback(view)
    }

    private fun dialpadHide() {
        val view = dialpadView()
        if (view.isVisible) {
            slideDown(view)
        } else {
            slideUp(view)
        }
    }

    private fun slideDown(view: View) {
        view.animate()
            .translationY(view.height.toFloat())
            //.alpha(0f)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // superfluous restoration
                    view.visibility = View.GONE
                    //view.alpha = 1f
                    view.translationY = 0f
                }
            })
        hasBeenScrolled = false
        if ((view == binding.dialpadRoundWrapper.root ||
                view == binding.dialpadClearWrapper.root ||
                view == binding.dialpadRectWrapper.root) &&
            binding.dialpadRoundWrapperUp.isGone
        ) slideUp(
            binding.dialpadRoundWrapperUp
        )
    }

    private fun slideUp(view: View) {
        view.visibility = View.VISIBLE
        //view.alpha = 0f
        if (view.height > 0) {
            slideUpNow(view)
        } else {
            // wait till height is measured
            view.post { slideUpNow(view) }
        }
        if (view == binding.dialpadRoundWrapper.root ||
            view == binding.dialpadClearWrapper.root ||
            view == binding.dialpadRectWrapper.root
        ) slideDown(binding.dialpadRoundWrapperUp)
    }

    private fun slideUpNow(view: View) {
        view.translationY = view.height.toFloat()
        view.animate()
            .translationY(0f)
            //.alpha(1f)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.visibility = View.VISIBLE
                    //view.alpha = 1f
                }
            })
    }

    private fun clearChar(view: View) {
        binding.dialpadInput.dispatchKeyEvent(binding.dialpadInput.getKeyEvent(KeyEvent.KEYCODE_DEL))
        maybePerformDialpadHapticFeedback(view)
    }

    private fun clearInput() {
        binding.dialpadInput.setText("")
    }

    // The call screen covers the dialpad; clear the number when the user comes back to it
    private fun clearInputOnReturn() {
        clearOnReturn = true
    }

    private fun loadContacts() {
        ContactsRepository.ensureLoaded(this) { snapshot ->
            ensureBackgroundThread {
                seenRepositoryVersion = ContactsRepository.version
                DialpadT9.ensureInitialized(applicationContext)
                val source = ContactSource(listedContacts(snapshot.contacts), snapshot)
                snapshot.searchKeys(t9Language()) // build the search keys here, not on the first keystroke
                contactSource = source
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    val firstLoad = !contactsLoadedOnce
                    contactsLoadedOnce = true
                    when {
                        firstLoad -> if (!checkDialIntent() && binding.dialpadInput.value.isEmpty()) dialpadValueChanged("")
                        binding.dialpadInput.value.isNotEmpty() -> dialpadValueChanged(binding.dialpadInput.value)
                    }
                }
            }
        }
    }

    private fun dialpadValueChanged(textFormat: String) {
        val len = textFormat.length
        val view = dialpadView()
        if (len == 0 && view.isGone) {
            slideUp(view)
        }
        //Only works for system apps, CALL_PRIVILEGED and MODIFY_PHONE_STATE permissions are required
        if (len > 8 && textFormat.startsWith("*#*#") && textFormat.endsWith("#*#*")) {
            val secretCode = textFormat.substring(4, textFormat.length - 4)
            if (isDefaultDialer()) {
                getSystemService(TelephonyManager::class.java)?.sendDialerSpecialCode(secretCode)
            } else {
                launchSetDefaultDialerIntent()
            }
            return
        }

        (binding.dialpadList.adapter as? ContactsAdapter)?.finishActMode()
        (binding.dialpadRecentsList.adapter as? RecentCallsAdapter)?.finishActMode()

        val hasInput = binding.dialpadInput.value.isNotEmpty()
        binding.dialpadAddNumber.beVisibleIf(hasInput)
        binding.dialpadAddNumber.setTextColor(getProperPrimaryColor())
        if (!hasInput) binding.dialpadList.beGone()
        val areMultipleSIMsAvailable = hasMultipleSims
        binding.dialpadClearWrapper.dialpadClearCharHolder.beVisibleIf((hasInput && config.dialpadStyle != DIALPAD_IOS && config.dialpadStyle != DIALPAD_CONCEPT) || areMultipleSIMsAvailable)
        binding.dialpadRectWrapper.dialpadClearCharHolder.beVisibleIf(config.dialpadStyle == DIALPAD_CONCEPT)
        binding.dialpadRoundWrapper.dialpadClearCharIosHolder.beVisibleIf((hasInput && config.dialpadStyle == DIALPAD_IOS) || areMultipleSIMsAvailable)
        binding.dialpadInput.beVisibleIf(hasInput)
        refreshMenuItems()

        // Typing bursts (and the number formatter re-firing the watcher) collapse into one search of the latest input.
        searchHandler.removeCallbacks(searchRunnable)
        searchHandler.post(searchRunnable)
    }

    private fun t9Language(): String {
        val langPref = config.dialpadSecondaryLanguage ?: ""
        val langLocale = Locale.getDefault().language
        val isAutoLang = DialpadT9.getSupportedSecondaryLanguages().contains(langLocale) && langPref == LANGUAGE_SYSTEM
        return if (isAutoLang) langLocale else langPref
    }

    private fun runSearch() {
        if (isDestroyed || isFinishing) return
        val input = binding.dialpadInput.text.toString()
        val text = if (config.formatPhoneNumbers) input.removeNumberFormatting() else input
        val searchContacts = text.isNotEmpty() && config.searchContactsInDialpad
        val searchRecents = text.isNotEmpty() || config.showRecentCallsOnDialpad
        val language = t9Language()
        val source = contactSource
        val recents = allRecentCalls
        val generation = ++searchGeneration

        searchExecutor.execute {
            if (generation != searchGeneration) return@execute
            val result = SearchResult(
                contacts = if (searchContacts) filterContacts(text, language, source, recents) else null,
                recents = if (searchRecents) filterRecents(text, language, recents) else null
            )
            runOnUiThread {
                if (generation == searchGeneration && !isDestroyed && !isFinishing) applySearchResult(text, source, result)
            }
        }
    }

    // Search thread only.
    private fun filterContacts(text: String, language: String, source: ContactSource, recents: List<RecentCall>): ArrayList<Contact> {
        class Match(val contact: Contact, val callCount: Int, val startsWithText: Boolean, val name: String)

        val keysByContact = source.snapshot.searchKeys(language)
        val callCounts = callCountsFor(recents)
        val matches = ArrayList<Match>()
        for (contact in source.contacts) {
            val keys = keysByContact[contact] ?: continue
            val matched = contact.doesContainPhoneNumber(text, convertLetters = true, search = true)
                || keys.matchKeys.any { it.contains(text, true) }
            if (!matched) continue
            val callCount = contact.phoneNumbers.maxOfOrNull { callCounts[it.value.normalizePhoneNumber()] ?: 0 } ?: 0
            matches.add(Match(contact, callCount, keys.displayNameDigits.startsWith(text), contact.getNameToDisplay()))
        }
        matches.sortWith(
            compareByDescending<Match> { it.callCount }
                .thenByDescending { it.startsWithText }
                .thenBy(collator) { it.name }
        )
        return matches.mapTo(ArrayList(matches.size)) { it.contact }
    }

    // Search thread only. Recomputed only when the recents list was replaced.
    private fun callCountsFor(recents: List<RecentCall>): Map<String, Int> {
        if (recents !== callCountsSource) {
            val counts = HashMap<String, Int>()
            recents.forEach { call ->
                val key = call.phoneNumber.normalizePhoneNumber()
                if (key.isNotEmpty()) counts[key] = (counts[key] ?: 0) + 1
            }
            callCounts = counts
            callCountsSource = recents
        }
        return callCounts
    }

    // Search thread only. Name, nickname and company match by their T9 digits, like the contact search.
    private fun filterRecents(text: String, language: String, recents: List<RecentCall>): List<RecentCall> {
        fun t9(value: String) = DialpadT9.convertLettersToNumbers(value, language).filter { it.isDigit() }
        return recents
            .map { it to t9(it.name) }
            .filter { (call, nameDigits) ->
                nameDigits.contains(text) ||
                    call.doesContainPhoneNumber(text) ||
                    t9(call.nickname).contains(text) ||
                    t9(call.company).contains(text) ||
                    call.jobPosition.contains(text, true)
            }
            .sortedWith(
                compareByDescending<Pair<RecentCall, String>> { it.first.dayCode }
                    .thenByDescending { it.second.startsWith(text) }
                    .thenByDescending { it.first.startTS }
            )
            .map { it.first }
    }

    private fun applySearchResult(text: String, source: ContactSource, result: SearchResult) {
        val filtered = result.contacts
        val filteredRecents = result.recents
        // Not searched: empty input shows every contact (the list is hidden then); with contact search off no
        // contact list is shown, so the recents must not fall back for lack of contact results.
        val hasContacts = filtered?.isNotEmpty() ?: (!config.searchContactsInDialpad || source.contacts.isNotEmpty())
        val hasRecents = !filteredRecents.isNullOrEmpty()
        val hasInput = binding.dialpadInput.value.isNotEmpty()

        if (filtered != null) {
            ContactsAdapter(
                activity = this,
                contacts = filtered,
                recyclerView = binding.dialpadList,
                highlightText = text,
                refreshItemsListener = null,
                showNumber = true,
                allowLongClick = false,
                // Tap opens the contact while swiping can call; without swipe actions a tap still calls
                itemClick = {
                    if (config.useSwipeToAction) {
                        startContactDetailsIntent(it as Contact)
                    } else {
                        startCallWithConfirmationCheck(it as Contact)
                        if (config.dialpadClearWhenStartCall) clearInputOnReturn()
                    }
                },
                profileIconClick = {
                    startContactDetailsIntent(it as Contact)
                }).apply {
                binding.dialpadList.adapter = this
            }
        }

        if (filteredRecents != null && !initSearch) { //So that there is no adapter update on first launch
            recentsAdapter?.updateItems(filteredRecents)
        }
        initSearch = false

        binding.dialpadList.beVisibleIf(hasContacts && hasInput && config.searchContactsInDialpad)
        binding.dialpadRecentsList.beVisibleIf(
            (!hasInput && config.showRecentCallsOnDialpad)
                || (hasInput && hasRecents && !hasContacts)
                || (!config.searchContactsInDialpad && config.showRecentCallsOnDialpad)
        )
        binding.dialpadPlaceholder.beVisibleIf(
            (!hasContacts && config.searchContactsInDialpad && !config.showRecentCallsOnDialpad)
                || (!hasRecents && config.showRecentCallsOnDialpad && !config.searchContactsInDialpad)
                || (!hasRecents && config.showRecentCallsOnDialpad && !hasContacts && config.searchContactsInDialpad)
        )
    }

    @SuppressLint("MissingSuperCall")
    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == REQUEST_CODE_SET_DEFAULT_DIALER && isDefaultDialer()) {
            dialpadValueChanged(binding.dialpadInput.value)
        }
    }

    private fun initCall(number: String = binding.dialpadInput.value, handleIndex: Int, displayName: String? = null) {
        if (number.isNotEmpty()) {
            val nameToDisplay = displayName ?: number
            if (handleIndex != -1 && hasMultipleSims) {
                callContactWithSimWithConfirmationCheck(number, nameToDisplay, handleIndex == 0)
            } else {
                startCallWithConfirmationCheck(number, nameToDisplay)
            }
            if (config.dialpadClearWhenStartCall) clearInputOnReturn()
        } else {
            // updateCallsCache stays false: the redial lookup must not touch the shared recents cache
            recentsHelper.getRecentCalls(queryLimit = 1, updateCallsCache = false) {
                val mostRecentNumber = it.firstOrNull()?.phoneNumber
                if (!mostRecentNumber.isNullOrEmpty()) {
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        binding.dialpadInput.setText(mostRecentNumber)
                        binding.dialpadInput.setSelection(mostRecentNumber.length)
                    }
                }
            }
        }
    }

    private fun speedDial(id: Int): Boolean {
        if (binding.dialpadInput.value.length == 1) {
            val speedDial = speedDialValues.firstOrNull { it.id == id }
            if (speedDial?.isValid() == true) {
                initCall(speedDial.number, -1, speedDial.getName(this))
                return true
            } else {
                ConfirmationDialog(this, getString(R.string.open_speed_dial_manage)) {
                    startActivity(Intent(applicationContext, ManageSpeedDialActivity::class.java))
                }
            }
        }
        return false
    }

    private fun startDialpadTone(char: Char) {
        if (config.dialpadBeeps) {
            pressedKeys.add(char)
            toneGeneratorHelper?.startTone(char)
        }
    }

    private fun stopDialpadTone(char: Char) {
        if (config.dialpadBeeps) {
            if (!pressedKeys.remove(char)) return
            if (pressedKeys.isEmpty()) {
                toneGeneratorHelper?.stopTone()
            } else {
                startDialpadTone(pressedKeys.last())
            }
        }
    }

    private fun maybePerformDialpadHapticFeedback(view: View?) {
        if (config.dialpadVibration) {
            view?.performHapticFeedback()
        }
    }

    private fun performLongClick(view: View, char: Char) {
        if (char == '0') {
            clearChar(view)
            dialpadPressed('+', view)
        } else if (char == '*') {
            clearChar(view)
            dialpadPressed(',', view)
        } else if (char == '#') {
            clearChar(view)
            when (config.dialpadHashtagLongClick) {
                DIALPAD_LONG_CLICK_WAIT -> dialpadPressed(';', view)
                DIALPAD_LONG_CLICK_SETTINGS_DIALPAD -> {
                    startActivity(Intent(applicationContext, SettingsDialpadActivity::class.java))
                }

                else -> {
                    startActivity(Intent(applicationContext, SettingsActivity::class.java))
                }
            }
        } else {
            val result = speedDial(char.digitToInt())
            if (result) {
                stopDialpadTone(char)
                clearChar(view)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCharClick(view: View, char: Char, longClickable: Boolean = true) {
        view.isClickable = true
        view.isLongClickable = true
        if (isTalkBackOn) {
            view.setOnClickListener {
                startDialpadTone(char)
                dialpadPressed(char, view)
                stopDialpadTone(char)
            }
            view.setOnLongClickListener { performLongClick(view, char); true}
        }
        else view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dialpadPressed(char, view)
                    startDialpadTone(char)
                    if (longClickable) {
                        longPressHandler.removeCallbacksAndMessages(null)
                        longPressHandler.postDelayed({
                            performLongClick(view, char)
                        }, longPressTimeout)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopDialpadTone(char)
                    if (longClickable) {
                        longPressHandler.removeCallbacksAndMessages(null)
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    val viewContainsTouchEvent = if (event.rawX.isNaN() || event.rawY.isNaN()) {
                        false
                    } else {
                        view.boundingBox.contains(event.rawX.roundToInt(), event.rawY.roundToInt())
                    }

                    if (!viewContainsTouchEvent) {
                        stopDialpadTone(char)
                        if (longClickable) {
                            longPressHandler.removeCallbacksAndMessages(null)
                        }
                    }
                }
            }
            false
        }
    }

    private fun initCallAnonymous() {
        val dialpadValue = binding.dialpadInput.value
        if (config.showWarningAnonymousCall) {
            val text = String.format(getString(R.string.call_anonymously_warning), dialpadValue)
            ConfirmationAdvancedDialog(
                this,
                text,
                R.string.call_anonymously_warning,
                R.string.ok,
                R.string.do_not_show_again,
                fromHtml = true
            ) {
                if (it) {
                    initCall("#31#$dialpadValue", 0)
                } else {
                    config.showWarningAnonymousCall = false
                    initCall("#31#$dialpadValue", 0)
                }
            }
        } else {
            initCall("#31#$dialpadValue", 0)
        }
    }

    private fun refreshItems(invalidate: Boolean = false, needUpdate: Boolean = false) {
        if (invalidate) {
            allRecentCalls = emptyList()
            config.recentCallsCache = ""
        }

        if (binding.dialpadInput.value.isNotEmpty()) {
            dialpadValueChanged(binding.dialpadInput.value)
        } else if (needUpdate || config.needUpdateRecents) {
            refreshCallLog(loadAll = false) { refreshCallLog(loadAll = true) }
        } else {
            var recents = emptyList<RecentCall>()
            if (!invalidate) {
                try {
                    recents = config.parseRecentCallsCache()
                } catch (_: Exception) {
                    config.recentCallsCache = ""
                }
            }

            if (recents.isNotEmpty()) {
                refreshCallLogFromCache(recents) { refreshCallLog(loadAll = true) }
            } else {
                refreshCallLog(loadAll = false) { refreshCallLog(loadAll = true) }
            }
        }
    }

    private fun refreshCallLog(loadAll: Boolean = false, callback: (() -> Unit)? = null) {
        getRecentCalls(loadAll) {
            allRecentCalls = it
            if (loadAll) config.saveRecentCallsCache(it)
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                gotRecents(it)
                callback?.invoke()
            }
        }
    }

    private fun refreshCallLogFromCache(cache: List<RecentCall>, callback: (() -> Unit)? = null) {
        gotRecents(cache)
        callback?.invoke()
    }

    private fun getRecentCalls(loadAll: Boolean, callback: (List<RecentCall>) -> Unit) {
        val queryCount = if (loadAll) config.queryLimitRecent else RecentsHelper.QUERY_LIMIT
        val existingRecentCalls = allRecentCalls

        with(recentsHelper) {
            if (config.groupSubsequentCalls) {
                getGroupedRecentCalls(existingRecentCalls, queryCount, true) {
                    if (loadAll) recentsHelper.updateRecentOutgoingNumbers(it)
                    prepareCallLog(it, callback)
                }
            } else {
                getRecentCalls(existingRecentCalls, queryCount, isDialpad = true, updateCallsCache = true) { it ->
                    if (loadAll) recentsHelper.updateRecentOutgoingNumbers(it)
                    val calls = if (config.groupAllCalls) it.distinctBy { it.phoneNumber } else it
                    prepareCallLog(calls, callback)
                }
            }
        }
    }

    private fun prepareCallLog(calls: List<RecentCall>, callback: (List<RecentCall>) -> Unit) {
        if (calls.isEmpty()) {
            callback(emptyList())
            return
        }

        ContactsRepository.ensureLoaded(this) { snapshot ->
            ensureBackgroundThread {
                val privateContacts = getPrivateContacts()
                callback(updateNamesIfEmpty(maybeFilterPrivateCalls(calls, privateContacts), snapshot, privateContacts))
            }
        }
    }

    private fun getPrivateContacts(): ArrayList<Contact> {
        return loadPrivateContacts(includeIgnored = true)
    }

    private fun maybeFilterPrivateCalls(calls: List<RecentCall>, privateContacts: List<Contact>): List<RecentCall> {
        val ignoredSources = baseConfig.ignoredContactSources
        return if (SMT_PRIVATE in ignoredSources) {
            val privateNumbers = privateContacts.flatMap { it.phoneNumbers }.map { it.value }
            calls.filterNot { it.phoneNumber in privateNumbers }
        } else {
            calls
        }
    }

    private fun updateNamesIfEmpty(
        calls: List<RecentCall>,
        snapshot: ContactsRepository.Snapshot,
        privateContacts: List<Contact>
    ): List<RecentCall> {
        // Misses are memoised too: repeated unknown numbers must not rescan the private contacts every row.
        val contactsByNumber = HashMap<String, Contact?>()
        return calls.map { call ->
            if (call.phoneNumber != call.name) return@map call
            val contact = if (contactsByNumber.containsKey(call.phoneNumber)) {
                contactsByNumber[call.phoneNumber]
            } else {
                (privateContacts.firstOrNull { it.doesContainPhoneNumber(call.phoneNumber) } ?: snapshot.find(call.phoneNumber))
                    .also { contactsByNumber[call.phoneNumber] = it }
            }
            if (contact == null) call else withUpdatedName(call = call, name = contact.getNameToDisplay())
        }
    }

    private fun withUpdatedName(call: RecentCall, name: String): RecentCall {
        return call.copy(
            name = name,
            groupedCalls = call.groupedCalls
                ?.map { it.copy(name = name) }
                ?.toMutableList()
                ?.ifEmpty { null }
        )
    }

    private fun gotRecents(recents: List<RecentCall>) {
        val currAdapter = binding.dialpadRecentsList.adapter
        if (currAdapter == null) {
            recentsAdapter = RecentCallsAdapter(
                activity = this,
                recyclerView = binding.dialpadRecentsList,
                refreshItemsListener = null,
                showOverflowMenu = true,
                showCallIcon = config.onRecentClick == SWIPE_ACTION_OPEN,
                hideTimeAtOtherDays = true,
                isDialpad = true,
                itemDelete = { deleted ->
                    val deletedSet = deleted.toHashSet()
                    allRecentCalls = allRecentCalls.filter { it !in deletedSet }
                },
                itemClick = {
                    itemClickAction(config.onRecentClick, it as RecentCall)
                    if (config.dialpadClearWhenStartCall) clearInputOnReturn()
                },
                profileInfoClick = { recentCall ->
                    actionOpen(recentCall)
                },
                profileIconClick = {
                    val recentCall = it as RecentCall
                    val contact = findContactByCall(recentCall)
                    if (contact != null) {
                        startContactDetailsIntent(contact)
                    } else {
                        startAddNumberToContact(recentCall.phoneNumber)
                    }
                }
            )

            binding.dialpadRecentsList.adapter = recentsAdapter
            recentsAdapter?.updateItems(recents)
        } else {
            recentsAdapter?.updateItems(recents)
        }
    }

    private fun callRecentNumber(recentCall: RecentCall) {
        if (config.callUsingSameSim && recentCall.simID > 0) {
            val sim = recentCall.simID == 1
            callContactWithSim(recentCall.phoneNumber, sim)
        }
        else {
            launchCallIntent(recentCall.phoneNumber, key = BuildConfig.RIGHT_APP_KEY)
        }
    }

    private fun showBlockedNumbers() {
        config.showBlockedNumbers = !config.showBlockedNumbers
        binding.dialpadToolbar.menu.findItem(R.id.show_blocked_numbers).title =
            if (config.showBlockedNumbers) getString(R.string.hide_blocked_numbers) else getString(R.string.show_blocked_numbers)
        config.needUpdateRecents = true
        runOnUiThread {
            refreshItems()
        }
    }

    private fun clearCallHistory() {
        val confirmationText = "${getString(R.string.clear_history_confirmation)}\n\n${getString(R.string.cannot_be_undone)}"
        ConfirmationDialog(this, confirmationText) {
            RecentsHelper(this).removeAllRecentCalls(this) {
                allRecentCalls = emptyList()
                runOnUiThread {
                    refreshItems(invalidate = true)
                }
            }
        }
    }

    private fun findContactByCall(recentCall: RecentCall): Contact? {
        return ContactsRepository.snapshot.find(recentCall.phoneNumber)
    }

    private fun itemClickAction(action: Int, call: RecentCall) {
        when (action) {
            SWIPE_ACTION_MESSAGE -> actionSMS(call)
            SWIPE_ACTION_CALL -> actionCall(call)
            SWIPE_ACTION_OPEN -> actionOpen(call)
            else -> {}
        }
    }

    private fun actionCall(call: RecentCall) {
        val recentCall = call
        if (config.showCallConfirmation) {
            CallConfirmationDialog(this, recentCall.name) {
                callRecentNumber(recentCall)
            }
        } else {
            callRecentNumber(recentCall)
        }
    }

    private fun actionSMS(call: RecentCall) {
        launchSendSMSIntent(call.phoneNumber)
    }

    private fun actionOpen(call: RecentCall) {
        val recentCalls = call.groupedCalls as ArrayList<RecentCall>? ?: arrayListOf(call)
        val contact = findContactByCall(call)
        Intent(this, CallHistoryActivity::class.java).apply {
            putExtra(CURRENT_RECENT_CALL, call)
            putExtra(CURRENT_RECENT_CALL_LIST, recentCalls)
            putExtra(CONTACT_ID, call.contactID)
            if (contact != null) {
                putExtra(IS_PRIVATE, contact.isPrivate())
            }
            launchActivityIntent(this)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshCallLog(event: Events.RefreshCallLog) {
        config.needUpdateRecents = true
        refreshItems(needUpdate = true)
    }
}
