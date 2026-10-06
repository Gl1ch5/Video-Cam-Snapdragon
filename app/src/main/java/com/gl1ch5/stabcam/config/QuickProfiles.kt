package com.gl1ch5.stabcam.config

import org.json.JSONObject

/** One-tap shooting profiles (shown on the mode label at the bottom of the camera screen). */
object QuickProfiles {
    class Profile(val id: String, val name: String, val values: Map<String, Any>)

    private fun p(id: String, name: String, vararg v: Pair<String, Any>) = Profile(id, name, mapOf(*v))

    val builtin = listOf(
        p("auto", "Авто", "stab.tauMaxMs" to 500, "stab.crop" to 1.15, "stab.maxAngleDeg" to 6.0, "stab.denoise" to 0.5, "stab.sharpen" to 0.35, "stab.horizonDeg" to 0.0, "video.lut" to "", "video.hdr" to "off"),
        p("walk", "Прогулка", "stab.tauMaxMs" to 800, "stab.crop" to 1.22, "stab.maxAngleDeg" to 9.0, "stab.denoise" to 0.5, "stab.sharpen" to 0.35, "stab.horizonDeg" to 10.0, "video.lut" to "", "video.hdr" to "off"),
        p("sport", "Спорт", "stab.tauMaxMs" to 300, "stab.crop" to 1.10, "stab.maxAngleDeg" to 4.0, "stab.denoise" to 0.3, "stab.sharpen" to 0.4, "stab.horizonDeg" to 0.0, "video.lut" to "vivid", "video.lutStrength" to 0.7, "video.hdr" to "off"),
        p("night", "Ночь", "stab.tauMaxMs" to 600, "stab.crop" to 1.15, "stab.denoise" to 0.75, "stab.sharpen" to 0.2, "stab.horizonDeg" to 0.0, "video.lut" to "", "video.hdr" to "off"),
        p("cinema", "Кино", "stab.tauMaxMs" to 1200, "stab.crop" to 1.2, "stab.maxAngleDeg" to 9.0, "stab.denoise" to 0.5, "stab.sharpen" to 0.3, "stab.horizonDeg" to 10.0, "video.lut" to "cinema", "video.lutStrength" to 0.85, "video.hdr" to "off"),
        p("hdr", "HDR", "stab.tauMaxMs" to 500, "stab.crop" to 1.15, "stab.denoise" to 0.5, "stab.sharpen" to 0.3, "stab.horizonDeg" to 0.0, "video.lut" to "", "video.hdr" to "hlg10"),
    )

    /** Built-ins plus the presets that enabled modules contribute. */
    fun all(modulePresets: List<Pair<String, JSONObject>>): List<Profile> =
        builtin + modulePresets.map { (name, cfg) -> Profile("mod:$name", name, flatten(cfg)) }

    fun flatten(o: JSONObject, prefix: String = ""): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for (k in o.keys()) {
            val v = o.get(k); val path = if (prefix.isEmpty()) k else "$prefix.$k"
            if (v is JSONObject && path != "video.bitrateMbps") out.putAll(flatten(v, path)) else out[path] = v
        }
        return out
    }

    fun apply(repo: ConfigRepository, profile: Profile) {
        profile.values.forEach { (k, v) -> repo.set(k, v) }
        repo.set("ui.profile", profile.id)
    }
}
