package com.mhw.phone.models

import androidx.annotation.Keep

@Keep
@kotlinx.serialization.Serializable
sealed class TimerState {
    @Keep
    @kotlinx.serialization.Serializable
    object Idle : TimerState()

    @Keep
    @kotlinx.serialization.Serializable
    data class Running(val duration: Long, val endTime: Long) : TimerState() {
        fun remaining(now: Long = System.currentTimeMillis()): Long = (endTime - now).coerceAtLeast(0L)
    }

    @Keep
    @kotlinx.serialization.Serializable
    data class Paused(val duration: Long, val tick: Long) : TimerState()

    @Keep
    @kotlinx.serialization.Serializable
    object Finished : TimerState()
}
