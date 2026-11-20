package com.mhw.phone.services

import android.telecom.Call
import android.telecom.CallScreeningService
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.isNumberBlocked
import com.goodwy.commons.helpers.BLOCKING_TYPE_REJECT
import com.goodwy.commons.helpers.BLOCKING_TYPE_SILENCE
import com.goodwy.commons.helpers.ContactLookupResult

import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.helpers.isQPlus
import com.mhw.phone.extensions.config
import com.mhw.phone.extensions.lookupContactSync
import com.mhw.phone.models.Events
import org.greenrobot.eventbus.EventBus

class SimpleCallScreeningService : CallScreeningService() {

    // The blocked-number provider and contact lookup are I/O: decide on a worker thread, then respond (the system allows a few seconds).
    override fun onScreenCall(callDetails: Call.Details) {
        ensureBackgroundThread {
            val isBlocked = try {
                shouldBlock(callDetails)
            } catch (_: SecurityException) {
                false
            }
            respondToCall(callDetails, isBlocked)
        }
    }

    private fun shouldBlock(callDetails: Call.Details): Boolean {
        val number = callDetails.handle?.schemeSpecificPart
        return when {
            // Blocking only applies to incoming calls; outgoing calls (our app, another app, a car) must go through
            isQPlus() && callDetails.callDirection == Call.Details.DIRECTION_OUTGOING -> false

            //To speed things up, we will not check any conditions if blocking is disabled
            !baseConfig.blockingEnabled -> false

            number != null && isNumberBlocked(number) -> {
                if (baseConfig.doNotBlockContactsAndRecent) isUnknownCaller(number) else true
            }

            number != null && baseConfig.blockUnknownNumbers -> isUnknownCaller(number)

            number == null && baseConfig.blockHiddenNumbers -> true

            else -> false
        }
    }

    private fun isUnknownCaller(number: String): Boolean {
        if (number in config.recentOutgoingNumbers) return false
        return lookupContactSync(number) == ContactLookupResult.NotFound
    }

    private fun respondToCall(callDetails: Call.Details, isBlocked: Boolean) {
        val response = if (isBlocked) {
            if (isQPlus() && baseConfig.blockingType == BLOCKING_TYPE_SILENCE) {
                CallResponse.Builder()
                    .setSilenceCall(true)
                    .build()
            } else {
                CallResponse.Builder()
                    .setDisallowCall(true)
                    .setRejectCall(baseConfig.blockingType == BLOCKING_TYPE_REJECT)
                    .setSkipCallLog(false) // not work https://issuetracker.google.com/issues/130081372
                    .setSkipNotification(true)
                    .build()
            }
        } else {
            CallResponse.Builder()
                .build()
        }

        respondToCall(callDetails, response)

        // setSkipCallLog() does not work on many versions of Android, so let's update the list after blocking the call
        // https://issuetracker.google.com/issues/130081372
        if (isBlocked) {
            config.needUpdateRecents = true
            EventBus.getDefault().post(Events.RefreshCallLog)
        }
    }
}
