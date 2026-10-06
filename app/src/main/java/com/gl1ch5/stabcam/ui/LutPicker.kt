package com.gl1ch5.stabcam.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.gl1ch5.stabcam.lut.Luts

/** Dark glass drop-down above the LUT button: colour swatch per look, subtitle, selected marker. */
class LutPicker(
    private val ctx: Context,
    private val onPick: (id: String) -> Unit,
    private val onImport: () -> Unit,
) {
    private var popup: PopupWindow? = null

    private val probes = listOf(
        floatArrayOf(0.88f, 0.66f, 0.52f), floatArrayOf(0.35f, 0.58f, 0.92f), floatArrayOf(0.30f, 0.68f, 0.30f),
        floatArrayOf(0.92f, 0.92f, 0.92f), floatArrayOf(0.55f, 0.55f, 0.55f), floatArrayOf(0.12f, 0.12f, 0.18f),
    )

    fun show(anchor: View, selectedId: String) {
        dismiss()
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }
        data class Row(val id: String, val name: String, val sub: String)
        val rows = buildList {
            add(Row("", "Без LUT", "оригинальные цвета"))
            Luts.builtin.forEach { add(Row(it.id, it.name, it.subtitle)) }
            runCatching { com.gl1ch5.stabcam.module.ModuleManager(ctx).lutEntries() }.getOrDefault(emptyList()).forEach { add(Row(it.id, it.name, it.subtitle)) }
            Luts.userEntries(ctx).forEach { add(Row(it.id, it.name, it.subtitle)) }
        }
        rows.forEachIndexed { i, r ->
            val selected = r.id == selectedId
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(if (selected) 0x33FFB020 else Color.TRANSPARENT) }
                setOnClickListener { onPick(r.id); dismiss() }
                alpha = 0f
                translationY = dp(10).toFloat()
            }
            row.addView(swatch(r.id, dp(64), dp(30)))
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
            texts.addView(TextView(ctx).apply { text = r.name; setTextColor(if (selected) 0xFFFFB020.toInt() else Color.WHITE); textSize = 15f; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) })
            texts.addView(TextView(ctx).apply { text = r.sub; setTextColor(0x99FFFFFF.toInt()); textSize = 11f })
            row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (selected) row.addView(View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFFB020.toInt()) } }, LinearLayout.LayoutParams(dp(8), dp(8)))
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
            row.animate().alpha(1f).translationY(0f).setStartDelay(i * 22L).setDuration(200).start()
        }
        list.addView(TextView(ctx).apply {
            text = "＋  Импорт .cube…"; setTextColor(0xFFFFB020.toInt()); textSize = 14f; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(8), 0); setOnClickListener { onImport(); dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))

        val scroll = ScrollView(ctx).apply {
            addView(list)
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0xB3141418.toInt()); setStroke(dp(1), 0x33FFFFFF) }
            clipToOutline = true
            isVerticalScrollBarEnabled = false
        }
        val w = dp(250)
        val maxH = (ctx.resources.displayMetrics.heightPixels * 0.6f).toInt()
        scroll.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        val h = minOf(scroll.measuredHeight, maxH)
        val pw = PopupWindow(scroll, w, h, true).apply {
            elevation = dp(12).toFloat()
            isOutsideTouchable = true
        }
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        pw.showAtLocation(anchor, Gravity.NO_GRAVITY, loc[0], loc[1] - h - dp(8))
        scroll.pivotX = 0f
        scroll.pivotY = h.toFloat()
        scroll.scaleX = 0.7f; scroll.scaleY = 0.7f; scroll.alpha = 0f
        scroll.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setInterpolator(OvershootInterpolator(1.4f)).start()
        popup = pw
    }

    fun dismiss() { popup?.dismiss(); popup = null }

    /** Strip of six probe colours (skin, sky, grass, white, grey, shadow) pushed through the look. */
    private fun swatch(id: String, w: Int, h: Int): View {
        val lut = if (id.isEmpty()) null else Luts.resolve(ctx, id)
        val colors = probes.map { p ->
            val o = lut?.apply(p[0], p[1], p[2]) ?: Triple(p[0], p[1], p[2])
            Color.rgb((o.first * 255).toInt(), (o.second * 255).toInt(), (o.third * 255).toInt())
        }.toIntArray()
        return View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply { cornerRadius = h / 2.4f }
            layoutParams = LinearLayout.LayoutParams(w, h)
        }
    }
}
