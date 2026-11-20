package com.mhw.phone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mhw.phone.activities.CallActivity
import com.mhw.phone.extensions.config
import com.mhw.phone.helpers.ACCEPT_CALL
import com.mhw.phone.helpers.CallManager
import com.mhw.phone.helpers.DECLINE_CALL
import com.mhw.phone.helpers.MICROPHONE_CALL
import com.mhw.phone.helpers.SPEAKER_CALL

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
