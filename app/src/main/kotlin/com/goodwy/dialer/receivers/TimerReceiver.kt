package com.goodwy.dialer.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.goodwy.dialer.extensions.hideTimerNotification
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.dialer.extensions.timerDb
import com.goodwy.dialer.helpers.INVALID_TIMER_ID
import com.goodwy.dialer.helpers.TIMER_HIDE
import com.goodwy.dialer.helpers.TIMER_ID
import com.goodwy.dialer.helpers.TIMER_RESTART
import com.goodwy.dialer.models.TimerEvent
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.TimeUnit

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val timerId = intent.getIntExtra(TIMER_ID, INVALID_TIMER_ID)
        if (timerId == INVALID_TIMER_ID) return

        when (intent.action) {
            TIMER_HIDE -> {
                context.hideTimerNotification(timerId)
                EventBus.getDefault().post(TimerEvent.Reset(timerId))
            }
            TIMER_RESTART -> {
                EventBus.getDefault().post(TimerEvent.Restart(timerId))
            }
            else -> { // Start a new. Start replaces any running countdown itself; a Reset here would race it (and delete one-shot timers).
                context.hideTimerNotification(timerId)
                val pendingResult = goAsync()
                ensureBackgroundThread {
                    try {
                        context.timerDb.getTimer(timerId)?.let { timer ->
                            EventBus.getDefault().post(TimerEvent.Start(timerId, TimeUnit.SECONDS.toMillis(timer.seconds.toLong())))
                        }
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
