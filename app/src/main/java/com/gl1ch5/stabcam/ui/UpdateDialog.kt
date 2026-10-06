package com.gl1ch5.stabcam.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.gl1ch5.stabcam.update.Updater
import com.gl1ch5.stabcam.util.Logger
import kotlin.concurrent.thread

/** "Доступно обновление": version, what changed, download progress inside the card, then install. */
class UpdateDialog(private val act: Activity) {

    fun show(u: Updater, r: Updater.Release, onLater: () -> Unit = {}) {
        val d = Dialog(act)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
        val accent = 0xFFFFB020.toInt()
        fun tv(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(act).apply {
            text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF01E1438.toInt(), 0xF00C0C14.toInt())).apply { cornerRadius = dp(26).toFloat(); setStroke(dp(1), 0x33FFFFFF) }
            setPadding(dp(22), dp(22), dp(22), dp(16))
        }
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(act).apply {
            text = "⬆"; textSize = 26f; gravity = Gravity.CENTER; setTextColor(accent)
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0x33FFB020) }
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        val ht = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        ht.addView(tv("Доступно обновление", 19f, Color.WHITE, true))
        ht.addView(tv("${u.currentId()}  →  ${r.id}", 12f, accent))
        head.addView(ht)
        card.addView(head)

        val notes = r.notes.lines().map { it.trim() }.filter { it.startsWith("-") || it.startsWith("•") }.take(8)
            .map { "•  " + it.trimStart('-', '•', ' ').replace(Regex("\\s*\\([0-9a-f]{6,}\\)$"), "") }
        if (notes.isNotEmpty()) {
            card.addView(tv("ЧТО НОВОГО", 11f, accent).apply { letterSpacing = 0.12f; setPadding(0, dp(16), 0, dp(6)) })
            card.addView(ScrollView(act).apply { addView(tv(notes.joinToString("\n"), 13f, 0xDDFFFFFF.toInt()).apply { setLineSpacing(0f, 1.15f) }); isVerticalScrollBarEnabled = false },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)))
        } else card.addView(tv("Новая сборка готова к установке.", 13f, 0xCCFFFFFF.toInt()).apply { setPadding(0, dp(14), 0, 0) })
        card.addView(tv("${"%.0f".format(r.size / 1024.0 / 1024)} МБ · установка займёт несколько секунд", 12f, 0x99FFFFFF.toInt()).apply { setPadding(0, dp(10), 0, 0) })

        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        val status = tv("", 12f, accent).apply { setPadding(0, dp(10), 0, 0); visibility = View.GONE }
        card.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        card.addView(status)

        fun button(t: String, primary: Boolean) = TextView(act).apply {
            text = t; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (primary) Color.BLACK else Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(if (primary) accent else 0x22FFFFFF) }
            setPadding(dp(8), dp(13), dp(8), dp(13))
        }
        val go = button("Обновить сейчас", true)
        val later = button("Позже", false)
        val btns = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(16), 0, 0) }
        btns.addView(go); btns.addView(later, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        card.addView(btns)

        later.setOnClickListener { d.dismiss(); onLater() }
        go.setOnClickListener {
            if (!u.canInstall()) { status.text = "Разрешите установку из StabCam и нажмите «Обновить» ещё раз"; status.visibility = View.VISIBLE; u.requestInstallPermission(); return@setOnClickListener }
            go.isEnabled = false; later.visibility = View.GONE; d.setCancelable(false)
            bar.visibility = View.VISIBLE; status.visibility = View.VISIBLE; status.text = "Загрузка…"
            thread {
                val f = u.download(r) { p -> act.runOnUiThread { bar.progress = p; status.text = "Загрузка… $p%" } }
                act.runOnUiThread {
                    if (f == null) { status.text = "Не удалось скачать. Проверьте сеть."; go.isEnabled = true; go.text = "Повторить"; later.visibility = View.VISIBLE; d.setCancelable(true) }
                    else { bar.progress = 100; status.text = "Устанавливаю… Android может попросить подтвердить." }
                }
                if (f != null) runCatching { u.install(f) }.onFailure { Logger.e(Updater.TAG, "Установка", it) }
                act.runOnUiThread { if (f != null) go.postDelayed({ d.dismiss() }, 2500) }
            }
        }

        d.setContentView(ScrollView(act).apply { addView(card); isVerticalScrollBarEnabled = false; setPadding(dp(14), dp(24), dp(14), dp(24)) })
        d.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); setDimAmount(0.6f) }
        d.show()
        card.scaleX = 0.9f; card.scaleY = 0.9f; card.alpha = 0f; card.translationY = dp(30).toFloat()
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).translationY(0f).setDuration(280).setInterpolator(OvershootInterpolator(1.1f)).start()
    }
}
