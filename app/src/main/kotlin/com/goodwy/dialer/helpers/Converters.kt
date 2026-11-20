package com.goodwy.dialer.helpers

import androidx.room.TypeConverter
import com.goodwy.dialer.extensions.RuntimeTypeAdapterFactory
import com.goodwy.dialer.models.StateWrapper
import com.goodwy.dialer.models.TimerState
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken

class Converters {
    private val stringType = object : TypeToken<List<String>>() {}.type

    fun jsonToStringList(value: String): ArrayList<String> = gson.fromJson<ArrayList<String>>(value, stringType) ?: arrayListOf()

    fun stringListToJson(list: ArrayList<String>) = gson.toJson(list)

    //timer
    val timerStates = valueOf<TimerState>()
        .registerSubtype(TimerState.Idle::class.java)
        .registerSubtype(TimerState.Running::class.java)
        .registerSubtype(TimerState.Paused::class.java)
        .registerSubtype(TimerState.Finished::class.java)

    inline fun <reified T : Any> valueOf(): RuntimeTypeAdapterFactory<T> = RuntimeTypeAdapterFactory.of(T::class.java)

    fun GsonBuilder.registerTypes(vararg types: TypeAdapterFactory) = apply {
        types.forEach { registerTypeAdapterFactory(it) }
    }

    val gsonTimer: Gson = GsonBuilder().registerTypes(timerStates).create()

    @TypeConverter
    fun jsonToTimerState(value: String?): TimerState {
        if (value.isNullOrEmpty()) return TimerState.Idle
        return try {
            val state = gsonTimer.fromJson(value, StateWrapper::class.java).state
            if (state is TimerState.Running && state.endTime == 0L) migrateLegacyRunning(state, value) else state
        } catch (_: Exception) {
            TimerState.Idle
        }
    }

    // Before 1.0.19 a running timer stored its remaining time as `tick` (rewritten every second) instead of an end
    // time: continue it from that remaining time rather than treating it as already finished.
    private fun migrateLegacyRunning(state: TimerState.Running, json: String): TimerState {
        val tick = LEGACY_TICK.find(json)?.groupValues?.get(1)?.toLongOrNull() ?: return TimerState.Finished
        return TimerState.Running(state.duration, System.currentTimeMillis() + tick)
    }

    private companion object {
        val LEGACY_TICK = Regex(""""tick"\s*:\s*(\d+)""")
    }

    @TypeConverter
    fun timerStateToJson(state: TimerState) = gsonTimer.toJson(StateWrapper(state))
}
