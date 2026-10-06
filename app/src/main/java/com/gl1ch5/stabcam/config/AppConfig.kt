package com.gl1ch5.stabcam.config

import org.json.JSONObject

/** Typed read-only view over the merged config JSON. */
class AppConfig(val json: JSONObject) {
    private val video = json.optJSONObject("video") ?: JSONObject()
    private val camera = json.optJSONObject("camera") ?: JSONObject()
    private val update = json.optJSONObject("update") ?: JSONObject()
    private val stab = json.optJSONObject("stab") ?: JSONObject()
    private val diag = json.optJSONObject("diagnostics") ?: JSONObject()
    private val ui = json.optJSONObject("ui") ?: JSONObject()

    val quality: Quality = Quality.parse(video.optString("quality", "4K60"))
    val codec: String = video.optString("codec", "hevc")
    val audio: Boolean = video.optBoolean("audio", true)
    val audioBitrate: Int = video.optInt("audioBitrateKbps", 256) * 1000
    val audioSampleRate: Int = video.optInt("audioSampleRate", 48000)
    val audioChannels: Int = video.optInt("audioChannels", 2)

    fun bitrateFor(q: Quality): Int {
        val map = video.optJSONObject("bitrateMbps")
        val mbps = map?.optDouble(q.id, Double.NaN)?.takeIf { !it.isNaN() } ?: q.defaultMbps
        return (mbps * 1_000_000).toInt()
    }

    val lensFacingBack: Boolean = camera.optString("lens", "back") != "front"
    val ois: Boolean = camera.optBoolean("ois", true)
    val stockEis: Boolean = camera.optBoolean("stockEis", false)
    val noiseReduction: String = camera.optString("noiseReduction", "fast")
    val edge: String = camera.optString("edge", "fast")
    val distortionCorrection: String = camera.optString("distortionCorrection", "fast")

    /** [{"name": "org.vendor.key", "type": "int|long|float|boolean|byte", "value": 1}] */
    val vendorTags: List<VendorTag> = buildList {
        val arr = camera.optJSONArray("vendorTags") ?: return@buildList
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            add(VendorTag(o.optString("name"), o.optString("type", "int"), o.opt("value")))
        }
    }

    /** Offer every quality even if Camera2 does not advertise it (OEMs hide modes from third-party apps). */
    val forceAllQualities: Boolean = camera.optBoolean("forceAllQualities", false)

    val updateAuto: Boolean = update.optBoolean("auto", true)
    val updateRepo: String = update.optString("repo", "Gl1ch5/Video-Cam-Snapdragon")
    val updateTag: String = update.optString("tag", "nightly")
    val probeOnStart: Boolean = diag.optBoolean("probeOnStart", true)

    val stabEnabled: Boolean = stab.optBoolean("enabled", true)
    val stabCrop: Float = stab.optDouble("crop", 1.10).toFloat()
    val stabMaxAngle: Double = stab.optDouble("maxAngleDeg", 4.0)
    val stabTauMax: Double = stab.optDouble("tauMaxMs", 350.0) / 1000
    val stabTauMin: Double = stab.optDouble("tauMinMs", 40.0) / 1000
    val stabVelTau: Double = stab.optDouble("velTauMs", 250.0) / 1000
    val stabReadoutNs: Long = (stab.optDouble("readoutMs", 0.0) * 1e6).toLong()
    val gyroAxes: List<String> = buildList {
        val a = stab.optJSONArray("gyroAxes") ?: return@buildList
        for (i in 0 until a.length()) add(a.optString(i))
    }

    val showInfo: Boolean = ui.optBoolean("showInfo", false)

    data class VendorTag(val name: String, val type: String, val value: Any?)
}

enum class Quality(val id: String, val width: Int, val height: Int, val fps: Int, val label: String, val defaultMbps: Double) {
    UHD120("4K120", 3840, 2160, 120, "4K·120", 150.0),
    UHD60("4K60", 3840, 2160, 60, "4K·60", 120.0),
    UHD30("4K30", 3840, 2160, 30, "4K·30", 80.0),
    K33_60("3.3K60", 3280, 1856, 60, "3.3K·60", 70.0),
    FHD60("1080p60", 1920, 1080, 60, "1080·60", 40.0),
    FHD30("1080p30", 1920, 1080, 30, "1080·30", 24.0);

    /** >60 fps goes through a Camera2 constrained high-speed session. */
    val highSpeed: Boolean get() = fps > 60

    companion object {
        fun parse(id: String) = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: UHD60
    }
}
