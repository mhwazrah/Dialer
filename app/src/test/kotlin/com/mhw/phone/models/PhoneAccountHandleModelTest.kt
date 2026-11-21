package com.mhw.phone.models

import android.content.ComponentName
import android.telecom.PhoneAccountHandle
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PhoneAccountHandleModelTest {
    @Test
    fun convertsToHandle() {
        val handle = PhoneAccountHandleModel("com.x", "com.x.Svc", "7").toPhoneAccountHandle()
        assertEquals(PhoneAccountHandle(ComponentName("com.x", "com.x.Svc"), "7"), handle)
    }

    @Test
    fun jsonKeysPinnedAndLegacyNamesAccepted() {
        val gson = Gson()
        val model = PhoneAccountHandleModel("p", "c", "i")
        val pinned = "{\"a\":\"p\",\"b\":\"c\",\"c\":\"i\"}"
        assertEquals(pinned, gson.toJson(model))
        assertEquals(model, gson.fromJson(pinned, PhoneAccountHandleModel::class.java))
        val readable = "{\"packageName\":\"p\",\"className\":\"c\",\"id\":\"i\"}"
        assertEquals(model, gson.fromJson(readable, PhoneAccountHandleModel::class.java))
    }
}
