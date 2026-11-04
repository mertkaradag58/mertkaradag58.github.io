package com.mertkaradag.stepviewer

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.core.widget.NestedScrollView

/**
 * İçeriği kadar uzayan ama [maxHeightDp] değerini geçmeyen kaydırılabilir alan.
 * Ölçüm balonu uzun sonuçlarda ekranı kaplamasın, içerik kaydırılarak okunsun diye.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : NestedScrollView(context, attrs) {

    var maxHeightDp = 150

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxPx = (maxHeightDp * resources.displayMetrics.density).toInt()
        val mode = View.MeasureSpec.getMode(heightMeasureSpec)
        val size = View.MeasureSpec.getSize(heightMeasureSpec)
        val limit = if (mode == View.MeasureSpec.UNSPECIFIED) maxPx else minOf(size, maxPx)
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
    }
}
