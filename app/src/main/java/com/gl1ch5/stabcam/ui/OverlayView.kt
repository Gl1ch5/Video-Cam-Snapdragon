package com.gl1ch5.stabcam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.abs
import kotlin.math.round

/**
 * Drawn over the preview: rule-of-thirds grid, an electronic level (turns green when the horizon is level, landscape
 * holds count as level too) and a small bar showing how much of the stabilization margin is in use.
 */
class OverlayView(ctx: Context) : View(ctx) {
    var grid = false; set(v) { field = v; invalidate() }
    var level = false; set(v) { field = v; invalidate() }
    var marginBar = false; set(v) { field = v; invalidate() }

    /** Device roll in degrees (0 = upright portrait). */
    var rollDeg = 0.0; set(v) { field = v; if (level) invalidate() }
    /** Share of the stabilization margin in use, 0..1 (negative = stabilization off). */
    var margin = -1.0; set(v) { field = v; if (marginBar) invalidate() }

    private val d = ctx.resources.displayMetrics.density
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF; strokeWidth = 1f * d }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND }
    private val barBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val barFg = Paint(Paint.ANTI_ALIAS_FLAG)

    init { setWillNotDraw(false) }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (grid) {
            for (i in 1..2) {
                c.drawLine(w * i / 3, 0f, w * i / 3, h, gridPaint)
                c.drawLine(0f, h * i / 3, w, h * i / 3, gridPaint)
            }
        }
        if (level) {
            val dev = rollDeg - round(rollDeg / 90.0) * 90.0
            val ok = abs(dev) < 1.0
            levelPaint.color = if (ok) 0xFF34C759.toInt() else 0xCCFFFFFF.toInt()
            val half = w * 0.18f
            c.save()
            c.rotate((-dev).toFloat(), w / 2, h / 2)
            c.drawLine(w / 2 - half, h / 2, w / 2 - 8 * d, h / 2, levelPaint)
            c.drawLine(w / 2 + 8 * d, h / 2, w / 2 + half, h / 2, levelPaint)
            c.restore()
            if (!ok) c.drawLine(w / 2 - half * 0.35f, h / 2, w / 2 + half * 0.35f, h / 2, gridPaint)
        }
        if (marginBar && margin >= 0) {
            val bw = w * 0.3f; val bh = 4 * d; val x = (w - bw) / 2; val y = h - 14 * d
            c.drawRoundRect(x, y, x + bw, y + bh, bh, bh, barBg)
            val m = margin.coerceIn(0.0, 1.0).toFloat()
            barFg.color = when { m > 0.9f -> 0xFFFF453A.toInt(); m > 0.6f -> 0xFFFFB020.toInt(); else -> 0xFF34C759.toInt() }
            c.drawRoundRect(x, y, x + bw * m, y + bh, bh, bh, barFg)
        }
    }
}
