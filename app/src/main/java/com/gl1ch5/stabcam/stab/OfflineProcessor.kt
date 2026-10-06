package com.gl1ch5.stabcam.stab

import android.content.ContentValues
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.view.Surface
import com.gl1ch5.stabcam.lut.Lut
import com.gl1ch5.stabcam.util.Logger
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** What the live recorder wrote next to a POST clip (see VideoCamera.writePostSidecars). */
class PostMeta(j: JSONObject) {
    val video: String = j.getString("video")
    val uri: String = j.optString("uri")
    val width = j.getInt("width")
    val height = j.getInt("height")
    val fps = j.getInt("fps")
    val baseTsNs = j.getLong("baseTsNs")
    val orientationHint = j.optInt("orientationHint", 0)
    val readoutNs = j.optLong("readoutNs", 8_000_000L)
    val hdr = j.optBoolean("hdr", false)
    val zoom = j.optDouble("zoom", 1.0).toFloat()
    val intrinsics = j.getJSONArray("intrinsics").let { FloatArray(4) { i -> it.getDouble(i).toFloat() } }
    val gyroAxes: List<String> = j.optJSONArray("gyroAxes")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    val frameRel: LongArray
    val frameExp: LongArray

    init {
        val f = j.getJSONArray("frames")
        frameRel = LongArray(f.length()) { f.getJSONArray(it).getLong(0) }
        frameExp = LongArray(f.length()) { f.getJSONArray(it).getLong(1) }
    }
}

/**
 * Post-processing of a POST clip: hardware decoder → GL (look-ahead stabilisation, rolling shutter, temporal
 * denoise, bicubic + sharpen, LUT) → hardware encoder. Audio is copied without re-encoding.
 */
class OfflineProcessor(
    private val ctx: Context,
    private val meta: PostMeta,
    private val gcsv: String,
    private val o: Options,
    private val onProgress: (fraction: Float, text: String) -> Unit,
) {
    class Options(
        val sigmaSec: Double = 0.25,
        val maxAngleDeg: Double = 8.0,
        val minCrop: Double = 1.03,
        val maxCrop: Double = 1.16,
        val denoise: Float = 0.5f,
        val denoiseSigma: Float = 0.04f,
        val sharpen: Float = 0.35f,
        val bicubic: Boolean = true,
        val lut: Lut? = null,
        val lutStrength: Float = 1f,
        val bitrate: Int = 50_000_000,
        val hevc: Boolean = true,
        val timeOffsetMs: Double = 0.0,
        val gyroAxes: List<String> = emptyList(),
        /** Horizon lock range in degrees (0 = off) and the recorded gravity log (`.grav.csv`). */
        val horizonDeg: Double = 0.0,
        val gravCsv: String? = null,
    )

    @Volatile var cancelled = false

    private lateinit var egl: EglCore
    private var encSurf: EGLSurface? = null
    private var tex = 0
    private lateinit var st: SurfaceTexture
    private lateinit var decSurface: Surface
    private var progWarpOes = 0
    private var progWarp2d = 0
    private var progDn = 0
    private val locOes = HashMap<String, Int>()
    private val loc2d = HashMap<String, Int>()
    private val locDn = HashMap<String, Int>()
    private val fbo = IntArray(2)
    private val fboTex = IntArray(2)
    private var histIdx = 0
    private var hasHist = false
    private var lastIdx = -10
    private var lutTex = 0
    private var lutSize = 2f
    private val rows = FloatArray(Stabilizer.ROWS * 9)
    private val rel = FloatArray(9)
    private val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0)
    }
    private val frameAvailable = Semaphore(0)

    private val w = meta.width
    private val h = meta.height
    private val k = meta.intrinsics

    /** Blocks until done. Returns the output Uri or null on failure/cancel. */
    fun run(): Uri? {
        val t0 = System.nanoTime()
        var outUri: Uri? = null
        var pfd: ParcelFileDescriptor? = null
        var ok = false
        val cbThread = HandlerThread("offline-cb").also { it.start() }
        var ex: MediaExtractor? = null
        var dec: MediaCodec? = null
        var enc: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            onProgress(0f, "Готовлю траекторию…")
            // 1) camera path and the look-ahead virtual path
            val (gt, gw) = GyroLog.parseGcsv(gcsv) ?: error("пустой гиро-лог")
            val path = OrientationPath.integrate(gt, gw, o.gyroAxes.ifEmpty { meta.gyroAxes })
            path0 = path
            val n = meta.frameRel.size
            val offsetNs = (o.timeOffsetMs * 1e6).toLong()
            val centre = LongArray(n) { meta.frameRel[it] + offsetNs + meta.frameExp[it] / 2 + meta.readoutNs / 2 }
            val real = Array(n) { path.at(centre[it]) }
            val tanHalf = (w / 2.0) / (k[0] * meta.zoom)
            val axes = o.gyroAxes.ifEmpty { meta.gyroAxes }
            val up = if (o.horizonDeg > 0 && o.gravCsv != null) gravityPerFrame(o.gravCsv, centre, axes) else null
            if (o.horizonDeg > 0 && up == null) Logger.w(TAG, "Горизонт включён, но лога гравитации нет: пропускаю")
            val intr = FrameFit.Intr(k[0].toDouble(), k[1].toDouble(), k[2].toDouble(), k[3].toDouble(), w.toDouble(), h.toDouble()).scaled(meta.zoom.toDouble())
            val plan = OfflineStabilizer.compute(centre, real,
                OfflineStabilizer.Params(o.sigmaSec, o.maxAngleDeg, tanHalf, o.minCrop, o.maxCrop, intr = intr, horizonDeg = if (up != null) o.horizonDeg else 0.0), up)
            Logger.i(TAG, "Траектория: $n кадров, кроп ${"%.3f".format(plan.crop.minOrNull() ?: 1.0)}–${"%.3f".format(plan.crop.maxOrNull() ?: 1.0)}, поправка до ${"%.2f".format(plan.offsetDeg.maxOrNull() ?: 0.0)}°")

            // 2) source
            ex = MediaExtractor()
            val src = openSource()
            ex.setDataSource(src.fileDescriptor)
            val vIdx = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
            val vFormat = ex.getTrackFormat(vIdx)
            ex.selectTrack(vIdx)

            // 3) output
            val out = createOutput()
            outUri = out.first; pfd = out.second
            muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(meta.orientationHint)
            val mime = if (o.hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
            val ef = MediaFormat.createVideoFormat(mime, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, o.bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, meta.fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setInteger(MediaFormat.KEY_PRIORITY, 1) // as fast as possible
                if (meta.hdr && o.hevc) {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG)
                    setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
                }
            }
            enc = MediaCodec.createEncoderByType(mime)
            try {
                enc.configure(MediaFormat(ef).apply { setInteger(MediaFormat.KEY_MAX_B_FRAMES, 2) }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                runCatching { enc!!.release() }
                enc = MediaCodec.createEncoderByType(mime)
                enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val encIn = enc.createInputSurface()
            enc.start()

            // 4) GL on this thread
            egl = EglCore(meta.hdr && o.hevc)
            encSurf = egl.createWindowSurface(encIn, hlg = meta.hdr && o.hevc && egl.is10bit)
            egl.makeCurrent(encSurf!!)
            egl.noSwapInterval()
            setupGl()
            st.setOnFrameAvailableListener({ frameAvailable.release() }, Handler(cbThread.looper))

            dec = MediaCodec.createDecoderByType(vFormat.getString(MediaFormat.KEY_MIME)!!)
            dec.configure(vFormat, decSurface, null, 0)
            dec.start()
            Logger.i(TAG, "Декодер ${dec.name}, энкодер ${enc.name}, ${w}x$h@${meta.fps}")

            // 5) the loop
            val info = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var vTrack = -1
            var aTrack = -1
            var muxStarted = false
            val aFormat = audioFormat(src)
            fun drain(end: Boolean) {
                while (true) {
                    val i = enc!!.dequeueOutputBuffer(encInfo, if (end) 10_000 else 0)
                    when {
                        i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            vTrack = muxer!!.addTrack(enc!!.outputFormat)
                            if (aFormat != null) aTrack = muxer!!.addTrack(aFormat)
                            muxer!!.start(); muxStarted = true
                        }
                        i >= 0 -> {
                            val b = enc!!.getOutputBuffer(i)
                            if (b != null && encInfo.size > 0 && (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxStarted) {
                                b.position(encInfo.offset); b.limit(encInfo.offset + encInfo.size)
                                muxer!!.writeSampleData(vTrack, b, encInfo)
                            }
                            enc!!.releaseOutputBuffer(i, false)
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                        i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    }
                }
            }

            var inDone = false
            var outDone = false
            var processed = 0
            while (!outDone && !cancelled) {
                if (!inDone) {
                    val ii = dec.dequeueInputBuffer(0)
                    if (ii >= 0) {
                        val buf = dec.getInputBuffer(ii)!!
                        val size = ex.readSampleData(buf, 0)
                        if (size < 0) { dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { dec.queueInputBuffer(ii, 0, size, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = dec.dequeueOutputBuffer(info, 5_000)
                if (oi >= 0) {
                    val render = info.size > 0
                    dec.releaseOutputBuffer(oi, render)
                    if (render) {
                        if (!frameAvailable.tryAcquire(1, TimeUnit.SECONDS)) error("декодер не отдал кадр")
                        st.updateTexImage()
                        val idx = nearest(meta.frameRel, info.presentationTimeUs * 1000L)
                        renderFrame(idx, info.presentationTimeUs * 1000L, plan, real)
                        processed++
                        if (processed % 12 == 0) {
                            val el = (System.nanoTime() - t0) / 1e9
                            onProgress(processed.toFloat() / n, "Кадр $processed из $n · ×${"%.1f".format(processed / meta.fps / el)} от реального времени")
                        }
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
                drain(false)
            }
            if (cancelled) { Logger.i(TAG, "Отменено"); return null }
            enc.signalEndOfInputStream()
            drain(true)

            // 6) audio: copy without re-encoding
            if (aFormat != null && muxStarted && aTrack >= 0) copyAudio(src, muxer, aTrack)
            muxer.stop()
            ok = true
            val soft = "StabCam ${com.gl1ch5.stabcam.BuildConfig.VERSION_NAME}"
            val desc = "${w}x$h ${meta.fps}fps | ${if (o.hevc) "HEVC" else "H.264"}${if (meta.hdr) " 10-bit HLG" else ""} | ${o.bitrate / 1_000_000} Mbps | post-processed: look-ahead stabilization ${o.sigmaSec}s, denoise ${o.denoise}${if (o.lut != null) ", LUT ${o.lut.name}" else ""}"
            Mp4Tagger.tag(pfd!!.fileDescriptor, listOf("mak" to android.os.Build.MANUFACTURER, "mod" to android.os.Build.MODEL, "swr" to soft, "too" to soft, "cmt" to desc, "des" to desc))
            val sec = (System.nanoTime() - t0) / 1e9
            Logger.i(TAG, "Готово: $processed кадров за ${"%.1f".format(sec)} с (×${"%.1f".format(processed / meta.fps / sec)})")
            onProgress(1f, "Готово за ${"%.1f".format(sec)} с")
            return outUri
        } catch (e: Throwable) {
            Logger.e(TAG, "Обработка не удалась", e)
            onProgress(0f, "Ошибка: ${e.message}")
            return null
        } finally {
            runCatching { dec?.stop() }; runCatching { dec?.release() }
            runCatching { enc?.stop() }; runCatching { enc?.release() }
            runCatching { muxer?.release() }
            runCatching { ex?.release() }
            runCatching { if (::egl.isInitialized) { encSurf?.let { egl.destroySurface(it) }; egl.release() } }
            runCatching { if (::decSurface.isInitialized) decSurface.release(); if (::st.isInitialized) st.release() }
            cbThread.quitSafely()
            runCatching { pfd?.close() }
            val u = outUri
            if (u != null) {
                if (ok) ctx.contentResolver.update(u, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                else runCatching { ctx.contentResolver.delete(u, null, null) }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private fun renderFrame(idx: Int, ptsNs: Long, plan: OfflineStabilizer.Result, real: Array<Quat>) {
        val ts = meta.frameRel[idx] + (o.timeOffsetMs * 1e6).toLong()
        Stabilizer.rowMatrices(plan.virtual[idx], ts, meta.readoutNs, meta.frameExp[idx], { path0.at(it) }, rows)
        var dn = false
        if (o.denoise > 0f) {
            if (idx != lastIdx + 1) hasHist = false
            dn = denoisePass(idx, real)
            lastIdx = idx
        }
        draw(dn, plan.crop[idx].toFloat())
        egl.setPresentationTime(encSurf!!, ptsNs)
        egl.swap(encSurf!!)
    }

    private lateinit var path0: OrientationPath

    private fun denoisePass(idx: Int, real: Array<Quat>): Boolean {
        val cur = 1 - histIdx
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[cur])
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(progDn)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex); GLES20.glUniform1i(locDn["uCur"]!!, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[histIdx]); GLES20.glUniform1i(locDn["uHist"]!!, 1)
        GLES20.glUniform2f(locDn["uSize"]!!, w.toFloat(), h.toFloat())
        val z = meta.zoom
        GLES20.glUniform4f(locDn["uK"]!!, k[0] * z, k[1] * z, w / 2f + (k[2] - w / 2f) * z, h / 2f + (k[3] - h / 2f) * z)
        if (hasHist && idx > 0) {
            val m = (real[idx - 1].conj() * real[idx]).toMatrix()
            for (r in 0..2) for (c in 0..2) rel[c * 3 + r] = m[r * 3 + c].toFloat()
        } else for (i in 0 until 9) rel[i] = if (i % 4 == 0) 1f else 0f
        GLES30.glUniformMatrix3fv(locDn["uRel"]!!, 1, false, rel, 0)
        GLES20.glUniform1f(locDn["uStr"]!!, o.denoise)
        GLES20.glUniform1f(locDn["uSigma"]!!, o.denoiseSigma)
        GLES20.glUniform1f(locDn["uSp"]!!, minOf(0.8f, o.denoise * 0.7f))
        GLES20.glUniform1i(locDn["uHasHist"]!!, if (hasHist && idx > 0) 1 else 0)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        histIdx = cur
        hasHist = true
        return true
    }

    private fun draw(denoised: Boolean, crop: Float) {
        val prog = if (denoised) progWarp2d else progWarpOes
        val l = if (denoised) loc2d else locOes
        GLES20.glViewport(0, 0, w, h)
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        if (denoised) GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[histIdx]) else GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glUniform1i(l["uTex"]!!, 0)
        GLES20.glUniform2f(l["uSize"]!!, w.toFloat(), h.toFloat())
        val z = meta.zoom
        GLES20.glUniform4f(l["uK"]!!, k[0] * z, k[1] * z, w / 2f + (k[2] - w / 2f) * z, h / 2f + (k[3] - h / 2f) * z)
        GLES20.glUniform1f(l["uZoom"]!!, crop)
        GLES20.glUniform1i(l["uPreview"]!!, 0)
        GLES20.glUniform1f(l["uSharp"]!!, o.sharpen)
        GLES20.glUniform1i(l["uBicubic"]!!, if (o.bicubic) 1 else 0)
        GLES20.glUniform1i(l["uHdr"]!!, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES20.glUniform1i(l["uLut"]!!, 2)
        GLES20.glUniform1f(l["uLutAmt"]!!, if (o.lut != null) o.lutStrength else 0f)
        GLES20.glUniform1f(l["uLutN"]!!, lutSize)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES30.glUniformMatrix3fv(l["uR"]!!, Stabilizer.ROWS, false, rows, 0)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun setupGl() {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        tex = ids[0]
        GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex)
        for (p in intArrayOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, p, GLES20.GL_LINEAR)
        for (p in intArrayOf(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T)) GLES20.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, p, GLES20.GL_CLAMP_TO_EDGE)
        st = SurfaceTexture(tex)
        st.setDefaultBufferSize(w, h)
        decSurface = Surface(st)
        // LUT
        val lt = IntArray(1)
        GLES20.glGenTextures(1, lt, 0)
        lutTex = lt[0]
        val l = o.lut
        val size = l?.size ?: 2
        val data = l?.rgb ?: ByteArray(2 * 2 * 2 * 3) { i -> val v = i / 3; val ch = i % 3; (if ((v shr ch) and 1 == 1) 255 else 0).toByte() }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8, size, size, size, 0, GLES20.GL_RGB, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(data))
        for (p in intArrayOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES20.GL_LINEAR)
        for (p in intArrayOf(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T, GLES30.GL_TEXTURE_WRAP_R)) GLES20.glTexParameteri(GLES30.GL_TEXTURE_3D, p, GLES20.GL_CLAMP_TO_EDGE)
        lutSize = size.toFloat()
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        progWarpOes = Shaders.warp(true)
        val names = listOf("uTex", "uSize", "uK", "uZoom", "uPreview", "uSharp", "uBicubic", "uHdr", "uLut", "uLutAmt", "uLutN", "uR")
        for (n in names) locOes[n] = GLES20.glGetUniformLocation(progWarpOes, n)
        if (o.denoise > 0f) {
            progWarp2d = Shaders.warp(false)
            for (n in names) loc2d[n] = GLES20.glGetUniformLocation(progWarp2d, n)
            progDn = Shaders.denoise()
            for (n in listOf("uCur", "uHist", "uSize", "uK", "uRel", "uStr", "uSigma", "uHasHist", "uSp")) locDn[n] = GLES20.glGetUniformLocation(progDn, n)
            val t2 = IntArray(2)
            GLES20.glGenTextures(2, t2, 0)
            GLES30.glGenFramebuffers(2, fbo, 0)
            for (i in 0..1) {
                fboTex[i] = t2[i]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[i])
                if (egl.is10bit) GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGB10_A2, w, h, 0, GLES20.GL_RGBA, GLES30.GL_UNSIGNED_INT_2_10_10_10_REV, null)
                else GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                for (p in intArrayOf(GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_TEXTURE_MAG_FILTER)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, p, GLES20.GL_LINEAR)
                for (p in intArrayOf(GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_TEXTURE_WRAP_T)) GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, p, GLES20.GL_CLAMP_TO_EDGE)
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[i])
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex[i], 0)
                check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete" }
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        }
    }

    /** Interpolated "up" vector (camera-image frame) at each frame centre, from the `.grav.csv` sidecar. */
    private fun gravityPerFrame(csv: String, centre: LongArray, axes: List<String>): Array<DoubleArray?>? {
        val t = ArrayList<Long>(); val g = ArrayList<DoubleArray>()
        for (line in csv.lineSequence().drop(1)) {
            val p = line.split(',')
            if (p.size < 4) continue
            t += (p[0].toDouble() * 1e6).toLong(); g += doubleArrayOf(p[1].toDouble(), p[2].toDouble(), p[3].toDouble())
        }
        if (t.size < 2) return null
        return Array(centre.size) { i ->
            val idx = nearest(t.toLongArray(), centre[i])
            val v = g[idx]
            val n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            if (n < 1e-6) null else OrientationPath.mapVec(axes, v[0] / n, v[1] / n, v[2] / n)
        }
    }

    private fun nearest(a: LongArray, t: Long): Int {
        var lo = 0; var hi = a.size - 1
        while (lo < hi) { val m = (lo + hi) ushr 1; if (a[m] < t) lo = m + 1 else hi = m }
        return if (lo > 0 && t - a[lo - 1] < a[lo] - t) lo - 1 else lo
    }

    private fun openSource(): ParcelFileDescriptor {
        runCatching { if (meta.uri.isNotEmpty()) ctx.contentResolver.openFileDescriptor(Uri.parse(meta.uri), "r")?.let { return it } }
        val col = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        ctx.contentResolver.query(col, arrayOf(MediaStore.Video.Media._ID), "${MediaStore.Video.Media.DISPLAY_NAME}=?", arrayOf(meta.video), null)?.use {
            if (it.moveToFirst()) return ctx.contentResolver.openFileDescriptor(android.content.ContentUris.withAppendedId(col, it.getLong(0)), "r")!!
        }
        error("исходный ролик ${meta.video} не найден (удалён?)")
    }

    private fun audioFormat(src: ParcelFileDescriptor): MediaFormat? {
        val e = MediaExtractor()
        return try {
            e.setDataSource(src.fileDescriptor)
            (0 until e.trackCount).map { e.getTrackFormat(it) }.firstOrNull { it.getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
        } finally { e.release() }
    }

    private fun copyAudio(src: ParcelFileDescriptor, muxer: MediaMuxer, aTrack: Int) {
        val e = MediaExtractor()
        try {
            e.setDataSource(src.fileDescriptor)
            val idx = (0 until e.trackCount).first { e.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("audio/") }
            e.selectTrack(idx)
            val buf = ByteBuffer.allocate(maxOf(1 shl 20, e.getTrackFormat(idx).let { if (it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0 }))
            val info = MediaCodec.BufferInfo()
            while (true) {
                val n = e.readSampleData(buf, 0)
                if (n < 0) break
                info.set(0, n, e.sampleTime, if (e.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                buf.position(0); buf.limit(n)
                muxer.writeSampleData(aTrack, buf, info)
                e.advance()
            }
        } finally { e.release() }
    }

    private fun createOutput(): Pair<Uri, ParcelFileDescriptor> {
        val name = meta.video.removeSuffix(".mp4") + "_POST.mp4"
        val v = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/StabCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v) ?: error("MediaStore insert failed")
        return uri to (ctx.contentResolver.openFileDescriptor(uri, "rw") ?: error("open fd failed"))
    }

    companion object {
        const val TAG = "Offline"
        private const val GL_TEXTURE_EXTERNAL_OES = 0x8D65
    }
}
