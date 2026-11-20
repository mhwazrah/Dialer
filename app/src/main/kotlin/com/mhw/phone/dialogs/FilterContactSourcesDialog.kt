package com.mhw.phone.dialogs

import androidx.appcompat.app.AlertDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.ContactsHelper
import com.goodwy.commons.helpers.SMT_PRIVATE
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.models.contacts.ContactSource
import com.mhw.phone.R
import com.mhw.phone.activities.SimpleActivity
import com.mhw.phone.adapters.FilterContactSourcesAdapter
import com.mhw.phone.databinding.DialogFilterContactSourcesBinding
import com.mhw.phone.extensions.config
import com.mhw.phone.extensions.loadPrivateContacts
import com.mhw.phone.helpers.ContactsRepository

class FilterContactSourcesDialog(val activity: SimpleActivity, private val callback: () -> Unit) {
    private val binding by activity.viewBinding(DialogFilterContactSourcesBinding::inflate)

    private var dialog: AlertDialog? = null
    private var contactSources = ArrayList<ContactSource>()
    private var contactCounts = emptyMap<String, Int>()
    @Volatile private var isContactSourcesReady = false
    @Volatile private var isContactsReady = false

    init {
        val contactHelper = ContactsHelper(activity)
        contactHelper.clearContactSourcesCache()
        contactHelper.getContactSources { contactSources ->
            contactSources.mapTo(this@FilterContactSourcesDialog.contactSources) { it.copy() }
            isContactSourcesReady = true
            processDataIfReady()
        }

        ContactsRepository.ensureLoaded(activity) { snapshot ->
            ensureBackgroundThread {
                val counts = snapshot.contacts.groupingBy { it.source }.eachCount().toMutableMap()
                // The snapshot has no private contacts while that source is hidden, but the dialog must still count them.
                if (SMT_PRIVATE in activity.config.ignoredContactSources) {
                    counts[SMT_PRIVATE] = activity.loadPrivateContacts(includeIgnored = true).size
                }
                contactCounts = counts
                isContactsReady = true
                processDataIfReady()
            }
        }
    }

    // Called from two background callbacks; the second one to arrive must see the first one's data.
    @Synchronized
    private fun processDataIfReady() {
        if (!isContactSourcesReady || activity.isFinishing || activity.isDestroyed) {
            return
        }

        val contactSourcesWithCount = ArrayList<ContactSource>()
        for (contactSource in contactSources) {
            val count = if (isContactsReady) {
                contactCounts[contactSource.name] ?: 0
            } else {
                -1
            }
            contactSourcesWithCount.add(contactSource.copy(count = count))
        }

        contactSources.clear()
        contactSources.addAll(contactSourcesWithCount)

        activity.runOnUiThread {
            val selectedSources = activity.getVisibleContactSources()
            binding.filterContactSourcesList.adapter = FilterContactSourcesAdapter(activity, contactSourcesWithCount, selectedSources)

            if (dialog == null) {
                activity.getAlertDialogBuilder()
                    .setPositiveButton(R.string.ok) { _, _ -> confirmContactSources() }
                    .setNegativeButton(R.string.cancel, null)
                    .apply {
                        activity.setupDialogStuff(binding.root, this, R.string.filter) { alertDialog ->
                            dialog = alertDialog
                        }
                    }
            }
        }
    }

    private fun confirmContactSources() {
        val selectedContactSources = (binding.filterContactSourcesList.adapter as FilterContactSourcesAdapter).getSelectedContactSources()
        val ignoredContactSources = contactSources.filter { !selectedContactSources.contains(it) }.map {
            if (it.type == SMT_PRIVATE) SMT_PRIVATE else it.getFullIdentifier()
        }.toHashSet()

        val previouslyIgnored = activity.config.ignoredContactSources
        if (previouslyIgnored != ignoredContactSources) {
            activity.config.ignoredContactSources = ignoredContactSources
            ContactsHelper(activity).clearContactSourcesCache()
            // Device contacts are filtered when listed; the private source is left out of the snapshot while hidden.
            if ((SMT_PRIVATE in previouslyIgnored) != (SMT_PRIVATE in ignoredContactSources)) {
                ContactsRepository.refresh(activity) { callback() }
            } else {
                callback()
            }
        }
        dialog?.dismiss()
    }
}
