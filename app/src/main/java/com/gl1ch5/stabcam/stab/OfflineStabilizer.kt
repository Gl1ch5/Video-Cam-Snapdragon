package com.gl1ch5.stabcam.stab

import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.tan

/** Camera orientation over time (body→world), built by integrating gyro rates. Pure math. */
class OrientationPath(private val t: LongArray, private val q: Array<Quat>) {
    val size get() = t.size

    /** Orientation at [tNs]; clamps to the ends, slerps between samples. */
    fun at(tNs: Long): Quat {
        if (t.isEmpty()) return Quat.IDENTITY
        if (tNs <= t[0]) return q[0]
        if (tNs >= t[t.size - 1]) return q[t.size - 1]
        var lo = 0
        var hi = t.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) ushr 1; if (t[mid] <= tNs) lo = mid else hi = mid }
        val f = (tNs - t[lo]).toDouble() / (t[hi] - t[lo]).coerceAtLeast(1)
        return Quat.slerp(q[lo], q[hi], f)
    }

    companion object {
        /** [axes] maps device rates to the camera frame, e.g. ["-y","-x","-z"] (same convention as [GyroTracker]). */
        fun integrate(t: LongArray, w: FloatArray, axes: List<String>): OrientationPath {
            val a = if (axes.size == 3) axes else listOf("-y", "-x", "-z")
            val idx = IntArray(3) { "xyz".indexOf(a[it].trim().last()).coerceAtLeast(0) }
            val sg = DoubleArray(3) { if (a[it].trim().startsWith("-")) -1.0 else 1.0 }
            val q = ArrayList<Quat>(t.size)
            var cur = Quat.IDENTITY
            var prev = DoubleArray(3)
            for (i in t.indices) {
                val c = DoubleArray(3) { sg[it] * w[i * 3 + idx[it]] }
                if (i > 0) {
                    val dt = ((t[i] - t[i - 1]) / 1e9).coerceIn(0.0, 0.05)
                    cur = (cur * Quat.fromRotVec((c[0] + prev[0]) * 0.5 * dt, (c[1] + prev[1]) * 0.5 * dt, (c[2] + prev[2]) * 0.5 * dt)).normalized()
                }
                prev = c
                q.add(cur)
            }
            return OrientationPath(t, q.toTypedArray())
        }
    }
}

/**
 * Offline (look-ahead, zero-phase) stabiliser: knows the whole camera path, so the virtual camera is a Gaussian
 * average of the real orientation over ±3σ in both directions — no lag, no overshoot, and the crop can follow
 * what the clip actually needs instead of guessing.
 */
object OfflineStabilizer {
    class Params(
        val sigmaSec: Double = 0.25,
        val maxAngleDeg: Double = 8.0,
        val tanHalfFov: Double = 0.705,
        val minCrop: Double = 1.03,
        val maxCrop: Double = 1.16,
        val cropSmoothSec: Double = 0.6,
    )

    class Result(val virtual: Array<Quat>, val crop: DoubleArray, val offsetDeg: DoubleArray)

    fun compute(timesNs: LongArray, real: Array<Quat>, p: Params): Result {
        val n = real.size
        if (n == 0) return Result(emptyArray(), DoubleArray(0), DoubleArray(0))
        val dt = if (n > 1) (timesNs[n - 1] - timesNs[0]) / 1e9 / (n - 1) else 1.0 / 30
        val win = max(1, (3 * p.sigmaSec / dt).toInt())
        val offs = Array(n) { DoubleArray(3) }
        for (i in 0 until n) {
            val qi = real[i].conj()
            var sx = 0.0; var sy = 0.0; var sz = 0.0; var sw = 0.0
            for (j in max(0, i - win)..minOf(n - 1, i + win)) {
                val d = (timesNs[j] - timesNs[i]) / 1e9 / p.sigmaSec
                val w = exp(-0.5 * d * d)
                val v = (qi * real[j]).toRotVec()
                sx += w * v[0]; sy += w * v[1]; sz += w * v[2]; sw += w
            }
            offs[i][0] = sx / sw; offs[i][1] = sy / sw; offs[i][2] = sz / sw
        }
        fun mag(v: DoubleArray) = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

        // crop: what the surrounding half-second needs, smoothed so the zoom never "breathes"
        val cw = max(1, (p.cropSmoothSec / dt).toInt())
        val need = DoubleArray(n) { i ->
            var m = 0.0
            for (j in max(0, i - cw)..minOf(n - 1, i + cw)) m = max(m, Math.toDegrees(mag(offs[j])))
            val rad = Math.toRadians(m * 1.2 + 0.6)
            (1.0 / (1.0 - tan(rad) / p.tanHalfFov)).coerceIn(p.minCrop, p.maxCrop)
        }
        val crop = DoubleArray(n)
        val sig = max(1.0, cw / 2.0)
        for (i in 0 until n) {
            var s = 0.0; var w = 0.0
            for (j in max(0, i - cw)..minOf(n - 1, i + cw)) { val d = (j - i) / sig; val g = exp(-0.5 * d * d); s += g * need[j]; w += g }
            crop[i] = s / w
        }

        // clamp to what the crop at each frame can hide (and to the absolute cap)
        val virt = Array(n) { Quat.IDENTITY }
        val offDeg = DoubleArray(n)
        for (i in 0 until n) {
            val limit = minOf(Math.toRadians(p.maxAngleDeg), atan((1.0 - 1.0 / crop[i]) * p.tanHalfFov * 0.9))
            val m = mag(offs[i])
            val k = if (m > limit && m > 0) limit / m else 1.0
            virt[i] = (real[i] * Quat.fromRotVec(offs[i][0] * k, offs[i][1] * k, offs[i][2] * k)).normalized()
            offDeg[i] = Math.toDegrees(m * k)
        }
        return Result(virt, crop, offDeg)
    }
}
