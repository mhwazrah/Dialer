package com.mhw.phone.extensions

import android.app.Activity
import android.content.Intent
import com.mhw.phone.helpers.CURRENT_RECENT_CALL
import com.mhw.phone.models.RecentCall
import com.mhw.phone.activities.CallHistoryActivity
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.util.TypedValue
import com.goodwy.commons.extensions.getContactPublicUri
import com.goodwy.commons.extensions.isPackageInstalled
import com.goodwy.commons.extensions.launchActivityIntent
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.helpers.CONTACT_ID
import com.goodwy.commons.helpers.FIRST_CONTACT_ID
import com.goodwy.commons.helpers.IS_PRIVATE
import com.goodwy.commons.helpers.SimpleContactsHelper
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.models.contacts.Contact
import com.mhw.phone.R
import com.mhw.phone.activities.AboutActivity
import com.mhw.phone.activities.SimpleActivity
import com.mhw.phone.databinding.DialpadGridBinding
import com.mhw.phone.databinding.DialpadRectangleBinding
import com.mhw.phone.databinding.DialpadRoundBinding

fun SimpleActivity.launchCreateNewContactIntent() {
    Intent().apply {
        action = Intent.ACTION_INSERT
        data = ContactsContract.Contacts.CONTENT_URI
        launchActivityIntent(this)
    }
}

// handle private contacts differently, only Simple Contacts Pro can open them
/** Opens the app's own contact page (CallHistoryActivity) for the contact's main number; without a number, the contacts app. */
fun Activity.startContactDetailsIntent(contact: Contact) {
    val number = (contact.phoneNumbers.firstOrNull { it.isPrimary } ?: contact.phoneNumbers.firstOrNull())?.value
    if (number.isNullOrBlank()) {
        startSystemContactDetails(contact)
        return
    }
    // The page is built around a call: a call-less entry for the number makes it load that number's history itself.
    val call = RecentCall(
        id = -1,
        phoneNumber = number,
        name = contact.getNameToDisplay(),
        photoUri = contact.photoUri,
        startTS = 0L,
        duration = 0,
        type = 0,
        simID = -1,
        simColor = 0,
        specificNumber = "",
        specificType = "",
        isUnknownNumber = false,
        contactID = contact.id,
        isVoiceMail = false,
    )
    Intent(this, CallHistoryActivity::class.java).apply {
        putExtra(CURRENT_RECENT_CALL, call)
        putExtra(CONTACT_ID, contact.id)
        putExtra(IS_PRIVATE, contact.isPrivate())
        launchActivityIntent(this)
    }
}

/** Opens the contact in the phone's contacts app (full details / editing). */
fun Activity.startSystemContactDetails(contact: Contact) {
    val simpleContacts = "com.goodwy.contacts"
    val simpleContactsDebug = "com.goodwy.contacts.debug"
    val newContacts = "dev.goodwy.contacts"
    val newContactsDebug = "dev.goodwy.contacts.debug"
    val contactsPackage = listOf(newContacts, simpleContacts, newContactsDebug, simpleContactsDebug)
        .firstOrNull { isPackageInstalled(it) }
    val isPrivateContact = contact.rawId > FIRST_CONTACT_ID
        && contact.contactId > FIRST_CONTACT_ID
        && contact.rawId == contact.contactId
        && contactsPackage != null
    if (isPrivateContact) {
        Intent().apply {
            action = Intent.ACTION_VIEW
            putExtra(CONTACT_ID, contact.rawId)
            putExtra(IS_PRIVATE, true)
            `package` = contactsPackage
            setDataAndType(
                ContactsContract.Contacts.CONTENT_LOOKUP_URI,
                "vnd.android.cursor.dir/person"
            )
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            launchActivityIntent(this)
        }
    } else {
        ensureBackgroundThread {
            val lookupKey = SimpleContactsHelper(this).getContactLookupKey(contact.rawId.toString())
            runOnUiThread {
                // Without a lookup key there is no contact to open (e.g. a company-only entry)
                if (lookupKey.isNullOrBlank()) return@runOnUiThread
                // ACTION_VIEW, not QUICK_CONTACT: some contacts apps (Samsung's) register no QUICK_CONTACT handler,
                // which left "No valid app found"; every contacts app opens a contact for ACTION_VIEW.
                Intent().apply {
                    action = Intent.ACTION_VIEW
                    data = ContactsContract.Contacts.getLookupUri(contact.contactId.toLong(), lookupKey)
                    launchActivityIntent(this)
                }
            }
        }
    }
}

//Goodwy
fun Activity.startContactEdit(contact: Contact) {
    Intent().apply {
        action = Intent.ACTION_EDIT
        data = getContactPublicUri(contact)
        launchActivityIntent(this)
    }
}

fun SimpleActivity.launchAbout() {
    startActivity(Intent(this, AboutActivity::class.java))
}

fun Activity.launchSendWhatsAppIntent(phoneNumber: String) {
    // wa.me wants the international number without the "+"
    val digits = PhoneNumberUtils.formatNumberToE164(phoneNumber, config.regionHint())?.drop(1)
    if (digits.isNullOrEmpty()) {
        toast(R.string.no_app_found)
        return
    }
    val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { isPackageInstalled(it) }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits"))
    if (pkg != null) intent.setPackage(pkg)
    try {
        startActivity(intent)
    } catch (_: android.content.ActivityNotFoundException) {
        toast(R.string.no_app_found)
    }
}

// Applies the configured dialpad-number font size to every digit/plus key across the three
// dialpad styles. Shared by DialpadActivity and SettingsDialpadActivity.
fun applyDialpadNumberFontSize(
    clearWrapper: DialpadGridBinding,
    rectWrapper: DialpadRectangleBinding,
    roundWrapper: DialpadRoundBinding,
    percent: Int,
) {
    val res = clearWrapper.root.resources
    val scale = percent / 100f
    val scaledPx = res.getDimension(R.dimen.dialpad_text_size) * scale
    // Pill keys are short: a smaller digit leaves room for the letters, including a second
    // language row, inside the pill. The "+" under 0 is secondary, so it is smaller still.
    val pillPx = res.getDimension(R.dimen.dialpad_pill_text_size) * scale
    clearWrapper.apply {
        listOf(dialpad1, dialpad2, dialpad3, dialpad4, dialpad5,
            dialpad6, dialpad7, dialpad8, dialpad9, dialpad0).forEach {
            it.setTextSize(TypedValue.COMPLEX_UNIT_PX, pillPx)
        }
        dialpadPlus.setTextSize(TypedValue.COMPLEX_UNIT_PX, pillPx * 0.6f)
    }
    rectWrapper.apply {
        listOf(dialpad1, dialpad2, dialpad3, dialpad4, dialpad5,
            dialpad6, dialpad7, dialpad8, dialpad9, dialpad0, dialpadPlus).forEach {
            it.setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledPx)
        }
    }
    roundWrapper.apply {
        listOf(dialpad1Ios, dialpad2Ios, dialpad3Ios, dialpad4Ios, dialpad5Ios,
            dialpad6Ios, dialpad7Ios, dialpad8Ios, dialpad9Ios, dialpad0Ios, dialpadPlusIos).forEach {
            it.setTextSize(TypedValue.COMPLEX_UNIT_PX, scaledPx)
        }
    }
}
