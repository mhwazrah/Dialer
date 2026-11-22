package com.mhw.phone.helpers

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.telecom.Call
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.isNumberBlocked
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.mhw.phone.extensions.config
import java.util.Locale

/**
 * Speaks a ringing caller's contact name once, with the phone's text-to-speech at ring volume.
 * Stays quiet when the call isn't audibly ringing (silent or vibrate mode, Do Not Disturb, a number the
 * blocklist silences) and for callers without a saved name.
 */
class CallerNameAnnouncer(private val context: Context) {
    private companion object {
        val HEADSET_TYPES = buildSet {
            addAll(listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET))
            addAll(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var announcedCall: Call? = null
    private var tts: TextToSpeech? = null

    fun announce(call: Call) {
        if (announcedCall == call) return
        stop()
        announcedCall = call
        val headsetMissing = context.config.announceOnlyWithHeadset && !isHeadsetConnected()
        if (!isRingingAudibly() || headsetMissing) return
        getCallContact(context, call) { contact ->
            val name = contact.name
            if (name.isBlank() || name == contact.number) return@getCallContact
            ensureBackgroundThread {
                val blocked = try {
                    context.baseConfig.blockingEnabled && context.isNumberBlocked(contact.number)
                } catch (_: SecurityException) {
                    false
                }
                if (!blocked) mainHandler.post { speak(call, name) }
            }
        }
    }

    fun stop() {
        announcedCall = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun speak(call: Call, name: String) {
        if (announcedCall != call) return // the call was answered, declined or silenced meanwhile
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS || tts !== engine) return@TextToSpeech
            engine?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE) // ring volume
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            // A default (e.g. English) voice skips or garbles Arabic script: use an Arabic voice when there is one
            if (name.any(::isArabicLetter)) {
                val arabic = Locale.forLanguageTag("ar")
                if ((engine?.isLanguageAvailable(arabic) ?: TextToSpeech.LANG_NOT_SUPPORTED) >= TextToSpeech.LANG_AVAILABLE) {
                    engine?.language = arabic
                }
            }
            engine?.speak(name, TextToSpeech.QUEUE_FLUSH, null, "caller_name")
        }
        tts = engine
    }

    // Arabic, Arabic Supplement and the Arabic presentation forms
    private fun isArabicLetter(c: Char) =
        c.code in 0x0600..0x06FF || c.code in 0x0750..0x077F || c.code in 0xFB50..0xFDFF || c.code in 0xFE70..0xFEFF

    private fun isHeadsetConnected(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSET_TYPES }
    }

    private fun isRingingAudibly(): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            notificationManager.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
    }
}
