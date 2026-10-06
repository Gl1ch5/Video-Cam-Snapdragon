package com.gl1ch5.stabcam.config

import org.json.JSONObject

/** Human-friendly levels that map to the numeric config keys (shared by the quick menu and Settings). */
object Presets {
    class Level(val label: String, val values: Map<String, Any>)

    val strength = listOf(
        Level("Слабая", mapOf("stab.tauMaxMs" to 300, "stab.crop" to 1.08, "stab.maxAngleDeg" to 4.0)),
        Level("Средняя", mapOf("stab.tauMaxMs" to 500, "stab.crop" to 1.12, "stab.maxAngleDeg" to 6.0)),
        Level("Сильная", mapOf("stab.tauMaxMs" to 800, "stab.crop" to 1.16, "stab.maxAngleDeg" to 8.0)),
    )
    val denoise = listOf(
        Level("Выкл", mapOf("stab.denoise" to 0.0)),
        Level("Слабое", mapOf("stab.denoise" to 0.3)),
        Level("Среднее", mapOf("stab.denoise" to 0.5)),
        Level("Сильное", mapOf("stab.denoise" to 0.75)),
    )
    val sharpen = listOf(
        Level("Выкл", mapOf("stab.sharpen" to 0.0)),
        Level("Мягко", mapOf("stab.sharpen" to 0.2)),
        Level("Средне", mapOf("stab.sharpen" to 0.35)),
        Level("Сильно", mapOf("stab.sharpen" to 0.6)),
    )
    val bitrate = listOf(
        Level("Экономно", mapOf("video.bitrateScale" to 0.7)),
        Level("Стандарт", mapOf("video.bitrateScale" to 1.0)),
        Level("Максимум", mapOf("video.bitrateScale" to 1.4)),
    )

    /** Index of the level whose first value is closest to what is in [json]. */
    fun indexOf(levels: List<Level>, json: JSONObject): Int {
        val (path, _) = levels[0].values.entries.first().let { it.key to it.value }
        val cur = read(json, path)?.toDoubleOrNull() ?: return levels.size / 2
        return levels.indices.minByOrNull { i -> kotlin.math.abs((levels[i].values[path] as Number).toDouble() - cur) } ?: 0
    }

    fun apply(repo: ConfigRepository, level: Level) = level.values.forEach { (k, v) -> repo.set(k, v) }

    private fun read(json: JSONObject, path: String): String? {
        var n: Any? = json
        for (k in path.split('.')) n = (n as? JSONObject)?.opt(k) ?: return null
        return n.toString()
    }
}
