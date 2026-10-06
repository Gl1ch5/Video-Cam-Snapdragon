package com.gl1ch5.stabcam.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.gl1ch5.stabcam.BuildConfig
import com.gl1ch5.stabcam.R
import com.gl1ch5.stabcam.camera.CameraCaps
import com.gl1ch5.stabcam.config.ConfigRepository
import com.gl1ch5.stabcam.update.Updater
import com.gl1ch5.stabcam.util.Logger
import android.content.Intent
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Settings are a thin UI over [ConfigRepository]: quick option rows write single keys into the user layer,
 * the JSON editor exposes the whole user layer for anything else (bitrates, vendor tags, ...).
 */
class SettingsActivity : Activity() {

    private lateinit var repo: ConfigRepository
    private lateinit var content: LinearLayout
    private lateinit var userEditor: EditText
    private lateinit var effectiveView: TextView

    private data class Option(val path: String, val title: String, val values: List<String>, val labels: List<String> = values)

    private val options = listOf(
        Option("video.codec", "Кодек", listOf("hevc", "avc"), listOf("HEVC (H.265)", "H.264")),
        Option("video.audio", "Звук", listOf("true", "false"), listOf("Вкл", "Выкл")),
        Option("camera.noiseReduction", "Шумоподавление", listOf("off", "minimal", "fast", "hq"), listOf("Выкл", "Минимум", "Быстрое", "Качество")),
        Option("camera.edge", "Резкость (edge)", listOf("off", "fast", "hq"), listOf("Выкл", "Быстрая", "Качество")),
        Option("camera.distortionCorrection", "Коррекция дисторсии", listOf("off", "fast", "hq"), listOf("Выкл", "Быстрая", "Качество")),
        Option("ui.showInfo", "Инфо-строка на экране", listOf("true", "false"), listOf("Вкл", "Выкл")),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ConfigRepository(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(48), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(getColor(R.color.bg))
            addView(content)
        })
        build()
    }

    private fun build() {
        content.removeAllViews()
        title("Настройки", 26f)
        caption("StabCam ${BuildConfig.VERSION_NAME} (код ${BuildConfig.VERSION_CODE})")
        val updStatus = caption("Обновления: автопроверка при запуске ${if (repo.load().updateAuto) "включена" else "выключена"}")
        row(
            button("Проверить обновление") {
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
            },
            button("Лог") { startActivity(Intent(this, LogActivity::class.java)) },
        )

        section("Профиль устройства")
        val preset = repo.activePreset
        body(preset?.let { "${it.name}\n${it.description}" } ?: "Пресет не найден — используются значения по умолчанию")
        caption(ConfigRepository.deviceProps().entries.joinToString("\n") { "${it.key}: ${it.value}" })

        section("Быстрые настройки")
        val eff = repo.effectiveJson()
        options.forEach { addOptionRow(it, eff) }

        section("Пользовательский конфиг (JSON)")
        caption("Переопределяет пресет. Пример: {\"video\":{\"bitrateMbps\":{\"4K60\":150}}}")
        userEditor = EditText(this).apply {
            setText(repo.userOverrides().toString(2))
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(getColor(R.color.text))
            setBackgroundResource(R.drawable.bg_pill_strong)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP
            minLines = 6
        }
        content.addView(userEditor, matchWrap())
        row(
            button("Сохранить") {
                val json = runCatching { JSONObject(userEditor.text.toString().ifBlank { "{}" }) }.getOrElse {
                    toast("Ошибка JSON: ${it.message}")
                    return@button
                }
                repo.saveUserOverrides(json)
                toast("Сохранено")
                build()
            },
            button("Сбросить") {
                repo.resetUserOverrides()
                toast("Сброшено к пресету")
                build()
            },
            button("Копировать") { copy("config", repo.effectiveJson().toString(2)) },
        )

        section("Лаборатория vendor-ключей")
        caption("Ключи OnePlus/Qualcomm из отчёта ниже. Задайте ключ и число (пробуйте 0, 1, 2, 3), затем откройте камеру и проверьте стабилизацию.")
        val keyEdit = EditText(this).apply {
            setText("com.oplus.video.stabilization.mode")
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(getColor(R.color.text))
        }
        val valEdit = EditText(this).apply {
            setText("1")
            hint = "значение"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            setTextColor(getColor(R.color.text))
        }
        content.addView(keyEdit, matchWrap())
        content.addView(valEdit, matchWrap())
        row(
            button("Применить") {
                val v = valEdit.text.toString().toIntOrNull() ?: return@button toast("Нужно число")
                val root = repo.userOverrides()
                val cam = root.optJSONObject("camera") ?: JSONObject().also { root.put("camera", it) }
                val old = cam.optJSONArray("vendorTags") ?: org.json.JSONArray()
                val list = org.json.JSONArray()
                for (i in 0 until old.length()) if (old.getJSONObject(i).optString("name") != keyEdit.text.toString()) list.put(old.getJSONObject(i))
                list.put(JSONObject().put("name", keyEdit.text.toString().trim()).put("type", "int").put("value", v))
                cam.put("vendorTags", list)
                repo.saveUserOverrides(root)
                toast("Ключ задан, откройте камеру")
                build()
            },
            button("Убрать все ключи") {
                val root = repo.userOverrides()
                root.optJSONObject("camera")?.remove("vendorTags")
                repo.saveUserOverrides(root)
                build()
            },
        )

        section("Итоговый конфиг")
        effectiveView = mono(eff.toString(2))

        section("Возможности камеры")
        caption("Нужно для следующего этапа (гиро-EIS): OIS data, timestamp source, интринсики, vendor-теги.")
        val reportView = mono("")
        reportView.visibility = View.GONE
        row(
            button("Показать отчёт") {
                val caps = CameraCaps.find(this, true)
                reportView.text = caps?.report() ?: "Камера не найдена"
                reportView.visibility = View.VISIBLE
            },
            button("Копировать отчёт") {
                copy("camera report", CameraCaps.find(this, true)?.report() ?: "")
            },
        )
    }

    private fun addOptionRow(opt: Option, eff: JSONObject) {
        val current = readPath(eff, opt.path)?.toString()
        val idx = opt.values.indexOf(current).coerceAtLeast(0)
        val rowView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundResource(R.drawable.bg_pill_strong)
        }
        val titleView = TextView(this).apply {
            text = opt.title
            setTextColor(getColor(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        val valueView = TextView(this).apply {
            text = opt.labels[idx]
            setTextColor(getColor(R.color.accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        }
        rowView.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        rowView.addView(valueView)
        rowView.setOnClickListener {
            val next = (opt.values.indexOf(readPath(repo.effectiveJson(), opt.path)?.toString()).coerceAtLeast(0) + 1) % opt.values.size
            val raw = opt.values[next]
            repo.set(opt.path, raw.toBooleanStrictOrNull() ?: raw)
            build()
        }
        content.addView(rowView, matchWrap().apply { topMargin = dp(8) })
    }

    private fun readPath(json: JSONObject, path: String): Any? {
        var node: Any? = json
        for (k in path.split('.')) node = (node as? JSONObject)?.opt(k) ?: return null
        return node
    }

    // --- tiny view helpers ---

    private fun title(text: String, size: Float) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        typeface = Typeface.DEFAULT_BOLD
    })

    private fun section(text: String) = content.addView(TextView(this).apply {
        this.text = text.uppercase()
        setTextColor(getColor(R.color.accent))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        letterSpacing = 0.08f
    }, matchWrap().apply { topMargin = dp(28); bottomMargin = dp(6) })

    private fun body(text: String) = content.addView(TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
    })

    private fun caption(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(getColor(R.color.text_dim))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }.also { content.addView(it, matchWrap().apply { topMargin = dp(4) }) }

    private fun mono(text: String): TextView = TextView(this).apply {
        this.text = text
        typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
        setTextColor(getColor(R.color.text_dim))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setBackgroundResource(R.drawable.bg_pill_strong)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }.also { content.addView(it, matchWrap()) }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEach { r.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        content.addView(r, matchWrap().apply { topMargin = dp(8) })
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun copy(label: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        toast("Скопировано")
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
