package com.gl1ch5.stabcam.stab

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.gl1ch5.stabcam.util.Logger
import com.gl1ch5.stabcam.lut.Lut
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Camera → SurfaceTexture → GL warp (gyro-stabilised, rolling-shutter aware) → preview surface + MediaCodec.
 * Everything GL runs on one thread; [cameraSurface] is what Camera2 writes to.
 */
class StabPipeline(
    private val gyro: GyroTracker,
    private val width: Int,
    private val height: Int,
    /** fx, fy, cx, cy in output pixels (zoom 1). */
    private val k: FloatArray,
    private val params: Stabilizer.Params,
    private val crop: Float,
    private val readoutDefaultNs: Long,
    /** Output sharpening 0..1 (limited unsharp mask) and bicubic (Catmull-Rom) resampling. */
    private val sharpen: Float = 0.35f,
    private val bicubic: Boolean = true,
    /** Temporal denoise strength 0..1 (0 = off) and noise tolerance (luma difference treated as noise). */
    private val denoise: Float = 0.5f,
    private val denoiseSigma: Float = 0.04f,
    /** Nominal capture rate and a thermal-status probe, for the cadence diagnostics in the log. */
    private val fps: Int = 60,
    private val thermal: () -> Int = { -1 },
    /** Shifts gyro lookup relative to frame timestamps (ms); fixes residual micro-jitter from clock offset. */
    timeOffsetMs: Double = 0.0,
    /** 10-bit HLG output (RGB10_A2 surfaces); falls back to 8-bit if EGL cannot do it. */
    hdr: Boolean = false,
    /** Scale the denoise tolerance with ISO (more gain = more noise). */
    private val denoiseAuto: Boolean = true,
) {
    private val offsetNs = (timeOffsetMs * 1e6).toLong()
    private val wantHdr = hdr
    var is10bit = false
        private set
    private val thread = HandlerThread("stab-gl", android.os.Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
    // A failure inside any GL task must never kill the app: log it and keep going.
    private val handler = object : Handler(thread.looper) {
        override fun dispatchMessage(msg: android.os.Message) {
            try { super.dispatchMessage(msg) } catch (t: Throwable) { Logger.e(TAG, "GL поток", t) }
        }
    }
    private lateinit var egl: EglCore
    private lateinit var st: SurfaceTexture
    lateinit var cameraSurface: Surface
        private set
    private var tex = 0
    private var program = 0 // warp reading the camera (external) texture
    private var program2d = 0 // warp reading the denoised 2D texture
    private var dnProgram = 0
    private val loc = HashMap<String, Int>()
    private val loc2d = HashMap<String, Int>()
    private val locDn = HashMap<String, Int>()
    // 3D LUT (always bound; a 2x2x2 identity when no look is selected)
    private var lutTex = 0
    private var lutSize = 2f
    @Volatile private var lutAmt = 0f
    private val fbo = IntArray(2)
    private val fboTex = IntArray(2)
    private var histIdx = 0
    private var hasHist = false
    private var prevQ: Quat? = null
    private val relRows = FloatArray(9)
    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }
    private var dummy: EGLSurface? = null
    private var previewSurf: EGLSurface? = null
    private var previewSize = 1 to 1
    private var encSurf: EGLSurface? = null
    private var rec: StabRecorder? = null
    private val stabilizer = Stabilizer(params)
    private val rows = FloatArray(Stabilizer.ROWS * 9)
    private val stm = FloatArray(16)

    @Volatile var enabled = true
    private val kBase = k.copyOf()
    @Volatile private var kCur = k.copyOf()
    @Volatile private var iso = 200
    @Volatile private var degraded = false
    /** Called (on the GL thread) when thermal throttling switches the heavy extras off/on. */
    @Volatile var onDegraded: ((Boolean) -> Unit)? = null
    /** POST mode: frames pass through untouched (no warp/denoise/sharpen/LUT); the frame timestamps are logged. */
    @Volatile var rawMode = false
    private var frameTs = LongArray(4096)
    private var frameExp = LongArray(4096)
    private var frameIso = IntArray(4096)
    private var frameN = 0
    @Volatile var lastBaseTs = -1L
        private set
    /** Preview orientation variant 1..4 (see shader); encoder output is unaffected. */
    @Volatile var previewRot = 1
    @Volatile private var zoom = 1f
    @Volatile private var exposureNs = 8_000_000L
    @Volatile private var readoutNs = readoutDefaultNs
    val readout: Long get() = readoutNs
    @Volatile private var released = false

    // axis calibration (a few seconds of shaking, low-res luma frames)
    private class Calib(val cb: (AxisCalibrator.Result?, String) -> Unit) {
        var prev: ByteArray? = null; var prevTs = 0L; var frames = 0
        val flows = ArrayList<DoubleArray>(); val disp = ArrayList<DoubleArray>()
        val lagDisp = ArrayList<Array<DoubleArray>>() // displacement for gyro windows shifted by -10..+10 ms
    }
    private var calib: Calib? = null
    private var grayProg = 0
    private var grayFbo = 0
    private var grayTex = 0
    private val cw = 160
    private val ch = 90
    private val grayBuf = ByteBuffer.allocateDirect(cw * ch * 4)
    private val statW = DoubleArray(3)
    private var statWn = 0

    // stats
    private var frames = 0L
    private var statT = 0L
    private var statN = 0
    private var statRenderNs = 0L
    private var statCorr = 0.0
    private var statMaxCorr = 0.0
    private var lastTs = 0L
    private var lastTsRate = 0L
    private var statDrops = 0
    private var statMaxDt = 0L
    private var statDtSum = 0L
    private var statDtN = 0

    init {
        val latch = CountDownLatch(1)
        var err: Throwable? = null
        handler.post {
            try { setup() } catch (t: Throwable) { err = t; Logger.e(TAG, "GL setup", t) }
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        err?.let { throw RuntimeException("GL setup failed: ${it.message}", it) }
    }

    /** Shake the phone for ~4 s: matches image motion against the gyro and reports the best axis mapping. */
    fun calibrateAxes(cb: (AxisCalibrator.Result?, String) -> Unit) { handler.post { calib = Calib(cb) } }

    fun setIso(v: Int) { if (v > 0) iso = v }

    /** Per-frame intrinsics from the HAL (focus breathing etc.); implausible values are ignored, the rest is smoothed. */
    fun setIntrinsics(n: FloatArray) {
        if (n.size < 4) return
        for (i in 0..1) if (Math.abs(n[i] - kBase[i]) / kBase[i] > 0.12f) return
        if (Math.abs(n[2] - kBase[2]) > width * 0.05f || Math.abs(n[3] - kBase[3]) > height * 0.05f) return
        val c = kCur
        kCur = FloatArray(4) { c[it] * 0.6f + n[it] * 0.4f }
        updateIntr()
    }

    private fun updateIntr() {
        val z = zoom.toDouble(); val c = kCur
        stabilizer.intr = FrameFit.Intr(c[0].toDouble(), c[1].toDouble(), c[2].toDouble(), c[3].toDouble(), width.toDouble(), height.toDouble()).scaled(z)
    }

    private fun sigmaEff(): Float =
        if (!denoiseAuto) denoiseSigma else (denoiseSigma * Math.sqrt(iso.coerceIn(50, 3200) / 200.0)).toFloat().coerceIn(0.012f, 0.12f)

    fun setExposure(ns: Long) { if (ns > 0) exposureNs = ns }
    fun setReadout(ns: Long) { if (ns > 0) readoutNs = ns }
    fun setZoom(z: Float) {
        if (z != zoom) hasHist = false
        zoom = z
        updateIntr()
        // Intrinsics are known for the main camera only: no warping on the ultrawide range.
        enabled = z >= 0.95f
    }

    private fun setup() {
        egl = EglCore(wantHdr)
        is10bit = wantHdr && egl.is10bit
        dummy = egl.createPbuffer().also { egl.makeCurrent(it) }
        egl.noSwapInterval()
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        tex = ids[0]
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        st = SurfaceTexture(tex)
        st.setDefaultBufferSize(width, height)
        st.setOnFrameAvailableListener({ if (!released) handler.post { drawFrame() } }, handler)
        cameraSurface = Surface(st)
        val lt = IntArray(1)
        GLES20.glGenTextures(1, lt, 0)
        lutTex = lt[0]
        uploadLut(2, ByteArray(2 * 2 * 2 * 3) { i -> val v = i / 3; val ch = i % 3; (if ((v shr ch) and 1 == 1) 255 else 0).toByte() })
        program = Shaders.warp(true)
        val names = listOf("uTex", "uSize", "uK", "uZoom", "uPreview", "uSharp", "uBicubic", "uHdr", "uLut", "uLutAmt", "uLutN", "uR")
        for (n in names) loc[n] = GLES20.glGetUniformLocation(program, n)
        if (denoise > 0f) {
            program2d = Shaders.warp(false)
            for (n in names) loc2d[n] = GLES20.glGetUniformLocation(program2d, n)
            dnProgram = Shaders.denoise()
            for (n in listOf("uCur", "uHist", "uSize", "uK", "uRel", "uStr", "uSigma", "uHasHist", "uSp")) locDn[n] = GLES20.glGetUniformLocation(dnProgram, n)
            val tex2 = IntArray(2)
            GLES20.glGenTextures(2, tex2, 0)
            GLES30.glGenFramebuffers(2, fbo, 0)
            for (i in 0..1) {
                fboTex[i] = tex2[i]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[i])
                if (is10bit) GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGB10_A2, width, height, 0, GLES20.GL_RGBA, GLES30.GL_UNSIGNED_INT_2_10_10_10_REV, null)
                else GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[i])
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex[i], 0)
                check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete" }
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            Logger.i(TAG, "Шумоподавление по времени: сила $denoise, допуск шума $denoiseSigma")
        }
        Logger.i(TAG, "GL готов: ${GLES20.glGetString(GLES20.GL_RENDERER)}, ${GLES20.glGetString(GLES20.GL_VERSION)}, буфер ${width}x$height")
    }

    private fun uploadLut(size: Int, rgb: ByteArray) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8, size, size, size, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(rgb))
        for (p in intArrayOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES20.GL_LINEAR)
        for (p in intArrayOf(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R)) GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES20.GL_CLAMP_TO_EDGE)
        lutSize = size.toFloat()
    }

    /** Applies [lut] (null = off) with [strength] 0..1 from the next frame. In HLG it grades the HLG signal. */
    fun setLut(lut: Lut?, strength: Float) {
        handler.post {
            if (lut == null) { lutAmt = 0f; return@post }
            runCatching {
                egl.makeCurrent(dummy!!)
                uploadLut(lut.size, lut.rgb)
                lutAmt = strength.coerceIn(0f, 1f)
                Logger.i(TAG, "LUT: ${lut.name} (${lut.size}³), сила $lutAmt")
            }.onFailure { Logger.e(TAG, "LUT", it) }
        }
    }

    fun setPreview(s: Surface?) {
        handler.post {
            previewSurf?.let { egl.destroySurface(it) }
            previewSurf = null
            if (s != null && s.isValid) {
                runCatching {
                    previewSurf = egl.createWindowSurface(s).also {
                        egl.makeCurrent(it); egl.noSwapInterval()
                        previewSize = egl.size(it)
                    }
                    Logger.i(TAG, "Превью: ${previewSize.first}x${previewSize.second}")
                }.onFailure { Logger.e(TAG, "preview surface", it) }
            }
            egl.makeCurrent(dummy!!)
        }
    }

    fun startRecording(rec: StabRecorder) {
        val latch = CountDownLatch(1)
        handler.post {
            runCatching {
                encSurf = egl.createWindowSurface(rec.inputSurface, hlg = is10bit)
                this.rec = rec
                frameN = 0
                stabilizer.reset()
            }.onFailure { Logger.e(TAG, "encoder surface", it) }
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
    }

    /** Frame timestamps (ns, camera clock) and exposure times of the last recording, valid after [stopRecording]. */
    class FrameLog(val ts: LongArray, val exp: LongArray, val iso: IntArray, val n: Int)
    fun frameLog() = FrameLog(frameTs, frameExp, frameIso, frameN)

    /** Stops feeding the encoder and finalises the file. Blocks. */
    fun stopRecording(): Boolean {
        var ok = false
        val latch = CountDownLatch(1)
        handler.post {
            val r = rec
            rec = null
            encSurf?.let { runCatching { egl.destroySurface(it) } }
            encSurf = null
            egl.makeCurrent(dummy!!)
            lastBaseTs = r?.baseTs ?: -1L
            ok = r?.finish() ?: false
            latch.countDown()
        }
        latch.await(12, TimeUnit.SECONDS)
        return ok
    }

    private fun drawFrame() {
        if (released) return
        val t0 = System.nanoTime()
        try {
            egl.makeCurrent(dummy!!)
            st.updateTexImage()
        } catch (e: Exception) {
            Logger.w(TAG, "updateTexImage", e); return
        }
        val ts = st.timestamp
        if (lastTs != 0L) {
            val dtf = ts - lastTs
            statDtSum += dtf; statDtN++
            if (dtf > statMaxDt) statMaxDt = dtf
            if (dtf > 1_500_000_000L / fps) statDrops++ // a frame (or more) missing
        }
        lastTs = ts
        st.getTransformMatrix(stm)
        frames++
        // per-axis angular rate for the log (device frame, rms)
        if (lastTsRate != 0L && ts > lastTsRate) {
            val d = gyro.integrateRaw(lastTsRate, ts); val dtr = (ts - lastTsRate) / 1e9
            for (a in 0..2) statW[a] += (d[a] / dtr) * (d[a] / dtr)
            statWn++
        }
        lastTsRate = ts
        calib?.let { calibStep(it, ts) }

        // Orientation at the frame centre, smoothed; per-row matrices for the rolling shutter.
        val readout = readoutNs
        val exposure = exposureNs
        if (enabled && !rawMode) {
            val centre = ts + offsetNs + exposure / 2 + readout / 2
            val qr = gyro.orientationAt(centre)
            val qv = stabilizer.update(centre, qr, gyro.upImg())
            Stabilizer.rowMatrices(qv, ts + offsetNs, readout, exposure, { gyro.orientationAt(it) }, rows)
        } else {
            Stabilizer.identityRows(rows)
            stabilizer.reset()
        }

        // Temporal denoise in the sensor frame (history reprojected by the gyro rotation between frames).
        var useDn = false
        if (denoise > 0f && !rawMode && !degraded) {
            val qNow = gyro.orientationAt(ts + offsetNs + exposure / 2 + readout / 2)
            useDn = runDenoise(qNow)
            prevQ = qNow
        }
        previewSurf?.let { s ->
            egl.makeCurrent(s)
            draw(previewSize.first, previewSize.second, true, useDn)
            egl.swap(s)
        }
        val r = rec
        val es = encSurf
        if (r != null && es != null) {
            egl.makeCurrent(es)
            r.onFrame(ts)
            if (rawMode) {
                if (frameN == frameTs.size) { frameTs = frameTs.copyOf(frameN * 2); frameExp = frameExp.copyOf(frameN * 2); frameIso = frameIso.copyOf(frameN * 2) }
                frameTs[frameN] = ts; frameExp[frameN] = exposure; frameIso[frameN] = iso; frameN++
            }
            draw(width, height, false, useDn)
            egl.setPresentationTime(es, r.relative(ts))
            egl.swap(es)
        }

        // stats every 2 s
        statN++
        statRenderNs += System.nanoTime() - t0
        statCorr += stabilizer.lastCorrectionDeg
        statMaxCorr = maxOf(statMaxCorr, stabilizer.lastCorrectionDeg)
        val now = System.nanoTime()
        if (statT == 0L) statT = now
        if (now - statT > 2_000_000_000L) {
            Logger.i(TAG, "%.1f fps, кадр %.1f мс, интервал ср %.1f макс %.1f мс (норма %.1f), пропусков %d, поправка ср %.2f° макс %.2f° кроп %.3f, гиро %s, тепло %d, стаб %s".format(
                statN * 1e9 / (now - statT), statRenderNs / statN / 1e6,
                if (statDtN > 0) statDtSum / statDtN / 1e6 else 0.0, statMaxDt / 1e6, 1000.0 / fps, statDrops,
                statCorr / statN, statMaxCorr, stabilizer.crop,
                if (gyro.latestTimeNs() - ts > -50_000_000L) "ok" else "ОТСТАЁТ", thermal(), if (enabled) "вкл" else "выкл"))
            statT = now; statN = 0; statRenderNs = 0; statCorr = 0.0; statMaxCorr = 0.0
            if (statWn > 0) Logger.i(TAG, "Угловая скорость rms (°/с, оси устройства): x=%.1f y=%.1f z=%.1f".format(
                Math.toDegrees(Math.sqrt(statW[0] / statWn)), Math.toDegrees(Math.sqrt(statW[1] / statWn)), Math.toDegrees(Math.sqrt(statW[2] / statWn))))
            statW.fill(0.0); statWn = 0
            val th = thermal()
            if (!degraded && th >= 3) { degraded = true; hasHist = false; Logger.w(TAG, "Нагрев (статус $th): отключаю шумоподавление и бикубику"); onDegraded?.invoke(true) }
            else if (degraded && th in 0..1) { degraded = false; Logger.i(TAG, "Нагрев спал: возвращаю шумоподавление и бикубику"); onDegraded?.invoke(false) }
            statDrops = 0; statMaxDt = 0; statDtSum = 0; statDtN = 0
        }
    }

    /** Renders the denoised current frame into the history FBO; returns false if it was skipped. */
    private fun runDenoise(qNow: Quat): Boolean {
        val cur = 1 - histIdx
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[cur])
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(dnProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(locDn["uCur"]!!, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[histIdx])
        GLES20.glUniform1i(locDn["uHist"]!!, 1)
        GLES20.glUniform2f(locDn["uSize"]!!, width.toFloat(), height.toFloat())
        val z = zoom
        GLES20.glUniform4f(locDn["uK"]!!, kCur[0] * z, kCur[1] * z, width / 2f + (kCur[2] - width / 2f) * z, height / 2f + (kCur[3] - height / 2f) * z)
        val pq = prevQ
        val have = hasHist && pq != null
        if (have) {
            val m = (pq!!.conj() * qNow).toMatrix() // current-frame ray -> previous-frame ray
            for (r in 0..2) for (c in 0..2) relRows[c * 3 + r] = m[r * 3 + c].toFloat()
        } else for (i in 0 until 9) relRows[i] = if (i % 4 == 0) 1f else 0f
        GLES30.glUniformMatrix3fv(locDn["uRel"]!!, 1, false, relRows, 0)
        GLES20.glUniform1f(locDn["uStr"]!!, denoise)
        GLES20.glUniform1f(locDn["uSigma"]!!, sigmaEff())
        GLES20.glUniform1f(locDn["uSp"]!!, minOf(0.8f, denoise * 0.7f))
        GLES20.glUniform1i(locDn["uHasHist"]!!, if (have) 1 else 0)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        histIdx = cur
        hasHist = true
        return true
    }

    private fun calibStep(c: Calib, ts: Long) {
        if (grayProg == 0) {
            grayProg = Shaders.gray()
            val t = IntArray(1); GLES20.glGenTextures(1, t, 0); grayTex = t[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, grayTex)
            GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, cw, ch, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0); grayFbo = f[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, grayFbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, grayTex, 0)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, grayFbo)
        GLES20.glViewport(0, 0, cw, ch)
        GLES20.glUseProgram(grayProg)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(grayProg, "uCur"), 0)
        GLES20.glEnableVertexAttribArray(0); GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        grayBuf.clear()
        GLES20.glReadPixels(0, 0, cw, ch, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, grayBuf)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        val cur = ByteArray(cw * ch) { grayBuf.get(it * 4) }
        c.frames++
        val prev = c.prev
        if (prev != null) {
            val s = AxisCalibrator.shift(prev, cur, cw, ch)
            val d = gyro.integrateRaw(c.prevTs, ts)
            if (s != null && (Math.abs(s[0]) + Math.abs(s[1])) > 0.25 && Math.abs(d[0]) + Math.abs(d[1]) + Math.abs(d[2]) > 2e-4) {
                // GL rows run bottom-up: flip the vertical flow into top-down sensor coordinates
                c.flows += doubleArrayOf(s[0] * width / cw, -s[1] * height / ch)
                c.disp += d
                c.lagDisp += Array(21) { li -> gyro.integrateRaw(c.prevTs + (li - 10) * 1_000_000L, ts + (li - 10) * 1_000_000L) }
            }
        }
        c.prev = cur; c.prevTs = ts
        if (c.frames >= 260) {
            calib = null
            if (c.flows.size < 25) c.cb(null, "Мало движения или нет деталей в кадре (${c.flows.size} измерений). Потрясите сильнее и наведите на сцену с текстурой.")
            else {
                val z = zoom
                val r = AxisCalibrator.solve(c.flows, c.disp, kCur[0] * z.toDouble(), kCur[1] * z.toDouble(), listOf("-y", "-x", "-z"))
                val byLag = (0 until 21).map { li -> c.lagDisp.map { it[li] } }
                val ls = AxisCalibrator.lagScores(c.flows, byLag, r.best.axes, kCur[0] * z.toDouble(), kCur[1] * z.toDouble())
                val bi = ls.indices.maxByOrNull { ls[it] } ?: 10
                r.lagMs = bi - 10; r.lagGain = ls[bi] - ls[10]
                Logger.i(TAG, "Калибровка времени: лучший сдвиг гироскопа ${r.lagMs} мс (совпадение ${"%.2f".format(ls[bi])}, при 0 мс ${"%.2f".format(ls[10])})")
                Logger.i(TAG, "Калибровка осей: лучший ${r.best.axes} (${"%.2f".format(r.best.score)}), второй ${r.second.axes} (${"%.2f".format(r.second.score)}), измерений ${c.flows.size}")
                c.cb(r, "")
            }
        }
    }

    private fun draw(w: Int, h: Int, preview: Boolean, denoised: Boolean) {
        val prog = if (denoised) program2d else program
        val l = if (denoised) loc2d else loc
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (denoised) GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[histIdx]) else GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(l["uTex"]!!, 0)
        GLES20.glUniform2f(l["uSize"]!!, width.toFloat(), height.toFloat())
        val z = zoom
        // Zoom about the image centre: focal lengths scale, principal point moves with it.
        val cx = width / 2f + (kCur[2] - width / 2f) * z
        val cy = height / 2f + (kCur[3] - height / 2f) * z
        GLES20.glUniform4f(l["uK"]!!, kCur[0] * z, kCur[1] * z, cx, cy)
        GLES20.glUniform1f(l["uZoom"]!!, if (enabled && !rawMode) stabilizer.crop.toFloat() else 1f)
        GLES20.glUniform1i(l["uPreview"]!!, if (preview) previewRot else 0)
        GLES20.glUniform1i(l["uHdr"]!!, if (is10bit) 1 else 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES20.glUniform1i(l["uLut"]!!, 2)
        GLES20.glUniform1f(l["uLutAmt"]!!, if (rawMode) 0f else lutAmt)
        GLES20.glUniform1f(l["uLutN"]!!, lutSize)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1f(l["uSharp"]!!, if (rawMode) 0f else sharpen)
        GLES20.glUniform1i(l["uBicubic"]!!, if (bicubic && !rawMode && !degraded) 1 else 0)
        GLES30.glUniformMatrix3fv(l["uR"]!!, Stabilizer.ROWS, false, rows, 0)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        released = true
        val latch = CountDownLatch(1)
        handler.post {
            runCatching { rec?.finish() }
            rec = null
            runCatching { cameraSurface.release() }
            runCatching { st.release() }
            encSurf?.let { runCatching { egl.destroySurface(it) } }
            previewSurf?.let { runCatching { egl.destroySurface(it) } }
            dummy?.let { runCatching { egl.destroySurface(it) } }
            runCatching { egl.release() }
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    companion object {
        const val TAG = "Stab"
        private const val GL_TEXTURE_EXTERNAL_OES = 0x8D65
    }
}
