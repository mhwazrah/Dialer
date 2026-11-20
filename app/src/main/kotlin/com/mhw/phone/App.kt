package com.mhw.phone

import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.goodwy.commons.RightApp
import com.goodwy.commons.extensions.baseConfig
import com.goodwy.commons.extensions.notificationManager
import com.goodwy.commons.extensions.showErrorToast
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.mhw.phone.helpers.ContactsRepository
import com.mhw.phone.helpers.DialpadT9
import com.mhw.phone.extensions.*
import com.mhw.phone.models.TimerEvent
import com.mhw.phone.models.Timer
import com.mhw.phone.models.TimerState
import com.mhw.phone.services.TimerStopService
import com.mhw.phone.services.startTimerService
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.concurrent.TimeUnit

class App : RightApp(), DefaultLifecycleObserver {

    private var countDownTimers = mutableMapOf<Int, CountDownTimer>()

    override fun onCreate() {
        super<RightApp>.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        // Warm the T9 language data off the main thread; screens that need it earlier wait on its lock
        ensureBackgroundThread { DialpadT9.ensureInitialized(this) }
        ensureBackgroundThread { warmUpNativeGlassBlur() }
        // Observers only mark the contact snapshot dirty; nothing is loaded until a screen asks for it
        ContactsRepository.registerObservers(this)
        EventBus.getDefault().register(this)
        unlockAllFeatures()
    }

    // MHW Phone ships every feature unlocked: no purchase flow, no "Project support" prompts.
    // The commons library gates features on these flags (isPro / isOrWasThankYouInstalled).
    private fun unlockAllFeatures() {
        // Written only when not already set: every write is a SharedPreferences commit
        if (!baseConfig.isPro) baseConfig.isPro = true
        if (!baseConfig.isProNoGP) baseConfig.isProNoGP = true
        if (!baseConfig.hadThankYouInstalled) baseConfig.hadThankYouInstalled = true
        if (!baseConfig.wasAppRated) baseConfig.wasAppRated = true // no "Rate" prompt (commons shows it every 40th launch)
    }

    override fun onStop(owner: LifecycleOwner) {
        timerHelper.getTimers { timers ->
            if (timers.any { it.state is TimerState.Running }) {
                startTimerService(this)
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        EventBus.getDefault().post(TimerStopService)
        timerHelper.getTimers { timers ->
            timers.forEach { timer ->
                val timerId = timer.id ?: return@forEach
                val running = timer.state as? TimerState.Running ?: return@forEach
                runOnMain {
                    if (countDownTimers[timerId] != null) return@runOnMain
                    // The process may have been dead while the timer ran: the persisted end time says what is left.
                    val remaining = running.remaining()
                    if (remaining > 0) {
                        // Persist the state as read, so a timer converted from the pre-1.0.19 format keeps one fixed
                        // end time instead of one recomputed on every read. (Not for an expired timer: the Finish
                        // path writes Finished, and a late Running write would re-fire the alarm on every start.)
                        timerHelper.insertOrUpdateTimer(timer)
                        startCountdown(timerId, running.duration, remaining)
                    } else {
                        EventBus.getDefault().post(TimerEvent.Finish(timerId, running.duration))
                    }
                }
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: TimerEvent.Reset) {
        updateTimerState(event.timerId) { TimerState.Idle }
        countDownTimers.remove(event.timerId)?.cancel()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: TimerEvent.Restart) {
        timerHelper.getTimer(event.timerId) { timer ->
            val duration = TimeUnit.SECONDS.toMillis(timer.seconds.toLong())
            EventBus.getDefault().post(TimerEvent.Start(event.timerId, duration))
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: TimerEvent.Delete) {
        countDownTimers.remove(event.timerId)?.cancel()
        timerHelper.deleteTimer(event.timerId) {
            EventBus.getDefault().post(TimerEvent.Refresh)
        }
    }

    // Start always runs the timer for its full length: the end time is stored once, nothing is written per second.
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: TimerEvent.Start) {
        startCountdown(event.timerId, event.duration, event.duration)
        val endTime = System.currentTimeMillis() + event.duration
        updateTimerState(event.timerId) { TimerState.Running(event.duration, endTime) }
    }

    // Main thread only; the single callback at the end time is all the countdown does.
    private fun startCountdown(timerId: Int, duration: Long, remaining: Long) {
        countDownTimers.remove(timerId)?.cancel()
        countDownTimers[timerId] = object : CountDownTimer(remaining, remaining) {
            override fun onTick(millisUntilFinished: Long) = Unit

            override fun onFinish() {
                countDownTimers.remove(timerId)
                EventBus.getDefault().post(TimerEvent.Finish(timerId, duration))
                EventBus.getDefault().post(TimerStopService)
            }
        }.start()
    }

    private fun runOnMain(action: () -> Unit) {
        Handler(Looper.getMainLooper()).post(action)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: TimerEvent.Finish) {
        timerHelper.getTimer(event.timerId) { timer ->
            val pendingIntent = getOpenTimerTabIntent(event.timerId)
            val notification = getTimerNotification(timer, pendingIntent)

            try {
                notificationManager.notify(event.timerId, notification)
            } catch (e: Exception) {
                showErrorToast(e)
            }

            updateTimerState(event.timerId) { TimerState.Finished }
            Handler(Looper.getMainLooper()).postDelayed({
                hideNotification(event.timerId)
            }, config.timerMaxReminderSecs * 1000L)
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: TimerEvent.Pause) {
        // countDownTimers is touched only on the main thread
        countDownTimers.remove(event.timerId)?.cancel()
        updateTimerState(event.timerId) { timer ->
            val running = timer.state as? TimerState.Running ?: return@updateTimerState timer.state
            TimerState.Paused(running.duration, running.remaining())
        }
    }

    // One read and one write per state change, never per tick. The state is derived from the stored timer.
    private fun updateTimerState(timerId: Int, newState: (Timer) -> TimerState) {
        timerHelper.getTimer(timerId) { timer ->
            val state = newState(timer)
            val newTimer = timer.copy(state = state)
            if (newTimer.oneShot && state is TimerState.Idle) {
                timerHelper.deleteTimer(newTimer.id!!) {
                    EventBus.getDefault().post(TimerEvent.Refresh)
                }
            } else {
                timerHelper.insertOrUpdateTimer(newTimer) {
                    EventBus.getDefault().post(TimerEvent.Refresh)
                }
            }
        }
    }
}
