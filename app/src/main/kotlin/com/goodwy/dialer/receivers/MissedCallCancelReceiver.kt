package com.goodwy.dialer.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.goodwy.dialer.extensions.clearMissedCallsOf

/** Swipe-dismiss of a missed-call notification. Not exported: only our own PendingIntent can reach it. */
class MissedCallCancelReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_NUMBER_KEY = "phoneNumber"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_NUMBER_KEY) ?: return
        val pendingResult = goAsync()
        context.applicationContext.clearMissedCallsOf(key) { pendingResult.finish() }
    }
}
