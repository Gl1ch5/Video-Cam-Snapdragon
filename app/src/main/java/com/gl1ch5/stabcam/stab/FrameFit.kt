package com.gl1ch5.stabcam.stab

/**
 * Exact "does the rotated, zoomed frame still lie inside the sensor image" test (no black edges).
 * Checks the four corners and four edge midpoints of the output frame, with a small inset that also covers the
 * per-row rolling-shutter differences. Pure math.
 */
object FrameFit {
    class Intr(val fx: Double, val fy: Double, val cx: Double, val cy: Double, val w: Double, val h: Double) {
        fun scaled(z: Double) = Intr(fx * z, fy * z, w / 2 + (cx - w / 2) * z, h / 2 + (cy - h / 2) * z, w, h)
    }

    /**
     * Intrinsics [fx, fy, cx, cy] in pixels of an output frame [outW]x[outH] that is a centre crop (to its aspect) of the
     * [arrW]x[arrH] active array, from a raw Camera2 calibration [cal] = (fx, fy, cx, cy, s) in array pixels.
     */
    fun outputIntrinsics(cal: FloatArray, arrW: Float, arrH: Float, outW: Int, outH: Int): FloatArray {
        var cropW = arrW
        var cropH = arrW * outH / outW
        if (cropH > arrH) { cropH = arrH; cropW = arrH * outW / outH }
        val offX = (arrW - cropW) / 2; val offY = (arrH - cropH) / 2
        val s = outW / cropW
        return floatArrayOf(cal[0] * s, cal[1] * s, (cal[2] - offX) * s, (cal[3] - offY) * s)
    }

    /** [r] is a row-major 3x3 rotation mapping output-camera rays to source-camera rays, [z] the zoom-in factor. */
    fun fits(r: DoubleArray, z: Double, k: Intr, inset: Double = 0.015): Boolean {
        val xs = doubleArrayOf(0.0, k.w, 0.0, k.w, k.w / 2, k.w / 2, 0.0, k.w)
        val ys = doubleArrayOf(0.0, 0.0, k.h, k.h, 0.0, k.h, k.h / 2, k.h / 2)
        for (i in xs.indices) {
            val dx = (xs[i] - k.cx) / k.fx / z
            val dy = (ys[i] - k.cy) / k.fy / z
            val sx = r[0] * dx + r[1] * dy + r[2]
            val sy = r[3] * dx + r[4] * dy + r[5]
            val sz = r[6] * dx + r[7] * dy + r[8]
            if (sz <= 0.05) return false
            val qx = sx / sz * k.fx + k.cx
            val qy = sy / sz * k.fy + k.cy
            if (qx < inset * k.w || qx > k.w * (1 - inset) || qy < inset * k.h || qy > k.h * (1 - inset)) return false
        }
        return true
    }

    /** Smallest zoom (1..[hi]) at which rotation [r] fits; [hi] if even that does not. */
    fun minZoom(r: DoubleArray, k: Intr, hi: Double = 2.5): Double {
        if (fits(r, 1.0, k)) return 1.0
        if (!fits(r, hi, k)) return hi
        var lo = 1.0; var up = hi
        repeat(16) { val m = (lo + up) / 2; if (fits(r, m, k)) up = m else lo = m }
        return up
    }

    /** Largest factor in [0,1] such that the rotation vector [rv] scaled by it still fits at zoom [z]. */
    fun scaleToFit(rv: DoubleArray, z: Double, k: Intr): Double {
        fun m(f: Double) = Quat.fromRotVec(rv[0] * f, rv[1] * f, rv[2] * f).toMatrix()
        if (fits(m(1.0), z, k)) return 1.0
        var lo = 0.0; var hi = 1.0
        repeat(16) { val mid = (lo + hi) / 2; if (fits(m(mid), z, k)) lo = mid else hi = mid }
        return lo
    }
}
