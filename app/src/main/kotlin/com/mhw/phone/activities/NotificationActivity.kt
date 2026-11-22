package com.mhw.phone.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.goodwy.commons.extensions.launchActivityIntent
import com.goodwy.commons.extensions.getLaunchIntent
import com.goodwy.commons.helpers.CURRENT_PHONE_NUMBER
import com.goodwy.commons.helpers.IS_RIGHT_APP
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.mhw.phone.BuildConfig
import com.mhw.phone.extensions.clearMissedCallsOf
import com.mhw.phone.helpers.MISSED_CALL_BACK
import com.mhw.phone.helpers.MISSED_CALL_OPEN
import com.mhw.phone.helpers.MISSED_CALL_MESSAGE
import com.mhw.phone.helpers.MISSED_CALL_REMIND
import com.mhw.phone.helpers.ContactsRepository
import com.mhw.phone.helpers.CALL_BACK_REMINDER_MINUTES
import com.mhw.phone.helpers.startCallBackReminder

//Empty activation to remove missed call notifications when you press to call or send a message
//https://stackoverflow.com/questions/18261969/clicking-android-notification-actions-does-not-close-notification-drawer?noredirect=1&lq=1
class NotificationActivity : SimpleActivity() {
    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val phoneNumber = intent.extras?.getString(CURRENT_PHONE_NUMBER) ?: run {
            finish()
            return
        }

        // Only this number's calls are marked read; the rest keep their notifications and the log flag.
        applicationContext.clearMissedCallsOf(phoneNumber)

        // A hidden number (empty key) has nobody to call or message
        when {
            intent.action == MISSED_CALL_OPEN -> launchActivityIntent(getLaunchIntent() ?: Intent(this, SplashActivity::class.java))

            intent.action == MISSED_CALL_BACK && phoneNumber.isNotEmpty() -> Intent(Intent.ACTION_CALL).apply {
                data = Uri.fromParts("tel", phoneNumber, null)
                putExtra(IS_RIGHT_APP, BuildConfig.RIGHT_APP_KEY)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                launchActivityIntent(this)
            }

            // No screen of ours stays open, so the countdown service is started right away
            intent.action == MISSED_CALL_REMIND && phoneNumber.isNotEmpty() -> {
                val app = applicationContext
                ensureBackgroundThread {
                    val name = ContactsRepository.findByNumber(app, phoneNumber)?.getNameToDisplay().orEmpty()
                    app.startCallBackReminder(name, phoneNumber, CALL_BACK_REMINDER_MINUTES * 60, startServiceNow = true)
                }
            }

            intent.action == MISSED_CALL_MESSAGE && phoneNumber.isNotEmpty() -> Intent(Intent.ACTION_VIEW).apply {
                data = Uri.fromParts("smsto", phoneNumber, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                launchActivityIntent(this)
            }
        }

        finish()
    }
}
