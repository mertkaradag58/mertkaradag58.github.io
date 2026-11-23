package com.mertkaradag.stepviewer.photocad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Fotoğraf + kontur çizimi. Tek parmak: nokta/delik sürükle veya kaydır, iki parmak: yakınlaştır.
 * Dokun: kenar/deliğe ölçü ver ([listener]); uzun bas: noktayı sil veya kenara nokta ekle.
 */
class SketchView(context: Context) : View(context) {

    enum class Mode { SEED, EDIT, DRAW }

    interface Listener {
        fun onSeed(x: Float, y: Float)
        fun onSegmentTap(loop: Sketch.Loop, index: Int)
        fun onCircleTap(loop: Sketch.Loop)
        /** Düzenleme başlamadan önce (geri al için). */
        fun onBeforeEdit()
        fun onEdited()
    }

    var listener: Listener? = null
    var sketch: Sketch? = null
        set(v) { field = v; invalidate() }
    var bitmap: Bitmap? = null
        set(v) { field = v; fitted = false; invalidate() }
    var mode = Mode.EDIT
        set(v) { field = v; drawLoop = null; invalidate() }
    /** Döndürmede eksen: -1 yok, 0 yatay, 1 dikey. */
    var axis = -1
        set(v) { field = v; invalidate() }
    var showLabels = true

    /** Elle çizimde oluşturulan döngü. */
    private var drawLoop: Sketch.Loop? = null

    private val density = resources.displayMetrics.density
    private val m = Matrix()
    private val inv = Matrix()
    private var fitted = false

    private val accent = Color.rgb(0xFF, 0x7A, 0x1A)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = accent
    }
    private val holePaint = Paint(linePaint).apply { color = Color.rgb(0x2E, 0xA8, 0xFF) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 0xFF, 0x7A, 0x1A) }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = accent
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = Color.rgb(0x22, 0xC5, 0x5E)
        pathEffect = DashPathEffect(floatArrayOf(10 * density, 6 * density), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12 * density; textAlign = Paint.Align.CENTER
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(190, 20, 24, 32) }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val emptyPaint = Paint().apply { color = Color.rgb(0xF4, 0xF5, 0xF7) }
    private val gridPaint = Paint().apply { color = Color.rgb(0xDD, 0xE1, 0xE6); strokeWidth = 1f }

    // -- Dönüşümler ------------------------------------------------------------------

    private fun contentSize(): Pair<Int, Int> {
        bitmap?.let { return it.width to it.height }
        val s = sketch
        if (s != null && s.imageW > 0) return s.imageW to s.imageH
        return 1000 to 1000
    }

    fun fit() {
        if (width == 0 || height == 0) return
        val (cw, ch) = contentSize()
        val pad = 16 * density
        val sc = min((width - 2 * pad) / cw, (height - 2 * pad) / ch)
        m.reset()
        m.postScale(sc, sc)
        m.postTranslate((width - cw * sc) / 2f, (height - ch * sc) / 2f)
        fitted = true
        invalidate()
    }

    private fun toScreen(x: Float, y: Float): FloatArray = floatArrayOf(x, y).also { m.mapPoints(it) }
    private fun toImage(x: Float, y: Float): FloatArray {
        m.invert(inv)
        return floatArrayOf(x, y).also { inv.mapPoints(it) }
    }
    private fun scale(): Float {
        val v = floatArrayOf(0f, 0f, 1f, 0f); m.mapPoints(v)
        return hypot(v[2] - v[0], v[3] - v[1])
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fit()
    }

    // -- Çizim ------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (!fitted) fit()
        canvas.drawColor(Color.rgb(0x1B, 0x1F, 0x27))
        val bmp = bitmap
        canvas.save()
        canvas.concat(m)
        if (bmp != null) {
            canvas.drawBitmap(bmp, 0f, 0f, bitmapPaint)
        } else {
            val (cw, ch) = contentSize()
            canvas.drawRect(0f, 0f, cw.toFloat(), ch.toFloat(), emptyPaint)
            var g = 0f
            while (g <= cw) { canvas.drawLine(g, 0f, g, ch.toFloat(), gridPaint); g += 50f }
            g = 0f
            while (g <= ch) { canvas.drawLine(0f, g, cw.toFloat(), g, gridPaint); g += 50f }
        }
        canvas.restore()

        val s = sketch ?: return
        val k = scale()
        // Dolgu (çift-tek kuralıyla delikler boş)
        val fill = Path().apply { fillType = Path.FillType.EVEN_ODD }
        for (l in s.loops) addLoopPath(fill, l, s)
        canvas.drawPath(fill, fillPaint)

        s.loops.forEachIndexed { li, l ->
            val p = Path(); addLoopPath(p, l, s)
            canvas.drawPath(p, if (li == 0) linePaint else holePaint)
        }

        if (axis >= 0) s.outerBounds()?.let { b ->
            val a = toScreen(b[0], b[1]); val c = toScreen(b[2], b[3])
            if (axis == 0) {
                val y = (a[1] + c[1]) / 2
                canvas.drawLine(a[0] - 20 * density, y, c[0] + 20 * density, y, axisPaint)
            } else {
                val x = (a[0] + c[0]) / 2
                canvas.drawLine(x, a[1] - 20 * density, x, c[1] + 20 * density, axisPaint)
            }
        }

        if (mode == Mode.EDIT) {
            val hr = 5 * density
            for (l in s.loops) {
                if (l.circle) {
                    val c = toScreen(l.cx, l.cy)
                    canvas.drawCircle(c[0], c[1], hr, handlePaint)
                    canvas.drawCircle(c[0], c[1], hr, handleStroke)
                    continue
                }
                for (i in 0 until l.size) {
                    val c = toScreen(l.xs[i], l.ys[i])
                    canvas.drawCircle(c[0], c[1], hr, handlePaint)
                    canvas.drawCircle(c[0], c[1], hr, handleStroke)
                }
            }
            if (showLabels && s.scaled) drawLabels(canvas, s, k)
        }

        drawLoop?.let { l ->
            if (l.size == 0) return@let
            val p = Path()
            for (i in 0 until l.size) {
                val c = toScreen(l.xs[i], l.ys[i])
                if (i == 0) p.moveTo(c[0], c[1]) else p.lineTo(c[0], c[1])
            }
            canvas.drawPath(p, linePaint)
            for (i in 0 until l.size) {
                val c = toScreen(l.xs[i], l.ys[i])
                canvas.drawCircle(c[0], c[1], if (i == 0) 9 * density else 5 * density, handlePaint)
                canvas.drawCircle(c[0], c[1], if (i == 0) 9 * density else 5 * density, handleStroke)
            }
        }
    }

    private fun addLoopPath(p: Path, l: Sketch.Loop, s: Sketch) {
        if (l.circle) {
            val c = toScreen(l.cx, l.cy)
            val k = scale()
            val rx = l.r * k
            val ry = (l.r * s.sx / s.sy).toFloat() * k
            p.addOval(RectF(c[0] - rx, c[1] - ry, c[0] + rx, c[1] + ry), Path.Direction.CW)
            return
        }
        for (i in 0 until l.size) {
            val c = toScreen(l.xs[i], l.ys[i])
            if (i == 0) p.moveTo(c[0], c[1]) else p.lineTo(c[0], c[1])
        }
        p.close()
    }

    private fun drawLabels(canvas: Canvas, s: Sketch, k: Float) {
        val minLen = 56 * density
        for (l in s.loops) {
            if (l.circle) {
                val c = toScreen(l.cx, l.cy)
                label(canvas, "Ø" + fmt(s.diameterMm(l)), c[0], c[1] - 14 * density)
                continue
            }
            for (i in 0 until l.size) {
                val j = (i + 1) % l.size
                val a = toScreen(l.xs[i], l.ys[i]); val b = toScreen(l.xs[j], l.ys[j])
                if (hypot(b[0] - a[0], b[1] - a[1]) < minLen) continue
                label(canvas, fmt(s.segmentLengthMm(l, i)), (a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
            }
        }
    }

    private fun label(canvas: Canvas, t: String, x: Float, y: Float) {
        val w = textPaint.measureText(t) / 2 + 5 * density
        val h = textPaint.textSize * 0.75f
        canvas.drawRoundRect(x - w, y - h - 3 * density, x + w, y + h - 1 * density, 4 * density, 4 * density, labelBg)
        canvas.drawText(t, x, y + h / 2, textPaint)
    }

    private fun fmt(v: Double) = String.format(Locale.getDefault(), if (v >= 100) "%.0f" else "%.1f", v)

    // -- Dokunma ----------------------------------------------------------------------

    private var dragLoop: Sketch.Loop? = null
    private var dragIndex = -1 // -1: daire merkezi
    private var panning = false
    private var lastX = 0f
    private var lastY = 0f
    private var multi = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val f = d.scaleFactor
            val cur = scale()
            if ((cur > 40f && f > 1f) || (cur < 0.02f && f < 1f)) return true
            m.postScale(f, f, d.focusX, d.focusY)
            invalidate()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap(e.x, e.y); return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (!multi && !dragStarted) {
                dragLoop = null
                panning = false
                onLong(e.x, e.y)
            }
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            fit(); return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                multi = false
                lastX = e.x; lastY = e.y
                dragLoop = null; dragIndex = -1; panning = false
                if (mode == Mode.EDIT) hitHandle(e.x, e.y)?.let { (l, i) ->
                    dragLoop = l; dragIndex = i
                }
                if (dragLoop == null) panning = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multi = true
                if (dragLoop != null) { dragLoop = null; listener?.onEdited() }
                panning = true
                lastX = midX(e); lastY = midY(e)
            }
            MotionEvent.ACTION_MOVE -> {
                val l = dragLoop
                if (l != null) {
                    val dx = e.x - lastX; val dy = e.y - lastY
                    if (!dragStarted && hypot(dx, dy) < 6 * density) return true
                    if (!dragStarted) { dragStarted = true; listener?.onBeforeEdit() }
                    val p = toImage(e.x, e.y)
                    if (dragIndex < 0) { l.cx = p[0]; l.cy = p[1] } else { l.xs[dragIndex] = p[0]; l.ys[dragIndex] = p[1] }
                    invalidate()
                } else if (panning) {
                    val x = if (e.pointerCount > 1) midX(e) else e.x
                    val y = if (e.pointerCount > 1) midY(e) else e.y
                    m.postTranslate(x - lastX, y - lastY)
                    lastX = x; lastY = y
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Kalan parmakla kaydırmaya devam et
                val idx = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(idx); lastY = e.getY(idx)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragLoop != null && dragStarted) listener?.onEdited()
                dragLoop = null; dragStarted = false; panning = false
            }
        }
        return true
    }

    private var dragStarted = false

    private fun midX(e: MotionEvent) = (e.getX(0) + e.getX(1)) / 2
    private fun midY(e: MotionEvent) = (e.getY(0) + e.getY(1)) / 2

    private fun hitHandle(x: Float, y: Float): Pair<Sketch.Loop, Int>? {
        val s = sketch ?: return null
        var best: Pair<Sketch.Loop, Int>? = null
        var bestD = 22 * density
        for (l in s.loops) {
            if (l.circle) {
                val c = toScreen(l.cx, l.cy)
                val d = hypot(c[0] - x, c[1] - y)
                if (d < bestD) { bestD = d; best = l to -1 }
                continue
            }
            for (i in 0 until l.size) {
                val c = toScreen(l.xs[i], l.ys[i])
                val d = hypot(c[0] - x, c[1] - y)
                if (d < bestD) { bestD = d; best = l to i }
            }
        }
        return best
    }

    /** En yakın kenar: (döngü, kenar indeksi, resimdeki izdüşüm noktası). */
    private fun hitSegment(x: Float, y: Float): Triple<Sketch.Loop, Int, FloatArray>? {
        val s = sketch ?: return null
        var best: Triple<Sketch.Loop, Int, FloatArray>? = null
        var bestD = 20 * density
        for (l in s.loops) {
            if (l.circle) continue
            for (i in 0 until l.size) {
                val j = (i + 1) % l.size
                val a = toScreen(l.xs[i], l.ys[i]); val b = toScreen(l.xs[j], l.ys[j])
                val vx = b[0] - a[0]; val vy = b[1] - a[1]
                val len2 = vx * vx + vy * vy
                if (len2 < 1f) continue
                val t = (((x - a[0]) * vx + (y - a[1]) * vy) / len2).coerceIn(0f, 1f)
                val px = a[0] + vx * t; val py = a[1] + vy * t
                val d = hypot(px - x, py - y)
                if (d < bestD) { bestD = d; best = Triple(l, i, toImage(px, py)) }
            }
        }
        return best
    }

    private fun hitCircle(x: Float, y: Float): Sketch.Loop? {
        val s = sketch ?: return null
        val p = toImage(x, y)
        val tol = 16 * density / scale()
        return s.loops.filter { it.circle }.minByOrNull { hypot(it.cx - p[0], it.cy - p[1]) }
            ?.takeIf { hypot(it.cx - p[0], it.cy - p[1]) < it.r + tol }
    }

    private fun onTap(x: Float, y: Float) {
        when (mode) {
            Mode.SEED -> {
                val p = toImage(x, y)
                listener?.onSeed(p[0], p[1])
            }
            Mode.EDIT -> {
                hitSegment(x, y)?.let { (l, i, _) -> listener?.onSegmentTap(l, i); return }
                hitCircle(x, y)?.let { listener?.onCircleTap(it) }
            }
            Mode.DRAW -> {
                val l = drawLoop ?: Sketch.Loop().also { drawLoop = it }
                if (l.size >= 3) {
                    val first = toScreen(l.xs[0], l.ys[0])
                    if (hypot(first[0] - x, first[1] - y) < 24 * density) { finishDrawing(); return }
                }
                val p = toImage(x, y)
                l.xs.add(p[0]); l.ys.add(p[1])
                invalidate()
            }
        }
    }

    private fun onLong(x: Float, y: Float) {
        if (mode != Mode.EDIT) return
        val s = sketch ?: return
        hitHandle(x, y)?.let { (l, i) ->
            listener?.onBeforeEdit()
            if (i < 0 || l.size <= 3) {
                // Delik silinir (dış kontur silinmez)
                if (s.loops.indexOf(l) > 0) s.loops.remove(l)
            } else {
                l.xs.removeAt(i); l.ys.removeAt(i)
            }
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate(); listener?.onEdited()
            return
        }
        hitSegment(x, y)?.let { (l, i, p) ->
            listener?.onBeforeEdit()
            l.xs.add(i + 1, p[0]); l.ys.add(i + 1, p[1])
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate(); listener?.onEdited()
        }
    }

    /** Elle çizimi bitirir: ilk çizimse dış kontur, değilse delik olarak eklenir. */
    fun finishDrawing(): Boolean {
        val l = drawLoop ?: return false
        val s = sketch ?: return false
        if (l.size < 3) return false
        listener?.onBeforeEdit()
        s.loops.add(l)
        drawLoop = null
        mode = Mode.EDIT
        listener?.onEdited()
        return true
    }

    fun undoDrawPoint() {
        drawLoop?.let { if (it.size > 0) { it.xs.removeAt(it.size - 1); it.ys.removeAt(it.size - 1); invalidate() } }
    }

    val drawingPoints: Int get() = drawLoop?.size ?: 0

    /** Ekran merkezinin resim koordinatı (yeni delik eklemek için). */
    fun centerInImage(): FloatArray = toImage(width / 2f, height / 2f)

    /** Ekranda 24 dp'ye karşılık gelen resim pikseli. */
    fun imagePx(dp: Float): Float = dp * density / max(scale(), 1e-3f)
}
