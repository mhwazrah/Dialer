package com.mhw.phone.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import androidx.activity.result.contract.ActivityResultContracts
import com.goodwy.commons.dialogs.*
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.RadioItem
import com.mhw.phone.BuildConfig
import com.mhw.phone.R
import com.mhw.phone.databinding.ActivitySettingsBinding
import com.mhw.phone.dialogs.ChangeTextDialog
import com.mhw.phone.extensions.*
import com.mhw.phone.helpers.*
import org.json.JSONException
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

class SettingsActivity : SimpleActivity() {
    companion object {
        private const val SETTINGS_FILE_TYPE = "application/json"
        private const val SETTINGS_FILE_NAME = "mhw-phone-settings.json"
        private val IMPORT_SETTINGS_FILE_TYPES = buildList {
            add("application/json")
            if (!isQPlus()) {
                // Workaround for https://github.com/FossifyOrg/Messages/issues/88
                add("application/octet-stream")
            }
        }
    }

    private val binding by viewBinding(ActivitySettingsBinding::inflate)
    private val getContent =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                toast(R.string.importing)
                importSettings(uri)
            }
        }

    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument(SETTINGS_FILE_TYPE)) { uri ->
        if (uri != null) {
            toast(R.string.exporting)
            exportSettings(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        useOverflowIcon = false
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupOptionsMenu()

//        setupEdgeToEdge(padBottomSystem = listOf(settingsNestedScrollview))

        boundStateKey = externalStateKey()
        bindSettings()
    }

    private var hasMultipleSims = false

    // Colours, pro state and SIM count are the only inputs that change while this screen is away:
    // the rows are rebound only when they changed, otherwise resume refreshes just the external-state rows.
    private var boundStateKey = ""

    private fun externalStateKey(): String {
        hasMultipleSims = areMultipleSIMsAvailable()
        return "${getProperTextColor()}|${getProperPrimaryColor()}|${getProperBackgroundColor()}|${checkPro()}|$hasMultipleSims"
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.settingsAppbar, NavigationIcon.Arrow)
        binding.settingsAppbar.setBackgroundColor(Color.TRANSPARENT)
        binding.settingsToolbar.setBackgroundColor(Color.TRANSPARENT)

        val stateKey = externalStateKey()
        if (stateKey != boundStateKey) {
            boundStateKey = stateKey
            bindSettings()
        } else {
            setupManageBlockedNumbers()
        }
    }

    private fun bindSettings() {
        updatePro()

        setupTheme()
        setupColorSimIcons()
        setupSimCardColorList()

        setupManageBlockedNumbers()
        setupChangeDateTimeFormat()
        setupLanguage()

        setupDefaultTab()

        setupSwipeRightAction()
        setupSwipeLeftAction()

        setupManageSpeedDial()
        setupDialpadPreferences()

        setupFlashForAlerts()

        setupAlwaysShowFullscreen()
        setupQuickAnswers()
        setupDisableProximitySensor()
        setupFlipToSilence()
        setupAnnounceCallerName()
        setupDialpadVibrations()
        setupCallStartEndVibrations()
        setupDialpadBeeps()
        setupShowCallConfirmation()
        setupCallUsingSameSim()

        setupStartNameWithSurname()

        setupSettingsExport()
        setupSettingsImport()

        setupAbout()

        updateTextColors(binding.settingsHolder)

        binding.apply {
            val properTextColor = getProperTextColor()
            val properPrimaryColor = getProperPrimaryColor()
            arrayOf(
                settingsAppearanceLabel,
                settingsGeneralLabel,
                settingsTabsLabel,
                settingsSwipeGesturesLabel,
                settingsDialpadLabel,
                settingsCallsLabel,
                settingsNotificationsLabel,
                settingsListViewLabel,
                settingsBackupsLabel,
                settingsOtherLabel).forEach {
                it.setTextColor(properPrimaryColor)
            }

            arrayOf(
                settingsColorCustomizationHolder,
                settingsGeneralHolder,
                settingsTabsHolder,
                settingsSwipeGesturesHolder,
                settingsDialpadHolder,
                settingsCallsHolder,
                settingsNotificationsHolder,
                settingsListViewHolder,
                settingsBackupsHolder,
                settingsOtherHolder
            ).forEach {
                it.setCardBackgroundColor(Color.TRANSPARENT)
                it.setBackgroundResource(R.drawable.bg_glass_card)
            }

            arrayOf(
                settingsExportCallsChevron,
                settingsImportCallsChevron,
                settingsManageBlockedNumbersChevron,
                settingsManageSpeedDialChevron,
                settingsDialpadStyleChevron,
                settingsAboutChevron
            ).forEach {
                it.applyColorFilter(properTextColor)
            }
        }
    }

    private fun updatePro(isPro: Boolean = checkPro()) {
        binding.apply {
            val stringId =
                if (isRTLLayout) com.goodwy.strings.R.string.swipe_right_action
                else com.goodwy.strings.R.string.swipe_left_action
            settingsSwipeLeftActionLabel.text = addLockedLabelIfNeeded(stringId, isPro)

            arrayOf(
                settingsSimCardColor1Holder,
                settingsSimCardColor2Holder,
                settingsSwipeLeftActionHolder
            ).forEach {
                it.alpha = if (isPro) 1f else 0.4f
            }
        }
    }

    private fun setupTheme() = binding.apply {
        settingsTheme.text = getThemeText()
        settingsThemeHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(APP_THEME_SYSTEM, getString(R.string.settings_theme_system)),
                RadioItem(APP_THEME_LIGHT, getString(R.string.settings_theme_light)),
                RadioItem(APP_THEME_DARK, getString(R.string.settings_theme_dark))
            )

            RadioGroupDialog(
                this@SettingsActivity,
                items,
                config.appTheme,
                R.string.settings_theme,
                defaultItemId = APP_THEME_SYSTEM
            ) {
                config.appTheme = it as Int
                applyThemeChange()
            }
        }
    }

    private fun getThemeText() = getString(
        when (config.appTheme) {
            APP_THEME_LIGHT -> R.string.settings_theme_light
            APP_THEME_DARK -> R.string.settings_theme_dark
            else -> R.string.settings_theme_system
        }
    )

    // Other screens pick the new colours up through needRestart (MainActivity / DialpadActivity recreate on resume)
    // A fresh start, not recreate(): recreate restores each switch's old position and, set again straight away, the
    // switch keeps showing it. Restarting without saved state shows the imported values.
    private fun restartAfterImport() {
        applyAppTheme()
        config.needRestart = true
        startActivity(Intent(this, SettingsActivity::class.java))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun applyThemeChange() {
        applyAppTheme()
        config.needRestart = true
        recreate()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        updateMenuItemColors(menu)
        return super.onCreateOptionsMenu(menu)
    }

    private fun setupOptionsMenu() {
        binding.settingsToolbar.menu.apply {
            findItem(R.id.calling_accounts).isVisible = canLaunchAccountsConfiguration()
        }
        binding.settingsToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.calling_accounts -> {
                    launchAccountsConfiguration()
                    true
                }
                R.id.whats_new -> {
                    WhatsNewDialog(this@SettingsActivity, whatsNewList())
                    true
                }
                else -> false
            }
        }
    }

    private fun setupLanguage() = binding.apply {
        settingsLanguage.text = Locale.getDefault().displayLanguage
        if (isTiramisuPlus()) {
            settingsLanguageHolder.beVisible()
            settingsLanguageHolder.setOnClickListener {
                launchChangeAppLanguageIntent()
            }
        } else {
            settingsLanguageHolder.beGone()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupManageBlockedNumbers() = binding.apply {
        if (baseConfig.blockingEnabled) {
            ensureBackgroundThread {
                val count = try {
                    getBlockedNumbers().size.toString()
                } catch (_: SecurityException) {
                    "0"
                }
                runOnUiThread {
                    if (!isDestroyed) settingsManageBlockedNumbersCount.text = count
                }
            }
        } else {
            settingsManageBlockedNumbersCount.text = getString(R.string.off)
        }

        val getProperTextColor = getProperTextColor()
        val red = resources.getColor(R.color.red_missed, theme)
        val colorUnknown = if (baseConfig.blockUnknownNumbers) red else getProperTextColor
        val alphaUnknown = if (baseConfig.blockUnknownNumbers) 1f else 0.6f
        settingsManageBlockedNumbersIconUnknown.apply {
            beGoneIf(!baseConfig.blockingEnabled)
            applyColorFilter(colorUnknown)
            alpha = alphaUnknown
        }

        val colorHidden = if (baseConfig.blockHiddenNumbers) red else getProperTextColor
        val alphaHidden = if (baseConfig.blockHiddenNumbers) 1f else 0.6f
        settingsManageBlockedNumbersIconHidden.apply {
            beGoneIf(!baseConfig.blockingEnabled)
            applyColorFilter(colorHidden)
            alpha = alphaHidden
        }

        settingsManageBlockedNumbersHolder.setOnClickListener {
            Intent(this@SettingsActivity, BlockedNumbersActivity::class.java).apply {
                startActivity(this)
            }
        }
    }

    private fun setupDialpadPreferences() {
        binding.settingsDialpadStyleHolder.setOnClickListener {
            startActivity(Intent(this, SettingsDialpadActivity::class.java))
        }
    }

    private fun setupManageSpeedDial() {
        binding.settingsManageSpeedDialHolder.setOnClickListener {
            Intent(this, ManageSpeedDialActivity::class.java).apply {
                startActivity(this)
            }
        }
    }

    private fun setupChangeDateTimeFormat() {
        updateDateTimeFormat()
        binding.settingsChangeDateTimeFormatHolder.setOnClickListener {
            ChangeDateTimeFormatDialog(this, true) {
                updateDateTimeFormat()
            }
        }
    }

    private fun updateDateTimeFormat() {
        val cal = Calendar.getInstance(Locale.ENGLISH).timeInMillis
        val formatDate = cal.formatDate(this@SettingsActivity)
        binding.settingsChangeDateTimeFormat.text = formatDate
    }

    private fun setupDefaultTab() {
        binding.settingsDefaultTab.text = getDefaultTabText()
        binding.settingsDefaultTabHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(TAB_LAST_USED, getString(R.string.last_used_tab)),
                RadioItem(TAB_FAVORITES, getString(R.string.favorites_tab), icon = R.drawable.ic_star_vector_scaled),
                RadioItem(TAB_CALL_HISTORY, getString(R.string.recents), icon = R.drawable.ic_clock_filled_scaled),
                RadioItem(TAB_CONTACTS, getString(R.string.contacts_tab), icon = R.drawable.ic_person_rounded_scaled))

            RadioGroupIconDialog(
                this@SettingsActivity,
                items,
                config.defaultTab,
                R.string.default_tab,
                defaultItemId = TAB_LAST_USED
            ) {
                config.defaultTab = it as Int
                binding.settingsDefaultTab.text = getDefaultTabText()
            }
        }
    }

    private fun getDefaultTabText() = getString(
        when (baseConfig.defaultTab) {
            TAB_FAVORITES -> R.string.favorites_tab
            TAB_CALL_HISTORY -> R.string.recents
            TAB_CONTACTS -> R.string.contacts_tab
            else -> R.string.last_used_tab
        }
    )

    private fun setupAlwaysShowFullscreen() {
        binding.apply {
            settingsAlwaysShowFullscreen.isChecked = config.showIncomingCallsFullScreen
            settingsAlwaysShowFullscreenHolder.setOnClickListener {
                settingsAlwaysShowFullscreen.toggle()
                config.showIncomingCallsFullScreen = settingsAlwaysShowFullscreen.isChecked
            }
        }
    }

    private fun setupQuickAnswers() {
        binding.apply {
            val getProperTextColor = getProperTextColor()
            settingsQuickAnswerOne.applyColorFilter(getProperTextColor)
            settingsQuickAnswerTwo.applyColorFilter(getProperTextColor)
            settingsQuickAnswerThree.applyColorFilter(getProperTextColor)

            settingsQuickAnswerOne.setOnClickListener {
                val index = 0
                ChangeTextDialog(
                    this@SettingsActivity,
                    currentText = config.quickAnswers[index],
                    showNeutralButton = true
                ) {
                    if (it == "") {
                        val text = getString(R.string.message_call_later)
                        addQuickAnswer(index, text)
                    } else {
                        addQuickAnswer(index, it)
                    }
                    toast(config.quickAnswers[index])
                }
            }
            settingsQuickAnswerTwo.setOnClickListener {
                val index = 1
                ChangeTextDialog(
                    this@SettingsActivity,
                    currentText = config.quickAnswers[index],
                    showNeutralButton = true
                ) {
                    if (it == "") {
                        val text = getString(R.string.message_on_my_way)
                        addQuickAnswer(index, text)
                    } else {
                        addQuickAnswer(index, it)
                    }
                    toast(config.quickAnswers[index])
                }
            }
            settingsQuickAnswerThree.setOnClickListener {
                val index = 2
                ChangeTextDialog(
                    this@SettingsActivity,
                    currentText = config.quickAnswers[index],
                    showNeutralButton = true
                ) {
                    if (it == "") {
                        val text = getString(R.string.message_cant_talk_right_now)
                        addQuickAnswer(index, text)
                    } else {
                        addQuickAnswer(index, it)
                    }
                    toast(config.quickAnswers[index])
                }
            }
        }
    }

    private fun addQuickAnswer(index: Int, text: String) {
        val quickAnswers = config.quickAnswers

        quickAnswers.removeAt(index)
        quickAnswers.add(index, text)

        config.quickAnswers = quickAnswers
    }

    private fun setupFlashForAlerts() {
        binding.apply {
            settingsFlashForAlerts.isChecked = config.flashForAlerts
            settingsFlashForAlertsHolder.setOnClickListener {
                settingsFlashForAlerts.toggle()
                config.flashForAlerts = settingsFlashForAlerts.isChecked
            }
        }
    }
    private fun setupStartNameWithSurname() {
        binding.apply {
            settingsStartNameWithSurname.isChecked = config.startNameWithSurname
            settingsStartNameWithSurnameHolder.setOnClickListener {
                settingsStartNameWithSurname.toggle()
                config.startNameWithSurname = settingsStartNameWithSurname.isChecked
            }
        }
    }

    private fun setupShowCallConfirmation() {
        binding.apply {
            settingsShowCallConfirmation.isChecked = config.showCallConfirmation
            settingsShowCallConfirmationHolder.setOnClickListener {
                settingsShowCallConfirmation.toggle()
                config.showCallConfirmation = settingsShowCallConfirmation.isChecked
            }
        }
    }

    private fun setupCallUsingSameSim() {
        binding.apply {
            settingsCallFromSameSimHolder.beVisibleIf(hasMultipleSims)
            settingsCallFromSameSim.isChecked = config.callUsingSameSim
            settingsCallFromSameSimHolder.setOnClickListener {
                settingsCallFromSameSim.toggle()
                config.callUsingSameSim = settingsCallFromSameSim.isChecked
            }
        }
    }

    private fun setupColorSimIcons() {
        binding.apply {
            settingsColorSimCardIconsHolder.beGoneIf(!hasMultipleSims)
            settingsColorSimCardIcons.isChecked = config.colorSimIcons
            settingsColorSimCardIconsHolder.setOnClickListener {
                settingsColorSimCardIcons.toggle()
                config.colorSimIcons = settingsColorSimCardIcons.isChecked
            }
        }
    }

    private fun setupSimCardColorList() {
        binding.apply {
            initSimCardColor()

            val pro = checkPro()
            val simList = getAvailableSIMCardLabels()
            if (simList.isNotEmpty()) {
                if (simList.size == 1) {
                    val sim1 = simList[0].label
                    settingsSimCardColor1Label.text = if (pro) sim1 else sim1 + " (${getString(R.string.feature_locked)})"
                } else {
                    val sim1 = simList[0].label
                    val sim2 = simList[1].label
                    settingsSimCardColor1Label.text = if (pro) sim1 else sim1 + " (${getString(R.string.feature_locked)})"
                    settingsSimCardColor2Label.text = if (pro) sim2 else sim2 + " (${getString(R.string.feature_locked)})"
                }
            }

            if (pro) {
                settingsSimCardColor1Holder.setOnClickListener {
                    ColorPickerDialog(
                        this@SettingsActivity,
                        color = config.simIconsColors[1],
                        addDefaultColorButton = true,
                        colorDefault = resources.getColor(R.color.ic_dialer, theme),
                        title = resources.getString(R.string.color_sim_card_icons)
                    ) { wasPositivePressed, color, wasDefaultPressed ->
                        if (wasPositivePressed || wasDefaultPressed) {
                            if (hasColorChanged(config.simIconsColors[1], color)) {
                                addSimCardColor(1, color)
                                initSimCardColor()
                            }
                        }
                    }
                }
                settingsSimCardColor2Holder.setOnClickListener {
                    ColorPickerDialog(
                        this@SettingsActivity,
                        color = config.simIconsColors[2],
                        addDefaultColorButton = true,
                        colorDefault = resources.getColor(R.color.color_primary, theme),
                        title = resources.getString(R.string.color_sim_card_icons)
                    ) { wasPositivePressed, color, wasDefaultPressed ->
                        if (wasPositivePressed || wasDefaultPressed) {
                            if (hasColorChanged(config.simIconsColors[2], color)) {
                                addSimCardColor(2, color)
                                initSimCardColor()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun initSimCardColor() {
        binding.apply {
            val pro = checkPro()
            arrayOf(
                settingsSimCardColor1Holder,
                settingsSimCardColor2Holder
            ).forEach {
                it.alpha = if (pro) 1f else 0.4f
            }
            settingsSimCardColor2Holder.beVisibleIf(hasMultipleSims)
            if (hasMultipleSims) settingsSimCardColor1Icon.setImageResource(R.drawable.ic_phone_one_vector)
            settingsSimCardColor1Icon.background.setTint(config.simIconsColors[1])
            settingsSimCardColor2Icon.background.setTint(config.simIconsColors[2])
            settingsSimCardColor1Icon.setColorFilter(config.simIconsColors[1].getContrastColor())
            settingsSimCardColor2Icon.setColorFilter(config.simIconsColors[2].getContrastColor())
        }
    }

    private fun addSimCardColor(index: Int, color: Int) {
        val recentColors = config.simIconsColors

        recentColors.removeAt(index)
        recentColors.add(index, color)

        val needUpdate = baseConfig.simIconsColors != recentColors
        baseConfig.simIconsColors = recentColors

        if (needUpdate) {
            // The cached rows carry the old SIM colour; drop them and let the next load rebuild.
            config.recentCallsCache = ""
            config.needUpdateRecents = true
        }
    }

    private fun hasColorChanged(old: Int, new: Int) = abs(old - new) > 1

    private fun setupSwipeRightAction() = binding.apply {
        if (isRTLLayout) settingsSwipeRightActionLabel.text = getString(R.string.swipe_left_action)
        settingsSwipeRightAction.text = getSwipeActionText(false)
        settingsSwipeRightActionHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(SWIPE_ACTION_DELETE, getString(com.goodwy.commons.R.string.delete), icon = com.goodwy.commons.R.drawable.ic_delete_outline),
                RadioItem(SWIPE_ACTION_BLOCK, getString(R.string.block_number), icon = R.drawable.ic_block_vector),
                RadioItem(SWIPE_ACTION_CALL, getString(R.string.call), icon = R.drawable.ic_phone_vector),
                RadioItem(SWIPE_ACTION_MESSAGE, getString(R.string.send_sms), icon = R.drawable.ic_messages),
                RadioItem(SWIPE_ACTION_WHATSAPP, getString(R.string.send_whatsapp), icon = R.drawable.ic_whatsapp_mono_vector),
                RadioItem(SWIPE_ACTION_NONE, getString(com.goodwy.commons.R.string.nothing)),
            )

            val title =
                if (isRTLLayout) R.string.swipe_left_action else R.string.swipe_right_action
            RadioGroupIconDialog(
                this@SettingsActivity,
                items,
                config.swipeRightAction,
                title,
                defaultItemId = SWIPE_ACTION_MESSAGE
            ) {
                config.swipeRightAction = it as Int
                settingsSwipeRightAction.text = getSwipeActionText(false)
            }
        }
    }

    private fun setupSwipeLeftAction() = binding.apply {
        val pro = checkPro()
        settingsSwipeLeftActionHolder.alpha = if (pro) 1f else 0.4f
        val stringId = if (isRTLLayout) R.string.swipe_right_action else R.string.swipe_left_action
        settingsSwipeLeftActionLabel.text = addLockedLabelIfNeeded(stringId, pro)
        settingsSwipeLeftAction.text = getSwipeActionText(true)
        settingsSwipeLeftActionHolder.setOnClickListener {
            if (pro) {
                val items = arrayListOf(
                    RadioItem(SWIPE_ACTION_DELETE, getString(com.goodwy.commons.R.string.delete), icon = com.goodwy.commons.R.drawable.ic_delete_outline),
                    RadioItem(SWIPE_ACTION_BLOCK, getString(R.string.block_number), icon = R.drawable.ic_block_vector),
                    RadioItem(SWIPE_ACTION_CALL, getString(R.string.call), icon = R.drawable.ic_phone_vector),
                    RadioItem(SWIPE_ACTION_MESSAGE, getString(R.string.send_sms), icon = R.drawable.ic_messages),
                    RadioItem(SWIPE_ACTION_WHATSAPP, getString(R.string.send_whatsapp), icon = R.drawable.ic_whatsapp_mono_vector),
                    RadioItem(SWIPE_ACTION_NONE, getString(com.goodwy.commons.R.string.nothing)),
                )

                val title =
                    if (isRTLLayout) R.string.swipe_right_action else R.string.swipe_left_action
                RadioGroupIconDialog(
                    this@SettingsActivity,
                    items,
                    config.swipeLeftAction,
                    title,
                    defaultItemId = SWIPE_ACTION_DELETE
                ) {
                    config.swipeLeftAction = it as Int
                    settingsSwipeLeftAction.text = getSwipeActionText(true)
                }
            }
        }
    }

    private fun getSwipeActionText(left: Boolean) = getString(
        when (if (left) config.swipeLeftAction else config.swipeRightAction) {
            SWIPE_ACTION_DELETE -> com.goodwy.commons.R.string.delete
            SWIPE_ACTION_BLOCK -> R.string.block_number
            SWIPE_ACTION_CALL -> R.string.call
            SWIPE_ACTION_MESSAGE -> R.string.send_sms
            SWIPE_ACTION_WHATSAPP -> R.string.send_whatsapp
            else -> com.goodwy.commons.R.string.nothing
        }
    )

    private fun setupAbout() = binding.apply {
        val flavorName = BuildConfig.FLAVOR
        val storeDisplayName = when (flavorName) {
            "gplay" -> "Google Play"
            "foss" -> "FOSS"
            "rustore" -> "RuStore"
            else -> "Huawei"
        }
        val versionName = BuildConfig.VERSION_NAME
        val fullVersionText = "Version: $versionName ($storeDisplayName)"

        settingsAboutVersion.text = fullVersionText
        settingsAboutHolder.setOnClickListener {
            startActivity(Intent(this@SettingsActivity, AboutActivity::class.java))
        }
    }

    private fun setupSettingsExport() {
        binding.settingsExportCallsHolder.setOnClickListener {
            saveDocument.launch(SETTINGS_FILE_NAME)
        }
    }

    private fun setupSettingsImport() {
        binding.settingsImportCallsHolder.setOnClickListener {
            getContent.launch(IMPORT_SETTINGS_FILE_TYPES.toTypedArray())
        }
    }

    private fun exportSettings(uri: Uri) {
        ensureBackgroundThread {
            try {
                val json = SettingsBackup.backup(getSharedPreferences(PREFS_KEY, MODE_PRIVATE))
                // "wt" truncates, so a shorter export over an existing file leaves no stale tail
                contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) }
                toast(R.string.exporting_successful)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }

    private fun importSettings(uri: Uri) {
        ensureBackgroundThread {
            try {
                val content = contentResolver.openInputStream(uri)!!.use { it.bufferedReader().readText() }
                SettingsBackup.restore(getSharedPreferences(PREFS_KEY, MODE_PRIVATE), content)
                applyPinnedSettings()
                toast(R.string.importing_successful)
                runOnUiThread { restartAfterImport() }
            } catch (_: JSONException) {
                toast(R.string.invalid_file_format)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }

    private fun setupDisableProximitySensor() {
        binding.apply {
            settingsDisableProximitySensor.isChecked = config.disableProximitySensor
            settingsDisableProximitySensorHolder.setOnClickListener {
                settingsDisableProximitySensor.toggle()
                config.disableProximitySensor = settingsDisableProximitySensor.isChecked
            }
        }
    }

    private fun setupFlipToSilence() {
        binding.apply {
            settingsFlipToSilence.isChecked = config.flipToSilence
            settingsFlipToSilenceHolder.setOnClickListener {
                settingsFlipToSilence.toggle()
                config.flipToSilence = settingsFlipToSilence.isChecked
            }
        }
    }

    private fun setupAnnounceCallerName() {
        binding.apply {
            settingsAnnounceCallerName.isChecked = config.announceCallerName
            settingsAnnounceCallerNameHolder.setOnClickListener {
                settingsAnnounceCallerName.toggle()
                config.announceCallerName = settingsAnnounceCallerName.isChecked
            }
        }
    }

    private fun setupDialpadVibrations() {
        binding.apply {
            settingsCallVibration.isChecked = config.callVibration
            settingsCallVibrationHolder.setOnClickListener {
                settingsCallVibration.toggle()
                // One switch for both: in-call keys and the dialpad screen keys
                config.callVibration = settingsCallVibration.isChecked
                config.dialpadVibration = settingsCallVibration.isChecked
            }
        }
    }

    private fun setupCallStartEndVibrations() {
        binding.apply {
            settingsCallStartEndVibration.isChecked = config.callStartEndVibration
            settingsCallStartEndVibrationHolder.setOnClickListener {
                settingsCallStartEndVibration.toggle()
                config.callStartEndVibration = settingsCallStartEndVibration.isChecked
            }
        }
    }

    private fun setupDialpadBeeps() {
        binding.apply {
            settingsDialpadBeeps.isChecked = config.dialpadBeeps
            settingsDialpadBeepsHolder.setOnClickListener {
                settingsDialpadBeeps.toggle()
                config.dialpadBeeps = settingsDialpadBeeps.isChecked
            }
        }
    }

    private fun checkPro(collection: Boolean = resources.getBoolean(R.bool.show_collection)) =
        if (collection) isOrWasThankYouInstalled(false) || isPro() || isCollection()
        else isOrWasThankYouInstalled(false) || isPro()
}
