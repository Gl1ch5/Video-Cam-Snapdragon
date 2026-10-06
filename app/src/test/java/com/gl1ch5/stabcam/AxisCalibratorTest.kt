package com.gl1ch5.stabcam

import com.gl1ch5.stabcam.stab.AxisCalibrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AxisCalibratorTest {
    private val fx = 2721.0
    private val fy = 2721.0

    /** Synthetic flows generated from a known device→camera mapping with noise. */
    private fun run(axes: List<String>, seed: Long): AxisCalibrator.Result {
        val rnd = Random(seed)
        val idx = IntArray(3) { "xyz".indexOf(axes[it].last()) }
        val sg = DoubleArray(3) { if (axes[it].startsWith("-")) -1.0 else 1.0 }
        val flows = ArrayList<DoubleArray>(); val disp = ArrayList<DoubleArray>()
        repeat(200) {
            val d = doubleArrayOf(rnd.nextGaussian() * 0.01, rnd.nextGaussian() * 0.01, rnd.nextGaussian() * 0.004)
            val w = DoubleArray(3) { sg[it] * d[idx[it]] }
            flows += doubleArrayOf(-fx * w[1] + rnd.nextGaussian() * 2, fy * w[0] + rnd.nextGaussian() * 2)
            disp += d
        }
        return AxisCalibrator.solve(flows, disp, fx, fy)
    }

    @Test
    fun recoversEveryProperRotationMapping() {
        val perms = listOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2), intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0))
        var n = 0
        for (p in perms) for (si in intArrayOf(-1, 1)) for (sj in intArrayOf(-1, 1)) {
            val inv = (0..2).sumOf { a -> (a + 1..2).count { b -> p[a] > p[b] } }
            val sz = (if (inv % 2 == 0) 1 else -1) * si * sj
            val truth = AxisCalibrator.spec(p, intArrayOf(si, sj, sz))
            val r = run(truth, 100L + n++)
            assertEquals("truth $truth", truth, r.best.axes)
            assertTrue("score ${r.best.score}", r.best.score > 0.9 && r.confident)
        }
        assertEquals(24, n)
    }

    @Test
    fun knownGoodDefaultScoresAsCurrent() {
        val r = run(listOf("-y", "-x", "-z"), 5)
        assertEquals(r.best.axes, r.current!!.axes)
    }

    @Test
    fun shiftFindsSubPixelMotion() {
        val w = 96; val h = 54
        fun img(ox: Double, oy: Double) = ByteArray(w * h) { i ->
            val x = i % w - ox; val y = i / w - oy
            (128 + 100 * Math.sin(x * 0.45) * Math.cos(y * 0.37) + 20 * Math.sin(x * 0.11 + y * 0.17)).toInt().coerceIn(0, 255).toByte()
        }
        val s = AxisCalibrator.shift(img(0.0, 0.0), img(3.4, -2.2), w, h)!!
        assertEquals(3.4, s[0], 0.5); assertEquals(-2.2, s[1], 0.5)
    }
}
