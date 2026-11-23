package com.mertkaradag.stepviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Kalite kontrol raporu: model üzerinde ölçülen özellikler, nominal + tolerans, gerçek ölçülen
 * değer ve uygun / uygun değil sonucu. Rapor dosya adına göre saklanır; PDF olarak çıkar.
 */
class Inspection(private val context: Context, val fileKey: String) {

    enum class Dim { MM, DEG }

    class Item(
        var label: String,
        var nominal: Double,
        var upper: Double,          // üst sapma (mm veya °)
        var lower: Double,          // alt sapma (negatif olabilir)
        var fit: String = "",       // ISO 286 kodu (H7, g6...), yoksa boş
        var actual: Double? = null, // ölçülen değer
        var unit: Dim = Dim.MM,
        var anchor: FloatArray? = null,  // modelde balon konumu (x, y, z)
    ) {
        val min: Double get() = nominal + lower
        val max: Double get() = nominal + upper

        /** null = ölçülmedi. Küçük kayan nokta payı ile. */
        val ok: Boolean?
            get() {
                val a = actual ?: return null
                return a >= min - 1e-9 && a <= max + 1e-9
            }
    }

    val items = ArrayList<Item>()
    var inspector: String = ""
    var order: String = ""
    var generalClass: String = "m"

    private val prefs = context.getSharedPreferences("inspection", Context.MODE_PRIVATE)

    init {
        inspector = prefs.getString("inspector", "") ?: ""
        generalClass = prefs.getString("general", "m") ?: "m"
        load()
    }

    private fun load() {
        val raw = prefs.getString("r:$fileKey", null) ?: return
        runCatching {
            val o = JSONObject(raw)
            order = o.optString("order")
            val arr = o.optJSONArray("items") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                val anchor = j.optJSONArray("anchor")?.let { a ->
                    FloatArray(3) { k -> a.optDouble(k).toFloat() }
                }
                items += Item(
                    label = j.optString("label"),
                    nominal = j.optDouble("nominal"),
                    upper = j.optDouble("upper"),
                    lower = j.optDouble("lower"),
                    fit = j.optString("fit"),
                    actual = if (j.has("actual") && !j.isNull("actual")) j.optDouble("actual") else null,
                    unit = if (j.optString("unit") == "deg") Dim.DEG else Dim.MM,
                    anchor = anchor,
                )
            }
        }
    }

    fun save() {
        val arr = JSONArray()
        for (it in items) {
            val j = JSONObject()
            j.put("label", it.label)
            j.put("nominal", it.nominal)
            j.put("upper", it.upper)
            j.put("lower", it.lower)
            j.put("fit", it.fit)
            it.actual?.let { a -> j.put("actual", a) }
            j.put("unit", if (it.unit == Dim.DEG) "deg" else "mm")
            it.anchor?.let { a -> j.put("anchor", JSONArray(a.map { v -> v.toDouble() })) }
            arr.put(j)
        }
        val o = JSONObject()
        o.put("order", order)
        o.put("items", arr)
        prefs.edit()
            .putString("r:$fileKey", o.toString())
            .putString("inspector", inspector)
            .putString("general", generalClass)
            .apply()
    }

    /** Varsayılan (genel) tolerans ile yeni madde. */
    fun newItem(label: String, nominal: Double, unit: Dim, anchor: FloatArray?): Item {
        val t = if (unit == Dim.DEG) Iso286.generalAngle(generalClass) else Iso286.generalLinear(generalClass, nominal)
        return Item(label, nominal, t, -t, "", null, unit, anchor)
    }

    fun counts(): Triple<Int, Int, Int> {
        var ok = 0
        var nok = 0
        var open = 0
        for (i in items) when (i.ok) {
            true -> ok++
            false -> nok++
            null -> open++
        }
        return Triple(ok, nok, open)
    }

    // ---------------------------------------------------------------------
    // Metin biçimleri
    // ---------------------------------------------------------------------

    fun value(v: Double, unit: Dim): String =
        String.format(Locale.getDefault(), if (unit == Dim.DEG) "%.2f°" else "%.3f", v)

    fun nominalText(i: Item): String {
        val n = if (i.unit == Dim.DEG) value(i.nominal, i.unit) else Holes.mm(i.nominal)
        return if (i.fit.isNotEmpty()) "$n ${i.fit}" else n
    }

    fun toleranceText(i: Item): String {
        val f = 1000.0  // dev() binde birim bekler (µm veya 0,001°)
        val unit = if (i.unit == Dim.DEG) "°" else ""
        return if (abs(i.upper + i.lower) < 1e-9) {
            "±" + FitDialog.dev(i.upper * f).drop(1) + unit
        } else {
            FitDialog.dev(i.upper * f) + unit + " / " + FitDialog.dev(i.lower * f) + unit
        }
    }

    // ---------------------------------------------------------------------
    // PDF
    // ---------------------------------------------------------------------

    class PdfTexts(
        val title: String,
        val fileLabel: String,
        val fileName: String,
        val dateLabel: String,
        val date: String,
        val inspectorLabel: String,
        val orderLabel: String,
        val generalLabel: String,
        val headers: List<String>,  // No, Özellik, Nominal, Tolerans, Min, Max, Ölçülen, Sonuç
        val okText: String,
        val nokText: String,
        val summary: String,
        val signature: String,
        val app: String,
    )

    /**
     * A4 dikey rapor. image: balonlu model görüntüsü (null olabilir).
     */
    fun writePdf(out: OutputStream, image: Bitmap?, t: PdfTexts) {
        val doc = PdfDocument()
        val pw = 595
        val ph = 842
        val margin = 36f
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 9.5f }
        val bold = Paint(text).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val titleP = Paint(bold).apply { textSize = 18f }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 120, 120); strokeWidth = 0.6f; style = Paint.Style.STROKE }
        val fillHead = Paint().apply { color = Color.rgb(235, 235, 235) }
        val okP = Paint(bold).apply { color = Color.rgb(27, 125, 52) }
        val nokP = Paint(bold).apply { color = Color.rgb(196, 30, 30) }
        val accent = Paint().apply { color = Color.rgb(242, 140, 40) }

        // Sütun genişlikleri (toplam 523)
        val widths = floatArrayOf(26f, 135f, 66f, 66f, 54f, 54f, 66f, 56f)
        val rowH = 18f
        var pageNo = 0
        var page: PdfDocument.Page? = null
        var c: Canvas? = null
        var y = 0f

        fun header() {
            pageNo++
            val info = PdfDocument.PageInfo.Builder(pw, ph, pageNo).create()
            page = doc.startPage(info)
            c = page!!.canvas
            val cv = c!!
            cv.drawRect(margin, margin, pw - margin, margin + 4f, accent)
            cv.drawText(t.title, margin, margin + 26f, titleP)
            val right = pw - margin
            val appW = text.measureText(t.app)
            cv.drawText(t.app, right - appW, margin + 26f, text)
            y = margin + 44f
            val meta = listOf(
                t.fileLabel to t.fileName,
                t.dateLabel to t.date,
                t.inspectorLabel to inspector,
                t.orderLabel to order,
            )
            for ((i, kv) in meta.withIndex()) {
                val x = margin + (i % 2) * 262f
                val yy = y + (i / 2) * 14f
                cv.drawText(kv.first + ":", x, yy, bold)
                cv.drawText(ellipsize(kv.second, text, 250f - bold.measureText(kv.first + ": ")), x + bold.measureText(kv.first + ": "), yy, text)
            }
            y += 30f
        }

        fun tableHeader() {
            val cv = c!!
            cv.drawRect(margin, y, pw - margin, y + rowH, fillHead)
            var x = margin
            for ((i, h) in t.headers.withIndex()) {
                cv.drawText(ellipsize(h, bold, widths[i] - 6f), x + 3f, y + 12.5f, bold)
                x += widths[i]
            }
            cv.drawRect(margin, y, pw - margin, y + rowH, line)
            y += rowH
        }

        header()
        if (image != null) {
            val maxW = pw - 2 * margin
            val maxH = 300f
            val scale = min(maxW / image.width, maxH / image.height)
            val w = image.width * scale
            val h = image.height * scale
            val left = margin + (maxW - w) / 2
            c!!.drawBitmap(image, null, RectF(left, y, left + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
            c!!.drawRect(margin, y, pw - margin, y + h, line)
            y += h + 14f
        }
        tableHeader()
        for ((idx, it) in items.withIndex()) {
            if (y + rowH > ph - margin - 60f) {
                doc.finishPage(page)
                header()
                tableHeader()
            }
            val cv = c!!
            val cells = listOf(
                (idx + 1).toString(),
                it.label,
                nominalText(it),
                toleranceText(it),
                value(it.min, it.unit),
                value(it.max, it.unit),
                it.actual?.let { a -> value(a, it.unit) } ?: "",
                when (it.ok) {
                    true -> t.okText
                    false -> t.nokText
                    null -> ""
                },
            )
            var x = margin
            for ((i, s) in cells.withIndex()) {
                val p = when {
                    i == 7 && it.ok == true -> okP
                    i == 7 && it.ok == false -> nokP
                    i == 0 -> bold
                    else -> text
                }
                cv.drawText(ellipsize(s, p, widths[i] - 6f), x + 3f, y + 12.5f, p)
                x += widths[i]
            }
            cv.drawLine(margin, y + rowH, pw - margin, y + rowH, line)
            y += rowH
        }
        // Dikey çizgiler son sayfada tabloyu kapatmaz; yalnızca alt bilgi
        if (y + 70f > ph - margin) {
            doc.finishPage(page)
            header()
        }
        val cv = c!!
        y += 16f
        cv.drawText(t.summary, margin, y, bold)
        y += 14f
        cv.drawText(t.generalLabel, margin, y, text)
        y += 34f
        cv.drawLine(pw - margin - 180f, y, pw - margin, y, line)
        cv.drawText(t.signature, pw - margin - 180f, y + 12f, text)
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }

    private fun ellipsize(s: String, p: Paint, w: Float): String {
        if (p.measureText(s) <= w) return s
        var e = s
        while (e.isNotEmpty() && p.measureText("$e…") > w) e = e.dropLast(1)
        return "$e…"
    }

    companion object {
        /** Görüntüye numaralı balonları çizer. mvp: modelin o anki görünüm matrisi. */
        fun drawBalloons(src: Bitmap, items: List<Item>, mvp: FloatArray, density: Float): Bitmap {
            val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
            val cv = Canvas(bmp)
            val r = 11f * density
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(242, 140, 40) }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * density
            }
            val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 11f * density
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            val placed = ArrayList<FloatArray>()
            for ((idx, it) in items.withIndex()) {
                val a = it.anchor ?: continue
                val x = a[0]
                val y = a[1]
                val z = a[2]
                val cx = mvp[0] * x + mvp[4] * y + mvp[8] * z + mvp[12]
                val cy = mvp[1] * x + mvp[5] * y + mvp[9] * z + mvp[13]
                val cw = mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15]
                if (abs(cw) < 1e-9) continue
                var sx = (cx / cw * 0.5f + 0.5f) * bmp.width
                var sy = (1f - (cy / cw * 0.5f + 0.5f)) * bmp.height
                if (sx < -r || sy < -r || sx > bmp.width + r || sy > bmp.height + r) continue
                sx = max(r, min(bmp.width - r, sx))
                sy = max(r, min(bmp.height - r, sy))
                // Üst üste binen balonları kaydır
                var guard = 0
                while (guard++ < 12 && placed.any { p -> (p[0] - sx) * (p[0] - sx) + (p[1] - sy) * (p[1] - sy) < 4 * r * r }) {
                    sy = if (sy + 2 * r < bmp.height - r) sy + 2 * r else sy - 2 * r
                }
                placed += floatArrayOf(sx, sy)
                cv.drawCircle(sx, sy, r, fill)
                cv.drawCircle(sx, sy, r, stroke)
                cv.drawText((idx + 1).toString(), sx, sy + txt.textSize * 0.36f, txt)
            }
            return bmp
        }
    }
}
