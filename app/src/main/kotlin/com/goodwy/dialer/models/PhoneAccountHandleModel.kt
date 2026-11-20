package com.goodwy.dialer.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

import android.content.ComponentName
import android.telecom.PhoneAccountHandle

// Stored as JSON in preferences. The JSON keys are pinned to the names release builds have been writing (R8-obfuscated
// letters), so existing data keeps loading and a future build can never rename them; readable names are accepted too.
@Keep
data class PhoneAccountHandleModel(
    @SerializedName(value = "a", alternate = ["packageName"]) val packageName: String,
    @SerializedName(value = "b", alternate = ["className"]) val className: String,
    @SerializedName(value = "c", alternate = ["id"]) val id: String
) {
    fun toPhoneAccountHandle(): PhoneAccountHandle {
        return PhoneAccountHandle(
            ComponentName(packageName, className), id
        )
    }
}

