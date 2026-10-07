package com.gl1ch5.stabcam.stab

import kotlin.math.exp

/**
 * Real-time (causal) orientation smoother.
 *
 * Constant-velocity model: the "virtual camera" advances with the slowly varying (intentional) angular velocity
 * of the real camera, so steady pans are followed without lag, and is pulled toward the real orientation with a
 * low-pass, which removes hand shake. The pull tightens as the offset approaches the crop margin
 * ([Params.maxAngleDeg]); the offset is hard-limited to it.
 */
class Stabilizer(private val p: Params) {

    class Params(
        val maxAngleDeg: Double = 4.0,
        /** Pull time constant when centred / when at the margin. */
        val tauMaxSec: Double = 0.35,
        val tauMinSec: Double = 0.04,
        /** Time constant of the intentional-motion velocity estimate. */
        val velTauSec: Double = 0.25,
        /** Adaptive crop: tan(half horizontal FOV) of the output frame, and the crop range. */
        val tanHalfFov: Double = 0.705,
        val minCrop: Double = 1.03,
        val maxCrop: Double = 1.12,
        val attackSec: Double = 0.15,
        val releaseSec: Double = 1.5,
        /** Exact fit test (preferred over the tan() approximation when set). */
        val intr: FrameFit.Intr? = null,
        /** Horizon lock range in degrees (0 = off). */
        val horizonDeg: Double = 0.0,
    )

    private var qv: Quat? = null
    private var prevReal: Quat? = null
    private var prevT = 0L
    private val vel = DoubleArray(3)
    @Volatile var intr: FrameFit.Intr? = p.intr
    private var horizon = 0.0
    /** Current horizon-lock roll applied, degrees (diagnostics). */
    val horizonNow: Double get() = horizon
    private var peakZoom = 1.0

    /** Current zoom-in factor: only as large as the recent shake needs, so the picture stays as sharp as possible. */
    var crop = (p.minCrop + p.maxCrop) / 2
        private set

    /** Last correction angle (deg), for stats. */
    var lastCorrectionDeg = 0.0
        private set

    /** Last virtual orientation (for tests and diagnostics). */
    fun virtualDebug(): Quat = qv ?: Quat.IDENTITY

    fun reset() { qv = null; prevReal = null; vel.fill(0.0); peakZoom = 1.0; horizon = 0.0 }

    /** Feeds the real orientation at frame centre time [tNs]; returns the virtual camera orientation. */
    fun update(tNs: Long, real: Quat, upImg: DoubleArray? = null): Quat {
        val cur = qv
        if (cur == null) {
            qv = real; prevReal = real; prevT = tNs
            lastCorrectionDeg = 0.0
            return real
        }
        val dt = ((tNs - prevT) / 1e9).coerceIn(1e-4, 0.25)

        val d = (prevReal!!.conj() * real).toRotVec()
        val a = 1.0 - exp(-dt / p.velTauSec)
        for (i in 0..2) vel[i] += (d[i] / dt - vel[i]) * a
        val base = (cur * Quat.fromRotVec(vel[0] * dt, vel[1] * dt, vel[2] * dt)).normalized()

        val fit = intr
        val maxRad: Double
        val offAbs = Math.toRadians(p.maxAngleDeg)
        maxRad = if (fit != null) offAbs else minOf(offAbs, kotlin.math.atan((1.0 - 1.0 / crop) * p.tanHalfFov * 0.9))
        val tight = ((real.conj() * base).angle() / maxRad).coerceIn(0.0, 1.0)
        val tau = p.tauMaxSec + (p.tauMinSec - p.tauMaxSec) * tight * tight
        var v = Quat.slerp(base, real, 1.0 - exp(-dt / tau))

        // Horizon lock: counter-rotate about the optical axis toward level, smoothed, limited to the configured range.
        // The roll is applied to the OUTPUT only; the smoothing state stays roll-free, otherwise it would pile up every frame.
        var rollQ = Quat.IDENTITY
        var rolled = false
        if (p.horizonDeg > 0 && upImg != null) {
            val target = Math.toDegrees(HorizonLock.correction(upImg)).coerceIn(-p.horizonDeg, p.horizonDeg)
            horizon += (target - horizon) * (1.0 - exp(-dt / 0.5))
            rollQ = Quat.fromRotVec(0.0, 0.0, Math.toRadians(horizon)); rolled = true
        }

        var out = if (rolled) (v * rollQ).normalized() else v
        var offQ = real.conj() * out
        if (fit != null) {
            // Soft wall: how much of the margin this offset uses along its own direction, compressed smoothly past a knee
            // (tanh), so the virtual camera glides into the limit instead of hitting it (a kink there is a visible jerk).
            val rv = offQ.toRotVec()
            val len = Math.sqrt(rv[0] * rv[0] + rv[1] * rv[1] + rv[2] * rv[2])
            if (len > 1e-9) {
                val probe = 8.0
                val lim = FrameFit.scaleToFit(DoubleArray(3) { rv[it] * probe }, crop, fit) * probe // offset scale that just fits
                val r = 1.0 / lim.coerceAtLeast(1e-6)
                val knee = SOFT_KNEE
                val r2 = if (r <= knee) r else knee + (1 - knee) * kotlin.math.tanh((r - knee) / (1 - knee))
                val k = (r2 / r).coerceAtMost(1.0) // r2 < 1 always, so the result fits
                if (k < 1.0) { out = (real * Quat.fromRotVec(rv[0] * k, rv[1] * k, rv[2] * k)).normalized(); offQ = real.conj() * out }
            }
        } else {
            val off = offQ.angle()
            if (off > maxRad) { out = Quat.slerp(real, out, maxRad / off); offQ = real.conj() * out }
        }
        v = if (rolled) (out * rollQ.conj()).normalized() else out

        qv = v
        prevReal = real
        prevT = tNs
        lastCorrectionDeg = Math.toDegrees(offQ.angle())

        // Peak-hold of the zoom this excursion needs: fast attack, slow release.
        val needZ = if (fit != null) FrameFit.minZoom(offQ.toMatrix(), fit) * 1.03
        else 1.0 / (1.0 - kotlin.math.tan(Math.toRadians(lastCorrectionDeg * 1.2 + 0.6)) / p.tanHalfFov)
        peakZoom = if (needZ > peakZoom) needZ else 1.0 + (peakZoom - 1.0) * exp(-dt / p.releaseSec)
        val target = peakZoom.coerceIn(p.minCrop, p.maxCrop)
        val rate = if (target > crop) p.attackSec else p.releaseSec
        crop += (target - crop) * (1.0 - exp(-dt / rate))
        return out
    }

    companion object {
        const val ROWS = 16
        /** Fraction of the crop margin used linearly before the soft wall starts compressing. */
        const val SOFT_KNEE = 0.6

        /**
         * Per-row source rotations (column-major mat3 each, [ROWS] of them) for the GL shader:
         * R_row = realAt(row time)^-1 · virtual.
         */
        fun rowMatrices(virtual: Quat, firstRowNs: Long, readoutNs: Long, exposureNs: Long, realAt: (Long) -> Quat, out: FloatArray) {
            for (k in 0 until ROWS) {
                val t = firstRowNs + exposureNs / 2 + readoutNs * k / (ROWS - 1)
                val m = (realAt(t).conj() * virtual).toMatrix() // row-major
                for (r in 0..2) for (c in 0..2) out[k * 9 + c * 3 + r] = m[r * 3 + c].toFloat()
            }
        }

        fun identityRows(out: FloatArray) {
            for (k in 0 until out.size / 9) for (i in 0 until 9) out[k * 9 + i] = if (i % 4 == 0) 1f else 0f
        }
    }
}
