package com.gl1ch5.stabcam

import com.gl1ch5.stabcam.stab.AxisCalibrator
import com.gl1ch5.stabcam.stab.FrameFit
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

class IntrinsicsTest {
    @Test
    fun centreCropOfActiveArrayMatchesOnePlusNumbers() {
        // 4096x3072 array, 3840x2160 output: crop 4096x2304, scale 0.9375, cy shifts by (3072-2304)/2
        val k = FrameFit.outputIntrinsics(floatArrayOf(2903.1f, 2902.6f, 2054.17f, 1539.94f), 4096f, 3072f, 3840, 2160)
        assertEquals(2721.7f, k[0], 0.5f); assertEquals(1083.7f, k[3], 0.5f); assertEquals(1925.8f, k[2], 0.5f)
    }

    @Test
    fun timeLagIsRecovered() {
        // gyro displacement series sampled at lags -10..10 ms; the flows follow the +4 ms one
        val rnd = Random(3)
        val lags = (-10..10).toList()
        val base = List(300) { doubleArrayOf(rnd.nextGaussian() * 0.01, rnd.nextGaussian() * 0.01, 0.0) }
        // lag L: shifted series (circular shift stands in for the integration window shift)
        val byLag = lags.map { l -> List(300) { i -> base[(i + l + 300) % 300] } }
        val axes = listOf("-y", "-x", "-z")
        val truth = byLag[lags.indexOf(4)]
        val flows = truth.map { d -> doubleArrayOf(-2721.0 * (-d[0]) , 2721.0 * (-d[1])) }
        // with axes -y,-x: w0 = -d[1] (about camera x), w1 = -d[0] (about camera y)
        val flows2 = truth.map { d -> doubleArrayOf(-2721.0 * (-d[0]) + rnd.nextGaussian(), 2721.0 * (-d[1]) + rnd.nextGaussian()) }
        val best = AxisCalibrator.bestLag(flows2, byLag, axes, 2721.0, 2721.0)
        assertEquals(4, lags[best])
        assertEquals(flows.size, flows2.size)
    }
}
