package com.mhw.phone.activities

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import com.mhw.phone.helpers.gson
import com.goodwy.commons.dialogs.RadioGroupDialog
import com.goodwy.commons.dialogs.RadioGroupIconDialog
import com.goodwy.commons.extensions.getPhoneNumberTypeText
import com.goodwy.commons.extensions.hideKeyboard
import com.goodwy.commons.extensions.telecomManager
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.extensions.updateTextColors
import com.goodwy.commons.extensions.viewBinding
import com.goodwy.commons.helpers.ContactsHelper
import com.goodwy.commons.helpers.NavigationIcon
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.helpers.PERMISSION_READ_PHONE_STATE
import com.goodwy.commons.models.PhoneNumber
import com.goodwy.commons.models.RadioItem
import com.goodwy.commons.models.contacts.Contact
import com.mhw.phone.R
import com.mhw.phone.adapters.SpeedDialAdapter
import com.mhw.phone.databinding.ActivityManageSpeedDialBinding
import com.mhw.phone.dialogs.AddSpeedDialDialog
import com.mhw.phone.dialogs.SelectContactDialog
import com.mhw.phone.dialogs.SelectSIMDialog
import com.mhw.phone.extensions.areMultipleSIMsAvailable
import com.mhw.phone.extensions.config
import com.mhw.phone.extensions.listedContacts
import com.mhw.phone.helpers.ContactsRepository
import com.mhw.phone.extensions.getAvailableSIMCardLabels
import com.mhw.phone.extensions.getHandleToUse
import com.mhw.phone.interfaces.RemoveSpeedDialListener
import com.mhw.phone.models.SpeedDial

class ManageSpeedDialActivity : SimpleActivity(), RemoveSpeedDialListener {
    private val binding by viewBinding(ActivityManageSpeedDialBinding::inflate)

    @Volatile private var allContacts: List<Contact> = emptyList()
    private var speedDialValues = mutableListOf<SpeedDial>()
    private var speedDialAdapter: SpeedDialAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(manageSpeedDialScrollview))
        }

        speedDialValues = config.getSpeedDialValues()
        updateAdapter()

        ContactsRepository.ensureLoaded(this) { snapshot ->
            ensureBackgroundThread { allContacts = listedContacts(snapshot.contacts) }
        }

        updateTextColors(binding.manageSpeedDialScrollview)
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.manageSpeedDialAppbar, NavigationIcon.Arrow, navigationClick = false)
        binding.manageSpeedDialAppbar.setBackgroundColor(Color.TRANSPARENT)
        binding.manageSpeedDialToolbar.setBackgroundColor(Color.TRANSPARENT)
        binding.manageSpeedDialToolbar.setNavigationOnClickListener {
            hideKeyboard()
            onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        config.speedDial = gson.toJson(speedDialValues)
    }

    @SuppressLint("MissingPermission")
    private fun updateAdapter() {
        speedDialAdapter?.let {
            it.notifyDataSetChanged()
            return
        }

        SpeedDialAdapter(this, speedDialValues, this, binding.speedDialList) { any ->
            val clickedContact = any as SpeedDial
            if (allContacts.isEmpty()) {
                showAddSpeedDialDialog(clickedContact)
                return@SpeedDialAdapter
            }

            val items = arrayListOf(
                RadioItem(1, getString(R.string.add_number), icon = R.drawable.ic_hashtag),
                RadioItem(2, getString(R.string.choose_contact), icon = R.drawable.ic_view_contact_details_vector),
                RadioItem(3, getString(R.string.voicemail), icon = R.drawable.ic_voicemail),
                RadioItem(0, getString(R.string.delete), icon = R.drawable.ic_delete_outline)
            )

            RadioGroupIconDialog(this, items, titleId = R.string.speed_dial) { newValue ->
                when (newValue) {
                    1 -> { showAddSpeedDialDialog(clickedContact) }
                    2 -> {
                        SelectContactDialog(this, allContacts) { selectedContact ->
                            if (selectedContact.phoneNumbers.size > 1) {
                                val radioItems = selectedContact.phoneNumbers.mapIndexed { index, item ->
                                    RadioItem(index, "${item.value} (${getPhoneNumberTypeText(item.type, item.label)})", item)
                                }
                                val userPhoneNumbersList = selectedContact.phoneNumbers.map { it.value }
                                val checkedItemId = userPhoneNumbersList.indexOf(clickedContact.number)
                                RadioGroupDialog(this, ArrayList(radioItems), checkedItemId = checkedItemId) { selectedValue ->
                                    val selectedNumber = selectedValue as PhoneNumber
                                    speedDialValues.first { it.id == clickedContact.id }.apply {
                                        displayName = selectedContact.getNameToDisplay()
                                        number = selectedNumber.value
                                        type = selectedNumber.type
                                        label = selectedNumber.label
                                    }
                                    updateAdapter()
                                }
                            } else {
                                speedDialValues.first { it.id == clickedContact.id }.apply {
                                    val selectedNumber = selectedContact.phoneNumbers.first()
                                    displayName = selectedContact.getNameToDisplay()
                                    number = selectedNumber.value
                                    type = selectedNumber.type
                                    label = selectedNumber.label
                                }
                                updateAdapter()
                            }
                        }}

                    // READ_PHONE_STATE is only asked for once the user actually picks voicemail
                    3 -> handlePermission(PERMISSION_READ_PHONE_STATE) { granted ->
                        if (granted) chooseVoicemail(clickedContact.id)
                    }

                    else -> {
                        speedDialValues.first { it.id == clickedContact.id }.apply {
                            displayName = ""
                            number = ""
                        }
                        updateAdapter()
                    }
                }
            }
        }.apply {
            speedDialAdapter = this
            binding.speedDialList.adapter = this
        }
    }

    override fun removeSpeedDial(ids: ArrayList<Int>) {
        ids.forEach { dialId ->
            speedDialValues.first { it.id == dialId }.apply {
                displayName = ""
                number = ""
                type = null
                label = null
            }
        }
        updateAdapter()
    }

    private fun showAddSpeedDialDialog(clickedContact: SpeedDial) {
        AddSpeedDialDialog(this, clickedContact) { newNumber ->
            speedDialValues.first { it.id == clickedContact.id }.apply {
                displayName = newNumber
                number = newNumber
                type = null
                label = null
            }
            updateAdapter()
        }
    }

    private fun chooseVoicemail(speedDialId: Int) {
        if (areMultipleSIMsAvailable()) {
            SelectSIMDialog(this, "") { handle, label -> setVoicemail(speedDialId, handle, label.orEmpty()) }
        } else {
            getHandleToUse(intent, "") { handle ->
                if (handle == null) return@getHandleToUse
                setVoicemail(speedDialId, handle, getAvailableSIMCardLabels().firstOrNull()?.label.orEmpty())
            }
        }
    }

    private fun setVoicemail(speedDialId: Int, handle: PhoneAccountHandle?, label: String) {
        val voiceMailNumber = try {
            telecomManager.getVoiceMailNumber(handle)
        } catch (_: SecurityException) {
            null
        }
        val speedDial = speedDialValues.firstOrNull { it.id == speedDialId }
        if (voiceMailNumber == null || speedDial == null) {
            toast(R.string.unknown)
            return
        }
        speedDial.displayName = getString(R.string.voicemail) + "($label)"
        speedDial.number = voiceMailNumber
        updateAdapter()
    }
}
