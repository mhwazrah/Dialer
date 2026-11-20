package com.mhw.phone.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

// Stored as JSON in preferences. The JSON keys are pinned to the names release builds have been writing (R8-obfuscated
// letters), so existing data keeps loading and a future build can never rename them; readable names are accepted too.
@Keep
data class CallerNote(
    @SerializedName(value = "a", alternate = ["id"]) var id: String,
    @SerializedName(value = "b", alternate = ["note"]) var note: String,
    @SerializedName(value = "c", alternate = ["creationDate"]) val creationDate: Long
)
