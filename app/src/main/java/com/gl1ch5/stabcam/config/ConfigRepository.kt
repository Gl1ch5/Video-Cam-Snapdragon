package com.gl1ch5.stabcam.config

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Layered JSON config:
 *   assets/config/default.json  ←  best matching preset from assets/config/presets  ←  files/config_user.json
 *
 * Later layers deep-merge over earlier ones. Only the user layer is writable.
 */
class ConfigRepository(private val ctx: Context) {

    data class Preset(
        val file: String,
        val name: String,
        val description: String,
        val priority: Int,
        val match: JSONObject,
        val config: JSONObject,
    )

    private val userFile = File(ctx.filesDir, "config_user.json")

    val presets: List<Preset> by lazy { loadPresets() }

    /** Preset chosen automatically for this device (highest priority among matching). */
    val activePreset: Preset? by lazy {
        presets.filter { matches(it.match) }.maxByOrNull { it.priority }
    }

    fun defaults(): JSONObject = JSONObject(readAsset("config/default.json"))

    fun userOverrides(): JSONObject =
        if (userFile.exists()) runCatching { JSONObject(userFile.readText()) }.getOrElse { JSONObject() }
        else JSONObject()

    fun saveUserOverrides(json: JSONObject) {
        userFile.writeText(json.toString(2))
    }

    fun resetUserOverrides() {
        userFile.delete()
    }

    /** Sets one value in the user layer, e.g. set("camera.ois", true). */
    fun set(path: String, value: Any) {
        val root = userOverrides()
        val keys = path.split('.')
        var node = root
        for (k in keys.dropLast(1)) {
            node = node.optJSONObject(k) ?: JSONObject().also { node.put(k, it) }
        }
        node.put(keys.last(), value)
        saveUserOverrides(root)
    }

    fun effectiveJson(): JSONObject {
        val merged = defaults()
        activePreset?.let { deepMerge(merged, it.config) }
        runCatching { deepMerge(merged, com.gl1ch5.stabcam.module.ModuleManager(ctx).configPatch()) }
        deepMerge(merged, userOverrides())
        return merged
    }

    fun load(): AppConfig = AppConfig(effectiveJson())

    private fun loadPresets(): List<Preset> {
        val names = ctx.assets.list("config/presets") ?: emptyArray()
        return names.filter { it.endsWith(".json") }.mapNotNull { file ->
            runCatching {
                val j = JSONObject(readAsset("config/presets/$file"))
                Preset(
                    file = file,
                    name = j.optString("name", file),
                    description = j.optString("description"),
                    priority = j.optInt("priority", 0),
                    match = j.optJSONObject("match") ?: JSONObject(),
                    config = j.optJSONObject("config") ?: JSONObject(),
                )
            }.getOrNull()
        }
    }

    private fun readAsset(path: String) = ctx.assets.open(path).bufferedReader().use { it.readText() }

    companion object {
        /** Device properties a preset "match" block can test. Every listed key must match (AND), any value in its list (OR). */
        fun deviceProps(): Map<String, String> = mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "brand" to Build.BRAND,
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "socManufacturer" to Build.SOC_MANUFACTURER,
            "socModel" to Build.SOC_MODEL,
        )

        fun matches(match: JSONObject, props: Map<String, String> = deviceProps()): Boolean {
            if (match.length() == 0) return false
            for (key in match.keys()) {
                val actual = props[key] ?: return false
                val wanted = match.opt(key)
                val list = when (wanted) {
                    is JSONArray -> (0 until wanted.length()).map { wanted.getString(it) }
                    else -> listOf(wanted.toString())
                }
                if (list.none { actual.contains(it, ignoreCase = true) }) return false
            }
            return true
        }

        /** Merges [src] into [dst] in place. Objects merge recursively; everything else is replaced. */
        fun deepMerge(dst: JSONObject, src: JSONObject): JSONObject {
            for (key in src.keys()) {
                val s = src.get(key)
                val d = dst.opt(key)
                if (s is JSONObject && d is JSONObject) deepMerge(d, s) else dst.put(key, s)
            }
            return dst
        }
    }
}
