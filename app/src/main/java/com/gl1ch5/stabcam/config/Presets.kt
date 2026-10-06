package com.gl1ch5.stabcam.config

import org.json.JSONObject

/** Human-friendly levels that map to the numeric config keys (shared by the quick menu and Settings). */
object Presets {
    class Level(val label: String, val values: Map<String, Any>)

    /** Stabilization modes (GoPro-style): bigger margin = more shake absorbed, tighter field of view. The crop adapts to the shake, so calm scenes stay wide. */
    val strength = listOf(
        Level("Лёгкая", mapOf("stab.crop" to 1.10, "stab.tauMaxMs" to 300, "stab.maxAngleDeg" to 4.0)),
        Level("Стандарт", mapOf("stab.crop" to 1.15, "stab.tauMaxMs" to 500, "stab.maxAngleDeg" to 6.0)),
        Level("Ходьба / бег", mapOf("stab.crop" to 1.30, "stab.tauMaxMs" to 700, "stab.maxAngleDeg" to 12.0)),
        Level("Максимум", mapOf("stab.crop" to 1.40, "stab.tauMaxMs" to 900, "stab.maxAngleDeg" to 15.0)),
    )
    val horizon = listOf(
        Level("Выкл", mapOf("stab.horizonDeg" to 0.0)),
        Level("±10°", mapOf("stab.horizonDeg" to 10.0)),
        Level("±25°", mapOf("stab.horizonDeg" to 25.0)),
        Level("±45° (ПОСТ)", mapOf("stab.horizonDeg" to 45.0)),
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
