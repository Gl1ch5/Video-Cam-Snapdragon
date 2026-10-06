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
        /** Maps a device-frame vector to the camera-image frame with the same axis spec as [integrate]. */
        fun mapVec(axes: List<String>, x: Double, y: Double, z: Double): DoubleArray {
            val a = if (axes.size == 3) axes else listOf("-y", "-x", "-z")
            val v = doubleArrayOf(x, y, z)
            return DoubleArray(3) { i -> (if (a[i].trim().startsWith("-")) -1.0 else 1.0) * v["xyz".indexOf(a[i].trim().last()).coerceAtLeast(0)] }
        }

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
        /** Exact fit test; when set the crop/clamp use it instead of the tan() approximation. */
        val intr: FrameFit.Intr? = null,
        val horizonDeg: Double = 0.0,
        val horizonSigmaSec: Double = 0.4,
        /** Largest zoom allowed when horizon lock needs it. */
        val horizonMaxCrop: Double = 1.5,
    )

    class Result(val virtual: Array<Quat>, val crop: DoubleArray, val offsetDeg: DoubleArray)

    fun compute(timesNs: LongArray, real: Array<Quat>, p: Params, up: Array<DoubleArray?>? = null): Result {
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
        val fit = p.intr

        // horizon lock: level correction per frame, smoothed both ways, then folded into the offset
        if (p.horizonDeg > 0 && up != null) {
            val raw = DoubleArray(n) { i -> up[i]?.let { Math.toDegrees(HorizonLock.correction(it)) } ?: 0.0 }
            val hw = max(1, (3 * p.horizonSigmaSec / dt).toInt())
            for (i in 0 until n) {
                var s = 0.0; var w = 0.0
                for (j in max(0, i - hw)..minOf(n - 1, i + hw)) { val d = (timesNs[j] - timesNs[i]) / 1e9 / p.horizonSigmaSec; val g = exp(-0.5 * d * d); s += g * raw[j]; w += g }
                val h = (s / w).coerceIn(-p.horizonDeg, p.horizonDeg)
                val q = Quat.fromRotVec(offs[i][0], offs[i][1], offs[i][2]) * Quat.fromRotVec(0.0, 0.0, Math.toRadians(h))
                val v = q.toRotVec(); offs[i][0] = v[0]; offs[i][1] = v[1]; offs[i][2] = v[2]
            }
        }
        val maxCrop = if (p.horizonDeg > 0) max(p.maxCrop, p.horizonMaxCrop) else p.maxCrop

        // crop: what the surrounding half-second needs, smoothed so the zoom never "breathes"
        val cw = max(1, (p.cropSmoothSec / dt).toInt())
        val needNow = DoubleArray(n) { i ->
            if (fit != null) FrameFit.minZoom(Quat.fromRotVec(offs[i][0], offs[i][1], offs[i][2]).toMatrix(), fit) * 1.03
            else 1.0 / (1.0 - tan(Math.toRadians(Math.toDegrees(mag(offs[i])) * 1.2 + 0.6)) / p.tanHalfFov)
        }
        val need = DoubleArray(n) { i ->
            var m = 1.0
            for (j in max(0, i - cw)..minOf(n - 1, i + cw)) m = max(m, needNow[j])
            m.coerceIn(p.minCrop, maxCrop)
        }
        val crop = DoubleArray(n)
        val sig = max(1.0, cw / 2.0)
        for (i in 0 until n) {
            var s = 0.0; var w = 0.0
            for (j in max(0, i - cw)..minOf(n - 1, i + cw)) { val d = (j - i) / sig; val g = exp(-0.5 * d * d); s += g * need[j]; w += g }
            crop[i] = max(s / w, need[i].coerceAtMost(s / w + 1e-9)).coerceIn(p.minCrop, maxCrop)
        }

        // clamp to what the crop at each frame can hide (exact), and to the absolute cap
        val virt = Array(n) { Quat.IDENTITY }
        val offDeg = DoubleArray(n)
        for (i in 0 until n) {
            var k = 1.0
            val m = mag(offs[i])
            val cap = Math.toRadians(p.maxAngleDeg + if (p.horizonDeg > 0) p.horizonDeg else 0.0)
            if (m > cap && m > 0) k = cap / m
            if (fit != null) k = minOf(k, FrameFit.scaleToFit(doubleArrayOf(offs[i][0] * k, offs[i][1] * k, offs[i][2] * k), crop[i], fit) * k)
            else {
                val limit = atan((1.0 - 1.0 / crop[i]) * p.tanHalfFov * 0.9)
                if (m * k > limit && m > 0) k = limit / m
            }
            virt[i] = (real[i] * Quat.fromRotVec(offs[i][0] * k, offs[i][1] * k, offs[i][2] * k)).normalized()
            offDeg[i] = Math.toDegrees(m * k)
        }
        return Result(virt, crop, offDeg)
    }
}
