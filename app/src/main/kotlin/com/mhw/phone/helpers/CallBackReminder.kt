package com.mhw.phone.helpers

import android.content.Context
import com.mhw.phone.extensions.createNewTimer
import com.mhw.phone.extensions.timerHelper
import com.mhw.phone.models.TimerEvent
import com.mhw.phone.models.TimerState
import com.mhw.phone.services.startTimerService
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.TimeUnit

const val CALL_BACK_REMINDER_MINUTES = 30

/** One reminder per number: setting it again restarts that number's countdown, other numbers keep theirs. */
internal fun callBackReminderId(number: String) = 20_000 + (number.filter(Char::isDigit).takeLast(9).hashCode() and 0xFFFF)

/**
 * "Call back X" in [seconds]. The row is stored already running, so the countdown service (started by the app going
 * to the background, or by [startServiceNow] when no screen of ours is open) finds it straight away.
 */
fun Context.startCallBackReminder(name: String, number: String, seconds: Int, startServiceNow: Boolean = false) {
    val duration = TimeUnit.SECONDS.toMillis(seconds.toLong())
    val timer = createNewTimer().copy(
        id = callBackReminderId(number),
        seconds = seconds,
        state = TimerState.Running(duration, System.currentTimeMillis() + duration),
        title = name.ifEmpty { number },
        label = number,
        oneShot = true, // deleted once dismissed
    )
    timerHelper.insertOrUpdateTimer(timer) {
        EventBus.getDefault().post(TimerEvent.Start(timer.id!!, duration))
        if (startServiceNow) startTimerService(this)
    }
}
