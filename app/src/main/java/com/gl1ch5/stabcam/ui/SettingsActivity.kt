package com.gl1ch5.stabcam.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.gl1ch5.stabcam.BuildConfig
import com.gl1ch5.stabcam.R
import com.gl1ch5.stabcam.camera.CameraCaps
import com.gl1ch5.stabcam.config.ConfigRepository
import com.gl1ch5.stabcam.config.Presets
import com.gl1ch5.stabcam.lut.Luts
import com.gl1ch5.stabcam.module.ModuleManager
import com.gl1ch5.stabcam.stab.GyroTracker
import com.gl1ch5.stabcam.update.Updater
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Settings in tabs: Видео · Стабилизация · Цвет · Система · Продвинутые.
 * Every control writes straight into the user config layer; the camera picks it up on return.
 */
class SettingsActivity : Activity() {

    private lateinit var repo: ConfigRepository
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var tabViews: List<TextView>
    private var tab = 0
    private var simple = false
    private lateinit var mods: ModuleManager
    private lateinit var lutPicker: LutPicker

    private val accent get() = getColor(R.color.accent)
    private lateinit var tabs: List<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ConfigRepository(this)
        mods = ModuleManager(this)
        simple = repo.load().simpleMode
        tabs = if (simple) listOf("Главное", "Моды") else listOf("Видео", "Стабилизация", "Цвет", "Моды", "Система", "Продвинутые")
        tab = tabs.indexOf(lastTab).coerceAtLeast(0)
        lutPicker = LutPicker(this, { id -> repo.set("video.lut", id); show(tab) }, {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_LUT)
        })
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF1B1233.toInt(), 0xFF0A0A12.toInt(), 0xFF000000.toInt()))
        }
        root.addView(TextView(this).apply {
            text = "Настройки"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(20), dp(44), dp(20), dp(4))
        })
        root.addView(TextView(this).apply {
            text = "StabCam ${BuildConfig.VERSION_NAME}"; setTextColor(0x99FFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(20), 0, dp(20), dp(10))
        })
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), dp(8)) }
        tabViews = tabs.mapIndexed { i, t ->
            TextView(this).apply {
                text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(dp(16), dp(9), dp(16), dp(9)); gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = dp(20).toFloat() }
                setOnClickListener { select(i) }
            }.also { strip.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }) }
        }
        root.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(40)) }
        scroll = ScrollView(this).apply { addView(content) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        paintTabs(false)
        show(tab)
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.let { uri ->
            val mi = tabs.indexOf("Моды")
            if (mi >= 0 && tab != mi) { tab = mi; lastTab = "Моды"; paintTabs(false); show(mi) }
            installFromUri(uri)
        }
    }

    private fun select(i: Int) {
        if (i == tab) return
        tab = i
        lastTab = tabs[i]
        paintTabs(true)
        content.animate().alpha(0f).translationY(dp(8).toFloat()).setDuration(110).withEndAction {
            show(i)
            scroll.scrollTo(0, 0)
            content.translationY = dp(8).toFloat()
            content.animate().alpha(1f).translationY(0f).setDuration(180).start()
        }.start()
    }

    private fun paintTabs(animate: Boolean) {
        tabViews.forEachIndexed { i, tv ->
            val on = i == tab
            val bg = tv.background as GradientDrawable
            val toBg = if (on) 0x44FFB020 else 0x22FFFFFF
            val toFg = if (on) accent else 0xCCFFFFFF.toInt()
            if (animate) {
                ValueAnimator.ofArgb(tv.currentTextColor, toFg).apply { duration = 160; addUpdateListener { tv.setTextColor(it.animatedValue as Int) }; start() }
            } else tv.setTextColor(toFg)
            bg.setColor(toBg)
        }
    }

    /** Rebuilds the current tab (cheap, keeps all rows in sync with the config). */
    private fun show(i: Int) {
        content.removeAllViews()
        val eff = repo.effectiveJson()
        try { buildTab(i, eff) } catch (e: Exception) {
            com.gl1ch5.stabcam.util.Logger.e("Settings", "Вкладка ${tabs[i]}", e)
            note("Не удалось построить вкладку: ${e.javaClass.simpleName}: ${e.message?.take(120)}. Подробности в логе (Система → Лог).")
        }
    }

    private fun buildTab(i: Int, eff: JSONObject) {
        when (tabs[i]) {
            "Главное" -> home(eff)
            "Видео" -> video(eff)
            "Стабилизация" -> stabilization(eff)
            "Цвет" -> color(eff)
            "Моды" -> modules()
            "Система" -> system()
            else -> advanced(eff)
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // tabs
    // ---------------------------------------------------------------------------------------------------------

    private fun modeChoice() {
        choice("Режим интерфейса", listOf("Простой", "Про"), if (simple) 0 else 1) { repo.set("ui.mode", if (it == 0) "simple" else "pro"); recreate() }
    }

    /** Simple mode: a handful of big, friendly choices. */
    private fun home(e: JSONObject) {
        section("Съёмка")
        val qIds = listOf("4K60", "4K30", "1080p60")
        choice("Качество", listOf("4K · 60", "4K · 30", "Full HD · 60"), qIds.indexOf(str(e, "video.quality")).let { if (it < 0) 0 else it }) { repo.set("video.quality", qIds[it]) }
        val stabIdx = if (!bool(e, "stab.enabled", true)) 0 else if (Presets.indexOf(Presets.strength, e) >= 2) 2 else 1
        choice("Стабилизация", listOf("Выкл", "Обычная", "Сильная"), stabIdx) {
            repo.set("stab.enabled", it != 0)
            if (it == 1) Presets.apply(repo, Presets.strength[1]) else if (it == 2) Presets.apply(repo, Presets.strength[2])
        }
        choice("Чистота картинки", listOf("Обычная", "Чище", "Максимум"), when { num(e, "stab.denoise", 0.5) >= 0.7 -> 2; num(e, "stab.denoise", 0.5) >= 0.45 -> 1; else -> 0 }) {
            Presets.apply(repo, Presets.denoise[intArrayOf(1, 2, 3)[it]]); Presets.apply(repo, Presets.sharpen[intArrayOf(1, 2, 3)[it]])
        }
        toggle("HDR (HLG)", "Ярче и глубже на HDR-экранах.", bool(e, "video.hdr", false, "hlg10")) { repo.set("video.hdr", if (it) "hlg10" else "off") }
        section("Цвет")
        val id = str(e, "video.lut")
        action("Стиль", if (id.isEmpty()) "Без LUT" else (Luts.builtin.firstOrNull { it.id == id }?.name ?: id.substringAfterLast('/')), "Выбрать") { anchor -> lutPicker.show(anchor, id) }
        section("Интерфейс")
        modeChoice()
        note("«Про» показывает все настройки и кнопки на экране камеры.")
    }

    // ---- modules -------------------------------------------------------------------------------------------

    private fun modules() {
        section("Мод с помощью нейросети")
        action("Скопировать промпт для нейросети", "С данными вашего телефона; ИИ сам поищет характеристики в интернете", "Копировать") {
            copy("prompt", ModuleManager.aiPrompt(deviceInfo()))
        }
        action("Вставить мод из буфера", "Готовый ответ нейросети — одним нажатием", "Вставить") {
            val t = getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            if (t.isBlank()) toast("Буфер пуст") else installFromText(t)
        }
        note("1. «Скопировать промпт». 2. Вставьте в нейросеть, допишите, что нужно (например: «базовый мод для моего телефона»). 3. Скопируйте ответ целиком и нажмите «Вставить мод из буфера». Если ответ обрезан или с комментариями, приложение попробует его починить.")

        section("Установленные")
        val list = mods.list()
        if (list.isEmpty()) note("Пока ничего не установлено. Попробуйте каталог ниже.")
        val conflicts = mods.conflicts()
        list.forEachIndexed { idx, m -> moduleCard(m, idx, list.size) }
        if (conflicts.isNotEmpty()) {
            note("Пересечения: " + conflicts.entries.take(5).joinToString("; ") { "${ModuleManager.LABELS[it.key] ?: it.key} — ${it.value.joinToString(" → ")}" } + ". Побеждает нижний в списке; порядок меняется стрелками.")
        }
        val presets = mods.presets()
        if (presets.isNotEmpty()) {
            section("Пресеты из модов")
            presets.forEach { (name, cfg) -> action(name, null, "Применить") { applyConfig(cfg); toast("Применён: $name") } }
        }

        section("Каталог")
        val installedIds = list.map { it.id }.toSet()
        mods.catalog().forEach { file ->
            val r = ModuleManager.check(mods.catalogText(file))
            if (r is ModuleManager.Result.Ok) {
                val m = r.module
                val fit = mods.deviceMatches(m)
                action("${m.icon}  ${m.name}", m.description.take(110) + if (fit == true) "\n✓ подходит вашему телефону" else "",
                    if (m.id in installedIds) "Установлен" else "Установить") { if (m.id !in installedIds) installFromText(mods.catalogText(file)) }
            }
        }
        section("Установка")
        action("Установить из файла", "Файл .module", "Выбрать") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_MOD)
        }
        action("Вставить ссылку", "https://…/мод.module", "Указать") { askUrl() }
        note("Мод может поменять настройки, добавить LUT-ы, пресеты и свои ползунки. Кода в модах нет: менять можно только разрешённые ключи, перед установкой показывается, что именно изменится. Моды комбинируются: «база для телефона» + «кино» + «ночь». Спецификация: docs/MODULES.md в репозитории.")
        section("Создать")
        action("Поделиться моим стилем", "Экспорт текущих настроек в .module", "Экспорт") {
            val j = mods.exportCurrent(repo.effectiveJson(), "Мои настройки").toString(2)
            copy("module", j)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, j), "Мод StabCam"))
        }
    }

    private fun moduleCard(m: ModuleManager.Module, idx: Int, total: Int) {
        val card = cardRow()
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(TextView(this).apply {
            text = m.icon; textSize = 22f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(if (m.enabled) 0x33FFB020 else 0x18FFFFFF) }
            alpha = if (m.enabled) 1f else 0.6f
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
        texts.addView(titleView(m.name))
        val fit = mods.deviceMatches(m)
        texts.addView(subView("v${m.version}" + (if (m.author.isNotEmpty()) " · ${m.author}" else "") + (if (m.lutCount > 0) " · LUT ${m.lutCount}" else "") + (if (m.presetCount > 0) " · пресетов ${m.presetCount}" else "") + (if (fit == false) " · ⚠ другое устройство" else if (fit == true) " · ✓ ваш телефон" else "")))
        line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(Switch(this).apply {
            isChecked = m.enabled
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, 0xFFBBBBBB.toInt()))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0x66FFB020, 0x44FFFFFF))
            setOnCheckedChangeListener { _, c -> mods.setEnabled(m.id, c); show(tab) }
        })
        card.addView(line)
        if (m.description.isNotEmpty()) card.addView(subView(m.description).apply { setPadding(0, dp(8), 0, 0) })
        val missing = m.depends.filter { d -> mods.list().none { it.id == d && it.enabled } }
        if (m.enabled && missing.isNotEmpty()) card.addView(subView("Нужны также: ${missing.joinToString()}").apply { setTextColor(0xFFFFC857.toInt()); setPadding(0, dp(6), 0, 0) })

        // module's own settings
        if (m.settings.isNotEmpty() && m.enabled) {
            card.addView(View(this).apply { setBackgroundColor(0x22FFFFFF) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(10); bottomMargin = dp(4) })
            m.settings.forEach { st -> moduleSetting(card, m, st) }
        }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
        fun chip(t: String, color: Int, on: Boolean = true, onClick: () -> Unit) = TextView(this).apply {
            text = t; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setPadding(dp(10), dp(4), dp(10), dp(4)); alpha = if (on) 1f else 0.3f
            if (on) setOnClickListener { onClick() }
        }
        actions.addView(chip("▲", 0xCCFFFFFF.toInt(), idx > 0) { mods.move(m.id, -1); show(tab) })
        actions.addView(chip("▼", 0xCCFFFFFF.toInt(), idx < total - 1) { mods.move(m.id, 1); show(tab) })
        actions.addView(TextView(this), LinearLayout.LayoutParams(0, 1, 1f))
        actions.addView(chip("Удалить", 0xFFFF6B6B.toInt()) { mods.remove(m.id); toast("Удалён: ${m.name}"); show(tab) })
        card.addView(actions)
    }

    private fun moduleSetting(card: LinearLayout, m: ModuleManager.Module, st: ModuleManager.Setting) {
        val cur = mods.value(m, st)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(2)) }
        card.addView(box)
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(TextView(this).apply { text = st.title; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val vv = TextView(this).apply { setTextColor(accent); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) }
        head.addView(vv)
        box.addView(head)
        if (st.desc.isNotEmpty()) box.addView(subView(st.desc))
        fun fmt(d: Double) = (if (d == Math.floor(d)) d.toLong().toString() else "%.2f".format(d).trimEnd('0').trimEnd('.')) + st.unit
        when (st.type) {
            "toggle" -> {
                vv.visibility = View.GONE
                head.addView(Switch(this).apply {
                    isChecked = cur as? Boolean ?: false
                    thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, 0xFFBBBBBB.toInt()))
                    trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0x66FFB020, 0x44FFFFFF))
                    setOnCheckedChangeListener { _, c -> mods.setValue(m, st.key, c) }
                })
            }
            "choice" -> {
                vv.visibility = View.GONE
                val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
                val views = ArrayList<TextView>()
                var sel = st.options.indexOfFirst { it.second.toString() == cur.toString() || (it.second as? Number)?.toDouble() == (cur as? Number)?.toDouble() }.coerceAtLeast(0)
                fun paint() = views.forEachIndexed { i, tv -> (tv.background as GradientDrawable).setColor(if (i == sel) 0x44FFB020 else 0x18FFFFFF); tv.setTextColor(if (i == sel) accent else 0xCCFFFFFF.toInt()) }
                st.options.forEachIndexed { i, (label, value) ->
                    views += TextView(this).apply {
                        text = label; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); gravity = Gravity.CENTER; setPadding(dp(12), dp(7), dp(12), dp(7))
                        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat() }
                        setOnClickListener { sel = i; paint(); mods.setValue(m, st.key, value) }
                    }.also { chips.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }) }
                }
                paint()
                box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) })
            }
            else -> {
                val steps = ((st.max - st.min) / st.step).toInt().coerceAtLeast(1)
                val c = (cur as? Number)?.toDouble() ?: st.min
                vv.text = fmt(c)
                box.addView(SeekBar(this).apply {
                    max = steps; progress = ((c - st.min) / st.step).toInt().coerceIn(0, steps)
                    progressTintList = ColorStateList.valueOf(accent); thumbTintList = ColorStateList.valueOf(accent)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                            val v = st.min + p * st.step
                            vv.text = fmt(v)
                            if (fromUser) mods.setValue(m, st.key, if (st.step >= 1.0 && st.step == Math.floor(st.step)) v.toLong() else v)
                        }
                        override fun onStartTrackingTouch(sb: SeekBar) {}
                        override fun onStopTrackingTouch(sb: SeekBar) {}
                    })
                })
            }
        }
    }

    /** Device + camera facts for the AI prompt. */
    private fun deviceInfo(): String {
        val caps = CameraCaps.find(this, true)
        val lines = ArrayList<String>()
        lines += "Модель: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.DEVICE}), Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
        lines += "Чип: ${android.os.Build.SOC_MANUFACTURER} ${android.os.Build.SOC_MODEL}"
        if (caps != null) {
            lines += "Основная камера: id ${caps.id}; режимы записи без обхода: ${caps.supportedQualities.joinToString { it.label }}"
            lines += "OIS заявлен в Camera2: ${if (caps.hasOis) "да" else "нет (может быть скрыт прошивкой)"}; HLG10: ${if (caps.supportsHlg10) "да" else "нет"}; стоковые режимы EIS: ${caps.eisModes}"
            lines += "Зум: ${caps.zoomRange.lower}–${caps.zoomRange.upper}×"
            lines += caps.report().lines().filter { it.startsWith("High-speed") || it.startsWith("Sensor size") || it.startsWith("Active array") || it.startsWith("Focal") || it.startsWith("Recorder sizes") }.joinToString("\n")
        }
        lines += "Текущий конфиг StabCam (сокращённо): " + repo.effectiveJson().toString().take(900)
        return lines.joinToString("\n")
    }

    private fun applyConfig(cfg: JSONObject, prefix: String = "") {
        for (k in cfg.keys()) {
            val v = cfg.get(k); val path = if (prefix.isEmpty()) k else "$prefix.$k"
            if (v is JSONObject && path != "video.bitrateMbps") applyConfig(v, path) else repo.set(path, v)
        }
    }

    private fun installFromUri(uri: android.net.Uri) = thread {
        val t = runCatching { contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }.getOrNull()
        runOnUiThread { if (t == null) toast("Не удалось прочитать файл") else installFromText(t) }
    }

    private fun askUrl() {
        val et = EditText(this).apply { hint = "https://…"; setTextColor(Color.WHITE); setHintTextColor(0x66FFFFFF); setPadding(dp(20), dp(12), dp(20), dp(12)) }
        android.app.AlertDialog.Builder(this).setTitle("Ссылка на .module").setView(et)
            .setPositiveButton("Скачать") { _, _ ->
                val url = et.text.toString().trim()
                if (!url.startsWith("https://")) { toast("Нужна ссылка https://"); return@setPositiveButton }
                thread {
                    val t = runCatching {
                        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        c.connectTimeout = 10_000; c.readTimeout = 15_000
                        c.inputStream.use { it.readNBytes(6_000_001).toString(Charsets.UTF_8) }
                    }.getOrNull()
                    runOnUiThread { if (t == null) toast("Не удалось скачать") else installFromText(t) }
                }
            }.setNegativeButton("Отмена", null).show()
    }

    /** Validates (tolerantly), shows the install card, installs on confirm. */
    private fun installFromText(text: String) {
        when (val r = mods.check(text)) {
            is ModuleManager.Result.Error -> android.app.AlertDialog.Builder(this).setTitle("Мод не подошёл").setMessage(r.message).setPositiveButton("OK", null).show()
            is ModuleManager.Result.Ok -> {
                val m = r.module
                val installed = mods.list()
                val diffs = mods.diff(m, repo.effectiveJson())
                val mine = ArrayList<String>().also { ModuleManager.flatten(m.json.optJSONObject("config") ?: JSONObject(), "", it) }
                val clash = installed.filter { it.enabled && it.id != m.id }.filter { o ->
                    val keys = ArrayList<String>().also { ModuleManager.flatten(o.json.optJSONObject("config") ?: JSONObject(), "", it) }
                    keys.any { it in mine }
                }.map { it.name }
                val missing = m.depends.filter { d -> installed.none { it.id == d } }
                ModuleInstallDialog(this).show(m, r.summary, r.notes, diffs, mods.deviceMatches(m), clash, missing) { enable ->
                    mods.install(m, enable); toast("Установлен: ${m.name}"); show(tab)
                }
            }
        }
    }

    private fun video(e: JSONObject) {
        section("Запись")
        val qIds = listOf("4K120", "4K60", "4K30", "3.3K60", "1080p60", "1080p30")
        choice("Качество", listOf("4K·120", "4K·60", "4K·30", "3.3K·60", "1080·60", "1080·30"), qIds.indexOf(str(e, "video.quality")).coerceAtLeast(0)) { repo.set("video.quality", qIds[it]) }
        note("Недоступные камере режимы приложение само заменит ближайшим.")
        choice("Кодек", listOf("HEVC", "H.264"), if (str(e, "video.codec") == "avc") 1 else 0) { repo.set("video.codec", if (it == 1) "avc" else "hevc") }
        choice("Битрейт", Presets.bitrate.map { it.label }, Presets.indexOf(Presets.bitrate, e)) { Presets.apply(repo, Presets.bitrate[it]) }
        toggle("10-бит HLG (HDR)", "Нужен STAB. На экранах без HDR картинка блёклая.", bool(e, "video.hdr", false, "hlg10")) { repo.set("video.hdr", if (it) "hlg10" else "off") }
        toggle("Звук", null, bool(e, "video.audio", true)) { repo.set("video.audio", it) }
    }

    private fun stabilization(e: JSONObject) {
        section("Стабилизация")
        toggle("Гиро-стабилизация", "Своя стабилизация по гироскопу (кнопка STAB).", bool(e, "stab.enabled", true)) { repo.set("stab.enabled", it) }
        choice("Сила", Presets.strength.map { it.label }, Presets.indexOf(Presets.strength, e)) { Presets.apply(repo, Presets.strength[it]) }
        choice("Горизонт", Presets.horizon.map { it.label }, Presets.indexOf(Presets.horizon, e)) { Presets.apply(repo, Presets.horizon[it]) }
        note("Горизонт выравнивает крен по гравитации (в живом режиме ограничен запасом кропа, полный диапазон работает при обработке ПОСТ).")
        toggle("Аппаратный OIS", "Оптическая стабилизация камеры.", bool(e, "camera.ois", true)) { repo.set("camera.ois", it) }
        toggle("Стоковый EIS", "Обрезает и мылит кадр; при STAB отключается.", bool(e, "camera.stockEis", false)) { repo.set("camera.stockEis", it) }
        section("Качество картинки")
        choice("Шумоподавление по времени", Presets.denoise.map { it.label }, Presets.indexOf(Presets.denoise, e)) { Presets.apply(repo, Presets.denoise[it]) }
        choice("Резкость", Presets.sharpen.map { it.label }, Presets.indexOf(Presets.sharpen, e)) { Presets.apply(repo, Presets.sharpen[it]) }
        note("Шумоподавление усредняет кадры (с учётом поворота), резкость возвращает детали после стабилизации.")
    }

    private fun color(e: JSONObject) {
        section("LUT")
        val id = str(e, "video.lut")
        action("Стиль", if (id.isEmpty()) "Без LUT" else (Luts.builtin.firstOrNull { it.id == id }?.name ?: id.removePrefix("file:").removeSuffix(".cube")), "Выбрать") { anchor ->
            lutPicker.show(anchor, id)
        }
        slider("Сила LUT", ((num(e, "video.lutStrength", 1.0)) * 100).toInt(), 100, { "${it}%" }) { repo.set("video.lutStrength", it / 100.0) }
        note("LUT работает и в HLG: стиль накладывается на HLG-сигнал. Импортированные .cube обычно рассчитаны на Rec.709, поэтому в HLG могут выглядеть иначе.")
    }

    private fun system() {
        modeChoice()
        section("Обновления")
        val up = getSharedPreferences("upd", MODE_PRIVATE)
        val lastMs = up.getLong("last_check", 0)
        val updStatus = note("Автопроверка: ${if (repo.load().updateAuto) "вкл" else "выкл"}. Последняя проверка: " +
            (if (lastMs == 0L) "ещё не было" else java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(lastMs)) + " — " + up.getString("last_status", "")))
        toggle("Автообновление", "Проверять при каждом входе в приложение.", repo.load().updateAuto) { repo.set("update.auto", it) }
        toggle("Устанавливать без вопросов", "Без окна: скачать и установить самому (нужно разрешение на установку).", repo.load().updateSilent) { repo.set("update.silent", it) }
        action("Проверить обновление", null, "Проверить") {
            val cfg = repo.load()
            val u = Updater(this, cfg.updateRepo, cfg.updateTag)
            updStatus.text = "Проверяю…"
            thread {
                val r = u.fetchLatest()
                runOnUiThread {
                    updStatus.text = when {
                        r == null -> "Не удалось проверить: ${u.lastStatus}"
                        !u.isNewer(r) -> "Установлена последняя версия (${u.currentId()})"
                        else -> "Доступна ${r.id}"
                    }
                    if (r != null && u.isNewer(r)) UpdateDialog(this).show(u, r)
                }
            }
        }
        section("Диагностика")
        toggle("Проверка стабилизации при запуске", "Показывает, что камера приняла (OIS, EIS, vendor-ключи).", repo.load().probeOnStart) { repo.set("diagnostics.probeOnStart", it) }
        action("Лог", "Живая лента, копирование, отправка", "Открыть") { startActivity(Intent(this, LogActivity::class.java)) }
        val report = mono("")
        report.visibility = View.GONE
        action("Возможности камеры", "OIS, HLG, fps, vendor-ключи", "Показать") {
            report.text = CameraCaps.find(this, true)?.report() ?: "Камера не найдена"
            report.visibility = View.VISIBLE
        }
        action("Копировать отчёт камеры", null, "Копировать") { copy("camera report", CameraCaps.find(this, true)?.report() ?: "") }
        section("Устройство")
        val p = repo.activePreset
        body(p?.let { "${it.name}\n${it.description}" } ?: "Пресет не найден — значения по умолчанию")
        note(ConfigRepository.deviceProps().entries.joinToString("\n") { "${it.key}: ${it.value}" })
    }

    private fun advanced(e: JSONObject) {
        section("Режим ПОСТ")
        note("Для тех, кто обрабатывает видео после съёмки: запись без обработки + гиро-лог и метаданные кадров.")
        toggle("Режим ПОСТ (RAW + гиро-лог)", "Битрейт выше, кроп/шумодав/LUT не применяются.", bool(e, "post.enabled", false)) { repo.set("post.enabled", it) }
        toggle("Копия .gcsv в Download/StabCam", "Для Gyroflow на ПК.", bool(e, "post.exportGcsv", true)) { repo.set("post.exportGcsv", it) }

        action("Обработка записей ПОСТ", "Стабилизация с предвидением, шумодав, LUT на телефоне за 10–20 с", "Открыть") { startActivity(Intent(this, PostActivity::class.java)) }

        section("Стабилизация (тонко)")
        val cur = repo.load().gyroAxes
        val idx = GyroTracker.CANDIDATES.indexOf(cur)
        action("Оси гироскопа", cur.joinToString(", ") + if (idx >= 0) "  (${idx + 1}/${GyroTracker.CANDIDATES.size})" else "", "Следующий") {
            repo.set("stab.gyroAxes", JSONArray(GyroTracker.CANDIDATES[(idx + 1) % GyroTracker.CANDIDATES.size])); show(tab)
        }
        choice("Поворот превью", listOf("1", "2", "3", "4"), (num(e, "stab.previewRot", 1.0).toInt() - 1).coerceIn(0, 3)) { repo.set("stab.previewRot", it + 1) }
        choice("Сдвиг гиро, мс", listOf("−6", "−3", "0", "+3", "+6"), listOf(-6.0, -3.0, 0.0, 3.0, 6.0).indexOfFirst { it == num(e, "stab.timeOffsetMs", 0.0) }.let { if (it < 0) 2 else it }) {
            repo.set("stab.timeOffsetMs", listOf(-6.0, -3.0, 0.0, 3.0, 6.0)[it])
        }
        choice("Камера: шумоподавление", listOf("Выкл", "Быстрое", "Качество"), listOf("off", "fast", "hq").indexOf(str(e, "camera.noiseReduction")).coerceAtLeast(1)) { repo.set("camera.noiseReduction", listOf("off", "fast", "hq")[it]) }
        choice("Камера: резкость (edge)", listOf("Выкл", "Быстрая", "Качество"), listOf("off", "fast", "hq").indexOf(str(e, "camera.edge")).coerceAtLeast(1)) { repo.set("camera.edge", listOf("off", "fast", "hq")[it]) }
        toggle("Показывать инфо-строку", null, bool(e, "ui.showInfo", false)) { repo.set("ui.showInfo", it) }

        section("Конфиг (JSON)")
        note("Переопределяет пресет. Пример: {\"video\":{\"bitrateMbps\":{\"4K60\":150}}}")
        val editor = EditText(this).apply {
            setText(repo.userOverrides().toString(2))
            typeface = Typeface.MONOSPACE; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(Color.WHITE)
            background = card(); setPadding(dp(14), dp(12), dp(14), dp(12)); gravity = Gravity.TOP; minLines = 6
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        content.addView(editor, matchWrap())
        row(
            button("Сохранить") {
                val json = runCatching { JSONObject(editor.text.toString().ifBlank { "{}" }) }.getOrElse { toast("Ошибка JSON: ${it.message}"); return@button }
                repo.saveUserOverrides(json); toast("Сохранено"); show(tab)
            },
            button("Сбросить") { repo.resetUserOverrides(); toast("Сброшено к пресету"); show(tab) },
            button("Копировать") { copy("config", repo.effectiveJson().toString(2)) },
        )
        section("Итоговый конфиг")
        mono(e.toString(2))

        section("Лаборатория vendor-ключей")
        note("Ключи OnePlus/Qualcomm из отчёта камеры. Задайте ключ и число, затем откройте камеру и проверьте.")
        var tagType = "int"
        val keyEdit = EditText(this).apply { setText("com.oplus.video.stabilization.mode"); typeface = Typeface.MONOSPACE; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(Color.WHITE) }
        val valEdit = EditText(this).apply { setText("1"); hint = "значение"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED; setTextColor(Color.WHITE) }
        content.addView(keyEdit, matchWrap()); content.addView(valEdit, matchWrap())
        choice("Тип значения", listOf("число", "строка", "массив чисел"), 0) { tagType = listOf("int", "string", "ints")[it]; valEdit.hint = when (tagType) { "string" -> "например com.oplus.camera"; "ints" -> "числа через запятую"; else -> "значение" }
            valEdit.inputType = if (tagType == "int") InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED else InputType.TYPE_CLASS_TEXT }
        row(
            button("Применить") {
                val raw = valEdit.text.toString().trim()
                val v: Any = when (tagType) {
                    "string" -> raw
                    "ints" -> JSONArray(raw.split(',', ' ').filter { it.isNotBlank() }.map { it.trim().toIntOrNull() ?: return@button toast("Нужны целые числа") })
                    else -> raw.toIntOrNull() ?: return@button toast("Нужно число")
                }
                val root = repo.userOverrides()
                val cam = root.optJSONObject("camera") ?: JSONObject().also { root.put("camera", it) }
                val old = cam.optJSONArray("vendorTags") ?: JSONArray()
                val list = JSONArray()
                for (i in 0 until old.length()) if (old.getJSONObject(i).optString("name") != keyEdit.text.toString()) list.put(old.getJSONObject(i))
                list.put(JSONObject().put("name", keyEdit.text.toString().trim()).put("type", tagType).put("value", v))
                cam.put("vendorTags", list)
                repo.saveUserOverrides(root); toast("Ключ задан, откройте камеру"); show(tab)
            },
            button("Убрать все ключи") {
                val root = repo.userOverrides(); root.optJSONObject("camera")?.remove("vendorTags"); repo.saveUserOverrides(root); show(tab)
            },
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_MOD && resultCode == RESULT_OK) { data?.data?.let { installFromUri(it) }; return }
        if (requestCode != REQ_LUT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        thread {
            val id = Luts.importCube(this, uri)
            runOnUiThread { if (id == null) toast("Не удалось прочитать .cube (нужен 3D LUT)") else { repo.set("video.lut", id); show(tab) } }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // row builders
    // ---------------------------------------------------------------------------------------------------------

    private fun section(text: String) = content.addView(TextView(this).apply {
        this.text = text.uppercase(); setTextColor(accent); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); letterSpacing = 0.1f
    }, matchWrap().apply { topMargin = dp(22); bottomMargin = dp(6); marginStart = dp(6) })

    private fun body(text: String) = content.addView(TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setPadding(dp(6), 0, dp(6), 0)
    })

    private fun note(text: String): TextView = TextView(this).apply {
        this.text = text; setTextColor(0x99FFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }.also { content.addView(it, matchWrap().apply { topMargin = dp(6); marginStart = dp(6); marginEnd = dp(6) }) }

    private fun mono(text: String): TextView = TextView(this).apply {
        this.text = text; typeface = Typeface.MONOSPACE; setTextIsSelectable(true)
        setTextColor(0xCCFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        background = card(); setPadding(dp(14), dp(12), dp(14), dp(12))
    }.also { content.addView(it, matchWrap()) }

    private fun card() = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0x66FFFFFF.and(0x33FFFFFF)); setStroke(dp(1), 0x22FFFFFF) }

    private fun cardRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; background = card(); setPadding(dp(16), dp(12), dp(16), dp(12))
    }.also { content.addView(it, matchWrap().apply { topMargin = dp(8) }) }

    private fun titleView(t: String) = TextView(this).apply { text = t; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) }
    private fun subView(t: String) = TextView(this).apply { text = t; setTextColor(0x99FFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setPadding(0, dp(2), 0, 0) }

    private fun toggle(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
        val card = cardRow()
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(titleView(title)); sub?.let { texts.addView(subView(it)) }
        line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this).apply {
            isChecked = value
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(accent, 0xFFBBBBBB.toInt()))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(0x66FFB020, 0x44FFFFFF))
            setOnCheckedChangeListener { _, c -> onChange(c) }
        }
        line.addView(sw)
        card.addView(line)
        card.setOnClickListener { sw.toggle() }
    }

    /** Segmented control: chips with an animated selection. */
    private fun choice(title: String, labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        val card = cardRow()
        card.addView(titleView(title))
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        val scrollH = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
        val views = ArrayList<TextView>()
        var cur = selected
        fun paint(i: Int, animate: Boolean) {
            val on = i == cur
            val tv = views[i]
            val bg = tv.background as GradientDrawable
            bg.setColor(if (on) 0x44FFB020 else 0x18FFFFFF)
            val to = if (on) accent else 0xCCFFFFFF.toInt()
            if (animate) ValueAnimator.ofArgb(tv.currentTextColor, to).apply { duration = 140; addUpdateListener { tv.setTextColor(it.animatedValue as Int) }; start() } else tv.setTextColor(to)
        }
        labels.forEachIndexed { i, l ->
            val tv = TextView(this).apply {
                text = l; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply { cornerRadius = dp(16).toFloat() }
                setOnClickListener {
                    if (cur == i) return@setOnClickListener
                    val old = cur; cur = i
                    paint(old, true); paint(i, true)
                    animate().scaleX(0.94f).scaleY(0.94f).setDuration(70).withEndAction { animate().scaleX(1f).scaleY(1f).setDuration(140).start() }.start()
                    onSelect(i)
                }
            }
            views += tv
            chips.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
            paint(i, false)
        }
        card.addView(scrollH)
    }

    private fun slider(title: String, value: Int, max: Int, fmt: (Int) -> String, onChange: (Int) -> Unit) {
        val card = cardRow()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(titleView(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val v = TextView(this).apply { text = fmt(value); setTextColor(accent); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
        head.addView(v)
        card.addView(head)
        card.addView(SeekBar(this).apply {
            this.max = max; progress = value
            progressTintList = ColorStateList.valueOf(accent); thumbTintList = ColorStateList.valueOf(accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { v.text = fmt(p); if (fromUser) onChange(p) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }, matchWrap().apply { topMargin = dp(6) })
    }

    private fun action(title: String, sub: String?, button: String, onClick: (View) -> Unit) {
        val card = cardRow()
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(titleView(title)); sub?.let { texts.addView(subView(it)) }
        line.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(TextView(this).apply { text = "$button  ›"; setTextColor(accent); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) })
        card.addView(line)
        card.setOnClickListener { onClick(card) }
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply { this.text = text; isAllCaps = false; setOnClickListener { onClick() } }

    private fun row(vararg views: View) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEach { r.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        content.addView(r, matchWrap().apply { topMargin = dp(8) })
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    // --- config readers ---

    private fun at(j: JSONObject, path: String): Any? {
        var n: Any? = j
        for (k in path.split('.')) n = (n as? JSONObject)?.opt(k) ?: return null
        return n
    }
    private fun str(j: JSONObject, path: String) = at(j, path)?.toString() ?: ""
    private fun num(j: JSONObject, path: String, def: Double) = at(j, path)?.toString()?.toDoubleOrNull() ?: def
    private fun bool(j: JSONObject, path: String, def: Boolean, trueValue: String? = null): Boolean {
        val v = at(j, path) ?: return def
        return if (trueValue != null) v.toString() == trueValue else v.toString().toBooleanStrictOrNull() ?: def
    }

    private fun copy(label: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        toast("Скопировано")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQ_LUT = 42
        private const val REQ_MOD = 43
        private var lastTab = ""
    }
}
