package com.gl1ch5.stabcam.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.CycleInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView

/** Small shared animations so every button reacts the same way. */
object Fx {
    /** Spring "pop" when a toggle changes state. */
    fun pop(v: View) {
        v.animate().cancel()
        v.scaleX = 0.7f; v.scaleY = 0.7f
        v.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(3.2f)).start()
    }

    /** Horizontal wiggle: "this is not available here". */
    fun shake(v: View) {
        ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f, 14f).apply { duration = 340; interpolator = CycleInterpolator(2.5f); start() }
    }

    /** Soft glow: brief grow + fade of a halo-like alpha dip. */
    fun pulse(v: View) {
        ObjectAnimator.ofPropertyValuesHolder(v, PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.18f, 1f), PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.18f, 1f))
            .apply { duration = 380; interpolator = AccelerateDecelerateInterpolator(); start() }
    }

    /** Text slides out one way and the new text slides in from the other; [dir] +1 = next (left), -1 = previous. */
    fun slideText(tv: TextView, text: String, dir: Int = 1) {
        if (tv.text.toString() == text) return
        val d = tv.resources.displayMetrics.density * 28f * dir
        tv.animate().cancel()
        tv.animate().translationX(-d).alpha(0f).setDuration(110).withEndAction {
            tv.text = text
            tv.translationX = d
            tv.animate().translationX(0f).alpha(1f).setDuration(190).setInterpolator(OvershootInterpolator(1.4f)).start()
        }.start()
    }

    /** Rotation flourish (menu / flip buttons). */
    fun spin(v: View, degrees: Float = 180f) {
        v.animate().rotationBy(degrees).setDuration(320).setInterpolator(OvershootInterpolator(1.6f)).start()
    }
}
