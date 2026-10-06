package com.gl1ch5.stabcam

import com.gl1ch5.stabcam.stab.FrameFit
import com.gl1ch5.stabcam.stab.HorizonLock
import com.gl1ch5.stabcam.stab.Quat
import com.gl1ch5.stabcam.stab.Stabilizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FrameFitTest {
    private val k = FrameFit.Intr(2721.0, 2721.0, 1925.0, 1084.0, 3840.0, 2160.0)
    private fun rot(x: Double, y: Double, z: Double) = Quat.fromRotVec(Math.toRadians(x), Math.toRadians(y), Math.toRadians(z))

    @Test
    fun rollNeedsMoreZoomThanYaw() {
        val roll6 = FrameFit.minZoom(rot(0.0, 0.0, 6.0).toMatrix(), k)
        val yaw6 = FrameFit.minZoom(rot(0.0, 6.0, 0.0).toMatrix(), k)
        // wide 16:9 frames lose more to a yaw (the edge ray is at ~35° already) than to a roll
        assertTrue("roll $roll6 yaw $yaw6", roll6 > 1.1 && yaw6 > 1.2)
        assertEquals(1.031, FrameFit.minZoom(Quat.IDENTITY.toMatrix(), k), 0.01) // the 1.5 % safety inset itself
    }

    @Test
    fun scaleToFitKeepsFrameInsideAtGivenZoom() {
        val rv = rot(3.0, 4.0, 9.0).toRotVec()
        val f = FrameFit.scaleToFit(rv, 1.10, k)
        assertTrue(f < 1.0)
        assertTrue(FrameFit.fits(Quat.fromRotVec(rv[0] * f, rv[1] * f, rv[2] * f).toMatrix(), 1.10, k))
    }

    @Test
    fun liveStabilizerNeverShowsBlackEdges() {
        val st = Stabilizer(Stabilizer.Params(maxAngleDeg = 10.0, intr = k))
        var t = 0L
        val rnd = java.util.Random(7)
        var yaw = 0.0; var roll = 0.0
        for (i in 0 until 900) {
            yaw += rnd.nextGaussian() * 1.2; roll += rnd.nextGaussian() * 0.9 // violent random walk
            val real = rot(0.0, yaw * 0.2, roll * 0.5 + 7.0 * sin(2 * PI * 1.0 * t / 1e9))
            st.update(t, real)
            val v = st.virtualDebug()
            val off = (real.conj() * v).toMatrix()
            assertTrue("frame $i crop ${st.crop}", FrameFit.fits(off, st.crop, k))
            t += 16_666_667L
        }
    }

    @Test
    fun horizonCorrectionLevelsEitherSign() {
        for (rollDeg in listOf(-20.0, -7.0, 5.0, 18.0)) {
            // camera rolled by rollDeg about its axis: world-up appears rotated in the image frame
            val up = Quat.fromRotVec(0.0, 0.0, Math.toRadians(rollDeg)).conj().rotate(doubleArrayOf(-0.98, 0.0, 0.2))
            val d = HorizonLock.correction(up)
            val after = Quat.fromRotVec(0.0, 0.0, d).conj().rotate(up)
            val resid = Math.toDegrees(Math.atan2(after[1], -after[0]))
            assertEquals("roll $rollDeg", 0.0, resid, 0.6)
        }
    }
}

class OfflineHorizonTest {
    @Test
    fun offlineHorizonLevelsConstantRollWithoutBlackEdges() {
        val k = FrameFit.Intr(2721.0, 2721.0, 1925.0, 1084.0, 3840.0, 2160.0)
        val n = 300
        val t = LongArray(n) { it * 16_666_667L }
        val roll = Math.toRadians(14.0)
        val real = Array(n) { Quat.fromRotVec(0.0, 0.0, roll) }
        // world up seen from the rolled camera, in the camera-image frame
        val up = Array<DoubleArray?>(n) { Quat.fromRotVec(0.0, 0.0, roll).conj().rotate(doubleArrayOf(-0.97, 0.0, 0.24)) }
        val p = com.gl1ch5.stabcam.stab.OfflineStabilizer.Params(maxAngleDeg = 6.0, intr = k, horizonDeg = 25.0)
        val r = com.gl1ch5.stabcam.stab.OfflineStabilizer.compute(t, real, p, up)
        val i = n / 2
        val off = real[i].conj() * r.virtual[i]
        assertTrue("offset ${Math.toDegrees(off.angle())}", Math.toDegrees(off.angle()) in 11.0..16.0) // counter-rotates ~14°
        assertTrue(FrameFit.fits(off.toMatrix(), r.crop[i], k))
        assertTrue("crop ${r.crop[i]}", r.crop[i] > 1.2)
    }
}
