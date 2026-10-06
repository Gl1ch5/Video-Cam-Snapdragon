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
    private var tab = lastTab
    private lateinit var lutPicker: LutPicker

    private val accent get() = getColor(R.color.accent)
    private val tabs = listOf("Видео", "Стабилизация", "Цвет", "Система", "Продвинутые")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ConfigRepository(this)
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
    }

    private fun select(i: Int) {
        if (i == tab) return
        tab = i
        lastTab = i
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
        when (i) {
            0 -> video(eff)
            1 -> stabilization(eff)
            2 -> color(eff)
            3 -> system()
            else -> advanced(eff)
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // tabs
    // ---------------------------------------------------------------------------------------------------------

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
        section("Обновления")
        val updStatus = note("Автопроверка при запуске: ${if (repo.load().updateAuto) "вкл" else "выкл"}")
        toggle("Автообновление", null, repo.load().updateAuto) { repo.set("update.auto", it) }
        action("Проверить обновление", null, "Проверить") {
            val cfg = repo.load()
            val u = Updater(this, cfg.updateRepo, cfg.updateTag)
            updStatus.text = "Проверяю…"
            thread {
                val r = u.fetchLatest()
                runOnUiThread {
                    updStatus.text = when {
                        r == null -> "Не удалось проверить (репозиторий приватный или нет сети) — см. лог"
                        !u.isNewer(r) -> "Установлена последняя версия (${u.currentId()})"
                        else -> "Доступна ${r.id}. Откройте камеру: обновление скачается и установится само"
                    }
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
        val keyEdit = EditText(this).apply { setText("com.oplus.video.stabilization.mode"); typeface = Typeface.MONOSPACE; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(Color.WHITE) }
        val valEdit = EditText(this).apply { setText("1"); hint = "значение"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED; setTextColor(Color.WHITE) }
        content.addView(keyEdit, matchWrap()); content.addView(valEdit, matchWrap())
        row(
            button("Применить") {
                val v = valEdit.text.toString().toIntOrNull() ?: return@button toast("Нужно число")
                val root = repo.userOverrides()
                val cam = root.optJSONObject("camera") ?: JSONObject().also { root.put("camera", it) }
                val old = cam.optJSONArray("vendorTags") ?: JSONArray()
                val list = JSONArray()
                for (i in 0 until old.length()) if (old.getJSONObject(i).optString("name") != keyEdit.text.toString()) list.put(old.getJSONObject(i))
                list.put(JSONObject().put("name", keyEdit.text.toString().trim()).put("type", "int").put("value", v))
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
        private var lastTab = 0
    }
}
