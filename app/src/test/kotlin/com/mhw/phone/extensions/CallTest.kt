package com.mhw.phone.extensions

import android.telecom.Call
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Only the null-receiver branches are reachable: [Call] has no public constructor and Robolectric has no shadow for it,
 * so getCallDuration/isOutgoing/hasCapability/isConference/isHD/isWifiCall on a real call (details, state, direction,
 * connect time) cannot be driven without changing production code.
 */
class CallTest {
    private val none: Call? = null

    @Test
    fun nullCallIsDisconnected() {
        assertEquals(Call.STATE_DISCONNECTED, none.getStateCompat())
    }

    @Test
    fun nullCallHasNoDuration() {
        assertEquals(0, none.getCallDuration())
    }

    @Test
    fun nullCallHasNoProperties() {
        assertFalse(none.isConference())
        assertFalse(none.isHD())
        assertFalse(none.isWifiCall())
    }
}
