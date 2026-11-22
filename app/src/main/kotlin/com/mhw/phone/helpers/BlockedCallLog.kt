package com.mhw.phone.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.provider.CallLog.Calls
import com.google.gson.reflect.TypeToken
import com.mhw.phone.R
import com.mhw.phone.extensions.config
import com.mhw.phone.models.RecentCall
import kotlin.math.abs

const val BLOCK_REASON_LIST = 1
const val BLOCK_REASON_UNKNOWN = 2
const val BLOCK_REASON_HIDDEN = 3
const val BLOCK_REASON_SYSTEM = 4

data class BlockedCall(val number: String, val time: Long, val reason: Int)

private const val MAX_BLOCKED_CALLS = 200
private const val MATCH_WINDOW_MS = 60_000L

/** Same caller whatever the format: "+966 50 123 4567" and "0501234567" share their last nine digits. */
internal fun blockedNumberKey(number: String): String {
    val digits = number.filter(Char::isDigit)
    return if (digits.length < 3) "" else digits.takeLast(9) // hidden numbers come as "", "-1" or "-2"
}

/** The reason recorded nearest to [time] for [number] (a hidden number is ""), within a minute either side. */
internal fun findBlockReason(records: List<BlockedCall>, number: String, time: Long): Int? {
    val key = blockedNumberKey(number)
    return records.filter { blockedNumberKey(it.number) == key && abs(it.time - time) <= MATCH_WINDOW_MS }
        .minByOrNull { abs(it.time - time) }?.reason
}

/** Our own reason when the screening service blocked the call, else the one the system wrote in the call log. */
@SuppressLint("InlinedApi") // only Android 10+ writes a reason; older ones read 0
internal fun blockReasonOf(records: List<BlockedCall>, number: String, time: Long, systemReason: Int): Int? =
    findBlockReason(records, number, time) ?: when (systemReason) {
        0 -> null
        Calls.BLOCK_REASON_BLOCKED_NUMBER -> BLOCK_REASON_LIST
        Calls.BLOCK_REASON_NOT_IN_CONTACTS -> BLOCK_REASON_UNKNOWN
        Calls.BLOCK_REASON_RESTRICTED_NUMBER, Calls.BLOCK_REASON_UNKNOWN_NUMBER, Calls.BLOCK_REASON_PAY_PHONE -> BLOCK_REASON_HIDDEN
        else -> BLOCK_REASON_SYSTEM
    }

fun blockReasonText(reason: Int) = when (reason) {
    BLOCK_REASON_LIST -> R.string.block_reason_list
    BLOCK_REASON_UNKNOWN -> R.string.block_reason_unknown
    BLOCK_REASON_HIDDEN -> R.string.block_reason_hidden
    else -> R.string.block_reason_system
}

/** Why each call was blocked, kept in one pref (newest [MAX_BLOCKED_CALLS]); the call log itself has no room for it. */
object BlockedCallLog {
    private val type = object : TypeToken<List<BlockedCall>>() {}.type

    @Synchronized
    fun read(context: Context): List<BlockedCall> = try {
        gson.fromJson<List<BlockedCall>>(context.config.blockedCallsLog, type)?.filterNotNull() ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    fun add(context: Context, number: String, reason: Int) {
        val records = read(context) + BlockedCall(number, System.currentTimeMillis(), reason)
        context.config.blockedCallsLog = gson.toJson(records.takeLast(MAX_BLOCKED_CALLS))
    }
}

const val FILTER_RECENT_CALLS_BLOCKED = 101
const val FILTER_RECENT_CALLS_SIM_BASE = 200

fun RecentCall.blockReasonIn(records: List<BlockedCall>): Int? = blockReasonOf(records, phoneNumber, startTS, blockReason ?: 0)

/** Recents filter chips: All, Contacts, Blocked, one per SIM ([FILTER_RECENT_CALLS_SIM_BASE] + SIM id), else a call-log type. */
fun RecentCall.matchesRecentsFilter(filter: Int, blockRecords: List<BlockedCall>): Boolean = when {
    filter == FILTER_RECENT_CALLS_ALL -> true
    filter == FILTER_RECENT_CALLS_CONTACTS -> contactID != null
    filter == FILTER_RECENT_CALLS_BLOCKED -> type == Calls.BLOCKED_TYPE || blockReasonIn(blockRecords) != null
    filter >= FILTER_RECENT_CALLS_SIM_BASE -> simID == filter - FILTER_RECENT_CALLS_SIM_BASE
    else -> type == filter
}
