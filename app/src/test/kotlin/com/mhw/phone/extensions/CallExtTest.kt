package com.mhw.phone.extensions

import android.content.ComponentName
import android.content.Context
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.test.core.app.ApplicationProvider
import com.mhw.phone.models.RecentCall
import com.mhw.phone.models.SIMAccount
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * recentCallSim is the only pure logic in CallExt.kt. The rest (startCallIntent, callContactWithSim, getHandleToUse,
 * showSelectSimDialog, handleFullScreenNotificationsPermission, ...) are SimpleActivity/BaseSimpleActivity
 * extensions that launch intents, request permissions or show dialogs; they need a real activity and are not covered.
 */
@RunWith(RobolectricTestRunner::class)
class CallExtTest {
    private lateinit var context: Context
    private lateinit var telecom: TelecomManager

    private fun handle(id: String) = PhoneAccountHandle(ComponentName("com.example", "Svc"), id)
    private val sim1 = SIMAccount(1, handle("a"), "SIM 1", "", 0)
    private val sim2 = SIMAccount(2, handle("b"), "SIM 2", "", 0)
    private val sims = listOf(sim1, sim2)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        telecom = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        context.config.callUsingSameSim = false
        context.config.removeCustomSIM(NUMBER)
        shadowOf(telecom).setDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL, null)
    }

    private companion object {
        const val NUMBER = "0501234567"
    }

    private fun call(simID: Int, number: String = NUMBER) = RecentCall(
        id = 1, phoneNumber = number, name = number, photoUri = "", startTS = 0L, duration = 0, type = 1,
        simID = simID, simColor = 0, specificNumber = "", specificType = "", isUnknownNumber = false, isVoiceMail = false
    )

    @Test
    fun nothingAppliesMeansAskTheUser() {
        assertNull(context.recentCallSim(call(simID = 1), sims))
    }

    @Test
    fun savedSimWinsOverEverything() {
        context.config.saveCustomSIM(NUMBER, sim2.handle)
        context.config.callUsingSameSim = true
        shadowOf(telecom).setDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL, sim1.handle)
        assertSame(sim2, context.recentCallSim(call(simID = 1), sims))
    }

    @Test
    fun savedSimNoLongerPresentFallsThrough() {
        context.config.saveCustomSIM(NUMBER, handle("gone"))
        context.config.callUsingSameSim = true
        assertSame(sim2, context.recentCallSim(call(simID = 2), sims))
    }

    @Test
    fun sameSimIsUsedOnlyWhenTheSettingIsOn() {
        assertNull(context.recentCallSim(call(simID = 2), sims))
        context.config.callUsingSameSim = true
        assertSame(sim2, context.recentCallSim(call(simID = 2), sims))
    }

    @Test
    fun sameSimIgnoresUnknownSimIds() {
        context.config.callUsingSameSim = true
        assertNull(context.recentCallSim(call(simID = -1), sims))
        assertNull(context.recentCallSim(call(simID = 0), sims))
        assertNull(context.recentCallSim(call(simID = 7), sims))
    }

    @Test
    fun defaultOutgoingSimIsTheLastResort() {
        shadowOf(telecom).setDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL, sim1.handle)
        assertSame(sim1, context.recentCallSim(call(simID = -1), sims))
    }

    @Test
    fun sameSimBeatsTheDefault() {
        context.config.callUsingSameSim = true
        shadowOf(telecom).setDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL, sim1.handle)
        assertSame(sim2, context.recentCallSim(call(simID = 2), sims))
    }

    @Test
    fun defaultNotAmongTheSimsMeansAsk() {
        shadowOf(telecom).setDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL, handle("other"))
        assertNull(context.recentCallSim(call(simID = -1), sims))
    }

    @Test
    fun noSimsMeansAsk() {
        context.config.saveCustomSIM(NUMBER, sim1.handle)
        assertNull(context.recentCallSim(call(simID = 1), emptyList()))
    }

    @Test
    fun savedSimIsPerNumber() {
        context.config.saveCustomSIM("0509999999", sim2.handle)
        assertNull(context.recentCallSim(call(simID = 1), sims))
    }
}
