package com.gl1ch5.stabcam.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.gl1ch5.stabcam.R
import com.gl1ch5.stabcam.config.ConfigRepository
import com.gl1ch5.stabcam.config.Quality
import com.gl1ch5.stabcam.lut.Luts
import com.gl1ch5.stabcam.stab.OfflineProcessor
import com.gl1ch5.stabcam.stab.PostMeta
import com.gl1ch5.stabcam.stab.PostJobs
import com.gl1ch5.stabcam.util.Logger
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/** Lists clips recorded in POST mode and runs the offline stabilisation / denoise / LUT pass on the phone. */
class PostActivity : Activity() {
    private lateinit var repo: ConfigRepository
    private lateinit var list: LinearLayout
    private var sigma = 0.25

    private val accent get() = getColor(R.color.accent)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = ConfigRepository(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF1B1233.toInt(), 0xFF0A0A12.toInt(), 0xFF000000.toInt()))
            setPadding(dp(16), dp(44), dp(16), dp(16))
        }
        root.addView(TextView(this).apply { text = "Обработка записей"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); typeface = Typeface.DEFAULT_BOLD })
        root.addView(TextView(this).apply {
            text = "Видео, снятые в режиме ПОСТ. Стабилизация с предвидением по гироскопу, шумоподавление и LUT применяются здесь, обычно за 10–20 секунд. Оригинал остаётся."
            setTextColor(0x99FFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setPadding(0, dp(4), 0, dp(12))
        })
        // look-ahead window
        root.addView(TextView(this).apply { text = "ПРЕДВИДЕНИЕ"; setTextColor(accent); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); letterSpacing = 0.1f; setPadding(dp(4), 0, 0, dp(6)) })
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val opts = listOf("Резко" to 0.15, "Обычно" to 0.25, "Плавно" to 0.4, "Кино" to 0.7)
        val views = ArrayList<TextView>()
        fun paint() = views.forEachIndexed { i, tv -> val on = opts[i].second == sigma
            (tv.background as GradientDrawable).setColor(if (on) 0x44FFB020 else 0x22FFFFFF); tv.setTextColor(if (on) accent else 0xCCFFFFFF.toInt()) }
        opts.forEach { (name, v) ->
            views += TextView(this).apply {
                text = name; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); gravity = Gravity.CENTER; setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply { cornerRadius = dp(16).toFloat() }
                setOnClickListener { sigma = v; paint() }
            }.also { chips.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) }) }
        }
        paint()
        root.addView(chips)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(24)) }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val dir = File(filesDir, "post")
        val metas = (dir.listFiles { f -> f.name.endsWith(".meta.json") } ?: emptyArray()).sortedByDescending { it.name }
        if (metas.isEmpty()) {
            list.addView(TextView(this).apply { text = "Пока нет записей. Включите «Режим ПОСТ» в Настройки → Продвинутые и снимите ролик."; setTextColor(0x99FFFFFF.toInt()); setPadding(dp(4), dp(20), dp(4), 0) })
            return
        }
        metas.forEach { f -> runCatching { addRow(f, PostMeta(JSONObject(f.readText()))) } }
    }

    private fun addRow(f: File, meta: PostMeta) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0x22FFFFFF); setStroke(dp(1), 0x22FFFFFF) }
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        card.addView(TextView(this).apply { text = meta.video; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f) })
        val secs = meta.frameRel.size.toDouble() / meta.fps
        card.addView(TextView(this).apply {
            text = "${meta.width}×${meta.height} · ${meta.fps} fps · ${"%.0f".format(secs)} с" + if (meta.hdr) " · HLG" else ""
            setTextColor(0x99FFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        val status = TextView(this).apply { setTextColor(accent); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setPadding(0, dp(6), 0, 0) }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = android.view.View.GONE }
        val go = Button(this).apply { text = "Обработать"; isAllCaps = false }
        val cancel = Button(this).apply { text = "Отмена"; isAllCaps = false; visibility = android.view.View.GONE }
        card.addView(status); card.addView(bar)
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(go, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); btns.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(btns)
        val job0 = PostJobs.find(f)
        rows[f.name] = Row(status, bar, go, cancel)
        job0?.let { render(f.name, it) }
        cancel.setOnClickListener { PostJobs.cancelAll() }
        go.setOnClickListener {
            val j = PostJobs.find(f)
            if (j?.state == "готово" && j.output != null) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(j.output, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                return@setOnClickListener
            }
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 5)
            if (File(f.parentFile, f.name.removeSuffix(".meta.json") + ".gcsv").exists()) PostJobs.enqueue(this, PostJobs.Job(f, sigma))
            else Toast.makeText(this, "Нет гиро-лога (.gcsv)", Toast.LENGTH_SHORT).show()
        }
    }

    private class Row(val status: TextView, val bar: ProgressBar, val go: Button, val cancel: Button)
    private val rows = HashMap<String, Row>()

    private fun render(metaName: String, j: PostJobs.Job) {
        val r = rows[metaName] ?: return
        r.status.text = if (j.state == "идёт") j.text else "${j.state.replaceFirstChar { it.uppercase() }}: ${j.text}"
        r.bar.visibility = if (j.state == "идёт" || j.state == "ожидает") android.view.View.VISIBLE else android.view.View.GONE
        r.bar.progress = (j.fraction * 1000).toInt()
        r.cancel.visibility = if (j.state == "идёт" || j.state == "ожидает") android.view.View.VISIBLE else android.view.View.GONE
        r.go.isEnabled = j.state != "идёт" && j.state != "ожидает"
        r.go.text = if (j.state == "готово") "Открыть результат" else "Обработать"
    }

    override fun onResume() {
        super.onResume()
        PostJobs.listener = { j -> runOnUiThread { render(j.metaFile.name, j) } }
        PostJobs.jobs.forEach { render(it.metaFile.name, it) }
    }

    override fun onPause() {
        super.onPause()
        PostJobs.listener = null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @Suppress("unused") private val keep = ValueAnimator::class
    @Suppress("unused") private val log = Logger::class
}
