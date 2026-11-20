package com.mhw.phone.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

import android.content.Context
import com.goodwy.commons.extensions.getPhoneNumberTypeText

// Stored as JSON in preferences. The JSON keys are pinned to the names release builds have been writing (R8-obfuscated
// letters), so existing data keeps loading and a future build can never rename them; readable names are accepted too.
@Keep
data class SpeedDial(
    @SerializedName(value = "a", alternate = ["id"]) val id: Int,
    @SerializedName(value = "b", alternate = ["number"]) var number: String,
    @SerializedName(value = "c", alternate = ["displayName"]) var displayName: String,
    @SerializedName(value = "d", alternate = ["type"]) var type: Int? = null,
    @SerializedName(value = "e", alternate = ["label"]) var label: String? = null
) {
    fun isValid() = number.trim().isNotEmpty()

    fun getName(context: Context): String {
        val type = type
        val label = label
        return if (type != null && label != null) {
            "$displayName - ${context.getPhoneNumberTypeText(type, label)}"
        } else {
            displayName
        }
    }
}
