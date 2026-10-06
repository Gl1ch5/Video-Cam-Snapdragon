package com.gl1ch5.stabcam.module

import android.content.Context
import com.gl1ch5.stabcam.lut.Lut
import com.gl1ch5.stabcam.lut.Luts
import com.gl1ch5.stabcam.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * `.module` files: a JSON document that can carry a config patch, LUTs (inline .cube text) and named presets.
 *
 * {
 *   "module": 1, "id": "com.example.cinema", "name": "Кино-пак", "author": "", "version": "1.0", "description": "",
 *   "config":  { "stab": { "denoise": 0.6 }, "video": { "bitrateScale": 1.2 } },
 *   "luts":    [ { "name": "Мой стиль", "cube": "LUT_3D_SIZE 17\n0 0 0\n..." } ],
 *   "presets": [ { "name": "Прогулка", "config": { "stab": { "crop": 1.1 } } } ]
 * }
 *
 * Modules cannot change update settings or run code: the config patch is checked against an allow-list.
 */
class ModuleManager(private val ctx: Context) {

    class Module(val id: String, val name: String, val author: String, val version: String, val description: String, val json: JSONObject, var enabled: Boolean) {
        val lutCount get() = json.optJSONArray("luts")?.length() ?: 0
        val presetCount get() = json.optJSONArray("presets")?.length() ?: 0
    }

    sealed class Result {
        class Ok(val module: Module, val summary: List<String>) : Result()
        class Error(val message: String) : Result()
    }

    private val dir = File(ctx.filesDir, "modules").apply { mkdirs() }
    private val stateFile = File(dir, "state.json")

    private fun enabledIds(): MutableSet<String> {
        val s = runCatching { JSONArray(stateFile.readText()) }.getOrNull() ?: return mutableSetOf()
        return (0 until s.length()).map { s.getString(it) }.toMutableSet()
    }

    private fun saveEnabled(ids: Set<String>) { stateFile.writeText(JSONArray(ids.toList()).toString()) }

    fun list(): List<Module> {
        val en = enabledIds()
        return (dir.listFiles { f -> f.name.endsWith(".module") } ?: emptyArray()).sortedBy { it.name }.mapNotNull { f ->
            runCatching { Companion.parse(JSONObject(f.readText()), en) }.getOrNull()
        }
    }

    fun check(text: String): Result = Companion.check(text)

    fun install(m: Module) {
        File(dir, "${m.id}.module").writeText(m.json.toString())
        saveEnabled(enabledIds().also { it += m.id })
        Logger.i("Module", "Установлен ${m.id} ${m.version}")
    }

    fun remove(id: String) {
        File(dir, "$id.module").delete()
        saveEnabled(enabledIds().also { it -= id })
    }

    fun setEnabled(id: String, on: Boolean) = saveEnabled(enabledIds().also { if (on) it += id else it -= id })

    /** Deep-merged config patches of all enabled modules (in file-name order). */
    fun configPatch(): JSONObject {
        val out = JSONObject()
        for (m in list().filter { it.enabled }) m.json.optJSONObject("config")?.let { merge(out, it) }
        return out
    }

    fun lutEntries(): List<Luts.Entry> = list().filter { it.enabled }.flatMap { m ->
        val arr = m.json.optJSONArray("luts") ?: return@flatMap emptyList()
        (0 until arr.length()).map { i -> Luts.Entry("mod:${m.id}/$i", arr.getJSONObject(i).optString("name", "LUT $i"), "мод · ${m.name}") }
    }

    fun lut(id: String): Lut? {
        val (mid, idx) = id.removePrefix("mod:").split('/').let { it[0] to it.getOrNull(1)?.toIntOrNull() }
        val m = list().firstOrNull { it.id == mid && it.enabled } ?: return null
        val o = m.json.optJSONArray("luts")?.optJSONObject(idx ?: return null) ?: return null
        return Luts.parseCube(id, o.optString("name", "LUT"), o.optString("cube"))
    }

    fun presets(): List<Pair<String, JSONObject>> = list().filter { it.enabled }.flatMap { m ->
        val arr = m.json.optJSONArray("presets") ?: return@flatMap emptyList()
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { (it.optString("name", "Пресет") + " · " + m.name) to (it.optJSONObject("config") ?: JSONObject()) } }
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private fun merge(dst: JSONObject, src: JSONObject) {
        for (k in src.keys()) {
            val s = src.get(k); val d = dst.opt(k)
            if (s is JSONObject && d is JSONObject) merge(d, s) else dst.put(k, s)
        }
    }

    /** Current user-visible settings as a shareable module. */
    fun exportCurrent(effective: JSONObject, name: String): JSONObject {
        val cfg = JSONObject()
        for (path in ALLOWED) {
            var n: Any? = effective
            for (k in path.split('.')) n = (n as? JSONObject)?.opt(k) ?: run { n = null; null }
            if (n == null) continue
            var node = cfg
            val parts = path.split('.')
            for (k in parts.dropLast(1)) node = node.optJSONObject(k) ?: JSONObject().also { node.put(k, it) }
            node.put(parts.last(), n)
        }
        return JSONObject().put("module", 1).put("id", "user." + name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "settings" } + "-" + (System.currentTimeMillis() / 1000 % 100000))
            .put("name", name).put("author", "").put("version", "1.0").put("description", "Экспорт настроек StabCam").put("config", cfg)
    }

    companion object {
    fun parse(j: JSONObject, en: Set<String>): Module {
        val id = j.getString("id")
        return Module(id, j.optString("name", id), j.optString("author"), j.optString("version", "1"), j.optString("description"), j, id in en)
    }

    /** Validates [text] (JSON) without installing it. */
    fun check(text: String): Result {
        val j = try { JSONObject(text.trim()) } catch (e: Exception) { return Result.Error("Это не JSON: ${e.message?.take(80)}") }
        if (j.optInt("module", 0) != 1) return Result.Error("Нет поля \"module\": 1 (формат .module версии 1)")
        val id = j.optString("id")
        if (!Regex("^[a-z0-9][a-z0-9._-]{2,63}$").matches(id)) return Result.Error("Поле id: 3–64 символа a-z, 0-9, точка, дефис")
        if (text.length > 6_000_000) return Result.Error("Файл слишком большой (макс. 6 МБ)")
        val summary = ArrayList<String>()
        j.optJSONObject("config")?.let {
            val bad = ArrayList<String>(); val keys = ArrayList<String>()
            walk(it, "", keys, bad)
            if (bad.isNotEmpty()) return Result.Error("Недопустимые ключи конфига: ${bad.take(5).joinToString()}")
            if (keys.isNotEmpty()) summary += "Настройки: ${keys.take(12).joinToString()}" + if (keys.size > 12) " и ещё ${keys.size - 12}" else ""
        }
        j.optJSONArray("luts")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: return Result.Error("luts[$i]: ожидается объект")
                val lut = Luts.parseCube("mod:$id/$i", o.optString("name", "LUT $i"), o.optString("cube"))
                    ?: return Result.Error("luts[$i] «${o.optString("name")}»: не удалось прочитать 3D .cube")
                summary += "LUT: ${lut.name} (${lut.size}³)"
            }
        }
        j.optJSONArray("presets")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: return Result.Error("presets[$i]: ожидается объект")
                val keys = ArrayList<String>(); val bad = ArrayList<String>()
                walk(o.optJSONObject("config") ?: JSONObject(), "", keys, bad)
                if (bad.isNotEmpty()) return Result.Error("presets[$i]: недопустимые ключи ${bad.take(5).joinToString()}")
                summary += "Пресет: ${o.optString("name", "Пресет $i")}"
            }
        }
        if (summary.isEmpty()) return Result.Error("Модуль пустой: нужны config, luts или presets")
        return Result.Ok(parse(j, emptySet()), summary)
    }

    private fun walk(o: JSONObject, prefix: String, keys: MutableList<String>, bad: MutableList<String>) {
        for (k in o.keys()) {
            val path = if (prefix.isEmpty()) k else "$prefix.$k"
            val v = o.get(k)
            if (v is JSONObject && path != "video.bitrateMbps") walk(v, path, keys, bad)
            else if (ALLOWED.any { path == it || path.startsWith("$it.") }) keys += path else bad += path
        }
    }


        /** Config paths a module may set. No update.*, no vendorTags, no diagnostics. */
        val ALLOWED = listOf(
            "video.quality", "video.codec", "video.hdr", "video.audio", "video.bitrateScale", "video.bitrateMbps", "video.lut", "video.lutStrength",
            "stab.enabled", "stab.crop", "stab.minCrop", "stab.maxAngleDeg", "stab.tauMaxMs", "stab.tauMinMs", "stab.velTauMs",
            "stab.sharpen", "stab.bicubic", "stab.denoise", "stab.denoiseSigma", "stab.timeOffsetMs",
            "camera.ois", "camera.stockEis", "camera.noiseReduction", "camera.edge", "camera.distortionCorrection",
            "ui.showInfo", "ui.mode", "post.enabled", "post.bitrateFactor", "post.exportGcsv",
        )

        /** Prompt to paste into a chat AI so it writes a valid .module for the user. */
        fun aiPrompt(): String = """
Ты помогаешь делать моды для камеры StabCam. Мод — это один JSON-файл (расширение .module). Верни ТОЛЬКО JSON, без пояснений и без markdown.

Формат:
{
  "module": 1,
  "id": "user.my-mod",              // a-z, 0-9, точка, дефис, 3-64 символа
  "name": "Название", "author": "", "version": "1.0", "description": "что делает",
  "config": { ... },                // необязательно: изменения настроек
  "luts": [ { "name": "Имя", "cube": "TITLE x\nLUT_3D_SIZE 17\nr g b\n..." } ],   // необязательно: 3D LUT в формате .cube, значения 0..1, красный меняется быстрее всего, LUT_3D_SIZE^3 строк
  "presets": [ { "name": "Имя", "config": { ... } } ]   // необязательно: именованные наборы настроек
}

Разрешённые ключи config (остальные модуль не примет):
video.quality: "4K120"|"4K60"|"4K30"|"3.3K60"|"1080p60"|"1080p30"; video.codec: "hevc"|"avc"; video.hdr: "off"|"hlg10";
video.audio: true|false; video.bitrateScale: 0.5..2; video.lut: ""|"rich"|"vivid"|"warm"|"cool"|"cinema"|"film"|"soft"|"bw"; video.lutStrength: 0..1;
stab.enabled: true|false; stab.crop: 1.0..1.25 (максимальный запас кропа); stab.minCrop: 1.0..1.1; stab.maxAngleDeg: 2..10; stab.tauMaxMs: 100..1500 (чем больше, тем плавнее);
stab.sharpen: 0..1; stab.denoise: 0..1; stab.denoiseSigma: 0.01..0.1; stab.timeOffsetMs: -10..10;
camera.ois: true|false; camera.stockEis: true|false; camera.noiseReduction/camera.edge/camera.distortionCorrection: "off"|"fast"|"hq";
ui.showInfo: true|false; ui.mode: "simple"|"pro"; post.enabled: true|false.

Что нужно сделать в моде: (опиши здесь, например: «кинематографичный цвет и мягкая стабилизация для прогулок»).
""".trimIndent()
    }
}
