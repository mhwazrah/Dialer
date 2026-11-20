package com.goodwy.dialer.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.goodwy.dialer.activities.CallActivity
import com.goodwy.dialer.extensions.config
import com.goodwy.dialer.helpers.ACCEPT_CALL
import com.goodwy.dialer.helpers.CallManager
import com.goodwy.dialer.helpers.DECLINE_CALL
import com.goodwy.dialer.helpers.MICROPHONE_CALL
import com.goodwy.dialer.helpers.SPEAKER_CALL

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DECLINE_CALL -> CallManager.reject()
            ACCEPT_CALL -> {
                CallManager.accept()
                if (!context.config.keepCallsInPopUp) {
                    try {
                        context.startActivity(CallActivity.getStartIntent(context))
                    } catch (e: Exception) {
                        // Background-launch restrictions: the call is already accepted, the notification stays usable.
                        e.printStackTrace()
                    }
                }
                if (context.config.keepCallsInPopUp && context.config.turnOnSpeakerInPopup) {
                    CallManager.toggleSpeakerRoute(true)
                }
            }

            MICROPHONE_CALL -> {
                // The service refreshes the notification once the system reports the new mute state
                CallManager.setMuted(!CallManager.isMicrophoneMuted)
            }

            SPEAKER_CALL -> {
                // The service refreshes the notification once the system reports the new route
                CallManager.toggleSpeakerRoute()
            }
        }
    }
}
