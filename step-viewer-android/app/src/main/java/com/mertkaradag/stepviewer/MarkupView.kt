package com.mertkaradag.stepviewer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Ekran görüntüsü üstüne işaretleme: serbest kalem, ok, dikdörtgen ve yazı.
 * Tüm koordinatlar görüntü (bitmap) pikselinde saklanır; [render] tam çözünürlükte çizer.
 */
@SuppressLint("ViewConstructor")
class MarkupView(context: Context, private val image: Bitmap) : View(context) {

    enum class Tool { PEN, ARROW, RECT, TEXT }

    private sealed class Mark(val color: Int) {
        class Stroke(color: Int, val points: MutableList<Float>) : Mark(color)
        class Arrow(color: Int, var x0: Float, var y0: Float, var x1: Float, var y1: Float) : Mark(color)
        class Box(color: Int, var x0: Float, var y0: Float, var x1: Float, var y1: Float) : Mark(color)
        class Label(color: Int, val x: Float, val y: Float, val text: String) : Mark(color)
    }

    var tool = Tool.PEN
    var color = Color.rgb(255, 82, 82)

    /** Yazı aracında dokunulan nokta (görüntü pikseli); metin sorulup [addText] çağrılır. */
    var onTextRequested: ((x: Float, y: Float) -> Unit)? = null

    /** Geri al düğmesinin durumu değişince. */
    var onChanged: (() -> Unit)? = null

    private val marks = ArrayList<Mark>()
    private var current: Mark? = null

    private val toView = Matrix()
    private val toImage = Matrix()
    private var scale = 1f

    // Görüntü boyutuna göre çizgi kalınlığı (telefon ekranında ~4 dp)
    private val baseStroke = max(image.width, image.height) / 280f
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 20, 23, 28) }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    val canUndo: Boolean get() = marks.isNotEmpty()

    fun undo() {
        if (marks.isNotEmpty()) marks.removeAt(marks.size - 1)
        invalidate()
        onChanged?.invoke()
    }

    fun addText(x: Float, y: Float, text: String) {
        if (text.isBlank()) return
        marks += Mark.Label(color, x, y, text.trim())
        invalidate()
        onChanged?.invoke()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        scale = min(w.toFloat() / image.width, h.toFloat() / image.height)
        val dx = (w - image.width * scale) / 2f
        val dy = (h - image.height * scale) / 2f
        toView.reset()
        toView.postScale(scale, scale)
        toView.postTranslate(dx, dy)
        toView.invert(toImage)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.concat(toView)
        canvas.drawBitmap(image, 0f, 0f, bitmapPaint)
        for (m in marks) drawMark(canvas, m)
        current?.let { drawMark(canvas, it) }
        canvas.restore()
    }

    /** İşaretlenmiş görüntüyü tam çözünürlükte üretir. */
    fun render(): Bitmap {
        val out = image.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        for (m in marks) drawMark(c, m)
        return out
    }

    private fun drawMark(c: Canvas, m: Mark) {
        strokePaint.color = m.color
        strokePaint.strokeWidth = baseStroke
        when (m) {
            is Mark.Stroke -> {
                val p = m.points
                if (p.size < 2) return
                if (p.size == 2) {
                    fillPaint.color = m.color
                    c.drawCircle(p[0], p[1], baseStroke / 2f, fillPaint)
                    return
                }
                val path = Path()
                path.moveTo(p[0], p[1])
                var i = 2
                while (i + 1 < p.size) {
                    // Yumuşak çizgi: ara noktalardan geçen ikinci derece eğri
                    val mx = (p[i - 2] + p[i]) / 2f
                    val my = (p[i - 1] + p[i + 1]) / 2f
                    path.quadTo(p[i - 2], p[i - 1], mx, my)
                    i += 2
                }
                path.lineTo(p[p.size - 2], p[p.size - 1])
                c.drawPath(path, strokePaint)
            }
            is Mark.Arrow -> {
                c.drawLine(m.x0, m.y0, m.x1, m.y1, strokePaint)
                val len = hypot(m.x1 - m.x0, m.y1 - m.y0)
                if (len < 1f) return
                val head = min(baseStroke * 5f, len * 0.5f)
                val a = atan2(m.y1 - m.y0, m.x1 - m.x0)
                val path = Path()
                path.moveTo(m.x1, m.y1)
                path.lineTo(m.x1 - head * cos(a - 0.45f), m.y1 - head * sin(a - 0.45f))
                path.lineTo(m.x1 - head * cos(a + 0.45f), m.y1 - head * sin(a + 0.45f))
                path.close()
                fillPaint.color = m.color
                c.drawPath(path, fillPaint)
            }
            is Mark.Box -> {
                c.drawRoundRect(
                    RectF(min(m.x0, m.x1), min(m.y0, m.y1), max(m.x0, m.x1), max(m.y0, m.y1)),
                    baseStroke, baseStroke, strokePaint,
                )
            }
            is Mark.Label -> {
                textPaint.textSize = baseStroke * 5.5f
                textPaint.color = m.color
                val pad = baseStroke * 2f
                val w = textPaint.measureText(m.text)
                val fm = textPaint.fontMetrics
                val top = m.y + fm.ascent - pad
                val bottom = m.y + fm.descent + pad
                c.drawRoundRect(RectF(m.x - pad, top, m.x + w + pad, bottom), pad, pad, labelBg)
                c.drawText(m.text, m.x, m.y, textPaint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pt = floatArrayOf(event.x, event.y)
        toImage.mapPoints(pt)
        val x = pt[0].coerceIn(0f, image.width.toFloat())
        val y = pt[1].coerceIn(0f, image.height.toFloat())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                current = when (tool) {
                    Tool.PEN -> Mark.Stroke(color, mutableListOf(x, y))
                    Tool.ARROW -> Mark.Arrow(color, x, y, x, y)
                    Tool.RECT -> Mark.Box(color, x, y, x, y)
                    Tool.TEXT -> null
                }
                if (tool == Tool.TEXT) onTextRequested?.invoke(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                when (val m = current) {
                    is Mark.Stroke -> {
                        val n = m.points.size
                        // Çok yakın noktaları atla
                        if (hypot(x - m.points[n - 2], y - m.points[n - 1]) > baseStroke * 0.5f) {
                            m.points += x
                            m.points += y
                        }
                    }
                    is Mark.Arrow -> { m.x1 = x; m.y1 = y }
                    is Mark.Box -> { m.x1 = x; m.y1 = y }
                    else -> Unit
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                current?.let {
                    marks += it
                    onChanged?.invoke()
                }
                current = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                current = null
                invalidate()
            }
        }
        return true
    }
}
