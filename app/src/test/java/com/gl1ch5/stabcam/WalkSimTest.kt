package com.gl1ch5.stabcam

import com.gl1ch5.stabcam.stab.FrameFit
import com.gl1ch5.stabcam.stab.Quat
import com.gl1ch5.stabcam.stab.Stabilizer
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Synthetic walking / running: the stabilized output must shake (angular jerk) much less than the input. */
class WalkSimTest {
    private val k = FrameFit.Intr(2721.7, 2721.2, 1920.0, 1080.0, 3840.0, 2160.0)

    private fun motion(t: Double, run: Boolean): DoubleArray {
        val a = if (run) 2.5 else 1.0
        val f = if (run) 2.8 else 1.9
        val r = java.util.Random((t * 1e4).toLong())
        // pitch bob at step rate, yaw sway at half rate, roll wobble, slow pan, small tremor
        val pitch = a * 2.0 * sin(2 * PI * f * t) + 0.15 * (r.nextDouble() - 0.5)
        val yaw = a * 1.5 * sin(2 * PI * f / 2 * t + 0.7) + 8.0 * t / 10 + 0.15 * (r.nextDouble() - 0.5)
        val roll = a * 2.0 * sin(2 * PI * f / 2 * t + 1.9)
        return doubleArrayOf(Math.toRadians(pitch), Math.toRadians(yaw), Math.toRadians(roll))
    }

    /** RMS angular acceleration (deg/s^2) of a quaternion sequence: what the eye sees as shake. */
    private fun jerk(q: List<Quat>, dt: Double): Double {
        var s = 0.0; var n = 0
        for (i in 2 until q.size) {
            val w1 = (q[i - 2].conj() * q[i - 1]).toRotVec(); val w2 = (q[i - 1].conj() * q[i]).toRotVec()
            val a = Math.sqrt((0..2).sumOf { (w2[it] - w1[it]) * (w2[it] - w1[it]) }) / dt / dt
            s += a * a; n++
        }
        return Math.toDegrees(Math.sqrt(s / n))
    }

    fun run(p: Stabilizer.Params, runMode: Boolean): Pair<Double, Double> {
        val st = Stabilizer(p); st.intr = k
        val dt = 1 / 60.0
        val real = ArrayList<Quat>(); val out = ArrayList<Quat>()
        for (i in 0 until 600) {
            val m = motion(i * dt, runMode)
            val q = Quat.fromRotVec(m[0], m[1], 0.0) * Quat.fromRotVec(0.0, 0.0, m[2])
            real += q; out += st.update((i * dt * 1e9).toLong(), q)
        }
        return jerk(real.drop(60), dt) to jerk(out.drop(60), dt)
    }

    @Test
    fun walkingAndRunningAreCalmed() {
        // Same numbers as Presets.strength "Стандарт" and "Ходьба / бег".
        val std = Stabilizer.Params(8.0, 0.5, 0.1, 0.3, minCrop = 1.03, maxCrop = 1.20)
        val run = Stabilizer.Params(15.0, 0.7, 0.1, 0.3, minCrop = 1.03, maxCrop = 1.40)
        val (wIn, wOut) = run(std, false)
        val (rIn, rOut) = run(run, true)
        assertTrue("walking: out $wOut vs in $wIn", wOut < 0.45 * wIn)
        assertTrue("running: out $rOut vs in $rIn", rOut < 0.85 * rIn) // was 1.18-1.42 x (worse than no stabilization) before the soft wall
    }
}
