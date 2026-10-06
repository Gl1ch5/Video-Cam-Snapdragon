package com.gl1ch5.stabcam.stab

import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Unit quaternion (w, x, y, z) used as body→world rotation. Pure math, no Android dependencies. */
class Quat(val w: Double, val x: Double, val y: Double, val z: Double) {

    operator fun times(o: Quat) = Quat(
        w * o.w - x * o.x - y * o.y - z * o.z,
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
    )

    fun conj() = Quat(w, -x, -y, -z)

    /** Rotates vector [v] by this quaternion. */
    fun rotate(v: DoubleArray): DoubleArray {
        val m = toMatrix()
        return doubleArrayOf(m[0] * v[0] + m[1] * v[1] + m[2] * v[2], m[3] * v[0] + m[4] * v[1] + m[5] * v[2], m[6] * v[0] + m[7] * v[1] + m[8] * v[2])
    }

    fun normalized(): Quat {
        val n = sqrt(w * w + x * x + y * y + z * z)
        return if (n < 1e-12) IDENTITY else Quat(w / n, x / n, y / n, z / n)
    }

    /** Rotation angle in radians, 0..π. */
    fun angle(): Double = 2.0 * acos(abs(w).coerceAtMost(1.0))

    /** Rotation vector (axis * angle, radians) as [x, y, z]. */
    fun toRotVec(): DoubleArray {
        val sgn = if (w < 0) -1.0 else 1.0
        val s = sqrt(x * x + y * y + z * z)
        if (s < 1e-12) return doubleArrayOf(0.0, 0.0, 0.0)
        val k = 2.0 * kotlin.math.atan2(s, w * sgn) / s * sgn
        return doubleArrayOf(x * k, y * k, z * k)
    }

    /** Row-major 3x3 rotation matrix. */
    fun toMatrix(): DoubleArray {
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        return doubleArrayOf(
            1 - 2 * (yy + zz), 2 * (xy - wz), 2 * (xz + wy),
            2 * (xy + wz), 1 - 2 * (xx + zz), 2 * (yz - wx),
            2 * (xz - wy), 2 * (yz + wx), 1 - 2 * (xx + yy),
        )
    }

    companion object {
        val IDENTITY = Quat(1.0, 0.0, 0.0, 0.0)

        /** Rotation by the rotation vector (axis * angle), radians. */
        fun fromRotVec(rx: Double, ry: Double, rz: Double): Quat {
            val a = sqrt(rx * rx + ry * ry + rz * rz)
            if (a < 1e-12) return Quat(1.0, rx / 2, ry / 2, rz / 2).normalized()
            val s = sin(a / 2) / a
            return Quat(cos(a / 2), rx * s, ry * s, rz * s)
        }

        /** Spherical interpolation from [a] (t=0) to [b] (t=1), shortest path. */
        fun slerp(a: Quat, b: Quat, t: Double): Quat {
            var dot = a.w * b.w + a.x * b.x + a.y * b.y + a.z * b.z
            var bw = b.w; var bx = b.x; var by = b.y; var bz = b.z
            if (dot < 0) { dot = -dot; bw = -bw; bx = -bx; by = -by; bz = -bz }
            if (dot > 0.9995) {
                return Quat(a.w + t * (bw - a.w), a.x + t * (bx - a.x), a.y + t * (by - a.y), a.z + t * (bz - a.z)).normalized()
            }
            val theta = acos(dot)
            val sa = sin((1 - t) * theta) / sin(theta)
            val sb = sin(t * theta) / sin(theta)
            return Quat(a.w * sa + bw * sb, a.x * sa + bx * sb, a.y * sa + by * sb, a.z * sa + bz * sb)
        }
    }
}
