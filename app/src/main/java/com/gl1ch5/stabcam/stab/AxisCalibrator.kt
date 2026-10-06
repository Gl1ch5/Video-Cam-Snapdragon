package com.gl1ch5.stabcam.stab

import kotlin.math.sqrt

/**
 * Finds how the device gyro axes map to the camera-image frame by matching measured image motion with gyro rotation.
 *
 * Camera-image frame (sensor): x right, y down, z forward. For a body-frame rotation rate ω a scene point moves by
 *   dx = -fx·ω_y·dt,  dy = +fy·ω_x·dt   (centre of the image, small angles).
 * Only x/y can be seen in a translation; the z sign follows from the mapping being a proper rotation (det = +1).
 * Pure math.
 */
object AxisCalibrator {
    class Candidate(val axes: List<String>, val score: Double)
    class Result(val best: Candidate, val second: Candidate, val current: Candidate?) {
        val confident get() = best.score >= 0.45 && best.score - second.score >= 0.15
    }

    private val PERMS = listOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2), intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0))

    private fun parity(p: IntArray): Int {
        var inv = 0
        for (a in 0..2) for (b in a + 1..2) if (p[a] > p[b]) inv++
        return if (inv % 2 == 0) 1 else -1
    }

    fun spec(p: IntArray, s: IntArray): List<String> = (0..2).map { (if (s[it] < 0) "-" else "") + "xyz"[p[it]] }

    /**
     * [flows] image motion per interval in sensor pixels (mx, my); [disp] the gyro rotation integrated over the same
     * interval in the device frame (rad, x y z); [fx], [fy] focal lengths in pixels.
     */
    fun solve(flows: List<DoubleArray>, disp: List<DoubleArray>, fx: Double, fy: Double, current: List<String> = listOf("-y", "-x", "-z")): Result {
        val all = ArrayList<Candidate>()
        for (p in PERMS) for (si in intArrayOf(-1, 1)) for (sj in intArrayOf(-1, 1)) {
            val sz = parity(p) * si * sj
            val s = intArrayOf(si, sj, sz)
            all += Candidate(spec(p, s), score(flows, disp, fx, fy, p, s))
        }
        all.sortByDescending { it.score }
        val cur = all.firstOrNull { it.axes == current }
        return Result(all[0], all[1], cur)
    }

    private fun score(flows: List<DoubleArray>, disp: List<DoubleArray>, fx: Double, fy: Double, p: IntArray, s: IntArray): Double {
        var dot = 0.0; var nm = 0.0; var np = 0.0
        for (i in flows.indices) {
            val w0 = s[0] * disp[i][p[0]] // about camera x
            val w1 = s[1] * disp[i][p[1]] // about camera y
            val px = -fx * w1
            val py = fy * w0
            dot += flows[i][0] * px + flows[i][1] * py
            nm += flows[i][0] * flows[i][0] + flows[i][1] * flows[i][1]
            np += px * px + py * py
        }
        return if (nm < 1e-9 || np < 1e-9) 0.0 else dot / sqrt(nm * np)
    }

    /** Sub-pixel global shift (content moved by +s from [prev] to [cur]) of two w×h gray images, ±[r] px search. Null if flat. */
    fun shift(prev: ByteArray, cur: ByteArray, w: Int, h: Int, r: Int = 8): DoubleArray? {
        val m = r + 2
        val ssd = Array(2 * r + 1) { DoubleArray(2 * r + 1) }
        var best = Double.MAX_VALUE; var bx = 0; var by = 0
        var mean = 0.0
        for (dy in -r..r) for (dx in -r..r) {
            var s = 0.0
            var y = m
            while (y < h - m) {
                var x = m
                while (x < w - m) {
                    val d = (cur[y * w + x].toInt() and 255) - (prev[(y - dy) * w + (x - dx)].toInt() and 255)
                    s += d * d
                    x += 2
                }
                y += 2
            }
            ssd[dy + r][dx + r] = s
            mean += s
            if (s < best) { best = s; bx = dx; by = dy }
        }
        mean /= (2 * r + 1) * (2 * r + 1)
        if (mean < 1e-6 || best > 0.6 * mean) return null // no texture or no clear minimum
        fun sub(a: Double, b: Double, c: Double): Double { val d = a - 2 * b + c; return if (d <= 1e-9) 0.0 else 0.5 * (a - c) / d }
        val ox = if (bx > -r && bx < r) sub(ssd[by + r][bx - 1 + r], ssd[by + r][bx + r], ssd[by + r][bx + 1 + r]) else 0.0
        val oy = if (by > -r && by < r) sub(ssd[by - 1 + r][bx + r], ssd[by + r][bx + r], ssd[by + 1 + r][bx + r]) else 0.0
        return doubleArrayOf(bx + ox, by + oy)
    }
}
