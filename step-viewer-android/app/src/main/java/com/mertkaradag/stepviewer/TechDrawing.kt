package com.mertkaradag.stepviewer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

/**
 * Otomatik teknik resim: ön, üst, sol görünüş (ISO E, birinci açı yöntemi) ve izometrik;
 * gizli çizgiler kesikli, genel ölçüler, antet. Sayfa birimi mm, başlangıç sol üst.
 */
object TechDrawing {

    const val VIEW_FRONT = 0
    const val VIEW_TOP = 1
    const val VIEW_LEFT = 2
    const val VIEW_ISO = 3

    class Views(
        val segs: FloatArray,
        /** Görünüş başına (görünenBaş, görünenAdet, gizliBaş, gizliAdet), segment cinsinden. */
        val ranges: IntArray,
        /** Gerçek sınır kutusu: min xyz, max xyz (mm). */
        val bbox: DoubleArray,
        val zUp: Boolean,
    )

    /** Arka planda çağrılmalı. */
    fun load(partIds: IntArray?, zUp: Boolean): Views? {
        val r = StepNative.drawingViews(partIds, zUp, true) ?: return null
        return Views(r[0] as FloatArray, r[1] as IntArray, r[2] as DoubleArray, zUp)
    }

    enum class Paper(val w: Float, val h: Float, val label: String) {
        A4(297f, 210f, "A4"),
        A3(420f, 297f, "A3"),
    }

    class Texts(
        val name: String,
        val nameLabel: String,
        val scaleLabel: String,
        val dateLabel: String,
        val date: String,
        val unitLabel: String,
        val projectionLabel: String,
        val sheetLabel: String,
        val app: String,
    )

    class Layout(
        val paper: Paper,
        val scale: Float,
        val scaleText: String,
        /** Görünüş başına: sayfadaki merkez (x, y) ve model sınır kutusu merkezi (x, y). */
        val place: Array<FloatArray>,
        val boxes: Array<RectF>,
        val frame: RectF,
        val title: RectF,
    )

    private val SCALES = floatArrayOf(10f, 5f, 2f, 1f, 0.5f, 0.2f, 0.1f, 0.05f, 0.02f, 0.01f, 0.005f, 0.002f, 0.001f)

    private fun scaleText(s: Float): String =
        if (s >= 1f) "${s.toInt()}:1" else "1:${Math.round(1f / s)}"

    /** Görünüşün 2D sınır kutusu (model mm). */
    private fun viewBox(v: Views, view: Int): RectF {
        val r = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (k in 0..1) {
            val start = v.ranges[view * 4 + k * 2]
            val count = v.ranges[view * 4 + k * 2 + 1]
            for (s in start until start + count) {
                val o = s * 4
                r.left = min(r.left, min(v.segs[o], v.segs[o + 2]))
                r.right = max(r.right, max(v.segs[o], v.segs[o + 2]))
                r.top = min(r.top, min(v.segs[o + 1], v.segs[o + 3]))
                r.bottom = max(r.bottom, max(v.segs[o + 1], v.segs[o + 3]))
            }
        }
        if (r.left > r.right) r.set(0f, 0f, 1f, 1f)
        return r // top = min y, bottom = max y (model yönü)
    }

    fun layout(v: Views, paper: Paper): Layout {
        val frame = RectF(20f, 10f, paper.w - 10f, paper.h - 10f)
        val titleH = 24f
        val title = RectF(frame.left, frame.bottom - titleH, frame.right, frame.bottom)
        val area = RectF(frame.left, frame.top, frame.right, title.top)
        val boxes = Array(4) { viewBox(v, it) }
        val c0 = max(boxes[VIEW_FRONT].width(), boxes[VIEW_TOP].width())
        val c1 = max(boxes[VIEW_LEFT].width(), boxes[VIEW_ISO].width())
        val r0 = max(boxes[VIEW_FRONT].height(), boxes[VIEW_LEFT].height())
        val r1 = max(boxes[VIEW_TOP].height(), boxes[VIEW_ISO].height())
        val gap = 18f // ölçü çizgileri için de yer
        val fit = min(
            (area.width() - 3 * gap) / max(c0 + c1, 1e-3f),
            (area.height() - 3 * gap) / max(r0 + r1, 1e-3f),
        )
        val scale = SCALES.firstOrNull { it <= fit } ?: SCALES.last()
        val totalW = scale * (c0 + c1) + gap
        val totalH = scale * (r0 + r1) + gap
        val x0 = area.left + (area.width() - totalW) / 2f
        val y0 = area.top + (area.height() - totalH) / 2f
        val col0 = x0 + scale * c0 / 2f
        val col1 = x0 + scale * c0 + gap + scale * c1 / 2f
        val row0 = y0 + scale * r0 / 2f
        val row1 = y0 + scale * r0 + gap + scale * r1 / 2f
        val centers = arrayOf(
            floatArrayOf(col0, row0), // ön
            floatArrayOf(col0, row1), // üst: önün altında (birinci açı)
            floatArrayOf(col1, row0), // sol: önün sağında (birinci açı)
            floatArrayOf(col1, row1), // izometrik
        )
        val place = Array(4) { i ->
            floatArrayOf(centers[i][0], centers[i][1], boxes[i].centerX(), boxes[i].centerY())
        }
        return Layout(paper, scale, scaleText(scale), place, boxes, frame, title)
    }

    private fun sx(l: Layout, view: Int, x: Float) = l.place[view][0] + l.scale * (x - l.place[view][2])
    private fun sy(l: Layout, view: Int, y: Float) = l.place[view][1] - l.scale * (y - l.place[view][3])

    /** Sayfa koordinatında çizgi listesi: (x1, y1, x2, y2, tür) tür 0 görünen, 1 gizli. */
    fun sheetLines(v: Views, l: Layout, block: (Float, Float, Float, Float, Int) -> Unit) {
        for (view in 0 until 4) {
            for (k in 0..1) {
                val start = v.ranges[view * 4 + k * 2]
                val count = v.ranges[view * 4 + k * 2 + 1]
                for (s in start until start + count) {
                    val o = s * 4
                    block(
                        sx(l, view, v.segs[o]), sy(l, view, v.segs[o + 1]),
                        sx(l, view, v.segs[o + 2]), sy(l, view, v.segs[o + 3]), k,
                    )
                }
            }
        }
    }

    /** Genel ölçü: sayfadaki iki nokta, ölçü çizgisi kayması ve yazı. */
    class Dim(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val horizontal: Boolean, val offset: Float, val text: String)

    fun dims(v: Views, l: Layout): List<Dim> {
        val b = v.bbox
        val sizeX = b[3] - b[0]
        val sizeY = b[4] - b[1]
        val sizeZ = b[5] - b[2]
        val up = if (v.zUp) sizeZ else sizeY
        val depth = if (v.zUp) sizeY else sizeZ
        val out = ArrayList<Dim>()
        val f = l.boxes[VIEW_FRONT]
        val fl = sx(l, VIEW_FRONT, f.left)
        val fr = sx(l, VIEW_FRONT, f.right)
        val ft = sy(l, VIEW_FRONT, f.bottom) // modelde üst = sayfada küçük y
        val fb = sy(l, VIEW_FRONT, f.top)
        out += Dim(fl, ft, fr, ft, true, -8f, Holes.mm(sizeX))
        out += Dim(fl, ft, fl, fb, false, -8f, Holes.mm(up))
        val s = l.boxes[VIEW_LEFT]
        val sl = sx(l, VIEW_LEFT, s.left)
        val sr = sx(l, VIEW_LEFT, s.right)
        val st = sy(l, VIEW_LEFT, s.bottom)
        out += Dim(sl, st, sr, st, true, -8f, Holes.mm(depth))
        return out
    }

    // ---------------------------------------------------------------- Canvas

    /** [canvas] mm biriminde olmalı (çağıran ölçekler). */
    fun draw(canvas: Canvas, v: Views, l: Layout, t: Texts) {
        canvas.drawColor(Color.WHITE)
        val thick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 0.5f
            strokeCap = Paint.Cap.ROUND
        }
        val thin = Paint(thick).apply { strokeWidth = 0.25f }
        val hidden = Paint(thin).apply {
            color = Color.rgb(60, 60, 60)
            pathEffect = DashPathEffect(floatArrayOf(3f, 1.5f), 0f)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 3.5f
            typeface = Typeface.SANS_SERIF
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.FILL }

        // Çerçeve
        canvas.drawRect(l.frame, thick)

        // Görünüşler: gizli çizgiler tek yol olarak (kesik desen sürekli olsun)
        val hiddenPath = Path()
        sheetLines(v, l) { x1, y1, x2, y2, kind ->
            if (kind == 0) {
                canvas.drawLine(x1, y1, x2, y2, thick)
            } else {
                hiddenPath.moveTo(x1, y1)
                hiddenPath.lineTo(x2, y2)
            }
        }
        canvas.drawPath(hiddenPath, hidden)

        // Ölçüler
        for (d in dims(v, l)) drawDim(canvas, d, thin, fill, text)

        // Antet
        drawTitle(canvas, l, t, thick, thin, text)
    }

    private fun drawDim(c: Canvas, d: Dim, line: Paint, fill: Paint, text: Paint) {
        val arrow = 2.5f
        if (d.horizontal) {
            val y = d.y1 + d.offset
            c.drawLine(d.x1, d.y1 - 1f, d.x1, y - 1.5f, line)
            c.drawLine(d.x2, d.y2 - 1f, d.x2, y - 1.5f, line)
            c.drawLine(d.x1, y, d.x2, y, line)
            arrowHead(c, d.x1, y, 1f, 0f, arrow, fill)
            arrowHead(c, d.x2, y, -1f, 0f, arrow, fill)
            val w = text.measureText(d.text)
            c.drawText(d.text, (d.x1 + d.x2) / 2f - w / 2f, y - 1f, text)
        } else {
            val x = d.x1 + d.offset
            c.drawLine(d.x1 - 1f, d.y1, x - 1.5f, d.y1, line)
            c.drawLine(d.x2 - 1f, d.y2, x - 1.5f, d.y2, line)
            c.drawLine(x, d.y1, x, d.y2, line)
            arrowHead(c, x, d.y1, 0f, 1f, arrow, fill)
            arrowHead(c, x, d.y2, 0f, -1f, arrow, fill)
            val w = text.measureText(d.text)
            c.save()
            c.rotate(-90f, x - 1f, (d.y1 + d.y2) / 2f)
            c.drawText(d.text, x - 1f - w / 2f, (d.y1 + d.y2) / 2f, text)
            c.restore()
        }
    }

    private fun arrowHead(c: Canvas, x: Float, y: Float, dx: Float, dy: Float, len: Float, fill: Paint) {
        val w = len * 0.3f
        val p = Path()
        p.moveTo(x, y)
        p.lineTo(x + dx * len - dy * w, y + dy * len + dx * w)
        p.lineTo(x + dx * len + dy * w, y + dy * len - dx * w)
        p.close()
        c.drawPath(p, fill)
    }

    /** Antet hücreleri: (sol, üst, sağ, alt, etiket, değer). */
    fun titleCells(l: Layout, t: Texts): List<Array<Any>> {
        val r = l.title
        val w = r.width()
        val xs = floatArrayOf(r.left, r.left + w * 0.40f, r.left + w * 0.55f, r.left + w * 0.72f, r.left + w * 0.86f, r.right)
        val mid = r.top + r.height() / 2f
        return listOf(
            arrayOf(xs[0], r.top, xs[1], r.bottom, t.nameLabel, t.name),
            arrayOf(xs[1], r.top, xs[2], mid, t.scaleLabel, l.scaleText),
            arrayOf(xs[1], mid, xs[2], r.bottom, t.sheetLabel, l.paper.label),
            arrayOf(xs[2], r.top, xs[3], mid, t.dateLabel, t.date),
            arrayOf(xs[2], mid, xs[3], r.bottom, t.unitLabel, "mm"),
            arrayOf(xs[3], r.top, xs[4], r.bottom, t.projectionLabel, ""),
            arrayOf(xs[4], r.top, xs[5], r.bottom, "", t.app),
        )
    }

    private fun drawTitle(c: Canvas, l: Layout, t: Texts, thick: Paint, thin: Paint, text: Paint) {
        c.drawRect(l.title, thick)
        val small = Paint(text).apply { textSize = 2.2f; color = Color.rgb(80, 80, 80) }
        val big = Paint(text).apply { textSize = 4.5f; isFakeBoldText = true }
        for ((i, cell) in titleCells(l, t).withIndex()) {
            val x1 = cell[0] as Float
            val y1 = cell[1] as Float
            val x2 = cell[2] as Float
            val y2 = cell[3] as Float
            c.drawRect(x1, y1, x2, y2, thin)
            val label = cell[4] as String
            val value = cell[5] as String
            if (label.isNotEmpty()) c.drawText(label, x1 + 1.5f, y1 + 3f, small)
            if (i == 5) {
                projectionSymbol(c, (x1 + x2) / 2f, (y1 + y2) / 2f + 1.5f, thin)
                continue
            }
            val p = if (i == 0) big else text
            var shown = value
            val maxW = x2 - x1 - 3f
            while (shown.length > 3 && p.measureText(shown) > maxW) shown = shown.dropLast(2) + "…"
            c.drawText(shown, x1 + 1.5f, y2 - 2.5f, p)
        }
    }

    /** ISO E (birinci açı) izdüşüm sembolü: kesik koni yan görünüşü + iki daire. */
    private fun projectionSymbol(c: Canvas, cx: Float, cy: Float, p: Paint) {
        val h = 7f
        // Birinci açı: kesik koni solda, daireler sağda
        val left = cx - 9f
        val p2 = Path()
        p2.moveTo(left, cy - h * 0.25f)
        p2.lineTo(left + 8f, cy - h / 2f)
        p2.lineTo(left + 8f, cy + h / 2f)
        p2.lineTo(left, cy + h * 0.25f)
        p2.close()
        c.drawPath(p2, p)
        c.drawCircle(cx + 6f, cy, h / 2f, p)
        c.drawCircle(cx + 6f, cy, h * 0.25f, p)
    }

    /** Önizleme bitmap'i. */
    fun bitmap(v: Views, l: Layout, t: Texts, pxPerMm: Float): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(
            (l.paper.w * pxPerMm).toInt(), (l.paper.h * pxPerMm).toInt(), android.graphics.Bitmap.Config.ARGB_8888,
        )
        val c = Canvas(bmp)
        c.scale(pxPerMm, pxPerMm)
        draw(c, v, l, t)
        return bmp
    }

    /** Tek sayfalık vektör PDF. */
    fun pdf(v: Views, l: Layout, t: Texts, out: java.io.OutputStream) {
        val pt = 72f / 25.4f
        val doc = android.graphics.pdf.PdfDocument()
        try {
            val info = android.graphics.pdf.PdfDocument.PageInfo.Builder(
                Math.round(l.paper.w * pt), Math.round(l.paper.h * pt), 1,
            ).create()
            val page = doc.startPage(info)
            page.canvas.scale(pt, pt)
            draw(page.canvas, v, l, t)
            doc.finishPage(page)
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    // ---------------------------------------------------------------- DXF

    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            if (ch.code in 32..126) sb.append(ch) else sb.append("\\U+").append(String.format("%04X", ch.code))
        }
        return sb.toString()
    }

    /** Sayfanın DXF (R12) hali: mm, y yukarı. Katmanlar: VISIBLE, HIDDEN, DIM, FRAME, TEXT. */
    fun dxf(v: Views, l: Layout, t: Texts): String {
        val sb = StringBuilder()
        val h = l.paper.h
        fun pair(code: Int, value: String) { sb.append(code).append('\n').append(value).append('\n') }
        fun num(code: Int, value: Float) = pair(code, String.format(java.util.Locale.US, "%.4f", value))
        fun line(layer: String, x1: Float, y1: Float, x2: Float, y2: Float) {
            pair(0, "LINE"); pair(8, layer)
            num(10, x1); num(20, h - y1); num(30, 0f)
            num(11, x2); num(21, h - y2); num(31, 0f)
        }
        fun txt(layer: String, x: Float, y: Float, size: Float, s: String, rot: Float = 0f) {
            if (s.isEmpty()) return
            pair(0, "TEXT"); pair(8, layer)
            num(10, x); num(20, h - y); num(30, 0f); num(40, size)
            pair(1, esc(s))
            if (rot != 0f) num(50, rot)
        }
        fun rect(layer: String, r: RectF) {
            line(layer, r.left, r.top, r.right, r.top)
            line(layer, r.right, r.top, r.right, r.bottom)
            line(layer, r.right, r.bottom, r.left, r.bottom)
            line(layer, r.left, r.bottom, r.left, r.top)
        }
        pair(999, "Cepte CAD - units: mm")
        pair(0, "SECTION"); pair(2, "HEADER")
        pair(9, "\$ACADVER"); pair(1, "AC1009")
        pair(9, "\$EXTMIN"); num(10, 0f); num(20, 0f); num(30, 0f)
        pair(9, "\$EXTMAX"); num(10, l.paper.w); num(20, l.paper.h); num(30, 0f)
        pair(0, "ENDSEC")
        pair(0, "SECTION"); pair(2, "ENTITIES")
        rect("FRAME", l.frame)
        sheetLines(v, l) { x1, y1, x2, y2, kind -> line(if (kind == 0) "VISIBLE" else "HIDDEN", x1, y1, x2, y2) }
        for (d in dims(v, l)) {
            if (d.horizontal) {
                val y = d.y1 + d.offset
                line("DIM", d.x1, d.y1 - 1f, d.x1, y - 1.5f)
                line("DIM", d.x2, d.y2 - 1f, d.x2, y - 1.5f)
                line("DIM", d.x1, y, d.x2, y)
                txt("DIM", (d.x1 + d.x2) / 2f - d.text.length * 1.2f, y - 1f, 3.5f, d.text)
            } else {
                val x = d.x1 + d.offset
                line("DIM", d.x1 - 1f, d.y1, x - 1.5f, d.y1)
                line("DIM", d.x2 - 1f, d.y2, x - 1.5f, d.y2)
                line("DIM", x, d.y1, x, d.y2)
                txt("DIM", x - 1f, (d.y1 + d.y2) / 2f + d.text.length * 1.2f, 3.5f, d.text, 90f)
            }
        }
        rect("FRAME", l.title)
        for ((i, cell) in titleCells(l, t).withIndex()) {
            val x1 = cell[0] as Float
            val y1 = cell[1] as Float
            val x2 = cell[2] as Float
            val y2 = cell[3] as Float
            rect("FRAME", RectF(x1, y1, x2, y2))
            txt("TEXT", x1 + 1.5f, y1 + 3f, 2.2f, cell[4] as String)
            if (i != 5) txt("TEXT", x1 + 1.5f, y2 - 2.5f, if (i == 0) 4.5f else 3.5f, cell[5] as String)
        }
        pair(0, "ENDSEC")
        pair(0, "EOF")
        return sb.toString()
    }
}
