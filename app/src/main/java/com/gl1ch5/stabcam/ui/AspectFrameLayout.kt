package com.gl1ch5.stabcam.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** Keeps a fixed width:height ratio (9:16 portrait for 16:9 video) and fits inside the parent. */
class AspectFrameLayout @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {

    var ratio: Float = 9f / 16f
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = (parent as? android.view.View)?.height?.takeIf { it > 0 } ?: MeasureSpec.getSize(heightMeasureSpec)
        var w = maxW
        var h = (w / ratio).toInt()
        if (maxH in 1 until h) {
            h = maxH
            w = (h * ratio).toInt()
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
        )
    }
}
