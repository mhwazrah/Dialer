package com.mhw.phone.activities

import com.mhw.phone.extensions.loadBrandedContactImage
import com.goodwy.commons.models.contacts.Contact
import android.widget.ImageView
import android.content.res.Resources
import com.goodwy.commons.dialogs.RadioGroupDialog
import com.goodwy.commons.models.RadioItem
import android.content.DialogInterface.BUTTON_POSITIVE
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import com.goodwy.commons.activities.ManageBlockedNumbersActivity
import java.io.File
import android.widget.CompoundButton
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.BlockedNumber
import com.mhw.phone.R
import com.mhw.phone.databinding.ActivityBlockedNumbersBinding
import com.mhw.phone.databinding.DialogAddBlockedNumberBinding
import com.mhw.phone.databinding.ItemBlockedNumberBinding
import com.mhw.phone.helpers.ContactsRepository

class BlockedNumbersActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityBlockedNumbersBinding::inflate)

    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportBlockedNumbers(uri)
    }
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importBlockedNumbers(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(blockedNestedScrollview))
            blockedAddHolder.setOnClickListener { showAddDialog() }
            blockedExportHolder.setOnClickListener { exportDocument.launch("blocked-numbers.txt") }
            blockedImportHolder.setOnClickListener { importDocument.launch(arrayOf("text/plain", "application/octet-stream")) }
        }
        // The quick-settings tile opens this screen to ask for the caller-ID (call screening) role
        if (intent.action == ManageBlockedNumbersActivity.SET_DEFAULT_CALLER_ID && isQPlus()) setDefaultCallerIdApp()
    }

    private fun exportBlockedNumbers(uri: Uri) {
        ensureBackgroundThread {
            try {
                contentResolver.openOutputStream(uri, "wt")!!.use { stream ->
                    BlockedNumbersExporter.exportBlockedNumbers(getBlockedNumbers(), stream) { result ->
                        toast(if (result == ExportResult.EXPORT_FAIL) com.goodwy.commons.R.string.exporting_failed else com.goodwy.commons.R.string.exporting_successful)
                    }
                }
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }

    // The commons importer reads a file path, so the picked document is copied to the cache first
    private fun importBlockedNumbers(uri: Uri) {
        ensureBackgroundThread {
            try {
                val file = File(cacheDir, "blocked-numbers-import.txt")
                contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
                val result = BlockedNumbersImporter(this).importBlockedNumbers(file.absolutePath)
                file.delete()
                toast(if (result == BlockedNumbersImporter.ImportResult.IMPORT_OK) com.goodwy.commons.R.string.importing_successful else com.goodwy.commons.R.string.no_items_found)
                loadBlockedNumbers()
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.blockedAppbar, NavigationIcon.Arrow)
        binding.blockedAppbar.setBackgroundColor(Color.TRANSPARENT)
        binding.blockedToolbar.setBackgroundColor(Color.TRANSPARENT)

        binding.apply {
            arrayOf(blockedSwitchesCard, blockedListCard, blockedBackupCard).forEach {
                it.setCardBackgroundColor(Color.TRANSPARENT)
                it.setBackgroundResource(R.drawable.bg_glass_card)
            }
            updateTextColors(blockedHolder)
            val primaryColor = getProperPrimaryColor()
            arrayOf(blockedListLabel, blockedBackupLabel, blockedAddLabel).forEach { it.setTextColor(primaryColor) }
            blockedAddIcon.applyColorFilter(primaryColor)
            arrayOf(blockedExportChevron, blockedImportChevron).forEach { it.applyColorFilter(getProperTextColor()) }
        }
        setupSwitches()
        loadBlockedNumbers()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode != REQUEST_CODE_SET_DEFAULT_CALLER_ID) return
        // The tile's request is answered once, granted or not, so a recreate never asks again
        val fromTile = intent.action == ManageBlockedNumbersActivity.SET_DEFAULT_CALLER_ID
        intent.action = null
        // Same as the commons screen: the tile's request turns blocking on once the role is granted
        if (resultCode == RESULT_OK && fromTile) {
            baseConfig.blockingEnabled = true
            setupSwitches()
        } else if (resultCode != RESULT_OK) {
            toast(com.goodwy.commons.R.string.must_make_default_caller_id_app, android.widget.Toast.LENGTH_LONG)
            baseConfig.blockingEnabled = false
            baseConfig.blockUnknownNumbers = false
            baseConfig.blockHiddenNumbers = false
            setupSwitches()
        }
    }

    private fun setupSwitches() {
        binding.apply {
            bindSwitch(blockedEnableHolder, blockedEnable, baseConfig.blockingEnabled) { enabled ->
                baseConfig.blockingEnabled = enabled
                if (enabled && isQPlus()) setDefaultCallerIdApp()
                setupSwitches()
            }
            bindSwitch(blockedUnknownHolder, blockedUnknown, baseConfig.blockUnknownNumbers) {
                baseConfig.blockUnknownNumbers = it
            }
            bindSwitch(blockedHiddenHolder, blockedHidden, baseConfig.blockHiddenNumbers) {
                baseConfig.blockHiddenNumbers = it
            }
            bindSwitch(blockedSkipContactsHolder, blockedSkipContacts, baseConfig.doNotBlockContactsAndRecent) {
                baseConfig.doNotBlockContactsAndRecent = it
            }
            blockedType.text = getString(blockingTypeText(baseConfig.blockingType))
            blockedTypeHolder.setOnClickListener { showBlockingTypeDialog() }
            arrayOf(blockedUnknownHolder, blockedHiddenHolder, blockedSkipContactsHolder, blockedTypeHolder).forEach {
                it.beGoneIf(!baseConfig.blockingEnabled)
            }
        }
    }

    private fun showBlockingTypeDialog() {
        val items = arrayListOf(BLOCKING_TYPE_REJECT, BLOCKING_TYPE_DO_NOT_REJECT, BLOCKING_TYPE_SILENCE)
            .filter { it != BLOCKING_TYPE_SILENCE || isQPlus() } // silencing needs Android 10's call screening API
            .map { RadioItem(it, getString(blockingTypeText(it))) }
        RadioGroupDialog(this, ArrayList(items), baseConfig.blockingType, com.goodwy.strings.R.string.blocking_type) {
            baseConfig.blockingType = it as Int
            binding.blockedType.text = getString(blockingTypeText(it))
        }
    }

    private fun blockingTypeText(type: Int) = when (type) {
        BLOCKING_TYPE_DO_NOT_REJECT -> com.goodwy.strings.R.string.blocking_type_do_not_reject
        BLOCKING_TYPE_SILENCE -> com.goodwy.strings.R.string.blocking_type_silence
        else -> com.goodwy.strings.R.string.blocking_type_reject
    }

    private fun bindSwitch(
        holder: android.view.View,
        switch: CompoundButton,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ) {
        switch.isChecked = checked
        holder.setOnClickListener {
            switch.toggle()
            onChanged(switch.isChecked)
        }
    }

    private fun loadBlockedNumbers() {
        ensureBackgroundThread {
            val numbers = try {
                getBlockedNumbers()
            } catch (_: SecurityException) {
                arrayListOf()
            }
            // The contact each number belongs to, when it is saved in the phone
            val contacts = numbers.associate { blocked ->
                blocked.number to runCatching { ContactsRepository.findByNumber(this, blocked.number) }.getOrNull()
            }
            runOnUiThread { if (!isDestroyed) showBlockedNumbers(numbers, contacts) }
        }
    }

    private fun showBlockedNumbers(numbers: List<BlockedNumber>, contacts: Map<String, Contact?>) = binding.apply {
        blockedList.removeAllViews()
        val textColor = getProperTextColor()
        numbers.forEach { blocked ->
            val item = ItemBlockedNumberBinding.inflate(layoutInflater, blockedList, false)
            val contact = contacts[blocked.number]
            val name = contact?.getNameToDisplay()?.takeIf { it.isNotBlank() && it != blocked.number }
            item.blockedNumberName.text = name
            item.blockedNumberName.beVisibleIf(name != null)
            item.blockedNumberText.text = blocked.number
            if (name != null) item.blockedNumberText.alpha = SECONDARY_TEXT_ALPHA
            if (name != null) {
                item.blockedNumberAvatar.loadBrandedContactImage(contact.photoUri, name)
            } else {
                // Not a saved contact (or a pattern like +234*): the block sign on a tonal circle
                item.blockedNumberAvatar.setBackgroundResource(R.drawable.bg_dialog_icon)
                item.blockedNumberAvatar.setImageResource(R.drawable.ic_block_vector)
                item.blockedNumberAvatar.scaleType = ImageView.ScaleType.CENTER_INSIDE
                item.blockedNumberAvatar.setPadding(AVATAR_ICON_PADDING, AVATAR_ICON_PADDING, AVATAR_ICON_PADDING, AVATAR_ICON_PADDING)
                item.blockedNumberAvatar.applyColorFilter(textColor)
            }
            item.blockedNumberDelete.applyColorFilter(textColor)
            item.blockedNumberDelete.setOnClickListener { deleteBlockedNumberAsync(blocked.number) }
            blockedList.addView(item.root)
        }
        updateTextColors(blockedList)
        blockedEmpty.beVisibleIf(numbers.isEmpty())
    }

    private fun deleteBlockedNumberAsync(number: String) {
        ensureBackgroundThread {
            deleteBlockedNumber(number)
            loadBlockedNumbers()
        }
    }

    private fun showAddDialog() {
        val dialogBinding = DialogAddBlockedNumberBinding.inflate(layoutInflater)
        getAlertDialogBuilder()
            .setPositiveButton(com.goodwy.commons.R.string.ok, null)
            .setNegativeButton(com.goodwy.commons.R.string.cancel, null)
            .apply {
                setupDialogStuff(
                    dialogBinding.root,
                    this,
                    com.goodwy.commons.R.string.add_a_blocked_number
                ) { alertDialog ->
                    alertDialog.showKeyboard(dialogBinding.text)
                    alertDialog.getButton(BUTTON_POSITIVE).setOnClickListener {
                        val number = dialogBinding.text.value.trim()
                        if (number.isEmpty()) return@setOnClickListener
                        alertDialog.dismiss()
                        addBlockedNumberAsync(number)
                    }
                }
            }
    }

    private fun addBlockedNumberAsync(number: String) {
        ensureBackgroundThread {
            if (addBlockedNumber(number)) {
                loadBlockedNumbers()
            } else {
                runOnUiThread { toast(com.goodwy.commons.R.string.unknown_error_occurred) }
            }
        }
    }

    private companion object {
        const val SECONDARY_TEXT_ALPHA = 0.7f // the number under a contact name
        val AVATAR_ICON_PADDING = (9 * Resources.getSystem().displayMetrics.density).toInt()
    }
}
