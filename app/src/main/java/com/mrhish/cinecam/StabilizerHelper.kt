package com.mrhish.cinecam

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

class StabilizerHelper(context: Context, private val onMotion: (Float, Float) -> Unit) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    private var smoothX = 0f
    private var smoothY = 0f
    private val smoothingFactor = 0.85f

    fun start() {
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val rawX = event.values[0]
        val rawY = event.values[1]

        smoothX = smoothingFactor * smoothX + (1 - smoothingFactor) * rawX
        smoothY = smoothingFactor * smoothY + (1 - smoothingFactor) * rawY

        onMotion(smoothX, smoothY)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
