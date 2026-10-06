package com.gl1ch5.stabcam.stab

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.gl1ch5.stabcam.util.Logger
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
) {
    private val thread = HandlerThread("stab-gl", android.os.Process.THREAD_PRIORITY_DISPLAY).also { it.start() }
    private val handler = Handler(thread.looper)
    private lateinit var egl: EglCore
    private lateinit var st: SurfaceTexture
    lateinit var cameraSurface: Surface
        private set
    private var tex = 0
    private var program = 0
    private val loc = HashMap<String, Int>()
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
    /** Preview orientation variant 1..4 (see shader); encoder output is unaffected. */
    @Volatile var previewRot = 1
    @Volatile private var zoom = 1f
    @Volatile private var exposureNs = 8_000_000L
    @Volatile private var readoutNs = readoutDefaultNs
    @Volatile private var released = false

    // stats
    private var frames = 0L
    private var statT = 0L
    private var statN = 0
    private var statRenderNs = 0L
    private var statCorr = 0.0
    private var statMaxCorr = 0.0

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

    fun setExposure(ns: Long) { if (ns > 0) exposureNs = ns }
    fun setReadout(ns: Long) { if (ns > 0) readoutNs = ns }
    fun setZoom(z: Float) {
        zoom = z
        // Intrinsics are known for the main camera only: no warping on the ultrawide range.
        enabled = z >= 0.95f
    }

    private fun setup() {
        egl = EglCore()
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
        program = buildProgram()
        for (n in listOf("uTex", "uST", "uSize", "uK", "uZoom", "uCrop", "uPreview", "uSharp", "uBicubic", "uR")) loc[n] = GLES20.glGetUniformLocation(program, n)
        Logger.i(TAG, "GL готов: ${GLES20.glGetString(GLES20.GL_RENDERER)}, ${GLES20.glGetString(GLES20.GL_VERSION)}, буфер ${width}x$height")
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
                encSurf = egl.createWindowSurface(rec.inputSurface)
                this.rec = rec
                stabilizer.reset()
            }.onFailure { Logger.e(TAG, "encoder surface", it) }
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
    }

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
        st.getTransformMatrix(stm)
        frames++

        // Orientation at the frame centre, smoothed; per-row matrices for the rolling shutter.
        val readout = readoutNs
        val exposure = exposureNs
        if (enabled) {
            val centre = ts + exposure / 2 + readout / 2
            val qr = gyro.orientationAt(centre)
            val qv = stabilizer.update(centre, qr)
            Stabilizer.rowMatrices(qv, ts, readout, exposure, { gyro.orientationAt(it) }, rows)
        } else {
            Stabilizer.identityRows(rows)
            stabilizer.reset()
        }

        previewSurf?.let { s ->
            egl.makeCurrent(s)
            draw(previewSize.first, previewSize.second, true)
            egl.swap(s)
        }
        val r = rec
        val es = encSurf
        if (r != null && es != null) {
            egl.makeCurrent(es)
            r.onFrame(ts)
            draw(width, height, false)
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
            Logger.i(TAG, "%.1f fps, кадр %.1f мс, поправка ср %.2f° макс %.2f°, гиро %s, стаб %s".format(
                statN * 1e9 / (now - statT), statRenderNs / statN / 1e6, statCorr / statN, statMaxCorr,
                if (gyro.latestTimeNs() - ts > -50_000_000L) "ok" else "ОТСТАЁТ", if (enabled) "вкл" else "выкл"))
            statT = now; statN = 0; statRenderNs = 0; statCorr = 0.0; statMaxCorr = 0.0
        }
    }

    private fun draw(w: Int, h: Int, preview: Boolean) {
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(loc["uTex"]!!, 0)
        GLES20.glUniformMatrix4fv(loc["uST"]!!, 1, false, stm, 0)
        GLES20.glUniform2f(loc["uSize"]!!, width.toFloat(), height.toFloat())
        val z = zoom
        // Zoom about the image centre: focal lengths scale, principal point moves with it.
        val cx = width / 2f + (k[2] - width / 2f) * z
        val cy = height / 2f + (k[3] - height / 2f) * z
        GLES20.glUniform4f(loc["uK"]!!, k[0] * z, k[1] * z, cx, cy)
        GLES20.glUniform1f(loc["uZoom"]!!, if (enabled) crop else 1f)
        GLES20.glUniform1i(loc["uPreview"]!!, if (preview) previewRot else 0)
        GLES20.glUniform1f(loc["uSharp"]!!, sharpen)
        GLES20.glUniform1i(loc["uBicubic"]!!, if (bicubic) 1 else 0)
        GLES30.glUniformMatrix3fv(loc["uR"]!!, Stabilizer.ROWS, false, rows, 0)
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

    private fun buildProgram(): Int {
        val vs = """#version 300 es
            layout(location = 0) in vec2 aPos;
            out vec2 vPos;
            void main() {
                vPos = vec2((aPos.x + 1.0) * 0.5, (1.0 - aPos.y) * 0.5);
                gl_Position = vec4(aPos, 0.0, 1.0);
            }"""
        val rowsN = Stabilizer.ROWS
        val fs = """#version 300 es
            #extension GL_OES_EGL_image_external_essl3 : require
            precision highp float;
            uniform samplerExternalOES uTex;
            uniform mat4 uST;
            uniform vec2 uSize;
            uniform vec4 uK;
            uniform float uZoom;
            uniform float uCrop;
            uniform int uPreview;
            uniform float uSharp;
            uniform int uBicubic;
            uniform mat3 uR[$rowsN];
            in vec2 vPos;
            out vec4 o;

            vec3 fetch(vec2 q) {
                vec4 tc = uST * vec4(q.x, 1.0 - q.y, 0.0, 1.0);
                return texture(uTex, tc.xy).rgb;
            }

            // Catmull-Rom with 9 bilinear taps: keeps edges crisp after the warp (bilinear alone softens them).
            vec3 catmull(vec2 q) {
                vec2 pos = q * uSize;
                vec2 c = floor(pos - 0.5) + 0.5;
                vec2 f = pos - c;
                vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
                vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
                vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
                vec2 w3 = f * f * (-0.5 + 0.5 * f);
                vec2 w12 = w1 + w2;
                vec2 o12 = w2 / w12;
                vec2 p0 = (c - 1.0) / uSize;
                vec2 p3 = (c + 2.0) / uSize;
                vec2 p12 = (c + o12) / uSize;
                vec3 r = fetch(vec2(p0.x, p0.y)) * w0.x * w0.y + fetch(vec2(p12.x, p0.y)) * w12.x * w0.y + fetch(vec2(p3.x, p0.y)) * w3.x * w0.y
                       + fetch(vec2(p0.x, p12.y)) * w0.x * w12.y + fetch(vec2(p12.x, p12.y)) * w12.x * w12.y + fetch(vec2(p3.x, p12.y)) * w3.x * w12.y
                       + fetch(vec2(p0.x, p3.y)) * w0.x * w3.y + fetch(vec2(p12.x, p3.y)) * w12.x * w3.y + fetch(vec2(p3.x, p3.y)) * w3.x * w3.y;
                return max(r, vec3(0.0));
            }
            void main() {
                // Output pixel in the sensor-oriented frame (preview is rotated 90° CW to portrait).
                vec2 pos = vPos;
                if (uPreview == 1) pos = vec2(vPos.y, 1.0 - vPos.x);
                else if (uPreview == 2) pos = vec2(1.0 - vPos.y, vPos.x);
                else if (uPreview == 4) pos = vec2(1.0) - vPos;
                vec3 d = vec3((pos.x * uSize.x - uK.z) / uK.x, (pos.y * uSize.y - uK.w) / uK.y, 1.0);
                d.xy /= uZoom;
                float rp = pos.y * float($rowsN - 1);
                int i0 = int(floor(rp));
                int i1 = min(i0 + 1, $rowsN - 1);
                float f = rp - float(i0);
                mat3 R = uR[i0] * (1.0 - f) + uR[i1] * f;
                vec3 s = R * d;
                vec2 q = vec2(s.x / s.z * uK.x + uK.z, s.y / s.z * uK.y + uK.w) / uSize;
                if (q.x < 0.0 || q.x > 1.0 || q.y < 0.0 || q.y > 1.0) { o = vec4(0.0, 0.0, 0.0, 1.0); return; }
                if (uPreview > 0 || uBicubic == 0) { o = vec4(fetch(q), 1.0); return; }
                vec3 c = catmull(q);
                if (uSharp > 0.0) {
                    vec2 px = 1.0 / uSize;
                    vec3 n = fetch(q + vec2(0.0, -px.y));
                    vec3 s2 = fetch(q + vec2(0.0, px.y));
                    vec3 e = fetch(q + vec2(px.x, 0.0));
                    vec3 w = fetch(q + vec2(-px.x, 0.0));
                    vec3 lo = min(min(n, s2), min(e, w));
                    vec3 hi = max(max(n, s2), max(e, w));
                    vec3 sh = c + uSharp * (c - 0.25 * (n + s2 + e + w));
                    // Limited overshoot: no halos around edges.
                    c = clamp(sh, min(lo, c) - 0.02, max(hi, c) + 0.02);
                }
                o = vec4(c, 1.0);
            }"""
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "link: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] == GLES20.GL_TRUE) { "shader: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    companion object {
        const val TAG = "Stab"
        private const val GL_TEXTURE_EXTERNAL_OES = 0x8D65
    }
}
