package com.mhw.phone.helpers

import android.telecom.CallAudioState.ROUTE_BLUETOOTH
import android.telecom.CallAudioState.ROUTE_EARPIECE
import android.telecom.CallAudioState.ROUTE_SPEAKER
import android.telecom.CallAudioState.ROUTE_WIRED_HEADSET
import android.telecom.CallAudioState.ROUTE_WIRED_OR_EARPIECE
import com.mhw.phone.models.AudioRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class CallAudioRoutingTest {
    private val phone = ROUTE_EARPIECE or ROUTE_SPEAKER

    @Test
    fun offTheSpeakerTheToggleOffersTheSpeaker() {
        assertEquals(AudioRoute.SPEAKER, speakerToggleTarget(ROUTE_EARPIECE, phone, ROUTE_WIRED_OR_EARPIECE))
        assertEquals(AudioRoute.SPEAKER, speakerToggleTarget(ROUTE_BLUETOOTH, phone or ROUTE_BLUETOOTH, ROUTE_EARPIECE))
    }

    @Test
    fun onTheSpeakerTheDefaultPreviousRouteIsNamedByWhatIsPresent() {
        assertEquals(AudioRoute.EARPIECE, speakerToggleTarget(ROUTE_SPEAKER, phone, ROUTE_WIRED_OR_EARPIECE))
        assertEquals(
            AudioRoute.WIRED_HEADSET,
            speakerToggleTarget(ROUTE_SPEAKER, ROUTE_WIRED_HEADSET or ROUTE_SPEAKER, ROUTE_WIRED_OR_EARPIECE)
        )
    }

    @Test
    fun onTheSpeakerItGoesBackToThePreviousRouteOrTheBestOneLeft() {
        assertEquals(AudioRoute.BLUETOOTH, speakerToggleTarget(ROUTE_SPEAKER, phone or ROUTE_BLUETOOTH, ROUTE_BLUETOOTH))
        // Bluetooth was the previous route but has disconnected: back to the earpiece
        assertEquals(AudioRoute.EARPIECE, speakerToggleTarget(ROUTE_SPEAKER, phone, ROUTE_BLUETOOTH))
        // Only a speaker (e.g. a tablet or the emulator): nothing else to go to
        assertEquals(AudioRoute.SPEAKER, speakerToggleTarget(ROUTE_SPEAKER, ROUTE_SPEAKER, ROUTE_WIRED_OR_EARPIECE))
    }

    @Test
    fun ringingAvatarDoublesButStaysWithinAQuarterOfTheScreen() {
        assertEquals(200, ringingAvatarSize(baseSize = 100, screenHeight = 1000)) // tall: full x2
        assertEquals(160, ringingAvatarSize(baseSize = 100, screenHeight = 640)) // short: capped
        assertEquals(100, ringingAvatarSize(baseSize = 100, screenHeight = 300)) // never smaller than normal
    }

    @Test
    fun rowGapIsEqualAboveBetweenAndBelowTheRows() {
        // 1000 px, two rows of 250 and 200 px: 550 px left over, three gaps of 183
        assertEquals(183, evenRowGap(top = 0, bottom = 1000, rowHeights = listOf(250, 200)))
        assertEquals(50, evenRowGap(top = 100, bottom = 300, rowHeights = listOf(100))) // one row: centred
        assertEquals(-33, evenRowGap(top = 0, bottom = 300, rowHeights = listOf(200, 200))) // does not fit: negative
    }
}
