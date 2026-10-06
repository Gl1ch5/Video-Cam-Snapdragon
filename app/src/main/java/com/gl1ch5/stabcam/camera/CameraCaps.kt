package com.gl1ch5.stabcam.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Range
import android.util.Size
import android.view.SurfaceHolder
import com.gl1ch5.stabcam.config.Quality

/** Everything the app needs to know about one camera, queried once. */
class CameraCaps(val id: String, val chars: CameraCharacteristics) {

    val facingBack = chars.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK
    val sensorOrientation: Int = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    private val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

    private val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.toList() ?: emptyList()
    val hasOis = CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in oisModes

    val eisModes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.toList() ?: emptyList()
    val hasStockEis = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in eisModes

    val hasOisData = CameraMetadata.STATISTICS_OIS_DATA_MODE_ON in
        (chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES)?.toList() ?: emptyList())

    val fpsRanges: List<Range<Int>> = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()

    val evRange: Range<Int> = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
    val evStep: Float = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f

    val zoomRange: Range<Float> = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f)

    val afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.toList() ?: emptyList()
    val nrModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList() ?: emptyList()
    val edgeModes = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList() ?: emptyList()
    val distortionModes = chars.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)?.toList() ?: emptyList()

    /** Rolling-shutter readout time in ns, or null if the HAL does not report it. */
    val readoutNs: Long? = null // reported per frame (CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)

    /** Intrinsics [fx, fy, cx, cy] in pixels of an output frame of [w]x[h] (centre-cropped to its aspect from the active array). */
    fun intrinsicsFor(w: Int, h: Int): FloatArray {
        val arr = chars.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            ?: chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
        val aw = arr.width().toFloat(); val ah = arr.height().toFloat()
        val cal = chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        val fx: Float; val fy: Float; val cx: Float; val cy: Float
        if (cal != null && cal.size >= 4 && cal[0] > 1f) {
            fx = cal[0]; fy = cal[1]; cx = cal[2]; cy = cal[3]
        } else {
            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 5f
            val pw = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: 6f
            fx = focal / pw * aw; fy = fx; cx = aw / 2; cy = ah / 2
        }
        var cropW = aw
        var cropH = aw * h / w
        if (cropH > ah) { cropH = ah; cropW = ah * w / h }
        val offX = (aw - cropW) / 2; val offY = (ah - cropH) / 2
        val s = w / cropW
        return floatArrayOf(fx * s, fy * s, (cx - offX) * s, (cy - offY) * s)
    }

    /** 10-bit HLG camera streams (Android 13+). */
    val supportsHlg10: Boolean = android.os.Build.VERSION.SDK_INT >= 33 &&
        (chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)?.supportedProfiles
            ?.contains(android.hardware.camera2.params.DynamicRangeProfiles.HLG10) == true)

    fun supportsHighSpeed(q: Quality): Boolean {
        val size = Size(q.width, q.height)
        val sizes = runCatching { map.highSpeedVideoSizes }.getOrNull() ?: return false
        return size in sizes && map.getHighSpeedVideoFpsRangesFor(size).any { it.upper >= q.fps && it.lower <= q.fps }
    }

    fun supports(q: Quality): Boolean {
        if (q.highSpeed) return supportsHighSpeed(q)
        val size = Size(q.width, q.height)
        val sizes = map.getOutputSizes(MediaRecorder::class.java) ?: return false
        if (size !in sizes) return false
        val minFrameNs = map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
        if (minFrameNs > 1_000_000_000L / q.fps + 100_000L) return false
        return fpsRanges.any { it.upper >= q.fps && it.lower <= q.fps }
    }

    fun qualities(forceAll: Boolean): List<Quality> =
        if (forceAll) Quality.entries.filter { if (it.highSpeed) supportsHighSpeed(it) else it.fps <= (fpsRanges.maxOfOrNull { r -> r.upper } ?: 30) } else Quality.entries.filter { supports(it) }

    val supportedQualities: List<Quality> get() = qualities(false)

    /** Fixed-fps range if available (what video wants), otherwise the widest one that reaches [fps]. */
    fun fpsRangeFor(fps: Int): Range<Int> =
        fpsRanges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: fpsRanges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: Range(fps, fps)

    /** 16:9 preview size, no larger than 1080p. */
    fun previewSize(): Size {
        val sizes = map.getOutputSizes(SurfaceHolder::class.java) ?: map.getOutputSizes(SurfaceTexture::class.java)
        return sizes
            .filter { it.width * 9 == it.height * 16 && it.width <= 1920 }
            .maxByOrNull { it.width * it.height }
            ?: Size(1920, 1080)
    }

    /** Human readable dump for the settings screen — used to plan the gyro-EIS stage. */
    fun report(): String = buildString {
        val level = when (chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> "?"
        }
        val tsSource = when (chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)) {
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> "REALTIME (синхронно с гиро)"
            else -> "UNKNOWN"
        }
        appendLine("Camera id: $id (${if (facingBack) "back" else "front"})")
        appendLine("Hardware level: $level")
        appendLine("Sensor orientation: $sensorOrientation°")
        appendLine("Timestamp source: $tsSource")
        appendLine("HLG10 (10-бит): ${if (supportsHlg10) "да" else "нет"}")
        appendLine("OIS: ${if (hasOis) "есть" else "нет"}   OIS data (STATISTICS_OIS_SAMPLES): ${if (hasOisData) "есть" else "нет"}")
        appendLine("Stock EIS modes: $eisModes")
        appendLine("Zoom ratio: ${zoomRange.lower}..${zoomRange.upper}")
        appendLine("FPS ranges: $fpsRanges")
        appendLine("Supported presets: ${supportedQualities.joinToString { it.label }}")
        chars.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.let { appendLine("Intrinsics [fx,fy,cx,cy,s]: ${it.toList()}") }
        chars.get(CameraCharacteristics.LENS_POSE_ROTATION)?.let { appendLine("Lens pose rotation: ${it.toList()}") }
        chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.let { appendLine("Focal lengths: ${it.toList()}") }
        chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { appendLine("Sensor size: $it mm") }
        chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { appendLine("Active array: $it") }
        val recSizes = map.getOutputSizes(MediaRecorder::class.java)
            ?.filter { it.width >= 1920 }
            ?.joinToString { s -> "${s.width}x${s.height}@${(1e9 / map.getOutputMinFrameDuration(MediaRecorder::class.java, s)).toInt()}" }
        appendLine("Recorder sizes: $recSizes")
        val hs = runCatching { map.highSpeedVideoSizes?.joinToString { s -> "${s.width}x${s.height}:" + map.getHighSpeedVideoFpsRangesFor(s).joinToString("/") { "${it.lower}-${it.upper}" } } }.getOrNull()
        appendLine("High-speed sizes: $hs")
        appendLine("HEVC 4K60 encoder: ${encoderSupports(MediaFormat.MIMETYPE_VIDEO_HEVC, 3840, 2160, 60)}")
        val vendorReq = chars.availableCaptureRequestKeys.map { it.name }.filterNot { it.startsWith("android.") }
        val vendorRes = chars.availableCaptureResultKeys.map { it.name }.filterNot { it.startsWith("android.") }
        appendLine()
        appendLine("Vendor request keys (${vendorReq.size}):")
        vendorReq.sorted().forEach { appendLine("  $it") }
        appendLine("Vendor result keys (${vendorRes.size}):")
        vendorRes.sorted().forEach { appendLine("  $it") }
    }

    companion object {
        fun find(ctx: Context, back: Boolean): CameraCaps? {
            val mgr = ctx.getSystemService(CameraManager::class.java)
            val wanted = if (back) CameraMetadata.LENS_FACING_BACK else CameraMetadata.LENS_FACING_FRONT
            // First camera with the right facing is the primary (logical) one on practically all devices.
            val id = mgr.cameraIdList.firstOrNull {
                mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted
            } ?: return null
            return CameraCaps(id, mgr.getCameraCharacteristics(id))
        }

        fun encoderSupports(mime: String, w: Int, h: Int, fps: Int): Boolean =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mime, true) } &&
                    runCatching {
                        info.getCapabilitiesForType(mime).videoCapabilities
                            .areSizeAndRateSupported(w, h, fps.toDouble())
                    }.getOrDefault(false)
            }
    }
}
