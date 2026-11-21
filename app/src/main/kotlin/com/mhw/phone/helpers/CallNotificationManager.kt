package com.mhw.phone.helpers

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.telecom.Call
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.SimpleContactsHelper
import com.goodwy.commons.helpers.isSPlus
import com.mhw.phone.R
import com.mhw.phone.activities.CallActivity
import com.mhw.phone.models.AudioRoute
import com.mhw.phone.receivers.CallActionReceiver

class CallNotificationManager(private val context: Context) {
    companion object {
        private const val CALL_NOTIFICATION_ID = 42
        private const val ACCEPT_CALL_CODE = 0
        private const val DECLINE_CALL_CODE = 1
        private const val MICROPHONE_CALL_CODE = 2
        private const val SPEAKER_CALL_CODE = 3
    }

    private val notificationManager = context.notificationManager
    private val callContactAvatarHelper = CallContactAvatarHelper(context)
    private val createdChannels = HashSet<String>()
    // The avatar is decoded once per photo; every notification refresh reuses it.
    private var cachedAvatarUri: String? = null
    private var cachedAvatar: android.graphics.Bitmap? = null
    // Flag to prevent notification display after service closure
    private var isServiceActive = false

    fun setupNotification(lowPriority: Boolean = false, forceFullScreen: Boolean = false) {
        isServiceActive = true
        try {
            // Ringing: CallStyle (Android 12+) renders the avatar + badge and the BUILT-IN
            // Decline/Answer pills correctly on every skin.
            // Ongoing: a standard Material notification, NOT CallStyle. CallStyle's *extra*
            // actions (Speaker/Mute — which are not built-in call buttons) render white-on-white
            // on Samsung OneUI; a standard notification's actions are always system-themed and
            // readable.
            val isRinging = CallManager.getState() == Call.STATE_RINGING
            if (isSPlus() && isRinging) {
                setupNotificationNew(lowPriority)
            } else {
                setupNotificationOld(lowPriority, forceFullScreen)
            }
        } catch (e: Exception) {
            recordFailure("setupNotification()", e)
            postFallbackNotification(lowPriority, forceFullScreen)
        }
    }

    @SuppressLint("NewApi")
    fun setupNotificationOld(lowPriority: Boolean, forceFullScreen: Boolean = false) {
        getCallContact(context.applicationContext, CallManager.getPrimaryCall()) { callContact ->
            try {
                val callContactAvatar = getCachedAvatar(callContact.photoUri)
                val callState = CallManager.getState()
                // forceFullScreen escalates a non-ringing call (e.g. an outgoing call whose direct
                // screen launch was blocked by the OEM) to the high-priority channel + full-screen
                // intent so the system itself surfaces the call UI.
                val isHighPriority = !lowPriority && (callState == Call.STATE_RINGING || forceFullScreen)
                val channelId =
                    if (isHighPriority) "right_dialer_call_high_priority" else "right_dialer_call"
                createNotificationChannel(isHighPriority, channelId)

                val openAppIntent = CallActivity.getStartIntent(context)
                //requestCode - NON_FULL_SCREEN = 0, FULL_SCREEN = 1, BUBBLE = 2
                val openAppPendingIntent =
                    PendingIntent.getActivity(context, 1, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                val acceptCallIntent = Intent(context, CallActionReceiver::class.java)
                acceptCallIntent.action = ACCEPT_CALL
                val acceptPendingIntent =
                    PendingIntent.getBroadcast(
                        context,
                        ACCEPT_CALL_CODE,
                        acceptCallIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                val declineCallIntent = Intent(context, CallActionReceiver::class.java)
                declineCallIntent.action = DECLINE_CALL
                val declinePendingIntent =
                    PendingIntent.getBroadcast(
                        context,
                        DECLINE_CALL_CODE,
                        declineCallIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                val microphoneCallIntent = Intent(context, CallActionReceiver::class.java)
                microphoneCallIntent.action = MICROPHONE_CALL
                val microphonePendingIntent =
                    PendingIntent.getBroadcast(
                        context,
                        MICROPHONE_CALL_CODE,
                        microphoneCallIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                val speakerCallIntent = Intent(context, CallActionReceiver::class.java)
                speakerCallIntent.action = SPEAKER_CALL
                val speakerPendingIntent = PendingIntent.getBroadcast(
                    context,
                    SPEAKER_CALL_CODE,
                    speakerCallIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                // Standard Material notification anatomy (no custom view), so the system renders
                // everything and it stays readable + correctly badged on every OEM skin:
                //   small icon = app icon, app name = automatic, sub text = call type (header),
                //   content title = contact name (empty when not saved), content text = phone number,
                //   large icon = contact avatar / general icon (system badges the app icon on it),
                //   actions = Speaker / Mute / Hang up (Decline / Accept while ringing).
                val isSavedContact = callContact.name.isNotEmpty() && callContact.name != callContact.number
                val contentTitle = if (isSavedContact) callContact.name else ""
                val phoneNumber = callContact.number

                val callTypeId = when (callState) {
                    Call.STATE_RINGING -> R.string.is_calling
                    Call.STATE_DIALING -> R.string.dialing
                    Call.STATE_DISCONNECTED -> R.string.call_ended
                    Call.STATE_DISCONNECTING -> R.string.call_ending
                    else -> R.string.ongoing_call
                }
                val callType = context.getString(callTypeId)

                val isMicrophoneMute = CallManager.isMicrophoneMuted
                val connectTime = CallManager.getCallConnectTime()
                val showChronometer = callState == Call.STATE_ACTIVE && connectTime > 0

                // Large icon: the contact photo, otherwise a colored general icon. The system draws
                // the small (app) icon badged on its corner automatically, so no manual compositing.
                val largeIcon: android.graphics.Bitmap? = try {
                    if (callContactAvatar != null) {
                        callContactAvatar
                    } else {
                        val placeholderName = callContact.name.ifEmpty { context.getString(R.string.unknown_caller) }
                        when {
                            callContact.number == callContact.name -> SimpleContactsHelper(context).getColoredContactIcon(placeholderName).convertToBitmap()
                            callContact.isABusinessCall -> SimpleContactsHelper(context).getColoredCompanyIcon(placeholderName).convertToBitmap()
                            else -> SimpleContactsHelper(context).getContactLetterIcon(placeholderName)
                        }
                    }
                } catch (_: Exception) {
                    null
                }

                val builder = Notification.Builder(context, channelId)
                    .setSmallIcon(R.drawable.ic_phone_vector)
                    .setLargeIcon(largeIcon)
                    .setSubText(callType)
                    .setContentTitle(contentTitle)
                    .setContentText(phoneNumber)
                    .setContentIntent(openAppPendingIntent)
                    .setCategory(Notification.CATEGORY_CALL)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setChannelId(channelId)

                // Live call duration in the time slot for active calls.
                if (showChronometer) {
                    builder.setWhen(connectTime)
                    builder.setUsesChronometer(true)
                    builder.setShowWhen(true)
                } else {
                    builder.setUsesChronometer(false)
                    builder.setShowWhen(false)
                }

                // Behavior area: actions. While ringing show Decline / Accept; otherwise Speaker /
                // Mute / Hang up. Icons reflect the current speaker/mute state.
                if (callState == Call.STATE_RINGING) {
                    builder.addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(context, R.drawable.ic_phone_down_vector),
                            context.getString(R.string.decline), declinePendingIntent
                        ).build()
                    )
                    builder.addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(context, R.drawable.ic_phone_vector),
                            context.getString(R.string.accept), acceptPendingIntent
                        ).build()
                    )
                } else {
                    // Like Mute/Unmute, the action names what a tap does: on the speaker it names the route it goes
                    // back to. Android 12+ shows these actions as text only, so the label is the only visible state.
                    val speakerTarget = CallManager.speakerToggleTarget() ?: AudioRoute.SPEAKER
                    val speakerLabel = context.getString(speakerTarget.stringRes)
                    val muteLabel = context.getString(if (isMicrophoneMute) R.string.unmute else R.string.mute)
                    val hangUpLabel = context.getString(R.string.hang_up)

                    val speakerIcon = speakerTarget.iconRes
                    builder.addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(context, speakerIcon),
                            speakerLabel, speakerPendingIntent
                        ).build()
                    )
                    val microphoneIcon = if (isMicrophoneMute) R.drawable.ic_microphone_off_vector else R.drawable.ic_microphone_vector
                    builder.addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(context, microphoneIcon),
                            muteLabel, microphonePendingIntent
                        ).build()
                    )
                    builder.addAction(
                        Notification.Action.Builder(
                            Icon.createWithResource(context, R.drawable.ic_phone_down_vector),
                            hangUpLabel, declinePendingIntent
                        ).build()
                    )
                }

                if (isHighPriority) {
                    builder.setFullScreenIntent(openAppPendingIntent, true)
                }

                val notification = builder.build()
                // it's rare but possible for the call state to change by now
                // We verify that the call still exists in CallManager.
                if (isServiceActive && CallManager.getPrimaryCall() != null && CallManager.getState() == callState) {
                    notificationManager.notify(CALL_NOTIFICATION_ID, notification)
                } else {
                    // If the call is no longer there, we turn off the notification just in case
                    notificationManager.cancel(CALL_NOTIFICATION_ID)
                }
            } catch (e: Exception) {
                recordFailure("setupNotificationOld()", e)
                postFallbackNotification(lowPriority, forceFullScreen)
            }
        }
    }

    @Synchronized
    private fun getCachedAvatar(photoUri: String?): android.graphics.Bitmap? {
        if (photoUri.isNullOrEmpty()) return null
        if (photoUri != cachedAvatarUri) {
            cachedAvatar = callContactAvatarHelper.getCallContactAvatar(photoUri)
            cachedAvatarUri = photoUri
        }
        return cachedAvatar
    }

    fun createNotificationChannel(isHighPriority: Boolean, channelId: String) {
        if (!createdChannels.add(channelId)) return
        val name = if (isHighPriority) {
            context.getString(R.string.call_notification_channel_high_priority)
        } else {
            context.getString(R.string.call_notification_channel)
        }

        val importance = if (isHighPriority) IMPORTANCE_HIGH else IMPORTANCE_DEFAULT
        NotificationChannel(channelId, name, importance).apply {
//            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            notificationManager.createNotificationChannel(this)
        }
    }

    /** CallStyle needs API 31; before that the fallback is the plain notification. */
    private fun postFallbackNotification(lowPriority: Boolean, forceFullScreen: Boolean) {
        if (isSPlus()) setupNotificationForError(lowPriority, forceFullScreen) else postBareNotification()
    }

    private fun recordFailure(where: String, e: Exception) {
        context.baseConfig.lastError = "CallNotificationManager().$where: $e"
    }

    /** Last resort: a plain ongoing notification, so a failure while styling never leaves the call without one. */
    private fun postBareNotification() {
        if (!isServiceActive || CallManager.getPrimaryCall() == null) return
        try {
            createNotificationChannel(false, "right_dialer_call")
            val openAppPendingIntent =
                PendingIntent.getActivity(context, 1, CallActivity.getStartIntent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(context, "right_dialer_call")
                .setSmallIcon(R.drawable.ic_phone_vector)
                .setContentTitle(context.getString(R.string.ongoing_call))
                .setContentIntent(openAppPendingIntent)
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .build()
            notificationManager.notify(CALL_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            recordFailure("postBareNotification()", e)
        }
    }

    fun cancelNotification() {
        isServiceActive = false
        synchronized(this) {
            cachedAvatar = null
            cachedAvatarUri = null
        }
        notificationManager.cancel(CALL_NOTIFICATION_ID)
    }

    @SuppressLint("NewApi")
    fun setupNotificationNew(lowPriority: Boolean) {
        try {
            getCallContact(context.applicationContext, CallManager.getPrimaryCall()) { callContact ->
                try {
                    val callContactAvatar = getCachedAvatar(callContact.photoUri)
                    val callState = CallManager.getState()
                    val isHighPriority = callState == Call.STATE_RINGING && !lowPriority
                    val channelId =
                        if (isHighPriority) "right_dialer_call_high_priority" else "right_dialer_call"
                    createNotificationChannel(isHighPriority, channelId)

                    val openAppIntent = CallActivity.getStartIntent(context)
                    //requestCode - NON_FULL_SCREEN = 0, FULL_SCREEN = 1, BUBBLE = 2
                    val openAppPendingIntent =
                        PendingIntent.getActivity(context, 1, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                    val acceptCallIntent = Intent(context, CallActionReceiver::class.java)
                    acceptCallIntent.action = ACCEPT_CALL
                    val acceptPendingIntent =
                        PendingIntent.getBroadcast(
                            context,
                            ACCEPT_CALL_CODE,
                            acceptCallIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )

                    val declineCallIntent = Intent(context, CallActionReceiver::class.java)
                    declineCallIntent.action = DECLINE_CALL
                    val declinePendingIntent =
                        PendingIntent.getBroadcast(
                            context,
                            DECLINE_CALL_CODE,
                            declineCallIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )

                    val name = callContact.name.ifEmpty { context.getString(R.string.unknown_caller) }
                    var callerName = name
                    if (callContact.numberLabel.isNotEmpty()) {
                        callerName += " - ${callContact.numberLabel}"
                    }

                    val icon: Icon? =
                        try {
                            if (callContactAvatar == null) {
                                val image =
                                    if (callContact.number == callContact.name) SimpleContactsHelper(context).getColoredContactIcon(name).convertToBitmap()
                                    else if (callContact.isABusinessCall) SimpleContactsHelper(context).getColoredCompanyIcon(name).convertToBitmap()
                                    else SimpleContactsHelper(context).getContactLetterIcon(name)
                                Icon.createWithBitmap(image)
                            } else {
                                Icon.createWithBitmap(callContactAvatar)
                            }
                        } catch (_: Exception) {
                            null
                        }

                    val person: Person = Person.Builder()
                        .setName(callerName)
                        .setIcon(icon)
                        .build()

                    val style = if (callState == Call.STATE_RINGING) {
                        Notification.CallStyle.forIncomingCall(person, declinePendingIntent, acceptPendingIntent)
                    } else {
                        Notification.CallStyle.forOngoingCall(person, declinePendingIntent)
                    }

                    val builder = Notification.Builder(context, channelId)
                        .setFullScreenIntent(openAppPendingIntent, isHighPriority)
                        .setSmallIcon(R.drawable.ic_phone_vector)
                        .setContentIntent(openAppPendingIntent)
                        .setCategory(Notification.CATEGORY_CALL)
                        .setOngoing(true)
    //                    .setUsesChronometer(callState == Call.STATE_ACTIVE)
                        .setChannelId(channelId)
                        .setColorized(true)
                        // Give the system (and OEM skins like Samsung OneUI) an explicit accent
                        // color so action chips don't end up rendered as white-on-white in dark mode.
                        .setColor(context.getProperPrimaryColor())
                        .setStyle(style)
                        .addPerson(person)

                    // Surface accept/decline directly to Wear OS / Galaxy Watch so the watch
                    // shows the call even when its companion app's per-app allow-list
                    // doesn't auto-enroll a non-stock dialer. The CallStyle pending intents
                    // are still used for the phone side; this extender just duplicates them
                    // explicitly for the wearable surface.
                    if (callState == Call.STATE_RINGING) {
                        val acceptAction = Notification.Action.Builder(
                            Icon.createWithResource(context, R.drawable.ic_phone_vector),
                            context.getString(R.string.accept),
                            acceptPendingIntent
                        ).build()
                        val declineAction = Notification.Action.Builder(
                            Icon.createWithResource(context, R.drawable.ic_phone_down_vector),
                            context.getString(R.string.decline),
                            declinePendingIntent
                        ).build()
                        @Suppress("DEPRECATION")
                        Notification.WearableExtender()
                            .addAction(acceptAction)
                            .addAction(declineAction)
                            .extend(builder)
                    }

                    if (callState == Call.STATE_ACTIVE) {
                        val connectTime = CallManager.getCallConnectTime()
                        if (connectTime > 0) {
                            builder.setWhen(connectTime)
                            builder.setUsesChronometer(true)
                            builder.setShowWhen(true)
                        } else {
                            builder.setUsesChronometer(false)
                            builder.setShowWhen(false)
                        }
                    } else {
                        builder.setUsesChronometer(false)
                        builder.setShowWhen(false)
                    }

                    // Dynamic microphone and speaker icon for active call
                    if (callState != Call.STATE_RINGING) {
                        // Action speaker
                        // Same as the standard notification: the action names what a tap does
                        val speakerTarget = CallManager.speakerToggleTarget() ?: AudioRoute.SPEAKER
                        val speakerIcon = speakerTarget.iconRes
                        val speakerLabel = context.getString(speakerTarget.stringRes)

                        val speakerCallIntent = Intent(context, CallActionReceiver::class.java)
                        speakerCallIntent.action = SPEAKER_CALL
                        val speakerPendingIntent = PendingIntent.getBroadcast(
                            context,
                            SPEAKER_CALL_CODE,
                            speakerCallIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )

                        val speakerCallAction = Notification.Action.Builder(
                            Icon.createWithResource(context, speakerIcon),
                            speakerLabel,
                            speakerPendingIntent
                        ).build()

                        // Action mute
                        val isMicrophoneMute = CallManager.isMicrophoneMuted
                        val microphoneMuteIcon = if (isMicrophoneMute) {
                            R.drawable.ic_microphone_off_vector
                        } else {
                            R.drawable.ic_microphone_vector
                        }

                        val microphoneLabel = if (isMicrophoneMute) {
                            context.getString(R.string.unmute)
                        } else {
                            context.getString(R.string.mute)
                        }

                        val microphoneCallIntent = Intent(context, CallActionReceiver::class.java)
                        microphoneCallIntent.action = MICROPHONE_CALL
                        val microphonePendingIntent = PendingIntent.getBroadcast(
                            context,
                            MICROPHONE_CALL_CODE,
                            microphoneCallIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )

                        val microphoneCallAction = Notification.Action.Builder(
                            Icon.createWithResource(context, microphoneMuteIcon),
                            microphoneLabel,
                            microphonePendingIntent
                        ).build()

                        // Add both buttons to the notification
                        builder.addAction(speakerCallAction)
                        builder.addAction(microphoneCallAction)
                    }

                    val notification = builder.build()
                    // it's rare but possible for the call state to change by now
                    // We verify that the call still exists in CallManager.
                    if (isServiceActive && CallManager.getPrimaryCall() != null && CallManager.getState() == callState) {
                        notificationManager.notify(CALL_NOTIFICATION_ID, notification)
                    } else {
                        // If the call is no longer there, we turn off the notification just in case
                        notificationManager.cancel(CALL_NOTIFICATION_ID)
                    }
                } catch (e: Exception) {
                    recordFailure("setupNotificationNew()", e)
                    setupNotificationOld(lowPriority)
                }
            }
        } catch (e: Exception) {
            recordFailure("setupNotificationNew()", e)
            setupNotificationOld(lowPriority)
        }
    }

    @SuppressLint("NewApi")
    fun setupNotificationForError(lowPriority: Boolean, forceFullScreen: Boolean = false) {
        getCallContact(context.applicationContext, CallManager.getPrimaryCall()) { callContact ->
            try {
                val callContactAvatar = getCachedAvatar(callContact.photoUri)
                val callState = CallManager.getState()
                val isHighPriority = !lowPriority && (callState == Call.STATE_RINGING || forceFullScreen)
                val channelId = if (isHighPriority) "right_dialer_call_high_priority" else "right_dialer_call"
                val importance = if (isHighPriority) IMPORTANCE_HIGH else IMPORTANCE_DEFAULT
                val name = if (isHighPriority) {
                    context.getString(R.string.call_notification_channel_high_priority)
                } else {
                    context.getString(R.string.call_notification_channel)
                }

                NotificationChannel(channelId, name, importance).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setSound(null, null)
                    notificationManager.createNotificationChannel(this)
                }

                val openAppIntent = CallActivity.getStartIntent(context)
                //requestCode - NON_FULL_SCREEN = 0, FULL_SCREEN = 1, BUBBLE = 2
                val openAppPendingIntent = PendingIntent.getActivity(context, 1, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                val acceptCallIntent = Intent(context, CallActionReceiver::class.java)
                acceptCallIntent.action = ACCEPT_CALL
                val acceptPendingIntent =
                    PendingIntent.getBroadcast(context, ACCEPT_CALL_CODE, acceptCallIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                val declineCallIntent = Intent(context, CallActionReceiver::class.java)
                declineCallIntent.action = DECLINE_CALL
                val declinePendingIntent =
                    PendingIntent.getBroadcast(context, DECLINE_CALL_CODE, declineCallIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

                var callerName = callContact.name.ifEmpty { context.getString(R.string.unknown_caller) }
                if (callContact.numberLabel.isNotEmpty()) {
                    callerName += " - ${callContact.numberLabel}"
                }

                val icon: Icon? = if (callContactAvatar == null) null
                else Icon.createWithAdaptiveBitmap(callContactAvatar)

                val person: Person = Person.Builder()
                    .setName(callerName)
                    .setIcon(icon)
                    .build()

                val style = if (callState == Call.STATE_RINGING) {
                    Notification.CallStyle.forIncomingCall(person, declinePendingIntent, acceptPendingIntent)
                } else {
                    Notification.CallStyle.forOngoingCall(person, declinePendingIntent)
                }

                val builder = Notification.Builder(context, channelId)
                    .setFullScreenIntent(openAppPendingIntent, isHighPriority)
                    .setSmallIcon(R.drawable.ic_phone_vector)
                    .setContentIntent(openAppPendingIntent)
                    .setCategory(Notification.CATEGORY_CALL)
                    .setOngoing(true)
                    .setUsesChronometer(callState == Call.STATE_ACTIVE)
                    .setChannelId(channelId)
                    .setStyle(style)
                    .addPerson(person)

                val notification = builder.build()
                // it's rare but possible for the call state to change by now
                // We verify that the call still exists in CallManager.
                if (isServiceActive && CallManager.getPrimaryCall() != null && CallManager.getState() == callState) {
                    notificationManager.notify(CALL_NOTIFICATION_ID, notification)
                } else {
                    // If the call is no longer there, we turn off the notification just in case
                    notificationManager.cancel(CALL_NOTIFICATION_ID)
                }
            } catch (e: Exception) {
                recordFailure("setupNotificationForError()", e)
                postBareNotification()
            }
        }
    }
}
