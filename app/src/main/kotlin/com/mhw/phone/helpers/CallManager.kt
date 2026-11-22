package com.mhw.phone.helpers

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.mhw.phone.extensions.getStateCompat
import com.mhw.phone.extensions.hasCapability
import com.mhw.phone.extensions.isConference
import com.mhw.phone.models.AudioRoute
import java.util.concurrent.CopyOnWriteArraySet

// inspired by https://github.com/Chooloo/call_manage
class CallManager {
    companion object {
        @SuppressLint("StaticFieldLeak")
        var inCallService: InCallService? = null
        private var call: Call? = null
        private val calls = mutableListOf<Call>()
        private val listeners = CopyOnWriteArraySet<CallManagerListener>()
        private var previousAudioRoute: Int = CallAudioState.ROUTE_WIRED_OR_EARPIECE
        private val mainHandler = Handler(Looper.getMainLooper())
        private val stopDtmfTone = Runnable { call?.stopDtmfTone() }

        // One shared callback for every call; unregistered in onCallRemoved/clear so no call keeps it alive.
        private val callCallback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                updateState()
            }

            override fun onDetailsChanged(call: Call, details: Call.Details) {
                updateState()
            }

            override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) {
                updateState()
            }
        }

        // Speaker and mute are read from the system's CallAudioState, never cached, so a new call can't inherit the last one's.
        val isSpeakerOn: Boolean
            get() = getCallAudioState()?.route == CallAudioState.ROUTE_SPEAKER

        val isMicrophoneMuted: Boolean
            get() = getCallAudioState()?.isMuted == true

        fun onCallAdded(call: Call) {
            this.call = call
            calls.add(call)
            for (listener in listeners) {
                listener.onPrimaryCallChanged(call)
            }
            call.registerCallback(callCallback)
        }

        fun onCallRemoved(call: Call) {
            call.unregisterCallback(callCallback)
            clearCallContact(call)
            calls.remove(call)
            updateState()
        }

        // Called when the service is gone. Listeners stay: they belong to the activities that added them.
        fun clear(service: InCallService) {
            if (inCallService !== service) return
            inCallService = null
            calls.forEach { it.unregisterCallback(callCallback) }
            calls.clear()
            clearCallContacts()
            call = null
            previousAudioRoute = CallAudioState.ROUTE_WIRED_OR_EARPIECE
            mainHandler.removeCallbacks(stopDtmfTone)
        }

        fun setMuted(muted: Boolean) {
            inCallService?.setMuted(muted)
        }

        fun onAudioStateChanged(audioState: CallAudioState) {
            for (listener in listeners) {
                listener.onMuteChanged(audioState.isMuted)
            }
            val route = AudioRoute.fromRoute(audioState.route) ?: return
            for (listener in listeners) {
                listener.onAudioStateChanged(route)
            }
        }

        fun getPhoneState(): PhoneState {
            return when (calls.size) {
                0 -> NoCall
                1 -> SingleCall(calls.first())
                2 -> {
                    val ringing = calls.find { it.getStateCompat() == Call.STATE_RINGING }
                    val active = calls.find { it.getStateCompat() == Call.STATE_ACTIVE }
                    val newCall = calls.find { it.getStateCompat() == Call.STATE_CONNECTING || it.getStateCompat() == Call.STATE_DIALING }
                    val onHold = calls.find { it.getStateCompat() == Call.STATE_HOLDING }
                    if (ringing != null) {
                        // Call waiting: a 2nd call is ringing while another call is in progress.
                        // Surface the ringing call as the primary/active one so the incoming UI
                        // shows the NEW caller; the other call becomes the secondary shown in the
                        // on-hold banner.
                        TwoCalls(ringing, calls.first { it != ringing })
                    } else if (active != null && newCall != null) {
                        TwoCalls(newCall, active)
                    } else if (newCall != null && onHold != null) {
                        TwoCalls(newCall, onHold)
                    } else if (active != null && onHold != null) {
                        TwoCalls(active, onHold)
                    } else {
                        TwoCalls(calls[0], calls[1])
                    }
                }

                else -> {
                    val conference = calls.find { it.isConference() } ?: return getTwoCallsWithoutConference()
                    val secondCall = if (conference.children.size + 1 != calls.size) {
                        calls.filter { !it.isConference() }
                            .subtract(conference.children.toSet())
                            .firstOrNull()
                    } else {
                        null
                    }
                    if (secondCall == null) {
                        SingleCall(conference)
                    } else {
                        val newCallState = secondCall.getStateCompat()
                        if (newCallState == Call.STATE_ACTIVE || newCallState == Call.STATE_CONNECTING || newCallState == Call.STATE_DIALING) {
                            TwoCalls(secondCall, conference)
                        } else {
                            TwoCalls(conference, secondCall)
                        }
                    }
                }
            }
        }

        // 3+ calls and none is a conference: show the most relevant call plus one other (usually the held one).
        private fun getTwoCallsWithoutConference(): PhoneState {
            val primary = calls.find { it.getStateCompat() == Call.STATE_RINGING }
                ?: calls.find {
                    val state = it.getStateCompat()
                    state == Call.STATE_ACTIVE || state == Call.STATE_CONNECTING || state == Call.STATE_DIALING
                }
                ?: calls.first()
            val secondary = calls.find { it != primary && it.getStateCompat() == Call.STATE_HOLDING }
                ?: calls.first { it != primary }
            return TwoCalls(primary, secondary)
        }

        fun getPhoneSize(): Int {
            return calls.size
        }

        fun getCallAudioState() = inCallService?.callAudioState

        fun getSupportedAudioRoutes(): Array<AudioRoute> {
            return AudioRoute.entries.filter {
                val supportedRouteMask = getCallAudioState()?.supportedRouteMask
                if (supportedRouteMask != null) {
                    supportedRouteMask and it.route == it.route
                } else {
                    false
                }
            }.toTypedArray()
        }

        fun getCallAudioRoute() = AudioRoute.fromRoute(getCallAudioState()?.route)

        // Per-Bluetooth-device selection. Available since Android 9 (API 28); on older
        // releases we fall back to the generic Bluetooth route handled by the system.
        fun getSupportedBluetoothDevices(): List<BluetoothDevice> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                getCallAudioState()?.supportedBluetoothDevices?.toList().orEmpty()
            } else {
                emptyList()
            }
        }

        fun getActiveBluetoothDevice(): BluetoothDevice? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                getCallAudioState()?.activeBluetoothDevice
            } else {
                null
            }
        }

        fun setBluetoothDevice(device: BluetoothDevice) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                inCallService?.requestBluetoothAudio(device)
                previousAudioRoute = CallAudioState.ROUTE_BLUETOOTH
                notifyAudioStateChanged()
            } else {
                setAudioRoute(CallAudioState.ROUTE_BLUETOOTH)
            }
        }

        fun toggleSpeakerRoute(keepCalls: Boolean = false) {
            val currentAudioState = getCallAudioState() ?: return

            if (keepCalls) {
                if (currentAudioState.route == CallAudioState.ROUTE_EARPIECE) {
                    setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                }
            } else {
                if (currentAudioState.route != CallAudioState.ROUTE_SPEAKER) {
                    // Turn on the speaker - save the current route
                    previousAudioRoute = currentAudioState.route
                    setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                } else {
                    // Turn off the speaker - return to the saved route
                    val targetRoute = getOptimalAudioRoute(previousAudioRoute, currentAudioState)
                    setAudioRoute(targetRoute)
                }
            }

            // We hereby notify listeners of a change in status.
            notifyAudioStateChanged()
        }

        /** Where [toggleSpeakerRoute] would switch to now: the speaker, or back from it. Null without a call. */
        fun speakerToggleTarget(): AudioRoute? {
            val state = getCallAudioState() ?: return null
            return speakerToggleTarget(state.route, state.supportedRouteMask, previousAudioRoute)
        }

        private fun getOptimalAudioRoute(preferredRoute: Int, audioState: CallAudioState): Int =
            optimalAudioRoute(preferredRoute, audioState.supportedRouteMask)

        fun setAudioRoute(newRoute: Int) {
            // Remember where to return to when the user leaves the speaker for another route
            if (newRoute != CallAudioState.ROUTE_SPEAKER && isSpeakerOn) {
                previousAudioRoute = newRoute
            }
            inCallService?.setAudioRoute(newRoute)
            notifyAudioStateChanged()
        }

        private fun notifyAudioStateChanged() {
            val route = getCallAudioRoute() ?: return
            for (listener in listeners) {
                listener.onAudioStateChanged(route)
            }
        }

        private fun updateState() {
            // With other live calls, drop disconnected ones first so the state reflects the remaining calls.
            // A lone disconnected call stays until listeners have seen it: CallActivity ends the call UI on it.
            if (calls.any { it.getStateCompat() != Call.STATE_DISCONNECTED }) {
                calls.removeAll { it.getStateCompat() == Call.STATE_DISCONNECTED }
            }
            val primaryCall = when (val phoneState = getPhoneState()) {
                is NoCall -> null
                is SingleCall -> phoneState.call
                is TwoCalls -> phoneState.active
            }
            var notify = true
            if (primaryCall == null) {
                call = null
            } else if (primaryCall != call) {
                call = primaryCall
                for (listener in listeners) {
                    listener.onPrimaryCallChanged(primaryCall)
                }
                notify = false
            }
            if (notify) {
                for (listener in listeners) {
                    listener.onStateChanged()
                }
            }

            // remove all disconnected calls manually in case they are still here
            calls.removeAll { it.getStateCompat() == Call.STATE_DISCONNECTED }
        }

        fun getPrimaryCall(): Call? {
            return call
        }

        fun getConferenceCalls(): List<Call> {
            return calls.find { it.isConference() }?.children ?: emptyList()
        }

        fun accept() {
            call?.answer(VideoProfile.STATE_AUDIO_ONLY)
        }

        fun reject(rejectWithMessage: Boolean = false, textMessage: String? = null) {
            if (call != null) {
                val state = getState()
                if (state == Call.STATE_RINGING) {
                    call!!.reject(rejectWithMessage, textMessage)
                } else if (state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING) {
                    call!!.disconnect()
                }
            }
        }

        fun toggleHold(): Boolean {
            val isOnHold = getState() == Call.STATE_HOLDING
            if (isOnHold) {
                call?.unhold()
            } else {
                call?.hold()
            }
            return !isOnHold
        }

        fun swap() {
            if (calls.size > 1) {
                calls.find { it.getStateCompat() == Call.STATE_HOLDING }?.unhold()
            }
        }

        fun merge() {
            if (call != null) {
                val conferenceableCalls = call!!.conferenceableCalls
                if (conferenceableCalls.isNotEmpty()) {
                    call!!.conference(conferenceableCalls.first())
                } else {
                    if (call!!.hasCapability(Call.Details.CAPABILITY_MERGE_CONFERENCE)) {
                        call!!.mergeConference()
                    }
                }
            }
        }

        fun addListener(listener: CallManagerListener) {
            listeners.add(listener)
        }

        fun removeListener(listener: CallManagerListener) {
            listeners.remove(listener)
        }

        fun getState() = getPrimaryCall()?.getStateCompat()

        fun keypad(char: Char) {
            mainHandler.removeCallbacks(stopDtmfTone)
            call?.playDtmfTone(char)
            mainHandler.postDelayed(stopDtmfTone, DIALPAD_TONE_LENGTH_MS)
        }

        fun getCallConnectTime(): Long {
            val primaryCall = getPrimaryCall()
            return if (primaryCall != null && primaryCall.getStateCompat() == Call.STATE_ACTIVE) {
                primaryCall.details.connectTimeMillis
            } else {
                0
            }
        }
    }
}

/** The route to leave the speaker for: [preferredRoute] if available, else Bluetooth, then wired/earpiece, then speaker. */
internal fun optimalAudioRoute(preferredRoute: Int, supportedRoutes: Int): Int = when {
    supportedRoutes and preferredRoute != 0 -> preferredRoute
    supportedRoutes and CallAudioState.ROUTE_BLUETOOTH != 0 -> CallAudioState.ROUTE_BLUETOOTH
    supportedRoutes and CallAudioState.ROUTE_WIRED_OR_EARPIECE != 0 -> CallAudioState.ROUTE_WIRED_OR_EARPIECE
    else -> CallAudioState.ROUTE_SPEAKER
}

/**
 * Where a speaker toggle goes from [route]: the speaker, or back from it to the [optimalAudioRoute]. The combined
 * "wired or earpiece" route is named by what is actually there: a plugged-in wired headset, else the earpiece.
 */
internal fun speakerToggleTarget(route: Int, supportedRoutes: Int, previousRoute: Int): AudioRoute? {
    if (route != CallAudioState.ROUTE_SPEAKER) return AudioRoute.SPEAKER
    return when (val target = optimalAudioRoute(previousRoute, supportedRoutes)) {
        CallAudioState.ROUTE_WIRED_OR_EARPIECE ->
            if (supportedRoutes and CallAudioState.ROUTE_WIRED_HEADSET != 0) AudioRoute.WIRED_HEADSET else AudioRoute.EARPIECE
        else -> AudioRoute.fromRoute(target)
    }
}

/** Caller photo size on the call screen: twice [baseSize], at most a quarter of [screenHeight] (never below [baseSize]). */
internal fun ringingAvatarSize(baseSize: Int, screenHeight: Int): Int = minOf(baseSize * 2, maxOf(baseSize, screenHeight / 4))

/** The equal gap that spaces [rowHeights] between [top] and [bottom]: one gap above, between and below the rows. */
internal fun evenRowGap(top: Int, bottom: Int, rowHeights: List<Int>): Int =
    (bottom - top - rowHeights.sum()) / (rowHeights.size + 1)

interface CallManagerListener {
    fun onStateChanged()
    fun onAudioStateChanged(audioState: AudioRoute)
    fun onPrimaryCallChanged(call: Call)
    fun onMuteChanged(isMuted: Boolean)
}

sealed class PhoneState
data object NoCall : PhoneState()
class SingleCall(val call: Call) : PhoneState()
class TwoCalls(val active: Call, val onHold: Call) : PhoneState()

