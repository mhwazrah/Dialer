package com.mhw.phone.helpers

import android.provider.CallLog.Calls

/** One call-log row as the statistics see it. [key] is the person: a contact id, else the number's last nine digits. */
data class StatCall(val key: String, val name: String, val type: Int, val seconds: Int, val hour: Int)

data class TopCaller(val name: String, val calls: Int, val seconds: Int)

data class CallStats(
    val total: Int,
    val incoming: Int,
    val outgoing: Int,
    val missed: Int,
    val talkSeconds: Int,
    val averageSeconds: Int,
    val topCallers: List<TopCaller>,
    val busiestHour: Int?,
)

private const val TOP_CALLERS = 5

/** Talk time counts answered calls only; blocked calls are left out entirely. */
fun callStats(calls: List<StatCall>): CallStats {
    val counted = calls.filter { it.type != Calls.BLOCKED_TYPE }
    val talked = counted.filter { (it.type == Calls.INCOMING_TYPE || it.type == Calls.OUTGOING_TYPE) && it.seconds > 0 }
    val talkSeconds = talked.sumOf { it.seconds }
    val topCallers = counted.groupBy { it.key }.values
        .map { personCalls -> TopCaller(personCalls.first().name, personCalls.size, personCalls.sumOf { it.seconds }) }
        .sortedWith(compareByDescending<TopCaller> { it.calls }.thenByDescending { it.seconds })
        .take(TOP_CALLERS)
    return CallStats(
        total = counted.size,
        incoming = counted.count { it.type == Calls.INCOMING_TYPE },
        outgoing = counted.count { it.type == Calls.OUTGOING_TYPE },
        missed = counted.count { it.type == Calls.MISSED_TYPE || it.type == Calls.REJECTED_TYPE },
        talkSeconds = talkSeconds,
        averageSeconds = if (talked.isEmpty()) 0 else talkSeconds / talked.size,
        topCallers = topCallers,
        busiestHour = counted.groupingBy { it.hour }.eachCount().maxByOrNull { it.value }?.key,
    )
}
