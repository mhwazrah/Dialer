package com.mhw.phone.extensions

import android.content.SharedPreferences
import android.telecom.PhoneAccountHandle
import com.mhw.phone.helpers.gson
import com.google.gson.JsonSyntaxException
import com.mhw.phone.models.PhoneAccountHandleModel

fun SharedPreferences.Editor.putPhoneAccountHandle(
    key: String,
    parcelable: PhoneAccountHandle
): SharedPreferences.Editor {
    val componentName = parcelable.componentName
    val myPhoneAccountHandleModel = PhoneAccountHandleModel(
        componentName.packageName, componentName.className, parcelable.id
    )
    val json = gson.toJson(myPhoneAccountHandleModel)
    return putString(key, json)
}

inline fun <reified T : PhoneAccountHandleModel?> SharedPreferences.getPhoneAccountHandleModel(
    key: String,
    default: T
): T {
    val json = getString(key, null)
    return try {
        if (json != null) {
            gson.fromJson(json, T::class.java)
        } else {
            default
        }
    } catch (_: JsonSyntaxException) {
        default
    }
}
