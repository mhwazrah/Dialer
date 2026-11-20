package com.goodwy.dialer.extensions

import android.app.Activity
import android.content.Intent
import com.goodwy.dialer.helpers.CURRENT_RECENT_CALL
import com.goodwy.dialer.models.RecentCall
import com.goodwy.dialer.activities.CallHistoryActivity
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.util.TypedValue
import android.view.View
import com.goodwy.commons.extensions.getContactPublicUri
import com.goodwy.commons.extensions.isPackageInstalled
import com.goodwy.commons.extensions.launchActivityIntent
import com.goodwy.commons.extensions.showSupportSnackbar
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.helpers.CONTACT_ID
import com.goodwy.commons.helpers.FIRST_CONTACT_ID
import com.goodwy.commons.helpers.IS_PRIVATE
import com.goodwy.commons.helpers.LICENSE_AUTOFITTEXTVIEW
import com.goodwy.commons.helpers.LICENSE_EVENT_BUS
import com.goodwy.commons.helpers.LICENSE_GLIDE
import com.goodwy.commons.helpers.LICENSE_INDICATOR_FAST_SCROLL
import com.goodwy.commons.helpers.SimpleContactsHelper
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.models.FAQItem
import com.goodwy.commons.models.contacts.Contact
import com.goodwy.dialer.BuildConfig
import com.goodwy.dialer.R
import com.goodwy.dialer.activities.SimpleActivity
import com.goodwy.dialer.databinding.DialpadGridBinding
import com.goodwy.dialer.databinding.DialpadRectangleBinding
import com.goodwy.dialer.databinding.DialpadRoundBinding

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

fun SimpleActivity.launchPurchase() {
    val productIdX1 = BuildConfig.PRODUCT_ID_X1
    val productIdX2 = BuildConfig.PRODUCT_ID_X2
    val productIdX3 = BuildConfig.PRODUCT_ID_X3
    val subscriptionIdX1 = BuildConfig.SUBSCRIPTION_ID_X1
    val subscriptionIdX2 = BuildConfig.SUBSCRIPTION_ID_X2
    val subscriptionIdX3 = BuildConfig.SUBSCRIPTION_ID_X3
    val subscriptionYearIdX1 = BuildConfig.SUBSCRIPTION_YEAR_ID_X1
    val subscriptionYearIdX2 = BuildConfig.SUBSCRIPTION_YEAR_ID_X2
    val subscriptionYearIdX3 = BuildConfig.SUBSCRIPTION_YEAR_ID_X3

    startPurchaseActivity(
        R.string.app_name,
        productIdList = arrayListOf(productIdX1, productIdX2, productIdX3),
        productIdListRu = arrayListOf(productIdX1, productIdX2, productIdX3),
        subscriptionIdList = arrayListOf(subscriptionIdX1, subscriptionIdX2, subscriptionIdX3),
        subscriptionIdListRu = arrayListOf(subscriptionIdX1, subscriptionIdX2, subscriptionIdX3),
        subscriptionYearIdList = arrayListOf(subscriptionYearIdX1, subscriptionYearIdX2, subscriptionYearIdX3),
        subscriptionYearIdListRu = arrayListOf(subscriptionYearIdX1, subscriptionYearIdX2, subscriptionYearIdX3),
    )
}

fun SimpleActivity.launchAbout() {
    val licenses = LICENSE_GLIDE or LICENSE_INDICATOR_FAST_SCROLL or LICENSE_AUTOFITTEXTVIEW or LICENSE_EVENT_BUS

    val faqItems = arrayListOf(
        FAQItem(R.string.faq_1_title, R.string.faq_1_text),
        FAQItem(R.string.faq_2_title, R.string.faq_2_text),
        FAQItem(R.string.faq_3_title, R.string.faq_3_text_g),
        FAQItem(R.string.faq_1_title_dialer_g, R.string.faq_1_text_dialer_g),
        FAQItem(R.string.faq_2_title_dialer_g, R.string.faq_2_text_dialer_g),
        FAQItem(R.string.faq_2_title_commons, R.string.faq_2_text_commons_g),
        FAQItem(R.string.faq_6_title_commons, R.string.faq_6_text_commons_g),
        FAQItem(R.string.faq_7_title_commons, R.string.faq_7_text_commons),
        FAQItem(R.string.faq_9_title_commons, R.string.faq_9_text_commons)
    )

    val productIdX1 = BuildConfig.PRODUCT_ID_X1
    val productIdX2 = BuildConfig.PRODUCT_ID_X2
    val productIdX3 = BuildConfig.PRODUCT_ID_X3
    val subscriptionIdX1 = BuildConfig.SUBSCRIPTION_ID_X1
    val subscriptionIdX2 = BuildConfig.SUBSCRIPTION_ID_X2
    val subscriptionIdX3 = BuildConfig.SUBSCRIPTION_ID_X3
    val subscriptionYearIdX1 = BuildConfig.SUBSCRIPTION_YEAR_ID_X1
    val subscriptionYearIdX2 = BuildConfig.SUBSCRIPTION_YEAR_ID_X2
    val subscriptionYearIdX3 = BuildConfig.SUBSCRIPTION_YEAR_ID_X3

    val flavorName = BuildConfig.FLAVOR
    val storeDisplayName = when (flavorName) {
        "gplay" -> "Google Play"
        "foss" -> "FOSS"
        "rustore" -> "RuStore"
        else -> "Huawei"
    }
    val versionName = BuildConfig.VERSION_NAME
    val fullVersionText = "$versionName ($storeDisplayName)"

    startAboutActivity(
        appNameId = R.string.app_name,
        licenseMask = licenses,
        versionName = fullVersionText,
        flavorName = BuildConfig.FLAVOR,
        faqItems = faqItems,
        showFAQBeforeMail = true,
        productIdList = arrayListOf(productIdX1, productIdX2, productIdX3),
        productIdListRu = arrayListOf(productIdX1, productIdX2, productIdX3),
        subscriptionIdList = arrayListOf(subscriptionIdX1, subscriptionIdX2, subscriptionIdX3),
        subscriptionIdListRu = arrayListOf(subscriptionIdX1, subscriptionIdX2, subscriptionIdX3),
        subscriptionYearIdList = arrayListOf(subscriptionYearIdX1, subscriptionYearIdX2, subscriptionYearIdX3),
        subscriptionYearIdListRu = arrayListOf(subscriptionYearIdX1, subscriptionYearIdX2, subscriptionYearIdX3),
    )
}

fun SimpleActivity.showSnackbar(view: View) {
    showSupportSnackbar(view) { launchPurchase() }
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
