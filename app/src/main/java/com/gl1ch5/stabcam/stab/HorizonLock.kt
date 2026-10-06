package com.gl1ch5.stabcam.stab

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.round
import kotlin.math.sqrt

/** Horizon levelling from the gravity ("up") direction in the camera-image frame. Pure math. */
object HorizonLock {
    /** Image-up direction in the (unrotated, landscape) sensor frame: the sensor's left edge is the top of an upright portrait picture. */
    private fun dev(u: DoubleArray): Double {
        val phi = atan2(u[1], -u[0])
        return phi - round(phi / (PI / 2)) * (PI / 2) // deviation from the nearest 90°: landscape holds count as level
    }

    /**
     * Rotation about the optical axis (radians) to apply to the camera body so the horizon is level.
     * The sign is chosen by trying both: the one that leaves the smaller residual wins, so it does not depend on axis conventions.
     * Returns 0 when the camera looks (nearly) straight up or down.
     */
    fun correction(up: DoubleArray): Double {
        val n = sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2])
        if (n < 1e-6 || abs(up[2]) / n > 0.95) return 0.0
        val d = dev(up)
        val q = Quat.fromRotVec(0.0, 0.0, d)
        val after = abs(dev(q.conj().rotate(up)))
        return if (after < abs(d)) d else -d
    }
}
