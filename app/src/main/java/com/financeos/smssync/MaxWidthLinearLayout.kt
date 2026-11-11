package com.financeos.smssync

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout

/** A vertical column that never grows wider than `maxWidth`; the parent centres it. */
class MaxWidthLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val maxWidthPx: Int

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.MaxWidthLinearLayout)
        maxWidthPx = a.getDimensionPixelSize(R.styleable.MaxWidthLinearLayout_maxWidth, Int.MAX_VALUE)
        a.recycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = View.MeasureSpec.getSize(widthMeasureSpec)
        val spec = if (size > maxWidthPx) {
            View.MeasureSpec.makeMeasureSpec(maxWidthPx, View.MeasureSpec.getMode(widthMeasureSpec).let { if (it == View.MeasureSpec.UNSPECIFIED) it else View.MeasureSpec.EXACTLY })
        } else widthMeasureSpec
        super.onMeasure(spec, heightMeasureSpec)
    }
}
