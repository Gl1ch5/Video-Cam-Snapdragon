package com.gl1ch5.stabcam.camera

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import com.gl1ch5.stabcam.util.Logger
import com.gl1ch5.stabcam.lut.Lut
import com.gl1ch5.stabcam.stab.GyroLog
import com.gl1ch5.stabcam.stab.GyroTracker
import com.gl1ch5.stabcam.stab.Mp4Tagger
import com.gl1ch5.stabcam.stab.StabPipeline
import com.gl1ch5.stabcam.stab.StabRecorder
import com.gl1ch5.stabcam.stab.Stabilizer
import android.view.Surface
import com.gl1ch5.stabcam.config.AppConfig
import com.gl1ch5.stabcam.config.Quality
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Camera2 + MediaRecorder video pipeline.
 *
 * Hardware OIS on, stock EIS off by default: the sensor-shift/lens OIS removes hand shake inside the exposure
 * without the crop and "jelly" the stock EIS adds. All camera work runs on one background thread.
 */
class VideoCamera(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onError(message: String)
        fun onSessionReady()
        fun onRecordingStarted()
        fun onRecordingStopped(uri: Uri?)
    }

    /** Live controls changed from the UI without a session restart. */
    data class Controls(
        val ois: Boolean,
        val stockEis: Boolean,
        val evIndex: Int = 0,
        val zoom: Float = 1f,
        val stab: Boolean = false,
    )

    private val thread = HandlerThread("camera").apply { start() }
    private val handler = object : Handler(thread.looper) {
        override fun dispatchMessage(msg: android.os.Message) {
            try { super.dispatchMessage(msg) } catch (t: Throwable) { Logger.e(TAG, "Камера", t) }
        }
    }
    private val executor = Executor { handler.post(it) }
    private val mgr = ctx.getSystemService(CameraManager::class.java)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var sessionHasRecorder = false
    private var gyro: GyroTracker? = null
    private var pipeline: StabPipeline? = null
    private var stabRecording = false
    private var lut: Lut? = null
    private var lutStrength = 1f
    private var caps: CameraCaps? = null
    private var previewSurface: Surface? = null
    private var config: AppConfig? = null
    private var quality: Quality = Quality.UHD60
    @Volatile var controls = Controls(ois = true, stockEis = false)
        private set

    private var recorder: MediaRecorder? = null
    private var recordUri: Uri? = null
    private var recordPfd: ParcelFileDescriptor? = null
    private var recordDescription: String? = null
    private var recordName = ""
    private var recordHint = 0
    @Volatile var isRecording = false
        private set

    @SuppressLint("MissingPermission")
    fun open(caps: CameraCaps, preview: Surface, cfg: AppConfig, q: Quality, c: Controls) = handler.post {
        closeInternal()
        this.caps = caps
        this.previewSurface = preview
        this.config = cfg
        this.quality = q
        this.controls = c
        try {
            mgr.openCamera(caps.id, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    Logger.i(TAG, "Камера ${caps.id} открыта")
                    device = d
                    createSession(withRecorder = false)
                }

                override fun onDisconnected(d: CameraDevice) {
                    d.close()
                    if (device == d) device = null
                }

                override fun onError(d: CameraDevice, error: Int) {
                    d.close()
                    if (device == d) device = null
                    listener.onError("Ошибка камеры ($error)")
                }
            })
        } catch (e: Exception) {
            listener.onError("Не удалось открыть камеру: ${e.message}")
        }
    }

    fun close() = handler.post { closeInternal() }

    fun release() {
        handler.post { closeInternal() }
        thread.quitSafely()
    }

    fun setLut(l: Lut?, strength: Float) = handler.post { lut = l; lutStrength = strength; pipeline?.setLut(l, strength) }

    fun setPreviewRot(n: Int) = handler.post { pipeline?.previewRot = n }

    fun updateControls(c: Controls) = handler.post {
        controls = c
        pipeline?.setZoom(c.zoom)
        applyRepeating()
    }

    fun startRecording(orientationHint: Int) = handler.post {
        if (isRecording || device == null) return@post
        if (pipeline != null) { startStab(orientationHint); return@post }
        try {
            prepareRecorder(orientationHint)
            createSession(withRecorder = true)
        } catch (e: Exception) {
            Logger.e(TAG, "prepare failed", e)
            cleanupRecorder(deleteFile = true)
            listener.onError("Не удалось начать запись: ${e.message}")
        }
    }

    fun stopRecording() = handler.post {
        if (!isRecording) return@post
        if (stabRecording) { stopStab(); return@post }
        isRecording = false
        val ok = runCatching {
            session?.stopRepeating()
            recorder?.stop()
        }.isSuccess
        val uri = recordUri
        cleanupRecorder(deleteFile = !ok)
        listener.onRecordingStopped(if (ok) uri else null)
        createSession(withRecorder = false)
    }

    private fun createSession(withRecorder: Boolean) {
        val dev = device ?: return
        val preview = previewSurface ?: return
        session?.close()
        session = null
        sessionHasRecorder = false

        ensureStab()
        val targets = pipeline?.let { listOf(it.cameraSurface) } ?: buildList {
            add(preview)
            if (withRecorder) recorder?.surface?.let { add(it) }
        }
        val outputs = targets.map { surf ->
            OutputConfiguration(surf).also { oc ->
                if (pipeline?.is10bit == true && android.os.Build.VERSION.SDK_INT >= 33) {
                    oc.dynamicRangeProfile = android.hardware.camera2.params.DynamicRangeProfiles.HLG10
                }
            }
        }
        val stateCb =
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    sessionHasRecorder = withRecorder
                    Logger.i(TAG, "Сессия настроена: ${quality.label}, запись=$withRecorder")
                    applyRepeating()
                    if (!withRecorder) listener.onSessionReady()
                    if (withRecorder && recorder != null) {
                        try {
                            recorder?.start()
                            isRecording = true
                            listener.onRecordingStarted()
                        } catch (e: Exception) {
                            Logger.e(TAG, "recorder start failed", e)
                            cleanupRecorder(deleteFile = true)
                            listener.onError("Запись не стартовала: ${e.message}")
                            createSession(withRecorder = false)
                        }
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Logger.e(TAG, "Сессия не настроилась: ${quality.label}, запись=$withRecorder")
                    listener.onError("Камера не поддерживает ${quality.label} в этой конфигурации")
                    if (withRecorder) {
                        cleanupRecorder(deleteFile = true)
                        createSession(withRecorder = false)
                    }
                }
            }
        if (withRecorder && quality.highSpeed) {
            Logger.i(TAG, "Constrained high-speed сессия: ${quality.label}")
            try {
                @Suppress("DEPRECATION")
                dev.createConstrainedHighSpeedCaptureSession(targets, stateCb, handler)
            } catch (e: Exception) {
                Logger.e(TAG, "High-speed сессия", e)
                listener.onError("High-speed: ${e.message}")
                cleanupRecorder(deleteFile = true)
                createSession(withRecorder = false)
            }
            return
        }
        val sessionConfig = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, stateCb)
        // Session parameters let the HAL pick the right sensor mode (fps, stabilization) up front.
        buildRequest(dev, targets)?.let { sessionConfig.sessionParameters = it.build() }
        try {
            dev.createCaptureSession(sessionConfig)
        } catch (e: Exception) {
            listener.onError("Ошибка сессии: ${e.message}")
        }
    }

    private fun applyRepeating() {
        val s = session ?: return
        val dev = device ?: return
        val targets = pipeline?.let { listOf(it.cameraSurface) } ?: buildList {
            previewSurface?.let { add(it) }
            if (sessionHasRecorder) recorder?.surface?.let { add(it) }
        }
        val builder = buildRequest(dev, targets) ?: return
        if (s is android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession) {
            runCatching { s.setRepeatingBurst(s.createHighSpeedRequestList(builder.build()), logCallback, handler) }
                .onFailure { Logger.w(TAG, "setRepeatingBurst", it) }
            return
        }
        runCatching { s.setRepeatingRequest(builder.build(), logCallback, handler) }
            .onFailure { Logger.w(TAG, "setRepeatingRequest", it) }
    }

    private var logged = 0
    private val logCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
            res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { pipeline?.setExposure(it) }
            if (config?.stabReadoutNs == 0L) res.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)?.let { pipeline?.setReadout(it) }
            if (logged++ < 2) Logger.i(TAG, "Кадр: " + echo(res) + " exp=${res.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.div(1000)}мкс")
        }
        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
            Logger.w(TAG, "Кадр не получен, reason=${f.reason}")
        }
    }

    private fun echo(res: TotalCaptureResult): String =
        "OIS=${name(res.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE))} " +
            "EIS=${name(res.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE))} " +
            "fps-range=${res.request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)}"

    private fun name(v: Int?) = when (v) { null -> "нет в результате"; 0 -> "выкл(0)"; 1 -> "вкл(1)"; else -> "$v" }

    /**
     * Runs on the live preview session: tries standard stabilization modes and every vendor "stabiliz*" key,
     * reports which values the HAL accepted/echoed. Echo proves the request was honoured, not that the lens moves.
     */
    fun probe(done: (List<String>) -> Unit) = handler.post {
        val dev = device; val s = session; val caps = caps
        if (dev == null || s == null || caps == null || isRecording) return@post
        val out = mutableListOf<String>()
        class Step(val label: String, val mod: (CaptureRequest.Builder) -> Unit, val check: (TotalCaptureResult?, Int) -> String)
        val steps = mutableListOf<Step>()
        fun okFail(label: String) = { _: TotalCaptureResult?, fails: Int -> if (fails == 0) "✔ $label: принят" else "✖ $label: отклонён ($fails ош.)" }
        steps += Step("OIS выкл (стандарт)", { it.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, 0) }) { r, f ->
            "• OIS выкл → результат ${name(r?.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE))}" }
        steps += Step("OIS вкл (стандарт)", { it.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, 1) }) { r, f ->
            val v = r?.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
            (if (v == 1) "✔" else "✖") + " OIS вкл → результат ${name(v)}" + if (caps.hasOis) "" else " (камера не заявляет OIS)" }
        for (m in caps.eisModes) steps += Step("EIS режим $m", {
            it.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, 1)
            it.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, m)
        }) { r, f -> "• EIS режим $m → результат ${r?.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)}" + if (f > 0) " (ошибок кадра: $f)" else "" }
        val vendor = caps.chars.availableCaptureRequestKeys.map { it.name }.filter { it.contains("stabiliz", true) && !it.startsWith("android.") }
        for (k in vendor) for (v in 0..3) steps += Step("$k=$v", {
            it.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, 1)
            runCatching { it.set(CaptureRequest.Key(k, Int::class.javaObjectType), v) }
        }, okFail("$k=$v"))

        fun run(i: Int) {
            if (i >= steps.size) {
                applyRepeating()
                out.forEach { Logger.i("Probe", it) }
                done(out)
                return
            }
            val st = steps[i]
            val b = buildRequest(dev, pipeline?.let { listOf(it.cameraSurface) } ?: listOfNotNull(previewSurface)) ?: return run(i + 1)
            st.mod(b)
            var last: TotalCaptureResult? = null
            var fails = 0
            val cb = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) { last = res }
                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) { fails++ }
            }
            try {
                session?.setRepeatingRequest(b.build(), cb, handler)
            } catch (e: Exception) {
                out += "✖ ${st.label}: ${e.message}"
                return run(i + 1)
            }
            handler.postDelayed({ out += st.check(last, fails); run(i + 1) }, 450)
        }
        Logger.i(TAG, "Диагностика стабилизации: ${steps.size} проверок")
        run(0)
    }

    private fun buildRequest(dev: CameraDevice, targets: List<Surface>): CaptureRequest.Builder? {
        val caps = caps ?: return null
        val cfg = config ?: return null
        val c = controls
        return dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            targets.forEach { addTarget(it) }
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, caps.fpsRangeFor(if (quality.highSpeed && !sessionHasRecorder) 30 else quality.fps))
            if (CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in caps.afModes) {
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }
            set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (c.ois) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
            )
            set(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (c.stockEis && caps.hasStockEis && pipeline == null) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
            )
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, c.evIndex.coerceIn(caps.evRange.lower, caps.evRange.upper))
            set(CaptureRequest.CONTROL_ZOOM_RATIO, c.zoom.coerceIn(caps.zoomRange.lower, caps.zoomRange.upper))
            pickMode(cfg.noiseReduction, caps.nrModes, mapOf(
                "off" to CameraMetadata.NOISE_REDUCTION_MODE_OFF,
                "fast" to CameraMetadata.NOISE_REDUCTION_MODE_FAST,
                "hq" to CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
                "minimal" to CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL,
            ))?.let { set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
            pickMode(cfg.edge, caps.edgeModes, mapOf(
                "off" to CameraMetadata.EDGE_MODE_OFF,
                "fast" to CameraMetadata.EDGE_MODE_FAST,
                "hq" to CameraMetadata.EDGE_MODE_HIGH_QUALITY,
            ))?.let { set(CaptureRequest.EDGE_MODE, it) }
            pickMode(cfg.distortionCorrection, caps.distortionModes, mapOf(
                "off" to CameraMetadata.DISTORTION_CORRECTION_MODE_OFF,
                "fast" to CameraMetadata.DISTORTION_CORRECTION_MODE_FAST,
                "hq" to CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
            ))?.let { set(CaptureRequest.DISTORTION_CORRECTION_MODE, it) }
            applyVendorTags(this, caps, cfg)
        }
    }

    private fun pickMode(name: String, available: List<Int>, table: Map<String, Int>): Int? =
        table[name.lowercase()]?.takeIf { it in available }

    private fun applyVendorTags(b: CaptureRequest.Builder, caps: CameraCaps, cfg: AppConfig) {
        if (cfg.vendorTags.isEmpty()) return
        val available = caps.chars.availableCaptureRequestKeys.map { it.name }.toSet()
        for (t in cfg.vendorTags) {
            if (t.name !in available || t.value == null) continue
            runCatching {
                when (t.type) {
                    "int" -> b.set(CaptureRequest.Key(t.name, Int::class.javaObjectType), (t.value as Number).toInt())
                    "long" -> b.set(CaptureRequest.Key(t.name, Long::class.javaObjectType), (t.value as Number).toLong())
                    "float" -> b.set(CaptureRequest.Key(t.name, Float::class.javaObjectType), (t.value as Number).toFloat())
                    "boolean" -> b.set(CaptureRequest.Key(t.name, Boolean::class.javaObjectType), t.value as Boolean)
                    "byte" -> b.set(CaptureRequest.Key(t.name, Byte::class.javaObjectType), (t.value as Number).toByte())
                    else -> Logger.w(TAG, "Unknown vendor tag type ${t.type}")
                }
            }.onFailure { Logger.w(TAG, "Vendor tag ${t.name} rejected", it) }
        }
    }

    private fun createOutput(): ParcelFileDescriptor {
        val name = "STAB_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/StabCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("MediaStore insert failed")
        recordUri = uri
        recordName = name
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw") ?: error("open fd failed")
        recordPfd = pfd
        return pfd
    }

    private fun prepareRecorder(orientationHint: Int) {
        val cfg = config!!
        val q = quality
        val pfd = createOutput()
        val useHevc = cfg.codec.equals("hevc", true) &&
            CameraCaps.encoderSupports(MediaFormat.MIMETYPE_VIDEO_HEVC, q.width, q.height, q.fps)

        recorder = MediaRecorder(ctx).apply {
            if (cfg.audio) setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(pfd.fileDescriptor)
            setVideoEncoder(if (useHevc) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
            setVideoSize(q.width, q.height)
            setVideoFrameRate(q.fps)
            setCaptureRate(q.fps.toDouble())
            setVideoEncodingBitRate(cfg.bitrateFor(q))
            if (cfg.audio) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(cfg.audioSampleRate)
                setAudioChannels(cfg.audioChannels)
                setAudioEncodingBitRate(cfg.audioBitrate)
            }
            setOrientationHint(orientationHint)
            prepare()
        }
    }

    private fun cleanupRecorder(deleteFile: Boolean) {
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { recordPfd?.close() }
        recordPfd = null
        val uri = recordUri ?: return
        recordUri = null
        if (deleteFile) {
            runCatching { ctx.contentResolver.delete(uri, null, null) }
        } else {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
                recordDescription?.let { put(MediaStore.Video.Media.DESCRIPTION, it) }
            }
            runCatching { ctx.contentResolver.update(uri, values, null, null) }
        }
    }

    private fun ensureStab() {
        if (pipeline != null) return
        val c = caps ?: return
        val cfg = config ?: return
        if (!(((controls.stab && cfg.stabEnabled) || cfg.postMode) && c.facingBack && !quality.highSpeed)) return
        val hdr = cfg.hdr && c.supportsHlg10 && cfg.codec.equals("hevc", true)
        val g = GyroTracker(ctx, cfg.gyroAxes)
        if (!g.start()) { Logger.e(TAG, "Стабилизация отключена: нет гироскопа"); return }
        val k = c.intrinsicsFor(quality.width, quality.height)
        val readout = if (cfg.stabReadoutNs > 0) cfg.stabReadoutNs else c.readoutNs ?: 8_000_000L
        Logger.i(TAG, "Стабилизация: K=[${k.joinToString { "%.1f".format(it) }}] readout=${readout / 1000}мкс crop=${cfg.stabCrop} макс=${cfg.stabMaxAngle}° сдвиг гиро=${cfg.stabTimeOffsetMs}мс HLG=$hdr")
        fun make(h: Boolean) = StabPipeline(
            g, quality.width, quality.height, k,
            Stabilizer.Params(cfg.stabMaxAngle, cfg.stabTauMax, cfg.stabTauMin, cfg.stabVelTau, tanHalfFov = (quality.width / 2.0) / k[0], minCrop = cfg.stabMinCrop.toDouble(), maxCrop = cfg.stabCrop.toDouble()), cfg.stabCrop, readout,
            cfg.stabSharpen, cfg.stabBicubic, cfg.stabDenoise, cfg.stabDenoiseSigma, cfg.stabTimeOffsetMs, h,
        )
        val p = try { make(hdr) } catch (e: Exception) {
            Logger.e(TAG, "Конвейер ${if (hdr) "10-бит" else ""} не запустился", e)
            if (!hdr) { g.stop(); return }
            try { make(false) } catch (e2: Exception) { Logger.e(TAG, "Стабилизация не запустилась, обычный режим", e2); g.stop(); return }
        }
        p.setZoom(controls.zoom)
        p.previewRot = cfg.stabPreviewRot
        p.rawMode = cfg.postMode
        p.setLut(lut, lutStrength)
        p.setPreview(previewSurface)
        gyro = g
        pipeline = p
        Logger.i(TAG, "Конвейер: ${if (p.is10bit) "10-бит HLG" else "8-бит"}")
    }

    private fun startStab(orientationHint: Int) {
        val p = pipeline ?: return
        val cfg = config ?: return
        val q = quality
        try {
            val pfd = createOutput()
            recordDescription = summary(p)
            recordHint = orientationHint
            val hevc = cfg.codec.equals("hevc", true) && CameraCaps.encoderSupports(MediaFormat.MIMETYPE_VIDEO_HEVC, q.width, q.height, q.fps)
            val rec = StabRecorder(
                pfd.fileDescriptor, q.width, q.height, q.fps, (cfg.bitrateFor(q) * (if (cfg.postMode) cfg.postBitrateFactor else 1.0)).toInt(), hevc, orientationHint,
                if (cfg.audio) StabRecorder.Audio(cfg.audioSampleRate, cfg.audioChannels, cfg.audioBitrate) else null,
                p.is10bit,
            )
            if (cfg.postMode) gyro?.startLog()
            p.startRecording(rec)
            stabRecording = true
            isRecording = true
            listener.onRecordingStarted()
        } catch (e: Exception) {
            Logger.e(TAG, "stab record start", e)
            cleanupRecorder(deleteFile = true)
            listener.onError("Не удалось начать запись: ${e.message}")
        }
    }

    private fun summary(p: StabPipeline): String {
        val cfg = config
        val q = quality
        val parts = mutableListOf(
            if (cfg?.postMode == true) "RAW for post-processing" else "",
            "${q.width}x${q.height} ${q.fps}fps",
            (if (cfg?.codec.equals("hevc", true)) "HEVC" else "H.264") + if (p.is10bit) " 10-bit HLG" else " 8-bit",
            "${(cfg?.bitrateFor(q) ?: 0) / 1_000_000} Mbps",
            "gyro stabilization (crop ${"%.2f".format(cfg?.stabCrop ?: 0f)}, denoise ${cfg?.stabDenoise})",
            "OIS " + if (controls.ois) "on" else "off",
        )
        if (!cfg?.lutId.isNullOrEmpty()) parts += "LUT ${cfg?.lutId}"
        return parts.filter { it.isNotEmpty() }.joinToString(" | ")
    }

    /** POST mode sidecars: gyro log (.gcsv, Gyroflow compatible) and frame/camera metadata (.meta.json). */
    private fun writePostSidecars(p: StabPipeline, ok: Boolean, uri: Uri?) {
        val g = gyro ?: return
        val (t, w, n) = g.stopLog()
        val base = p.lastBaseTs
        val (ft, fe, fn) = p.frameLog()
        if (!ok || base < 0 || fn == 0) return
        val cfg = config ?: return
        val c = caps ?: return
        val q = quality
        try {
            val nameBase = recordName.removeSuffix(".mp4")
            val dir = java.io.File(ctx.filesDir, "post").apply { mkdirs() }
            val gc = GyroLog.gcsv(base, t, w, n, base - 400_000_000L, ft[fn - 1] + 400_000_000L, note = "StabCam ${com.gl1ch5.stabcam.BuildConfig.VERSION_NAME} ${android.os.Build.MODEL}")
            java.io.File(dir, "$nameBase.gcsv").writeText(gc)
            val frames = org.json.JSONArray()
            for (i in 0 until fn) frames.put(org.json.JSONArray().put(ft[i] - base).put(fe[i]))
            val k = c.intrinsicsFor(q.width, q.height)
            val meta = org.json.JSONObject()
                .put("version", 1).put("video", recordName).put("uri", uri?.toString() ?: "")
                .put("width", q.width).put("height", q.height).put("fps", q.fps)
                .put("baseTsNs", base).put("orientationHint", recordHint).put("sensorOrientation", c.sensorOrientation)
                .put("intrinsics", org.json.JSONArray(k.map { it.toDouble() }))
                .put("readoutNs", p.readout).put("hdr", p.is10bit).put("zoom", controls.zoom.toDouble())
                .put("gyroAxes", org.json.JSONArray(cfg.gyroAxes))
                .put("frames", frames)
            java.io.File(dir, "$nameBase.meta.json").writeText(meta.toString())
            Logger.i(TAG, "ПОСТ: записаны гиро-лог (${n} отсчётов) и метаданные ($fn кадров)")
            if (cfg.postExportGcsv) {
                val v = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "$nameBase.gcsv")
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/StabCam")
                }
                ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)?.let { u ->
                    ctx.contentResolver.openOutputStream(u)?.use { it.write(gc.toByteArray()) }
                    Logger.i(TAG, "gcsv для Gyroflow: Download/StabCam/$nameBase.gcsv")
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Не удалось записать sidecar", e)
        }
    }

    private fun stopStab() {
        val ok = pipeline?.stopRecording() ?: false
        if (config?.postMode == true) pipeline?.let { writePostSidecars(it, ok, recordUri) }
        if (ok) recordPfd?.fileDescriptor?.let { fd ->
            val soft = "StabCam ${com.gl1ch5.stabcam.BuildConfig.VERSION_NAME}"
            val desc = recordDescription ?: ""
            Mp4Tagger.tag(fd, listOf(
                "mak" to android.os.Build.MANUFACTURER, "mod" to android.os.Build.MODEL, "swr" to soft, "too" to soft,
                "cmt" to desc, "des" to desc, "inf" to "SoC ${android.os.Build.SOC_MODEL}",
            ))
        }
        isRecording = false
        stabRecording = false
        val uri = recordUri
        cleanupRecorder(deleteFile = !ok)
        listener.onRecordingStopped(if (ok) uri else null)
    }

    private fun closeInternal() {
        if (stabRecording) stopStab()
        pipeline?.release(); pipeline = null
        gyro?.stop(); gyro = null
        if (isRecording) {
            isRecording = false
            val ok = runCatching { recorder?.stop() }.isSuccess
            val uri = recordUri
            cleanupRecorder(deleteFile = !ok)
            listener.onRecordingStopped(if (ok) uri else null)
        }
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
    }

    companion object {
        private const val TAG = "VideoCamera"
    }
}
