package com.mertkaradag.stepviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Hassas imleç ve büyüteç. Parmağın biraz üstünde bir artı işareti gösterir (parmak hedefi
 * kapatmaz); üst köşede imlecin çevresi büyütülmüş olarak görünür. Dokunma almaz.
 */
class LoupeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val d = resources.displayMetrics.density

    /** İmlecin parmaktan yukarı uzaklığı (piksel). */
    val fingerOffset = 72f * d

    /** Büyütülen bölgenin kenarı (ekran pikseli). */
    val sampleSize = (56f * d).toInt()

    private val loupeRadius = 64f * d
    private var cursorX = -1f
    private var cursorY = -1f
    private var image: Bitmap? = null
    private var label: String = ""
    private var snapped = false

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
        color = Color.WHITE
    }
    private val ringOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5.5f * d
        color = Color.argb(150, 0, 0, 0)
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * d
        color = Color.rgb(232, 101, 14)
    }
    private val crossShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.6f * d
        color = Color.argb(170, 255, 255, 255)
    }
    private val snapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * d
        color = Color.rgb(46, 160, 67)
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 29, 36, 51) }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12.5f * d
    }
    private val clip = Path()

    val active: Boolean get() = cursorX >= 0f

    /** İmleç konumu (ekran pikseli); negatif = gizle. */
    fun setCursor(x: Float, y: Float) {
        cursorX = x
        cursorY = y
        if (x < 0f) {
            image = null
            label = ""
        }
        invalidate()
    }

    fun setImage(bmp: Bitmap?) {
        image = bmp
        invalidate()
    }

    /** İmlecin yakaladığı öğe (ör. "Köşe", "Orta nokta"); boş = yok. */
    fun setSnap(text: String, isSnapped: Boolean) {
        label = text
        snapped = isSnapped
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (cursorX < 0f) return
        // Artı imleç
        val r = 14f * d
        val gap = 4f * d
        for (p in arrayOf(crossShadow, cross)) {
            canvas.drawLine(cursorX - r, cursorY, cursorX - gap, cursorY, p)
            canvas.drawLine(cursorX + gap, cursorY, cursorX + r, cursorY, p)
            canvas.drawLine(cursorX, cursorY - r, cursorX, cursorY - gap, p)
            canvas.drawLine(cursorX, cursorY + gap, cursorX, cursorY + r, p)
        }
        if (snapped) canvas.drawRect(cursorX - 8 * d, cursorY - 8 * d, cursorX + 8 * d, cursorY + 8 * d, snapPaint)

        // Büyüteç: imleç sol yarıdaysa sağ üstte, değilse sol üstte
        val cx = if (cursorX < width / 2f) width - loupeRadius - 16 * d else loupeRadius + 16 * d
        val cy = loupeRadius + 120 * d
        val bmp = image
        if (bmp != null && !bmp.isRecycled) {
            // Örnek bölge imleç merkezli; kenarda kırpıldıysa kaydırma düzeltmesi
            val half = sampleSize / 2f
            val sx0 = (cursorX - half).coerceIn(0f, maxOf(0f, width - sampleSize.toFloat()))
            val sy0 = (cursorY - half).coerceIn(0f, maxOf(0f, height - sampleSize.toFloat()))
            val scale = (loupeRadius * 2f) / sampleSize
            val offX = (cursorX - sx0 - half) * scale
            val offY = (cursorY - sy0 - half) * scale
            clip.reset()
            clip.addCircle(cx, cy, loupeRadius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawColor(Color.WHITE)
            val dst = RectF(
                cx - loupeRadius - offX, cy - loupeRadius - offY,
                cx + loupeRadius - offX, cy + loupeRadius - offY,
            )
            canvas.drawBitmap(bmp, null, dst, bmpPaint)
            canvas.restore()
            val cr = 10f * d
            canvas.drawLine(cx - cr, cy, cx + cr, cy, crossShadow)
            canvas.drawLine(cx, cy - cr, cx, cy + cr, crossShadow)
            canvas.drawLine(cx - cr, cy, cx + cr, cy, cross)
            canvas.drawLine(cx, cy - cr, cx, cy + cr, cross)
            canvas.drawCircle(cx, cy, loupeRadius, ringOuter)
            canvas.drawCircle(cx, cy, loupeRadius, ring)
        }
        if (label.isNotEmpty()) {
            val tw = labelText.measureText(label)
            val ly = cy + loupeRadius + 14 * d
            val rect = RectF(cx - tw / 2 - 10 * d, ly, cx + tw / 2 + 10 * d, ly + 24 * d)
            canvas.drawRoundRect(rect, 12 * d, 12 * d, labelBg)
            canvas.drawText(label, cx - tw / 2, ly + 16.5f * d, labelText)
        }
    }
}
