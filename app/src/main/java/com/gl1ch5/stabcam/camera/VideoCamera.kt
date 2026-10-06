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
import android.util.Log
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
        fun onRecordingStarted()
        fun onRecordingStopped(uri: Uri?)
    }

    /** Live controls changed from the UI without a session restart. */
    data class Controls(
        val ois: Boolean,
        val stockEis: Boolean,
        val evIndex: Int = 0,
        val zoom: Float = 1f,
    )

    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val mgr = ctx.getSystemService(CameraManager::class.java)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var sessionHasRecorder = false
    private var caps: CameraCaps? = null
    private var previewSurface: Surface? = null
    private var config: AppConfig? = null
    private var quality: Quality = Quality.UHD60
    @Volatile var controls = Controls(ois = true, stockEis = false)
        private set

    private var recorder: MediaRecorder? = null
    private var recordUri: Uri? = null
    private var recordPfd: ParcelFileDescriptor? = null
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

    fun updateControls(c: Controls) = handler.post {
        controls = c
        applyRepeating()
    }

    fun startRecording(orientationHint: Int) = handler.post {
        if (isRecording || device == null) return@post
        try {
            prepareRecorder(orientationHint)
            createSession(withRecorder = true)
        } catch (e: Exception) {
            Log.e(TAG, "prepare failed", e)
            cleanupRecorder(deleteFile = true)
            listener.onError("Не удалось начать запись: ${e.message}")
        }
    }

    fun stopRecording() = handler.post {
        if (!isRecording) return@post
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

        val targets = buildList {
            add(preview)
            if (withRecorder) recorder?.surface?.let { add(it) }
        }
        val outputs = targets.map { OutputConfiguration(it) }
        val sessionConfig = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR, outputs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    sessionHasRecorder = withRecorder
                    applyRepeating()
                    if (withRecorder && recorder != null) {
                        try {
                            recorder?.start()
                            isRecording = true
                            listener.onRecordingStarted()
                        } catch (e: Exception) {
                            Log.e(TAG, "recorder start failed", e)
                            cleanupRecorder(deleteFile = true)
                            listener.onError("Запись не стартовала: ${e.message}")
                            createSession(withRecorder = false)
                        }
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    listener.onError("Камера не поддерживает ${quality.label} в этой конфигурации")
                    if (withRecorder) {
                        cleanupRecorder(deleteFile = true)
                        createSession(withRecorder = false)
                    }
                }
            })
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
        val targets = buildList {
            previewSurface?.let { add(it) }
            if (sessionHasRecorder) recorder?.surface?.let { add(it) }
        }
        val builder = buildRequest(dev, targets) ?: return
        runCatching { s.setRepeatingRequest(builder.build(), null, handler) }
            .onFailure { Log.w(TAG, "setRepeatingRequest", it) }
    }

    private fun buildRequest(dev: CameraDevice, targets: List<Surface>): CaptureRequest.Builder? {
        val caps = caps ?: return null
        val cfg = config ?: return null
        val c = controls
        return dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            targets.forEach { addTarget(it) }
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, caps.fpsRangeFor(quality.fps))
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
                if (c.stockEis && caps.hasStockEis) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
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
                    else -> Log.w(TAG, "Unknown vendor tag type ${t.type}")
                }
            }.onFailure { Log.w(TAG, "Vendor tag ${t.name} rejected", it) }
        }
    }

    private fun prepareRecorder(orientationHint: Int) {
        val cfg = config!!
        val q = quality
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
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw") ?: error("open fd failed")
        recordPfd = pfd

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
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            runCatching { ctx.contentResolver.update(uri, values, null, null) }
        }
    }

    private fun closeInternal() {
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
