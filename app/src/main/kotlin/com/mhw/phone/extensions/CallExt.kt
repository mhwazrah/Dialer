package com.mhw.phone.extensions

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.core.content.IntentCompat
import com.goodwy.commons.R
import com.goodwy.commons.activities.BaseSimpleActivity
import com.mhw.phone.dialogs.CallConfirmDialog
import com.goodwy.commons.dialogs.PermissionRequiredDialog
import com.goodwy.commons.extensions.canUseFullScreenIntent
import com.goodwy.commons.extensions.initiateCall
import com.goodwy.commons.extensions.launchActivityIntent
import com.goodwy.commons.extensions.openFullScreenIntentSettings
import com.goodwy.commons.extensions.openNotificationSettings
import com.goodwy.commons.extensions.telecomManager
import com.goodwy.commons.helpers.IS_RIGHT_APP
import com.goodwy.commons.helpers.PERMISSION_CALL_PHONE
import com.goodwy.commons.helpers.PERMISSION_READ_PHONE_STATE
import com.goodwy.commons.models.contacts.Contact
import com.mhw.phone.BuildConfig
import com.mhw.phone.activities.DialerActivity
import com.mhw.phone.activities.WidgetCallActivity
import com.mhw.phone.activities.SimpleActivity
import com.mhw.phone.dialogs.SelectSIMDialog
import com.mhw.phone.models.RecentCall
import com.mhw.phone.models.SIMAccount
import com.mhw.phone.dialogs.SelectSimButtonDialog
import com.mhw.phone.helpers.SIM_DIALOG_STYLE_LIST

/**
 * Places a call through this app. Commons' launchCallIntent addresses the call to the package "com.goodwy.dialer"
 * whenever this app is the default dialer (and fails once that app is gone), so calls go to our own DialerActivity.
 * Without the call permission it opens the dialer with the number instead, like commons.
 */
fun BaseSimpleActivity.placeCall(recipient: String, handle: PhoneAccountHandle? = null) {
    handlePermission(PERMISSION_CALL_PHONE) { granted ->
        val intent = Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.fromParts("tel", recipient, null)).apply {
            if (handle != null) putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            putExtra(IS_RIGHT_APP, BuildConfig.RIGHT_APP_KEY)
            if (granted && isDefaultPhoneApp()) setClass(this@placeCall, DialerActivity::class.java)
        }
        launchActivityIntent(intent)
    }
}

fun SimpleActivity.startCallIntent(
    recipient: String,
    forceSimSelector: Boolean = false
) {
    if (isDefaultPhoneApp()) {
        getHandleToUse(
            intent = null,
            phoneNumber = recipient,
            forceSimSelector = forceSimSelector
        ) { handle ->
            placeCall(recipient, handle)
        }
    } else {
        placeCall(recipient)
    }
}

fun SimpleActivity.startCallWithConfirmationCheck(
    recipient: String,
    name: String,
    forceSimSelector: Boolean = false,
    photoUri: String? = null,
    onCancel: () -> Unit = {}
) {
    if (config.showCallConfirmation) {
        CallConfirmDialog(this, name, recipient, photoUri, onCancel = onCancel) {
            startCallIntent(recipient, forceSimSelector)
        }
    } else {
        startCallIntent(recipient, forceSimSelector)
    }
}

fun SimpleActivity.startCallWithConfirmationCheck(contact: Contact) {
    if (config.showCallConfirmation) {
        CallConfirmDialog(this, contact.getNameToDisplay(), contact.primaryNumber(), contact.photoUri) {
            initiateCall(contact) { placeCall(it) }
        }
    } else {
        initiateCall(contact) { placeCall(it) }
    }
}

fun BaseSimpleActivity.callContactWithSim(
    recipient: String,
    useMainSIM: Boolean
) {
    handlePermission(PERMISSION_READ_PHONE_STATE) { granted ->
        if (!granted) return@handlePermission
        val wantedSimIndex = if (useMainSIM) 0 else 1
        val handle = getAvailableSIMCardLabels()
            .sortedBy { it.id }
            .getOrNull(wantedSimIndex)?.handle
        placeCall(recipient, handle)
    }
}

/**
 * SIM a recent call is called back with: the number's saved SIM, else the call's own SIM when "call using the same SIM"
 * is on, else the default outgoing SIM. Null means none applies and the user is asked.
 */
fun Context.recentCallSim(call: RecentCall, sims: List<SIMAccount>): SIMAccount? {
    config.getCustomSIM(call.phoneNumber)?.let { saved -> sims.firstOrNull { it.handle == saved }?.let { return it } }
    if (config.callUsingSameSim && call.simID > 0) sims.firstOrNull { it.id == call.simID }?.let { return it }
    val defaultHandle = try {
        telecomManager.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL)
    } catch (_: SecurityException) {
        null
    }
    return sims.firstOrNull { it.handle == defaultHandle }
}

fun BaseSimpleActivity.callContactWithSimWithConfirmationCheck(
    recipient: String,
    name: String,
    useMainSIM: Boolean
) {
    if (config.showCallConfirmation) {
        CallConfirmDialog(this, name, recipient) {
            callContactWithSim(recipient, useMainSIM)
        }
    } else {
        callContactWithSim(recipient, useMainSIM)
    }
}

// used at devices with multiple SIM cards
@SuppressLint("MissingPermission")
fun SimpleActivity.getHandleToUse(
    intent: Intent?,
    phoneNumber: String,
    forceSimSelector: Boolean = false,
    callback: (handle: PhoneAccountHandle?) -> Unit
) {
    handlePermission(PERMISSION_READ_PHONE_STATE) {
        if (!it) {
            callback(null)
            return@handlePermission
        }
        val defaultHandle = telecomManager.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL)
        val intentHandle = intent?.let { sourceIntent ->
            IntentCompat.getParcelableExtra(sourceIntent, TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, PhoneAccountHandle::class.java)
        }
        val customHandle = config.getCustomSIM(phoneNumber)
        when {
            forceSimSelector -> showSelectSimDialog(phoneNumber, callback)
            intentHandle != null -> callback(intentHandle)
            customHandle != null && areMultipleSIMsAvailable() -> callback(customHandle)
            defaultHandle != null -> callback(defaultHandle)
            else -> showSelectSimDialog(phoneNumber, callback)
        }
    }
}

fun SimpleActivity.showSelectSimDialog(
    phoneNumber: String,
    callback: (handle: PhoneAccountHandle?) -> Unit
) {
    if (config.simDialogStyle == SIM_DIALOG_STYLE_LIST) {
        SelectSIMDialog(
            activity = this,
            phoneNumber = phoneNumber,
            onDismiss = {
                if (this is DialerActivity || this is WidgetCallActivity) {
                    finish()
                }
            }
        ) { handle, _ ->
            callback(handle)
        }
    } else {
        SelectSimButtonDialog(
            activity = this,
            phoneNumber = phoneNumber,
            onDismiss = {
                if (this is DialerActivity || this is WidgetCallActivity) {
                    finish()
                }
            }
        ) { handle, _ ->
            callback(handle)
        }
    }
}

fun SimpleActivity.handleFullScreenNotificationsPermission(callback: (granted: Boolean) -> Unit) {
    handleNotificationPermission { granted ->
        if (granted) {
            if (canUseFullScreenIntent()) {
                callback(true)
            } else {
                PermissionRequiredDialog(
                    activity = this,
                    textId = R.string.allow_full_screen_notifications_incoming_calls,
                    positiveActionCallback = {
                        @SuppressLint("NewApi")
                        openFullScreenIntentSettings(BuildConfig.APPLICATION_ID)
                    },
                    negativeActionCallback = {
                        callback(false)
                    }
                )
            }
        } else {
            PermissionRequiredDialog(
                activity = this,
                textId = R.string.allow_notifications_incoming_calls,
                positiveActionCallback = {
                    openNotificationSettings()
                },
                negativeActionCallback = {
                    callback(false)
                }
            )
        }
    }
}
