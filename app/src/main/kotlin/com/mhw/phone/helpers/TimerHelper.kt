package com.mhw.phone.helpers

import android.content.Context
import com.mhw.phone.extensions.timerDb
import com.mhw.phone.models.Timer
import com.goodwy.commons.helpers.ensureBackgroundThread

class TimerHelper(val context: Context) {
    private val timerDao = context.timerDb

    fun getTimers(callback: (timers: List<Timer>) -> Unit) {
        ensureBackgroundThread {
            callback.invoke(timerDao.getTimers())
        }
    }

    // The callback is skipped when the timer no longer exists (deleted while an event was in flight).
    fun getTimer(timerId: Int, callback: (timer: Timer) -> Unit) {
        ensureBackgroundThread {
            timerDao.getTimer(timerId)?.let(callback)
        }
    }

//    fun tryGetTimer(timerId: Int, callback: (timer: Timer?) -> Unit) {
//        ensureBackgroundThread {
//            callback.invoke(timerDao.getTimer(timerId))
//        }
//    }
//
//    fun findTimers(seconds: Int, label: String, callback: (timers: List<Timer>) -> Unit) {
//        ensureBackgroundThread {
//            callback.invoke(timerDao.findTimers(seconds, label))
//        }
//    }

    fun insertOrUpdateTimer(timer: Timer, callback: (id: Long) -> Unit = {}) {
        ensureBackgroundThread {
            val id = timerDao.insertOrUpdateTimer(timer)
            callback.invoke(id)
        }
    }

    fun deleteTimer(id: Int, callback: () -> Unit = {}) {
        ensureBackgroundThread {
            timerDao.deleteTimer(id)
            callback.invoke()
        }
    }

//    fun deleteTimers(timers: List<Timer>, callback: () -> Unit = {}) {
//        ensureBackgroundThread {
//            timerDao.deleteTimers(timers)
//            callback.invoke()
//        }
//    }
}
