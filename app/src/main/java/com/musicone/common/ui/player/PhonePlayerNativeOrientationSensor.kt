package com.musicone

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock

/** 与现有方向监听共用前台及旋转锁生命周期，不申请界面帧。 */
internal class PhonePlayerNativeOrientationSensor(context: Context, private val handler: Handler,
    initialRotation: Int, private val publish: (PhonePlayerNativeOrientation) -> Unit) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    // 优先使用标准加速度计配合低通滤波，确保模拟器注入与真机均可稳定获得姿态
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        ?: manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val gate = PhonePlayerNativeOrientationGate(initialRotation)
    private val filter = PhonePlayerGravityFilter()
    private var registered = false
    private val settle = Runnable {
        if (registered) publish(gate.settle(SystemClock.uptimeMillis()))
    }

    fun start() {
        if (registered) return
        publish(gate.state)
        registered = sensor != null && manager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI, handler) == true
    }

    fun stop(resetRotation: Boolean = false) {
        if (registered) manager?.unregisterListener(this)
        registered = false
        handler.removeCallbacks(settle)
        filter.reset()
        gate.reset(if (resetRotation) 0 else gate.state.rotationDegrees)
        // 系统旋转锁原本会明确返回竖屏；该策略不受平放暂停的限制。
        publish(if (resetRotation) PhonePlayerNativeOrientation(0, ready = true) else gate.state)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!registered) return
        val values = event.values
        val pose = if (event.sensor.type == Sensor.TYPE_GRAVITY)
            phonePlayerGravityPose(values[0], values[1], values[2])
        else filter.update(values[0], values[1], values[2], event.timestamp)
        publish(gate.update(pose?.orientation ?: -1, pose?.inclination ?: Float.NaN, SystemClock.uptimeMillis()))
        handler.removeCallbacks(settle)
        gate.decisionAtMs?.let { handler.postAtTime(settle, it) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
