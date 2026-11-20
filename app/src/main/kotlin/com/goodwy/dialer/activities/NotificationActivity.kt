package com.goodwy.dialer.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.goodwy.commons.extensions.launchActivityIntent
import com.goodwy.commons.extensions.getLaunchIntent
import com.goodwy.commons.helpers.CURRENT_PHONE_NUMBER
import com.goodwy.commons.helpers.IS_RIGHT_APP
import com.goodwy.dialer.BuildConfig
import com.goodwy.dialer.extensions.clearMissedCallsOf
import com.goodwy.dialer.helpers.MISSED_CALL_BACK
import com.goodwy.dialer.helpers.MISSED_CALL_OPEN
import com.goodwy.dialer.helpers.MISSED_CALL_MESSAGE

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

            intent.action == MISSED_CALL_MESSAGE && phoneNumber.isNotEmpty() -> Intent(Intent.ACTION_VIEW).apply {
                data = Uri.fromParts("smsto", phoneNumber, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                launchActivityIntent(this)
            }
        }

        finish()
    }
}
