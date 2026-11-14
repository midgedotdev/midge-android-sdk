package dev.midge.sdk

import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * Shaking opens the report: the accelerometer is listened to only while one of the app's activities is started, and
 * a shake is three strong movements within a second (a phone put down on a table isn't one).
 */
internal object Shake : SensorEventListener {
    private const val THRESHOLD_G = 2.3f
    private const val WINDOW_MS = 1000L
    private const val NEEDED = 3

    private var sensors: SensorManager? = null
    private val peaks = ArrayDeque<Long>()
    private var lastFired = 0L

    fun install(app: Application) {
        sensors = app.getSystemService(SensorManager::class.java)
        if (Foreground.inForeground) resume()
    }

    fun resume() {
        val manager = sensors ?: return
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        manager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
    }

    fun pause() {
        sensors?.unregisterListener(this)
        peaks.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = event.values
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g < THRESHOLD_G) return
        val now = SystemClock.uptimeMillis()
        peaks.addLast(now)
        while (peaks.isNotEmpty() && now - peaks.first() > WINDOW_MS) peaks.removeFirst()
        if (peaks.size >= NEEDED && now - lastFired > 2000) {
            lastFired = now
            peaks.clear()
            Midge.report()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
