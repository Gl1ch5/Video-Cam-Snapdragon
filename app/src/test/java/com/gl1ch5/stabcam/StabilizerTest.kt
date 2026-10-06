package com.gl1ch5.stabcam

import com.gl1ch5.stabcam.stab.Quat
import com.gl1ch5.stabcam.stab.Stabilizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class StabilizerTest {
    private fun yaw(deg: Double) = Quat.fromRotVec(0.0, Math.toRadians(deg), 0.0)

    @Test
    fun quatRoundTripAndAngle() {
        val q = Quat.fromRotVec(0.3, -0.2, 0.1)
        assertEquals(sqrt3(0.3, -0.2, 0.1), q.angle(), 1e-9)
        assertTrue((q * q.conj()).angle() < 1e-9)
    }

    private fun sqrt3(a: Double, b: Double, c: Double) = Math.sqrt(a * a + b * b + c * c)

    @Test
    fun matrixOfYawMovesXAxis() {
        val m = yaw(90.0).toMatrix()
        // Rotating x unit vector by +90° about y gives -z.
        assertEquals(0.0, m[0], 1e-9); assertEquals(-1.0, m[6], 1e-9)
    }

    @Test
    fun smoothingRemovesShakeAndKeepsSlowPan() {
        val st = Stabilizer(Stabilizer.Params(maxAngleDeg = 4.0))
        val dt = 16_666_667L
        var t = 0L
        var maxVirtualJitter = 0.0
        var maxRealJitter = 0.0
        var prevV: Quat? = null
        for (i in 0 until 600) {
            // 5 Hz, ±1.5° shake on top of a 10 deg/s pan
            val pan = 10.0 * (t / 1e9)
            val shake = 1.5 * sin(2 * PI * 5.0 * t / 1e9)
            val real = yaw(pan + shake)
            val v = st.update(t, real)
            if (i > 60) {
                val ref = yaw(pan)
                maxVirtualJitter = maxOf(maxVirtualJitter, Math.toDegrees((ref.conj() * v).angle()))
                maxRealJitter = maxOf(maxRealJitter, Math.toDegrees((ref.conj() * real).angle()))
            }
            prevV = v
            t += dt
        }
        assertTrue("virtual $maxVirtualJitter real $maxRealJitter", maxVirtualJitter < maxRealJitter * 0.5)
    }

    @Test
    fun correctionNeverExceedsMargin() {
        val st = Stabilizer(Stabilizer.Params(maxAngleDeg = 3.0, tauMaxSec = 5.0, tauMinSec = 5.0))
        var t = 0L
        for (i in 0 until 300) {
            // 20 degree step: far outside the margin
            st.update(t, yaw(if (i < 10) 0.0 else 20.0))
            assertTrue(st.lastCorrectionDeg <= 3.0 + 1e-3)
            t += 16_666_667L
        }
    }

    @Test
    fun steadyFastPanHasNoLag() {
        val st = Stabilizer(Stabilizer.Params())
        var t = 0L
        var last = 0.0
        for (i in 0 until 400) {
            val real = yaw(40.0 * t / 1e9)
            st.update(t, real)
            if (i > 200) last = st.lastCorrectionDeg
            t += 16_666_667L
        }
        assertTrue("offset $last", last < 1.0)
    }
}
