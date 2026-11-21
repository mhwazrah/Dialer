package com.mhw.phone.helpers

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/** Listens to the accelerometer while a call rings and calls [onFlip] once when the phone is turned face-down. */
class FlipToSilence(context: Context, private val onFlip: () -> Unit) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var detector: FlipDetector? = null

    fun start() {
        if (detector != null) return
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        detector = FlipDetector()
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        if (detector == null) return
        sensorManager.unregisterListener(this)
        detector = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val flipped = detector?.onSample(event.values[2], event.timestamp / NANOS_PER_MILLI) ?: return
        if (flipped) {
            stop()
            onFlip()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
