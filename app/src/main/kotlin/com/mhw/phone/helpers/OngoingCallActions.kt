package com.mhw.phone.helpers

import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.widget.RemoteViews
import com.mhw.phone.R
import com.mhw.phone.models.AudioRoute

/** What the ongoing-call notification's buttons do. */
class OngoingCallActions(val speaker: PendingIntent, val mute: PendingIntent, val hangUp: PendingIntent)

/**
 * Expanded ongoing-call notification (under the system header): caller, then Speaker / Mute / Hang up as round icon
 * buttons. Android 12+ draws standard notification actions as text only, hence the custom view. A button filled with the
 * accent is on; its description names what a tap does.
 */
fun Context.ongoingCallView(name: String, number: String, actions: OngoingCallActions) =
    RemoteViews(packageName, R.layout.notification_call_expanded).apply {
        setTextViewText(R.id.notification_caller_name, name.ifEmpty { number })
        setTextViewText(R.id.notification_caller_number, if (name.isEmpty()) "" else number)

        val isSpeakerOn = CallManager.getCallAudioRoute() == AudioRoute.SPEAKER
        val isMuted = CallManager.isMicrophoneMuted
        val speakerTarget = CallManager.speakerToggleTarget() ?: AudioRoute.SPEAKER
        setActionButton(this@ongoingCallView, R.id.notification_speaker, R.drawable.ic_volume_up_vector, isSpeakerOn,
            getString(speakerTarget.stringRes))
        setActionButton(this@ongoingCallView, R.id.notification_mute,
            if (isMuted) R.drawable.ic_microphone_off_vector else R.drawable.ic_microphone_vector, isMuted,
            getString(if (isMuted) R.string.unmute else R.string.mute))
        setInt(R.id.notification_hang_up, "setColorFilter", Color.WHITE)

        setOnClickPendingIntent(R.id.notification_speaker, actions.speaker)
        setOnClickPendingIntent(R.id.notification_mute, actions.mute)
        setOnClickPendingIntent(R.id.notification_hang_up, actions.hangUp)
    }

// Active: white icon on the accent; idle: an icon in the notification's text colour on a faint circle
private fun RemoteViews.setActionButton(context: Context, id: Int, icon: Int, active: Boolean, description: String) {
    setImageViewResource(id, icon)
    setInt(id, "setBackgroundResource", if (active) R.drawable.bg_notification_action_active else R.drawable.bg_notification_action_idle)
    setInt(id, "setColorFilter", if (active) Color.WHITE else context.getColor(R.color.notification_action_idle_icon))
    setContentDescription(id, description)
}
