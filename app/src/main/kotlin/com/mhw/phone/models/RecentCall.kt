package com.mhw.phone.models

import android.telephony.PhoneNumberUtils
import com.goodwy.commons.extensions.normalizePhoneNumber
import com.mhw.phone.extensions.getDayCode
import com.google.gson.annotations.SerializedName
import java.io.Serializable

/**
 * Used at displaying recent calls.
 * For contacts with multiple numbers specify the number and type
 */
@kotlinx.serialization.Serializable
data class RecentCall(
    var id: Int,
    var phoneNumber: String,
    var name: String,
    var nickname: String = "",
    var company: String = "",
    var jobPosition: String = "",
    var photoUri: String,
    val startTS: Long,
    var duration: Int,
    var type: Int,
    val simID: Int,
    val simColor: Int,
    var specificNumber: String,
    var specificType: String,
    val isUnknownNumber: Boolean,
    @SerializedName("title") val groupedCalls: MutableList<RecentCall>? = null,
    var contactID: Int? = null,
    var features: Int? = null,
    val isVoiceMail: Boolean,
    var blockReason: Int? = 0,
    val phoneAccountId: String? = null,
    val phoneAccountComponentName: String? = null,
    val countryIso: String? = null,
    val numberPresentation: Int? = null,
) : CallLogItem(), Serializable {
    val dayCode = startTS.getDayCode()

    fun doesContainPhoneNumber(text: String): Boolean {
        return if (text.toLongOrNull() != null) {
            val normalizedText = text.normalizePhoneNumber()
            val normalizedNumber = phoneNumber.normalizePhoneNumber()
            PhoneNumberUtils.compare(normalizedNumber, normalizedText) ||
                phoneNumber.contains(text) ||
                normalizedNumber.contains(normalizedText) ||
                phoneNumber.contains(normalizedText)
        } else {
            false
        }
    }

    fun isABusinessCall() = name.contains(company) && company.isNotEmpty()
}

/** SIM of the newest call that recorded one (simID > 0), or null when none did. */
fun List<RecentCall>.lastUsedSimId(): Int? = filter { it.simID > 0 }.maxByOrNull { it.startTS }?.simID
