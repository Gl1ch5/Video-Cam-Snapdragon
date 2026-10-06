package com.gl1ch5.stabcam.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/** Compact dark-glass drop-down: rows "title … value", tap cycles the value; the last row opens full settings. */
class QuickMenu(private val ctx: Context) {
    class Item(val title: String, val value: () -> String, val tap: () -> Unit, val closeOnTap: Boolean = false, val accent: Boolean = false)

    private var popup: PopupWindow? = null

    fun show(anchor: View, items: List<Item>, onDismiss: () -> Unit) {
        popup?.dismiss()
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0xB3141418.toInt()); setStroke(dp(1), 0x33FFFFFF) }
        }
        val values = ArrayList<Pair<TextView, Item>>()
        fun refresh() = values.forEach { (tv, it) -> tv.text = it.value() }
        items.forEachIndexed { i, it ->
            if (it.accent) col.addView(View(ctx).apply { setBackgroundColor(0x22FFFFFF) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(dp(10), dp(4), dp(10), dp(4)) })
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(14), 0)
                alpha = 0f; translationY = -dp(8).toFloat()
            }
            row.addView(TextView(ctx).apply {
                text = it.title; textSize = 15f
                setTextColor(if (it.accent) 0xFFFFB020.toInt() else Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val v = TextView(ctx).apply { text = it.value(); textSize = 14f; setTextColor(if (it.accent) 0xFFFFB020.toInt() else 0xFFFFB020.toInt()) }
            row.addView(v)
            values += v to it
            row.setOnClickListener { _ ->
                it.tap()
                if (it.closeOnTap) popup?.dismiss() else {
                    refresh()
                    v.animate().cancel(); v.scaleX = 1.25f; v.scaleY = 1.25f
                    v.animate().scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(OvershootInterpolator(3f)).start()
                }
            }
            col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
            row.animate().alpha(1f).translationY(0f).setStartDelay(i * 25L).setDuration(200).start()
        }
        val w = dp(280)
        col.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val pw = PopupWindow(col, w, col.measuredHeight, true).apply {
            elevation = dp(12).toFloat()
            isOutsideTouchable = true
            setOnDismissListener { popup = null; onDismiss() }
        }
        pw.showAsDropDown(anchor, -(w - anchor.width), dp(2))
        col.pivotX = w.toFloat(); col.pivotY = 0f
        col.scaleX = 0.75f; col.scaleY = 0.75f; col.alpha = 0f
        col.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(220).setInterpolator(OvershootInterpolator(1.3f)).start()
        popup = pw
    }
}
