package com.goodwy.dialer.services

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.goodwy.commons.extensions.*
import com.goodwy.dialer.R
import com.goodwy.dialer.models.TimerEvent
import com.goodwy.dialer.models.TimerState
import com.goodwy.dialer.extensions.*
import com.goodwy.dialer.helpers.*
import com.goodwy.dialer.receivers.TimerReceiver
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class TimerService : Service() {
    private companion object {
        const val CHANNEL_ID = "right_dialer_alarm_timer"
    }

    private val bus = EventBus.getDefault()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var isStopping = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        bus.register(this)
    }

    private fun createNotificationChannel() {
        NotificationChannel(CHANNEL_ID, getString(R.string.timer), NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            notificationManager.createNotificationChannel(this)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("ForegroundServiceType")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        isStopping = false
        updateNotification()
        startForeground(
            TIMER_RUNNING_NOTIFICATION_ID,
            notification(
                title = getString(R.string.app_launcher_name),
                contentText = getString(R.string.timers_notification_msg),
                firstRunningTimerId = INVALID_TIMER_ID,
                endTime = null
            )
        )
        return START_NOT_STICKY
    }

    private fun updateNotification() {
        timerHelper.getTimers { timers ->
            val runningTimers = timers.filter { it.state is TimerState.Running }
            if (runningTimers.isNotEmpty()) {
                val firstTimer = runningTimers.first()
                val endTime = (firstTimer.state as TimerState.Running).endTime
                val contextText = when {
                    firstTimer.label.isNotEmpty() -> getString(
                        R.string.call_back_person_g,
                        firstTimer.label
                    )

                    else -> resources.getQuantityString(
                        R.plurals.timer_notification_msg,
                        runningTimers.size,
                        runningTimers.size
                    )
                }

                mainHandler.post {
                    // The last timer may have finished while this was queued
                    if (isStopping) return@post
                    try {
                        startForeground(
                            TIMER_RUNNING_NOTIFICATION_ID,
                            notification(
                                title = getString(R.string.remind_me),
                                contentText = contextText,
                                firstRunningTimerId = firstTimer.id!!,
                                endTime = endTime,
                                number = firstTimer.label
                            )
                        )
                    } catch (e: Exception) {
                        showErrorToast(e)
                    }
                }
            } else {
                mainHandler.post { stopService() }
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: TimerStopService) {
        stopService()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: TimerEvent.Refresh) {
        if (!isStopping) {
            updateNotification()
        }
    }

    // Main thread only
    private fun stopService() {
        isStopping = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        bus.unregister(this)
    }

    private fun notification(
        title: String,
        contentText: String,
        firstRunningTimerId: Int,
        endTime: Long?,
        number: String = ""
    ): Notification {
        val restart = Intent(this, TimerReceiver::class.java).apply {
            action = TIMER_RESTART
            putExtra(TIMER_ID, firstRunningTimerId)
        }
        val cancelIntent = PendingIntent.getBroadcast(
            this, firstRunningTimerId, restart, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val collapsedView = RemoteViews(this.packageName, R.layout.timer_notification).apply {
            setText(R.id.timer_title, title)
            setText(R.id.timer_content, contentText)
            setOnClickPendingIntent(R.id.timer_repeat, cancelIntent)
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
//            .setContentTitle(title)
//            .setContentText(contentText)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setCustomContentView(collapsedView)
            .setSmallIcon(R.drawable.ic_remind_call)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setSound(null)
            .setOngoing(true)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                R.drawable.ic_cross_vector,
                getString(R.string.dismiss),
                getHideTimerPendingIntent(firstRunningTimerId)
            )
        if (number != "") {
            builder.addAction(
                R.drawable.ic_messages,
                getString(R.string.message),
                sendSMSPendingIntentUpdateCurrent(number)
            )
            builder.addAction(
                R.drawable.ic_phone_vector,
                getString(R.string.call_back_g),
                startCallPendingIntentUpdateCurrent(number)
            )
        }

        // The system draws the countdown from the stored end time: no per-second updates needed
        if (endTime != null) {
            builder.setWhen(endTime).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        }

        if (firstRunningTimerId != INVALID_TIMER_ID) {
            builder.setContentIntent(this.getOpenTimerTabIntent(firstRunningTimerId))
        }

        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        return builder.build()
    }
}

fun startTimerService(context: Context) {
    Handler(Looper.getMainLooper()).post {
        try {
            ContextCompat.startForegroundService(context, Intent(context, TimerService::class.java))
        } catch (e: Exception) {
            context.showErrorToast(e)
        }
    }
}

object TimerStopService
