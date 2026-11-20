package com.mhw.phone.receivers

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.telephony.PhoneNumberUtils
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.CURRENT_PHONE_NUMBER
import com.goodwy.commons.helpers.SimpleContactsHelper
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.mhw.phone.R
import com.mhw.phone.activities.NotificationActivity
import com.mhw.phone.activities.SplashActivity
import com.mhw.phone.extensions.contactLetterBitmap
import com.mhw.phone.extensions.getNotificationBitmap
import com.mhw.phone.extensions.updateUnreadCountBadge
import com.mhw.phone.helpers.*

class MissedCallReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION) return
        val extras = intent.extras ?: return
        if (extras.getInt(TelecomManager.EXTRA_NOTIFICATION_COUNT) == 0) return

        // Null/blank/"-1" means a hidden number: it is never dialed or looked up.
        val phoneNumber = extras.getString(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER)
            ?.takeIf { it.isNotBlank() && it != "-1" }

        // The lookup, the call-log count and the round contact icon need a background thread.
        val pendingResult = goAsync()
        ensureBackgroundThread {
            try {
                showNotification(context, phoneNumber)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                pendingResult.finish()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun showNotification(context: Context, phoneNumber: String?) {
        createNotificationChannel(context)

        val counts = RecentsHelper(context).getNewMissedCallCounts()
        val key = phoneNumber.orEmpty()
        // The log row may not be visible yet when telecom posts this broadcast, so never report less than this call.
        val count = (counts[key] ?: counts.entries.firstOrNull { phoneNumber != null && it.key.isNotEmpty() && PhoneNumberUtils.compare(it.key, phoneNumber) }?.value ?: 0)
            .coerceAtLeast(1)
        val total = counts.values.sum().coerceAtLeast(count)

        val contact = phoneNumber?.let {
            try {
                ContactsRepository.findByNumber(context, it)
            } catch (_: Exception) {
                null
            }
        }
        val name = contact?.getNameToDisplay() ?: phoneNumber ?: context.getString(R.string.unknown_caller)
        val company = contact?.organization?.company.orEmpty()
        val bitmap = context.getNotificationBitmap(contact?.photoUri.orEmpty()) ?: when {
            contact == null -> SimpleContactsHelper(context).getColoredContactIcon(name).convertToBitmap()
            company.isNotEmpty() && name.contains(company) -> SimpleContactsHelper(context).getColoredCompanyIcon(name).convertToBitmap()
            else -> context.contactLetterBitmap(name)
        }

        var callerName = name
        val specificPhoneNumber = phoneNumber?.let { number ->
            contact?.phoneNumbers?.firstOrNull { it.normalizedNumber == number.normalizePhoneNumber() }
        }
        if (specificPhoneNumber != null) {
            val label = context.getPhoneNumberTypeText(specificPhoneNumber.type, specificPhoneNumber.label)
            if (label.isNotEmpty()) {
                callerName += " - $label"
            }
        }

        context.updateUnreadCountBadge(total)
        context.notificationManager.notify(key.hashCode(), buildNotification(context, callerName, phoneNumber, bitmap, count))
    }

    private fun createNotificationChannel(context: Context) {
        val notificationManager = context.notificationManager
        val name = context.getString(R.string.missed_call_notifications_g)
        val importance = NotificationManager.IMPORTANCE_HIGH

        if (notificationManager.getNotificationChannel(MISSED_CALL_CHANNEL_ID) != null) return

        NotificationChannel(MISSED_CALL_CHANNEL_ID, name, importance).apply {
            setBypassDnd(false)
            setSound(null, null)
            enableVibration(false)
            notificationManager.createNotificationChannel(this)
        }
    }

    private fun notificationActivityIntent(context: Context, action: String, key: String, flags: Int): PendingIntent {
        val intent = Intent(context, NotificationActivity::class.java).apply {
            this.action = action
            putExtra(CURRENT_PHONE_NUMBER, key)
            // Opening the app must not wipe a task that is already running (e.g. a live call screen)
            this.flags = if (action == MISSED_CALL_OPEN) Intent.FLAG_ACTIVITY_NEW_TASK
            else Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(context, key.hashCode(), intent, flags)
    }

    /** [phoneNumber] is null for a hidden number: then there is nobody to call back or message. */
    private fun buildNotification(context: Context, name: String, phoneNumber: String?, bitmap: Bitmap?, count: Int): Notification {
        val key = phoneNumber.orEmpty()
        val cancel = Intent(context, MissedCallCancelReceiver::class.java).putExtra(MissedCallCancelReceiver.EXTRA_NUMBER_KEY, key)
        val cancelIntent = PendingIntent.getBroadcast(context, key.hashCode(), cancel, PendingIntent.FLAG_IMMUTABLE)

        val text = if (count == 1) context.getString(R.string.missed_call_g)
        else context.resources.getQuantityString(R.plurals.missed_calls, count, count)

        return NotificationCompat.Builder(context, MISSED_CALL_CHANNEL_ID).apply {
            if (count == 1) {
                setContentTitle(text)
                setContentText(name)
            } else {
                setContentTitle(name)
                setContentText(text)
            }
            setLargeIcon(bitmap)
            setStyle(getCallStyle(name, key, text))
            color = context.getProperPrimaryColor()
            setSmallIcon(R.drawable.ic_call_missed_vector)
            setAutoCancel(true)
            setGroup(MISSED_CALLS)
            // Routed through NotificationActivity so tapping the body marks only this number read
            setContentIntent(notificationActivityIntent(context, MISSED_CALL_OPEN, key, PendingIntent.FLAG_IMMUTABLE))
            setDeleteIntent(cancelIntent)
            setCategory(Notification.CATEGORY_CALL)
            setSound(null)

            if (phoneNumber != null) {
                addAction(
                    R.drawable.ic_phone_vector,
                    context.getString(R.string.call_back_g),
                    notificationActivityIntent(context, MISSED_CALL_BACK, key, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
                )
                addAction(
                    R.drawable.ic_messages,
                    context.getString(R.string.message),
                    notificationActivityIntent(context, MISSED_CALL_MESSAGE, key, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
                )
            }
        }.build()
    }

    private fun getCallStyle(
        name: String,
        address: String,
        message: String,
    ): NotificationCompat.MessagingStyle {
        val caller = Person.Builder()
            .setName(name)
            .setKey(address)
            .build()

        return NotificationCompat.MessagingStyle(caller).also { style ->
            val callMessage = NotificationCompat.MessagingStyle.Message(
                message,
                System.currentTimeMillis(),
                caller
            )
            style.addMessage(callMessage)
        }
    }
}
