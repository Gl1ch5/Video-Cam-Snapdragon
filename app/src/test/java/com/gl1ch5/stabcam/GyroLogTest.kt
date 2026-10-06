package com.gl1ch5.stabcam

import com.gl1ch5.stabcam.stab.GyroLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroLogTest {
    @Test
    fun gcsvRoundTrip() {
        val base = 1_000_000_000L
        val t = longArrayOf(base - 5_000_000, base, base + 2_500_000, base + 900_000_000)
        val w = floatArrayOf(0.1f, 0.2f, 0.3f, 1f, 2f, 3f, -1f, -2f, -3f, 9f, 9f, 9f)
        val text = GyroLog.gcsv(base, t, w, 4, base - 10_000_000, base + 10_000_000)
        assertTrue(text.startsWith("GYROFLOW IMU LOG"))
        assertTrue(text.contains("orientation,Xyz"))
        val (pt, pw) = GyroLog.parseGcsv(text)!!
        assertEquals(3, pt.size)             // the last sample is outside the window
        assertEquals(-5_000_000L, pt[0]); assertEquals(0L, pt[1]); assertEquals(2_500_000L, pt[2])
        assertEquals(-3f, pw[8], 1e-5f)
        assertNotNull(pw)
    }
}
