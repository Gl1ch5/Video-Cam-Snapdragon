package com.gl1ch5.stabcam.module

import android.content.Context
import com.gl1ch5.stabcam.config.ConfigRepository
import com.gl1ch5.stabcam.lut.Lut
import com.gl1ch5.stabcam.lut.Luts
import com.gl1ch5.stabcam.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * `.module` files: a JSON document that can carry a config patch, LUTs (inline .cube text), named presets and
 * user-tweakable settings. Full spec: docs/MODULES.md.
 *
 * Modules combine: enabled modules are layered in the user's order (later wins on conflicts); a module can be
 * limited to certain devices (`match`), declare `depends`, and expose `settings` whose values are substituted into
 * `"${name}"` placeholders of its config. No code runs; config keys are checked against an allow-list.
 */
class ModuleManager(private val ctx: Context) {

    class Setting(
        val key: String, val title: String, val type: String, val desc: String,
        val min: Double, val max: Double, val step: Double, val default: Any, val unit: String,
        val options: List<Pair<String, Any>>,
    )

    class Module(val id: String, val json: JSONObject, var enabled: Boolean) {
        val name: String = json.optString("name", id)
        val author: String = json.optString("author")
        val version: String = json.optString("version", "1")
        val description: String = json.optString("description")
        val icon: String = json.optString("icon", "🧩")
        val depends: List<String> = json.optJSONArray("depends")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val match: JSONObject? = json.optJSONObject("match")
        val settings: List<Setting> = parseSettings(json.optJSONArray("settings"))
        val lutCount get() = json.optJSONArray("luts")?.length() ?: 0
        val presetCount get() = json.optJSONArray("presets")?.length() ?: 0
    }

    sealed class Result {
        class Ok(val module: Module, val summary: List<String>, val notes: List<String>) : Result()
        class Error(val message: String) : Result()
    }

    class Diff(val path: String, val label: String, val old: String, val new: String)

    private val dir = File(ctx.filesDir, "modules").apply { mkdirs() }
    private val stateFile = File(dir, "state.json")

    // --- state: enabled set, order, per-module setting values -------------------------------------------------

    private class State(val enabled: MutableSet<String>, val order: MutableList<String>, val values: JSONObject)

    private fun state(): State {
        val txt = runCatching { stateFile.readText() }.getOrNull()
        val j = txt?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (j == null) { // old format: a plain array of enabled ids
            val arr = txt?.let { runCatching { JSONArray(it) }.getOrNull() }
            val en = arr?.let { a -> (0 until a.length()).map { a.getString(it) }.toMutableSet() } ?: mutableSetOf()
            return State(en, en.toMutableList(), JSONObject())
        }
        fun strs(k: String) = j.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        return State(strs("enabled").toMutableSet(), strs("order").toMutableList(), j.optJSONObject("values") ?: JSONObject())
    }

    private fun save(s: State) {
        stateFile.writeText(JSONObject().put("enabled", JSONArray(s.enabled.toList())).put("order", JSONArray(s.order)).put("values", s.values).toString())
    }

    fun list(): List<Module> {
        val st = state()
        val mods = (dir.listFiles { f -> f.name.endsWith(".module") } ?: emptyArray()).mapNotNull { f ->
            runCatching { Module(JSONObject(f.readText()).getString("id"), JSONObject(f.readText()), false) }.getOrNull()
        }
        mods.forEach { it.enabled = it.id in st.enabled }
        return mods.sortedWith(compareBy({ st.order.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.name }))
    }

    fun check(text: String): Result = Companion.check(text)

    fun install(m: Module, enable: Boolean = true) {
        File(dir, "${m.id}.module").writeText(m.json.toString())
        val st = state()
        if (m.id !in st.order) st.order += m.id
        if (enable) st.enabled += m.id else st.enabled -= m.id
        save(st)
        Logger.i("Module", "Установлен ${m.id} ${m.version} (${if (enable) "вкл" else "выкл"})")
    }

    fun remove(id: String) {
        File(dir, "$id.module").delete()
        val st = state(); st.enabled -= id; st.order -= id; st.values.remove(id); save(st)
    }

    fun setEnabled(id: String, on: Boolean) { val st = state(); if (on) st.enabled += id else st.enabled -= id; save(st) }

    /** Moves a module up (delta -1) or down (+1) in the layering order; later modules win. */
    fun move(id: String, delta: Int) {
        val st = state()
        val order = list().map { it.id }.toMutableList()
        val i = order.indexOf(id); val j = i + delta
        if (i < 0 || j < 0 || j >= order.size) return
        order.removeAt(i); order.add(j, id)
        st.order.clear(); st.order.addAll(order); save(st)
    }

    fun value(m: Module, s: Setting): Any = state().values.optJSONObject(m.id)?.opt(s.key)?.takeIf { it != JSONObject.NULL } ?: s.default

    fun setValue(m: Module, key: String, v: Any) {
        val st = state()
        val o = st.values.optJSONObject(m.id) ?: JSONObject().also { st.values.put(m.id, it) }
        o.put(key, v); save(st)
    }

    /** Deep-merged, placeholder-resolved config patches of all enabled modules, in layering order. */
    fun configPatch(): JSONObject {
        val out = JSONObject()
        for (m in list().filter { it.enabled }) m.json.optJSONObject("config")?.let { merge(out, resolve(it, valuesOf(m))) }
        return out
    }

    private fun valuesOf(m: Module): Map<String, Any> = m.settings.associate { it.key to value(m, it) }

    /** Config paths set by more than one enabled module: path → module names (last one wins). */
    fun conflicts(): Map<String, List<String>> {
        val by = LinkedHashMap<String, MutableList<String>>()
        for (m in list().filter { it.enabled }) {
            val keys = ArrayList<String>()
            m.json.optJSONObject("config")?.let { flatten(it, "", keys) }
            keys.forEach { by.getOrPut(it) { mutableListOf() } += m.name }
        }
        return by.filterValues { it.size > 1 }
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
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { (it.optString("name", "Пресет") + " · " + m.name) to resolve(it.optJSONObject("config") ?: JSONObject(), valuesOf(m)) } }
    }

    /** Before/after of every key the module would change, against [effective] (the current config). */
    fun diff(m: Module, effective: JSONObject): List<Diff> {
        val patch = resolve(m.json.optJSONObject("config") ?: JSONObject(), m.settings.associate { it.key to it.default })
        val keys = ArrayList<String>(); flatten(patch, "", keys)
        return keys.map { path ->
            Diff(path, LABELS[path] ?: path, show(at(effective, path)), show(at(patch, path)))
        }
    }

    fun deviceMatches(m: Module): Boolean? = m.match?.let { ConfigRepository.matches(it) }

    fun catalog(): List<String> = (ctx.assets.list("modules") ?: emptyArray()).filter { it.endsWith(".module") }.sorted()
    fun catalogText(name: String): String = ctx.assets.open("modules/$name").bufferedReader().use { it.readText() }

    /** Current user-visible settings as a shareable module. */
    fun exportCurrent(effective: JSONObject, name: String): JSONObject {
        val cfg = JSONObject()
        for (path in ALLOWED) {
            val v = at(effective, path) ?: continue
            var node = cfg
            val parts = path.split('.')
            for (k in parts.dropLast(1)) node = node.optJSONObject(k) ?: JSONObject().also { node.put(k, it) }
            node.put(parts.last(), v)
        }
        return JSONObject().put("module", 1).put("id", "user." + name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "settings" } + "-" + (System.currentTimeMillis() / 1000 % 100000))
            .put("name", name).put("author", "").put("version", "1.0").put("description", "Экспорт настроек StabCam").put("icon", "📦").put("config", cfg)
    }

    companion object {
        /** Config paths a module may set. No update.*, no vendorTags, no diagnostics. */
        val ALLOWED = listOf(
            "video.quality", "video.codec", "video.hdr", "video.audio", "video.bitrateScale", "video.bitrateMbps", "video.lut", "video.lutStrength",
            "stab.enabled", "stab.crop", "stab.minCrop", "stab.maxAngleDeg", "stab.tauMaxMs", "stab.tauMinMs", "stab.velTauMs",
            "stab.sharpen", "stab.bicubic", "stab.denoise", "stab.denoiseSigma", "stab.timeOffsetMs",
            "camera.ois", "camera.stockEis", "camera.noiseReduction", "camera.edge", "camera.distortionCorrection", "camera.forceAllQualities",
            "ui.showInfo", "ui.mode", "ui.profile", "post.enabled", "post.bitrateFactor", "post.exportGcsv", "post.cameraNr", "stab.denoiseAuto", "camera.shutterCapMs",
        )

        val LABELS = mapOf(
            "video.quality" to "Качество", "video.codec" to "Кодек", "video.hdr" to "HDR (HLG)", "video.audio" to "Звук",
            "video.bitrateScale" to "Масштаб битрейта", "video.lut" to "LUT", "video.lutStrength" to "Сила LUT",
            "stab.enabled" to "Гиро-стабилизация", "stab.crop" to "Макс. кроп", "stab.minCrop" to "Мин. кроп", "stab.maxAngleDeg" to "Макс. угол, °",
            "stab.tauMaxMs" to "Плавность, мс", "stab.sharpen" to "Резкость", "stab.denoise" to "Шумоподавление", "stab.timeOffsetMs" to "Сдвиг гиро, мс",
            "camera.ois" to "OIS", "camera.stockEis" to "Стоковый EIS", "camera.noiseReduction" to "Камера: шумодав", "camera.edge" to "Камера: резкость",
            "camera.forceAllQualities" to "Показывать все режимы", "ui.mode" to "Режим интерфейса", "post.enabled" to "Режим ПОСТ",
        )

        private val ID = Regex("^[a-z0-9][a-z0-9._-]{2,63}$")

        /**
         * Cleans what chat AIs typically return: markdown fences, // and /* */ comments, trailing commas, smart quotes,
         * text around the JSON, and a cut-off tail (open brackets get closed). Returns the JSON text and a list of fixes.
         */
        fun sanitize(raw: String): Pair<String, List<String>> {
            val notes = ArrayList<String>()
            var t = raw.trim()
            if (t.contains("```")) { t = t.replace(Regex("```[a-zA-Z]*"), ""); notes += "убрано оформление markdown" }
            val q = t.replace('“', '"').replace('”', '"').replace('„', '"')
            if (q != t) { t = q; notes += "исправлены типографские кавычки" }
            val start = t.indexOf('{')
            if (start < 0) return t to notes
            if (start > 0) { t = t.substring(start); notes += "отброшен текст перед JSON" }

            val out = StringBuilder()
            val stack = ArrayList<Char>()
            var i = 0
            var inStr = false
            var removedComment = false
            var endAt = -1
            while (i < t.length) {
                val c = t[i]
                if (inStr) {
                    out.append(c)
                    if (c == '\\' && i + 1 < t.length) { out.append(t[i + 1]); i += 2; continue }
                    if (c == '"') inStr = false
                    i++; continue
                }
                if (c == '"') { inStr = true; out.append(c); i++; continue }
                if (c == '/' && i + 1 < t.length && t[i + 1] == '/') { while (i < t.length && t[i] != '\n') i++; removedComment = true; continue }
                if (c == '/' && i + 1 < t.length && t[i + 1] == '*') { val e = t.indexOf("*/", i + 2); i = if (e < 0) t.length else e + 2; removedComment = true; continue }
                if (c == '{' || c == '[') stack += c
                if (c == '}' || c == ']') {
                    // drop a trailing comma before a closing bracket
                    var k = out.length - 1
                    while (k >= 0 && out[k].isWhitespace()) k--
                    if (k >= 0 && out[k] == ',') { out.deleteCharAt(k); notes.add("убраны лишние запятые") }
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    out.append(c); i++
                    if (stack.isEmpty()) { endAt = i; break }
                    continue
                }
                out.append(c); i++
            }
            if (removedComment) notes += "убраны комментарии"
            if (endAt < 0) {
                // cut-off answer: close an open string and all open brackets
                var s = out.toString().trimEnd()
                if (inStr) s += "\""
                s = s.trimEnd().trimEnd(',')
                for (b in stack.reversed()) s += if (b == '{') "}" else "]"
                notes += "ответ был обрезан: дописаны закрывающие скобки"
                return s to notes.distinct()
            }
            return out.toString() to notes.distinct()
        }

        /** Replaces `"${name}"` strings by the setting value (number/boolean/string), recursively. */
        fun resolve(o: JSONObject, values: Map<String, Any>): JSONObject {
            val out = JSONObject()
            for (k in o.keys()) out.put(k, resolveAny(o.get(k), values))
            return out
        }

        private fun resolveAny(v: Any, values: Map<String, Any>): Any = when (v) {
            is JSONObject -> resolve(v, values)
            is JSONArray -> JSONArray().also { a -> for (i in 0 until v.length()) a.put(resolveAny(v.get(i), values)) }
            is String -> Regex("^\\$\\{([a-zA-Z0-9_]+)\\}$").matchEntire(v)?.let { values[it.groupValues[1]] } ?: v
            else -> v
        }

        fun parseSettings(arr: JSONArray?): List<Setting> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val type = o.optString("type", "slider")
                val opts = o.optJSONArray("options")?.let { a -> (0 until a.length()).mapNotNull { j -> a.optJSONObject(j)?.let { it.optString("label") to it.get("value") } } } ?: emptyList()
                val def: Any = if (o.has("default")) o.get("default") else when (type) { "toggle" -> false; "choice" -> opts.firstOrNull()?.second ?: 0; else -> o.optDouble("min", 0.0) }
                Setting(o.optString("key"), o.optString("title", o.optString("key")), type, o.optString("desc"), o.optDouble("min", 0.0), o.optDouble("max", 1.0), o.optDouble("step", 0.05), def, o.optString("unit"), opts)
            }.filter { it.key.matches(Regex("[a-zA-Z0-9_]+")) }
        }

        fun parse(j: JSONObject, enabled: Boolean = false) = Module(j.getString("id"), j, enabled)

        /** Validates [text] without installing. Tolerates typical AI formatting mistakes (see [sanitize]). */
        fun check(text: String): Result = try { checkImpl(text) } catch (e: Exception) {
            Logger.e("Module", "check", e)
            Result.Error("Внутренняя ошибка проверки: ${e.javaClass.simpleName}: ${e.message?.take(100)}")
        }

        private fun checkImpl(text: String): Result {
            val (clean, notes) = sanitize(text)
            val j = try { JSONObject(clean) } catch (e: Exception) {
                val msg = e.message.orEmpty()
                val pos = Regex("character (\\d+)").find(msg)?.groupValues?.get(1)?.toIntOrNull()
                val ctx = pos?.let { "…" + clean.substring(maxOf(0, it - 40), minOf(clean.length, it + 20)).replace('\n', ' ') + "…" }
                return Result.Error("Не удалось разобрать JSON. ${msg.take(90)}" + (ctx?.let { "\nУчасток: $it" } ?: "") + "\nПопросите нейросеть прислать мод целиком, одним JSON-блоком, без комментариев.")
            }
            if (j.optInt("module", 0) != 1) return Result.Error("Нет поля \"module\": 1 (формат .module версии 1)")
            val id = j.optString("id")
            if (!ID.matches(id)) return Result.Error("Поле id: 3–64 символа a-z, 0-9, точка, дефис")
            if (text.length > 6_000_000) return Result.Error("Файл слишком большой (макс. 6 МБ)")
            val settings = parseSettings(j.optJSONArray("settings"))
            val settingKeys = settings.map { it.key }.toSet()
            val summary = ArrayList<String>()
            j.optJSONObject("config")?.let {
                val bad = ArrayList<String>(); val keys = ArrayList<String>()
                walk(it, "", keys, bad)
                if (bad.isNotEmpty()) return Result.Error("Недопустимые ключи конфига: ${bad.take(5).joinToString()}")
                val unknown = Regex("\\$\\{([a-zA-Z0-9_]+)\\}").findAll(it.toString()).map { m -> m.groupValues[1] }.filter { k -> k !in settingKeys }.toSet()
                if (unknown.isNotEmpty()) return Result.Error("В config используются необъявленные настройки: ${unknown.joinToString()}")
                if (keys.isNotEmpty()) summary += "Настройки: ${keys.size}"
            }
            if (settings.isNotEmpty()) summary += "Свои настройки мода: ${settings.size}"
            j.optJSONArray("luts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: return Result.Error("luts[$i]: ожидается объект")
                    val lut = Luts.parseCube("mod:$id/$i", o.optString("name", "LUT $i"), o.optString("cube"))
                        ?: return Result.Error("luts[$i] «${o.optString("name")}»: не удалось прочитать 3D .cube (проверьте LUT_3D_SIZE и число строк: размер³)")
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
            if (j.optJSONObject("config") == null && j.optJSONArray("luts") == null && j.optJSONArray("presets") == null) return Result.Error("Модуль пустой: нужны config, luts или presets")
            return Result.Ok(parse(j), summary, notes)
        }

        private fun walk(o: JSONObject, prefix: String, keys: MutableList<String>, bad: MutableList<String>) {
            for (k in o.keys()) {
                val path = if (prefix.isEmpty()) k else "$prefix.$k"
                val v = o.get(k)
                if (v is JSONObject && path != "video.bitrateMbps") walk(v, path, keys, bad)
                else if (ALLOWED.any { path == it || path.startsWith("$it.") }) keys += path else bad += path
            }
        }

        fun flatten(o: JSONObject, prefix: String, out: MutableList<String>) {
            for (k in o.keys()) {
                val path = if (prefix.isEmpty()) k else "$prefix.$k"
                val v = o.get(k)
                if (v is JSONObject && path != "video.bitrateMbps") flatten(v, path, out) else out += path
            }
        }

        private fun merge(dst: JSONObject, src: JSONObject) {
            for (k in src.keys()) {
                val s = src.get(k); val d = dst.opt(k)
                if (s is JSONObject && d is JSONObject) merge(d, s) else dst.put(k, s)
            }
        }

        private fun at(j: JSONObject, path: String): Any? {
            var n: Any? = j
            for (k in path.split('.')) n = (n as? JSONObject)?.opt(k) ?: return null
            return n
        }

        private fun show(v: Any?): String = when (v) {
            null -> "—"
            is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')
            is Boolean -> if (v) "вкл" else "выкл"
            is String -> v.ifEmpty { "—" }
            else -> v.toString()
        }

        /** Prompt to paste into a chat AI. [device] is filled in by the app (model, SoC, camera capabilities). */
        fun aiPrompt(device: String): String = """
Ты помогаешь настроить камеру-приложение StabCam (видео 4K, гиро-стабилизация, шумоподавление, LUT) под конкретный телефон. Результат — один файл-мод (JSON, расширение .module).

ШАГ 1. ПОИЩИ В ИНТЕРНЕТЕ. Найди технические данные этого телефона и его камеры: размер сенсора и основная камера, наличие OIS, поддерживаемые режимы видео (4K60, 4K120, HLG/Dolby Vision), особенности стабилизации и шумоподавления в видео, известные проблемы Camera2 на этом чипе. Используй это, чтобы выбрать разумные значения. Если поиска нет, опирайся на данные ниже и скажи об этом в description.

МОЙ ТЕЛЕФОН (данные из приложения):
$device

ШАГ 2. ВЕРНИ ТОЛЬКО JSON. Один блок кода ```json, без пояснений до и после, без комментариев (// и /* */), без запятых перед закрывающими скобками. Весь ответ должен поместиться целиком.

ФОРМАТ:
{
  "module": 1,
  "id": "user.my-mod",                 // a-z, 0-9, точка, дефис; 3-64 символа
  "name": "Название", "icon": "🎬", "author": "", "version": "1.0",
  "description": "что делает и почему такие значения (кратко)",
  "match": { "manufacturer": ["OnePlus"], "model": ["CPH2767"] },   // необязательно: для каких устройств; сравнение по подстроке
  "depends": [ "user.base-device" ],  // необязательно: id модов, которые нужны вместе с этим
  "config": { "stab": { "denoise": "${'$'}{denoise}" }, "video": { "quality": "4K60" } },
  "settings": [                        // необязательно: свои ползунки/переключатели мода; значения подставляются в "${'$'}{ключ}" внутри config
    { "key": "denoise", "title": "Шумоподавление", "type": "slider", "min": 0, "max": 1, "step": 0.05, "default": 0.5 },
    { "key": "hdr", "title": "HDR", "type": "toggle", "default": false },
    { "key": "style", "title": "Стиль", "type": "choice", "options": [ { "label": "Мягкий", "value": 0.4 }, { "label": "Сильный", "value": 0.8 } ], "default": 0.4 }
  ],
  "luts": [ { "name": "Имя", "cube": "LUT_3D_SIZE 17\n0 0 0\n..." } ],   // необязательно: .cube, значения 0..1, красный меняется быстрее всего, ровно размер³ строк
  "presets": [ { "name": "Имя", "config": { } } ]                      // необязательно
}

РАЗРЕШЁННЫЕ КЛЮЧИ config (остальные приложение отвергнет):
video.quality: "4K120"|"4K60"|"4K30"|"3.3K60"|"1080p60"|"1080p30"; video.codec: "hevc"|"avc"; video.hdr: "off"|"hlg10"; video.audio: true|false;
video.bitrateScale: 0.5..2 (множитель стандартного битрейта); video.lut: ""|"rich"|"vivid"|"warm"|"cool"|"cinema"|"film"|"soft"|"bw"; video.lutStrength: 0..1;
stab.enabled: true|false; stab.crop: 1.0..1.25 (макс. запас кропа); stab.minCrop: 1.0..1.1; stab.maxAngleDeg: 2..10; stab.tauMaxMs: 100..1500 (больше = плавнее, но запаздывает);
stab.sharpen: 0..1; stab.denoise: 0..1; stab.denoiseSigma: 0.01..0.1; stab.timeOffsetMs: -10..10;
camera.ois: true|false; camera.stockEis: true|false; camera.noiseReduction, camera.edge, camera.distortionCorrection: "off"|"fast"|"hq"; camera.forceAllQualities: true|false;
ui.showInfo: true|false; ui.mode: "simple"|"pro"; post.enabled: true|false.

СОВЕТЫ: моды комбинируются (включённые накладываются по порядку, последний выигрывает), поэтому делай мод узким: например «базовые параметры телефона» отдельно от «кинематографичного стиля». Для настроек пользователя лучше дать 2–4 понятных поля в settings.

ЧТО НУЖНО СДЕЛАТЬ: (опиши здесь, например: «базовый мод с правильными параметрами для моего телефона, максимальное качество без лишнего нагрева»).
""".trimIndent()
    }
}
