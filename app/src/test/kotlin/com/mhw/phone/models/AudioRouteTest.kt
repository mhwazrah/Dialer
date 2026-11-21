package com.mhw.phone.models

import android.telecom.CallAudioState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioRouteTest {
    @Test
    fun fromRouteFindsEachEntry() {
        AudioRoute.entries.forEach { assertEquals(it, AudioRoute.fromRoute(it.route)) }
        assertEquals(AudioRoute.SPEAKER, AudioRoute.fromRoute(CallAudioState.ROUTE_SPEAKER))
    }

    @Test
    fun unknownOrNullRouteIsNull() {
        assertNull(AudioRoute.fromRoute(null))
        assertNull(AudioRoute.fromRoute(-1))
    }
}
