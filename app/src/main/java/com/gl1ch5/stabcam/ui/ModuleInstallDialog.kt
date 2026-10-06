package com.gl1ch5.stabcam.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gl1ch5.stabcam.module.ModuleManager

/** Glass card shown before a module is installed: who/what, device fit, exactly what changes (old → new). */
class ModuleInstallDialog(private val act: Activity) {

    fun show(
        m: ModuleManager.Module, summary: List<String>, notes: List<String>, diffs: List<ModuleManager.Diff>,
        deviceOk: Boolean?, conflicts: List<String>, missingDeps: List<String>,
        onInstall: (enable: Boolean) -> Unit,
    ) {
        val d = Dialog(act)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
        val accent = 0xFFFFB020.toInt()

        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF01E1438.toInt(), 0xF00C0C14.toInt())).apply {
                cornerRadius = dp(26).toFloat(); setStroke(dp(1), 0x33FFFFFF)
            }
            setPadding(dp(20), dp(20), dp(20), dp(14))
        }
        fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(act).apply {
            text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        // header
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(act).apply {
            text = m.icon; textSize = 28f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0x33FFB020) }
        }, LinearLayout.LayoutParams(dp(56), dp(56)))
        val ht = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, 0, 0) }
        ht.addView(text(m.name, 19f, Color.WHITE, true))
        ht.addView(text("v${m.version}" + if (m.author.isNotEmpty()) " · ${m.author}" else "", 12f, 0x99FFFFFF.toInt()))
        head.addView(ht, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(head)
        if (m.description.isNotEmpty()) card.addView(text(m.description, 13f, 0xCCFFFFFF.toInt()).apply { setPadding(0, dp(12), 0, 0) })

        // badges
        fun badge(t: String, color: Int) = TextView(act).apply {
            text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); setTextColor(color); setPadding(dp(10), dp(5), dp(10), dp(5))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor((color and 0x00FFFFFF) or 0x22000000) }
        }
        val badges = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        fun addBadge(t: String, c: Int) = badges.addView(badge(t, c), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
        when (deviceOk) {
            true -> addBadge("✓ подходит вашему телефону", 0xFF6BE675.toInt())
            false -> addBadge("⚠ для другого устройства", 0xFFFFC857.toInt())
            null -> addBadge("для любых устройств", 0xCCFFFFFF.toInt())
        }
        if (m.lutCount > 0) addBadge("LUT ${m.lutCount}", accent)
        if (m.settings.isNotEmpty()) addBadge("настроек ${m.settings.size}", accent)
        card.addView(android.widget.HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(badges) })

        // warnings
        if (missingDeps.isNotEmpty()) card.addView(text("Нужны ещё моды: ${missingDeps.joinToString()}", 12f, 0xFFFFC857.toInt()).apply { setPadding(0, dp(10), 0, 0) })
        if (conflicts.isNotEmpty()) card.addView(text("Пересекается с включёнными модами: ${conflicts.take(4).joinToString()}. Победит тот, что ниже в списке (порядок можно менять).", 12f, 0xFFFFC857.toInt()).apply { setPadding(0, dp(10), 0, 0) })
        if (notes.isNotEmpty()) card.addView(text("Исправлено автоматически: ${notes.joinToString("; ")}", 12f, 0x99FFFFFF.toInt()).apply { setPadding(0, dp(10), 0, 0) })

        // what changes
        if (diffs.isNotEmpty()) {
            card.addView(text("ИЗМЕНИТ", 11f, accent).apply { letterSpacing = 0.12f; setPadding(0, dp(16), 0, dp(6)) })
            val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            diffs.forEach { df ->
                val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(5), 0, dp(5)); alpha = 0f }
                row.addView(text(df.label, 13f, Color.WHITE), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(text("${df.old}  →  ${df.new}", 13f, accent))
                list.addView(row)
            }
            val sc = ScrollView(act).apply { addView(list); isVerticalScrollBarEnabled = false }
            card.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { })
            for (i in 0 until list.childCount) list.getChildAt(i).animate().alpha(1f).setStartDelay(60L + i * 30L).setDuration(180).start()
            sc.post { if (sc.height > dp(220)) sc.layoutParams = sc.layoutParams.apply { height = dp(220) } }
        }
        if (summary.isNotEmpty()) card.addView(text(summary.joinToString(" · "), 12f, 0x99FFFFFF.toInt()).apply { setPadding(0, dp(10), 0, 0) })

        // buttons
        fun button(t: String, primary: Boolean, onClick: () -> Unit) = TextView(act).apply {
            text = t; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (primary) Color.BLACK else Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(if (primary) accent else 0x22FFFFFF) }
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener { d.dismiss(); onClick() }
        }
        val btns = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(16), 0, 0) }
        btns.addView(button("Установить и включить", true) { onInstall(true) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val second = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        second.addView(button("Выключенным", false) { onInstall(false) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6); topMargin = dp(8) })
        second.addView(button("Отмена", false) {}, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = dp(8) })
        btns.addView(second)
        card.addView(btns)

        val outer = ScrollView(act).apply { addView(card); isVerticalScrollBarEnabled = false; setPadding(dp(14), dp(24), dp(14), dp(24)) }
        d.setContentView(outer)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.55f)
        }
        d.show()
        card.scaleX = 0.9f; card.scaleY = 0.9f; card.alpha = 0f; card.translationY = dp(24).toFloat()
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).translationY(0f).setDuration(260).setInterpolator(OvershootInterpolator(1.1f)).start()
    }
}
