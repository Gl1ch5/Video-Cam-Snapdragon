package com.gl1ch5.stabcam

import com.gl1ch5.stabcam.stab.OfflineStabilizer
import com.gl1ch5.stabcam.stab.OrientationPath
import com.gl1ch5.stabcam.stab.Quat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class OfflineStabilizerTest {
    private fun yaw(deg: Double) = Quat.fromRotVec(0.0, Math.toRadians(deg), 0.0)
    private val dt = 16_666_667L

    @Test
    fun lookaheadRemovesShakeAndHasNoLagOnPan() {
        val n = 600
        val t = LongArray(n) { it * dt }
        fun pan(i: Int) = 20.0 * t[i] / 1e9
        val real = Array(n) { yaw(pan(it) + 1.2 * sin(2 * PI * 6 * t[it] / 1e9)) }
        val r = OfflineStabilizer.compute(t, real, OfflineStabilizer.Params())
        var vErr = 0.0; var rErr = 0.0
        for (i in 80 until n - 80) {
            vErr = maxOf(vErr, Math.toDegrees((yaw(pan(i)).conj() * r.virtual[i]).angle()))
            rErr = maxOf(rErr, Math.toDegrees((yaw(pan(i)).conj() * real[i]).angle()))
        }
        assertTrue("virtual $vErr real $rErr", vErr < 0.15 && vErr < rErr * 0.2)
    }

    @Test
    fun cropStaysInRangeAndCorrectionFitsIt() {
        val n = 400
        val t = LongArray(n) { it * dt }
        val real = Array(n) { yaw(5.0 * sin(2 * PI * 1.5 * t[it] / 1e9)) }
        val p = OfflineStabilizer.Params()
        val r = OfflineStabilizer.compute(t, real, p)
        for (i in 0 until n) {
            assertTrue(r.crop[i] in p.minCrop - 1e-9..p.maxCrop + 1e-9)
            val limit = Math.toDegrees(Math.atan((1 - 1 / r.crop[i]) * p.tanHalfFov * 0.9))
            assertTrue("i=$i off ${r.offsetDeg[i]} limit $limit", r.offsetDeg[i] <= limit + 1e-6)
        }
    }

    @Test
    fun stillClipHasMinimalCrop() {
        val n = 200
        val t = LongArray(n) { it * dt }
        val r = OfflineStabilizer.compute(t, Array(n) { yaw(0.0) }, OfflineStabilizer.Params())
        assertEquals(1.03, r.crop[n / 2], 0.02)
    }

    @Test
    fun orientationPathIntegratesAndInterpolates() {
        // 90 deg/s about device y ("-x" camera axis with axes -y,-x,-z) for one second
        val n = 101
        val t = LongArray(n) { it * 10_000_000L }
        val w = FloatArray(n * 3) { if (it % 3 == 1) Math.toRadians(90.0).toFloat() else 0f }
        val path = OrientationPath.integrate(t, w, listOf("-y", "-x", "-z"))
        assertEquals(90.0, Math.toDegrees((path.at(0).conj() * path.at(1_000_000_000L)).angle()), 0.5)
        assertEquals(45.0, Math.toDegrees((path.at(0).conj() * path.at(500_000_000L)).angle()), 0.5)
    }
}
