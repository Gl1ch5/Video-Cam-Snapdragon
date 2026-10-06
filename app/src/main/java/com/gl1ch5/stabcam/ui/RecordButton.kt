package com.gl1ch5.stabcam.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/** White ring + red disc that morphs into a rounded square while recording. */
class RecordButton @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() }

    /** 0 = idle (circle), 1 = recording (square). */
    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var pulser: ValueAnimator? = null
    private var pulseP = 0f

    var recording = false
        set(value) {
            if (field == value) return
            field = value
            pulser?.cancel(); pulseP = 0f
            if (value) pulser = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 700; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { pulseP = it.animatedValue as Float; invalidate() }
                start()
            }
            animator?.cancel()
            animator = ValueAnimator.ofFloat(progress, if (value) 1f else 0f).apply {
                duration = 220
                interpolator = DecelerateInterpolator()
                addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                start()
            }
        }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        val stroke = r * 0.09f
        ring.strokeWidth = stroke
        ring.alpha = (255 * (1f - 0.45f * pulseP)).toInt()
        fill.alpha = (255 * (1f - 0.25f * pulseP)).toInt()
        canvas.drawCircle(cx, cy, r - stroke / 2, ring)

        val idleR = r - stroke * 2.1f
        val half = idleR + (idleR * 0.5f - idleR) * progress
        val corner = idleR + (idleR * 0.18f - idleR) * progress
        canvas.drawRoundRect(cx - half, cy - half, cx + half, cy + half, corner, corner, fill)
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        animate().scaleX(if (pressed) 0.92f else 1f).scaleY(if (pressed) 0.92f else 1f).setDuration(90).start()
    }
}
