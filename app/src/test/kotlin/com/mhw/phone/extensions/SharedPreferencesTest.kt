package com.mhw.phone.extensions

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.telecom.PhoneAccountHandle
import androidx.test.core.app.ApplicationProvider
import com.mhw.phone.models.PhoneAccountHandleModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedPreferencesTest {
    private lateinit var prefs: SharedPreferences
    private val fallback = PhoneAccountHandleModel("fb.pkg", "fb.Cls", "fb")

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("sp-ext-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun roundTripsPhoneAccountHandle() {
        val handle = PhoneAccountHandle(ComponentName("com.x", "com.x.Svc"), "sim-1")
        prefs.edit().putPhoneAccountHandle("k", handle).commit()

        val model = prefs.getPhoneAccountHandleModel("k", fallback)

        assertEquals(PhoneAccountHandleModel("com.x", "com.x.Svc", "sim-1"), model)
        assertEquals(handle, model.toPhoneAccountHandle())
    }

    @Test
    fun storesPinnedShortKeys() {
        prefs.edit().putPhoneAccountHandle("k", PhoneAccountHandle(ComponentName("p", "c"), "i")).commit()
        assertEquals("{\"a\":\"p\",\"b\":\"c\",\"c\":\"i\"}", prefs.getString("k", null))
    }

    @Test
    fun missingKeyReturnsDefault() {
        assertEquals(fallback, prefs.getPhoneAccountHandleModel("none", fallback))
        assertNull(prefs.getPhoneAccountHandleModel<PhoneAccountHandleModel?>("none", null))
    }

    @Test
    fun malformedJsonReturnsDefault() {
        prefs.edit().putString("k", "{not json").commit()
        assertEquals(fallback, prefs.getPhoneAccountHandleModel("k", fallback))
    }
}
