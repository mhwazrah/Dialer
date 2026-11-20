package com.goodwy.dialer.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.telephony.PhoneNumberUtils
import android.telecom.Call
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.PERMISSION_READ_PHONE_STATE
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.dialer.R
import com.goodwy.dialer.extensions.config
import com.goodwy.dialer.extensions.isConference
import com.goodwy.dialer.models.CallContact
import java.util.concurrent.ConcurrentHashMap

// Resolved contact per call, dropped in CallManager.onCallRemoved/clear. Notification updates and the call UI
// ask for the same contact repeatedly during one call.
private val callContacts = ConcurrentHashMap<Call, CallContact>()

fun clearCallContact(call: Call) {
    callContacts.remove(call)
}

fun clearCallContacts() {
    callContacts.clear()
}

fun getCallContact(context: Context, call: Call?, callback: (CallContact) -> Unit) {
    if (call.isConference()) {
        callback(CallContact(context.getString(R.string.conference), "", "", "", ""))
        return
    }

    val cached = call?.let { callContacts[it] }
    if (cached != null) {
        callback(cached.copy())
        return
    }

    ensureBackgroundThread {
        val callContact = CallContact("", "", "", "", "")
        val handle = try {
            call?.details?.handle?.toString()
        } catch (_: NullPointerException) {
            null
        }

        if (handle == null) {
            callback(callContact)
            return@ensureBackgroundThread
        }

        val uri = Uri.decode(handle)
        if (!uri.startsWith("tel:")) {
            callback(callContact)
            return@ensureBackgroundThread
        }

        // The caller must always get exactly one callback, even if the contact lookup fails.
        var delivered = false
        fun deliver() {
            if (delivered) return
            delivered = true
            if (call != null) callContacts[call] = callContact.copy()
            callback(callContact)
        }

        try {
            val number = uri.substringAfter("tel:")

            @SuppressLint("MissingPermission")
            if (context.hasPermission(PERMISSION_READ_PHONE_STATE)) {
                try {
                    val telecomManager = context.telecomManager
                    telecomManager.callCapablePhoneAccounts.forEach { account ->
                        val phoneAccount = telecomManager.getPhoneAccount(account) ?: return@forEach
                        val voiceMailNumber = telecomManager.getVoiceMailNumber(phoneAccount.accountHandle)
                        if (voiceMailNumber == number) {
                            callContact.isVoiceMail = true
                        }
                    }
                } catch (_: Exception) {
                }
            }

            try {
                callContact.number = try {
                    if (context.config.formatPhoneNumbers) {
                        number.formatPhoneNumber()
                    } else {
                        number
                    }
                } catch (_: Exception) {
                    number
                }

                // Index / PhoneLookup lookup: a call process never pays for a full contact load.
                val contact = ContactsRepository.findByNumber(context, number)
                if (contact != null) {
                    callContact.name = contact.getNameToDisplay()
                    callContact.photoUri = contact.photoUri

                    val specificPhoneNumber = contact.phoneNumbers.firstOrNull { it.normalizedNumber == number || PhoneNumberUtils.compare(it.value, number) }
                    if (specificPhoneNumber != null) {
                        callContact.numberLabel = context.getPhoneNumberTypeText(specificPhoneNumber.type, specificPhoneNumber.label)
                    }

                    val showCallerDescription = try { context.config.showCallerDescription }  catch (_: Exception) { SHOW_CALLER_COMPANY }
                    if (showCallerDescription != SHOW_CALLER_NOTHING) {
                        if (contact.organization.company.isNotEmpty() && showCallerDescription == SHOW_CALLER_COMPANY) {
                            if (contact.organization.jobPosition.isNotEmpty()) {
                                callContact.description = contact.organization.company + " (" + contact.organization.jobPosition + ")"
                            } else {
                                callContact.description = contact.organization.company
                            }
                        }
                        if (contact.nickname.isNotEmpty() && showCallerDescription == SHOW_CALLER_NICKNAME) {
                            callContact.description = contact.nickname
                        }
                    }

                    callContact.isABusinessCall =
                        contact.organization.company.isNotEmpty() && contact.getNameToDisplay().contains(contact.organization.company)
                } else {
                    callContact.name = callContact.number
                }
            } catch (_: Exception) {
                if (callContact.name.isEmpty()) callContact.name = number
            }
            deliver()
        } catch (_: Exception) {
            deliver()
        }
    }
}
