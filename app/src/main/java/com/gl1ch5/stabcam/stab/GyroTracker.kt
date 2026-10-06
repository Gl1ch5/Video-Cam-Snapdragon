package com.gl1ch5.stabcam.stab

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.gl1ch5.stabcam.util.Logger

/**
 * Integrates the gyroscope into a continuous camera orientation history.
 * Sensor timestamps are SystemClock.elapsedRealtimeNanos(), the same clock as camera frame timestamps
 * when SENSOR_INFO_TIMESTAMP_SOURCE == REALTIME.
 *
 * [axes] maps device gyro axes to the camera-image frame, e.g. ["-y","-x","-z"] (back camera, sensor orientation 90°).
 */
class GyroTracker(private val ctx: Context, axes: List<String>) : SensorEventListener {

    private class Axis(val index: Int, val sign: Double)

    private val map: List<Axis> = parse(axes)
    private val thread = HandlerThread("gyro", android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
    private val n = 4096
    private val times = LongArray(n)
    private val qw = DoubleArray(n); private val qx = DoubleArray(n); private val qy = DoubleArray(n); private val qz = DoubleArray(n)
    private var count = 0L
    private var cur = Quat.IDENTITY
    private var lastT = 0L
    private var lastW = DoubleArray(3)
    private var samples = 0L
    private var started = 0L

    // Raw device-frame rates (rad/s): short ring always on, full log while recording.
    private val rawN = 2048
    private val rawT = LongArray(rawN)
    private val rawW = FloatArray(rawN * 3)
    private var rawCount = 0L
    private var logging = false
    private var logT = LongArray(0)
    private var logW = FloatArray(0)
    private var logN = 0

    /** Starts the full log, pre-seeded with the last ~0.4 s so the clip start is covered. */
    @Synchronized fun startLog() {
        logT = LongArray(1 shl 14); logW = FloatArray((1 shl 14) * 3); logN = 0
        val have = minOf(rawCount, rawN.toLong(), 200L)
        for (k in have downTo 1) append(((rawCount - k) % rawN).toInt())
        logging = true
    }

    /** Stops logging; returns (timestamps ns, xyz rates, count). */
    @Synchronized fun stopLog(): Triple<LongArray, FloatArray, Int> {
        logging = false
        return Triple(logT, logW, logN)
    }

    private fun append(i: Int) {
        if (logN == logT.size) { logT = logT.copyOf(logN * 2); logW = logW.copyOf(logN * 6) }
        logT[logN] = rawT[i]
        logW[logN * 3] = rawW[i * 3]; logW[logN * 3 + 1] = rawW[i * 3 + 1]; logW[logN * 3 + 2] = rawW[i * 3 + 2]
        logN++
    }

    fun start(): Boolean {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) ?: run {
            Logger.e(TAG, "Гироскоп не найден")
            return false
        }
        thread.start()
        started = System.nanoTime()
        val ok = sm.registerListener(this, gyro, 2500, 0, Handler(thread.looper))
        Logger.i(TAG, "Гироскоп ${gyro.name}: макс. частота ${if (gyro.minDelay > 0) 1e6 / gyro.minDelay else 0.0} Гц, запрос 400 Гц, ok=$ok, оси=${map.joinToString { (if (it.sign < 0) "-" else "") + "xyz"[it.index] }}")
        return ok
    }

    fun stop() {
        ctx.getSystemService(SensorManager::class.java).unregisterListener(this)
        thread.quitSafely()
        val sec = (System.nanoTime() - started) / 1e9
        if (sec > 0) Logger.i(TAG, "Гироскоп остановлен: ${"%.0f".format(samples / sec)} Гц средняя частота")
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        val w = DoubleArray(3) { i -> map[i].sign * e.values[map[i].index] }
        synchronized(this) {
            if (lastT != 0L) {
                val dt = ((e.timestamp - lastT) / 1e9).coerceIn(0.0, 0.05)
                val dq = Quat.fromRotVec((w[0] + lastW[0]) * 0.5 * dt, (w[1] + lastW[1]) * 0.5 * dt, (w[2] + lastW[2]) * 0.5 * dt)
                cur = (cur * dq).normalized()
            }
            lastT = e.timestamp
            lastW = w
            val ri = (rawCount % rawN).toInt()
            rawT[ri] = e.timestamp; rawW[ri * 3] = e.values[0]; rawW[ri * 3 + 1] = e.values[1]; rawW[ri * 3 + 2] = e.values[2]
            rawCount++
            if (logging) append(ri)
            val i = (count % n).toInt()
            times[i] = e.timestamp; qw[i] = cur.w; qx[i] = cur.x; qy[i] = cur.y; qz[i] = cur.z
            count++
            samples++
        }
    }

    /** Camera orientation at [tNs]; clamps to the available history. */
    @Synchronized
    fun orientationAt(tNs: Long): Quat {
        if (count == 0L) return Quat.IDENTITY
        val oldest = maxOf(0L, count - n)
        var lo = oldest
        var hi = count - 1
        if (tNs >= times[(hi % n).toInt()]) return q(hi)
        if (tNs <= times[(lo % n).toInt()]) return q(lo)
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (times[(mid % n).toInt()] <= tNs) lo = mid else hi = mid
        }
        val t0 = times[(lo % n).toInt()]
        val t1 = times[(hi % n).toInt()]
        val f = if (t1 > t0) (tNs - t0).toDouble() / (t1 - t0) else 0.0
        return Quat.slerp(q(lo), q(hi), f)
    }

    @Synchronized fun latestTimeNs(): Long = if (count == 0L) 0L else times[((count - 1) % n).toInt()]

    private fun q(i: Long): Quat { val k = (i % n).toInt(); return Quat(qw[k], qx[k], qy[k], qz[k]) }

    companion object {
        const val TAG = "Gyro"

        private fun parse(axes: List<String>): List<Axis> {
            val a = if (axes.size == 3) axes else listOf("-y", "-x", "-z")
            return a.map { s ->
                val t = s.trim().lowercase()
                Axis("xyz".indexOf(t.last()).coerceAtLeast(0), if (t.startsWith("-")) -1.0 else 1.0)
            }
        }

        /** Candidates for the long-press "cycle axes" test: sign/permutation variants of the expected mapping. */
        val CANDIDATES = listOf(
            listOf("-y", "-x", "-z"), listOf("y", "x", "-z"), listOf("-y", "-x", "z"), listOf("y", "x", "z"),
            listOf("-y", "x", "z"), listOf("y", "-x", "-z"), listOf("-y", "x", "-z"), listOf("y", "-x", "z"),
        )
    }
}
