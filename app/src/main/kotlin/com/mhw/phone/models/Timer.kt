package com.mhw.phone.models

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "timers")
@Keep
@kotlinx.serialization.Serializable
data class Timer(
    @PrimaryKey(autoGenerate = true) var id: Int?,
    var seconds: Int,
    val state: TimerState,
    var vibrate: Boolean,
    var soundUri: String,
    var soundTitle: String,
    var title: String,
    var label: String,
    var description: String,
    var createdAt: Long,
    var channelId: String? = null,
    var oneShot: Boolean = false
)
