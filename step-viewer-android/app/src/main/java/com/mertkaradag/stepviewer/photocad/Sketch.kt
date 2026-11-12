package com.mertkaradag.stepviewer.photocad

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Fotoğraf üzerindeki kontur çizimi. Noktalar resim pikselinde (y aşağı) tutulur;
 * mm karşılığı [sx], [sy] (mm/piksel) ile bulunur. İlk döngü dış kontur, diğerleri delik.
 */
class Sketch {

    /** Çokgen veya daire döngüsü. */
    class Loop(
        var xs: MutableList<Float> = ArrayList(),
        var ys: MutableList<Float> = ArrayList(),
        var circle: Boolean = false,
        var cx: Float = 0f,
        var cy: Float = 0f,
        /** Daire yarıçapı: x yönünde piksel. */
        var r: Float = 0f,
    ) {
        val size: Int get() = xs.size
        fun copy() = Loop(ArrayList(xs), ArrayList(ys), circle, cx, cy, r)
    }

    val loops = ArrayList<Loop>()
    var sx = 1.0
    var sy = 1.0
    /** Kullanıcı ölçeği girdi mi? */
    var scaled = false
    /** Fotoğraf dosyası (proje klasöründe), yoksa boş çizim. */
    var image: String? = null
    var imageW = 0
    var imageH = 0

    val outer: Loop? get() = loops.firstOrNull()
    val isEmpty: Boolean get() = loops.isEmpty() || (!loops[0].circle && loops[0].size < 3)

    fun snapshot(): List<Loop> = loops.map { it.copy() }
    fun restore(s: List<Loop>) {
        loops.clear()
        loops.addAll(s.map { it.copy() })
    }

    // -- Ölçüler ------------------------------------------------------------------------

    /** Dış konturun piksel sınır kutusu [x0, y0, x1, y1]. */
    fun outerBounds(): FloatArray? {
        val o = outer ?: return null
        if (o.circle) return floatArrayOf(o.cx - o.r, o.cy - o.r * (sx / sy).toFloat(), o.cx + o.r, o.cy + o.r * (sx / sy).toFloat())
        if (o.size < 2) return null
        return floatArrayOf(o.xs.min(), o.ys.min(), o.xs.max(), o.ys.max())
    }

    /** Dış kontur genişlik/yükseklik (mm). */
    fun sizeMm(): DoubleArray {
        val b = outerBounds() ?: return doubleArrayOf(0.0, 0.0)
        return doubleArrayOf((b[2] - b[0]) * sx, (b[3] - b[1]) * sy)
    }

    fun segmentLengthMm(loop: Loop, i: Int): Double {
        val j = (i + 1) % loop.size
        return hypot((loop.xs[j] - loop.xs[i]) * sx, (loop.ys[j] - loop.ys[i]) * sy)
    }

    fun diameterMm(loop: Loop): Double = 2.0 * loop.r * sx

    /** Ölçeği yeni dış boyuta göre ayarlar (biri 0 ise oran korunur). */
    fun setSize(widthMm: Double, heightMm: Double) {
        val cur = sizeMm()
        if (widthMm > 0 && cur[0] > 0) sx *= widthMm / cur[0]
        if (heightMm > 0 && cur[1] > 0) sy *= heightMm / cur[1]
        if (widthMm > 0 && heightMm <= 0) sy = sx
        if (heightMm > 0 && widthMm <= 0) sx = sy
        scaled = true
    }

    // -- Düzenleme -------------------------------------------------------------------------

    /**
     * Kenar uzunluğunu değiştirir. Yatay/dikey kenarlarda parçanın o kenarın sağında (veya
     * üstünde) kalan tüm noktaları kaydırır (gerdirme); eğik kenarlarda yalnızca uç noktası
     * kenar doğrultusunda taşınır.
     */
    fun setSegmentLength(loop: Loop, i: Int, mm: Double) {
        val j = (i + 1) % loop.size
        val ax = loop.xs[i] * sx; val ay = loop.ys[i] * sy
        val bx = loop.xs[j] * sx; val by = loop.ys[j] * sy
        val len = hypot(bx - ax, by - ay)
        if (len < 1e-9 || mm <= 0) return
        val delta = mm - len
        val ang = Math.toDegrees(atan2(abs(by - ay), abs(bx - ax)))
        when {
            ang < 1.0 -> {
                // Yatay: sağ uçtan sonraki her şey sağa kayar
                val edge = max(loop.xs[i], loop.xs[j]) - 0.5f
                val d = (delta / sx).toFloat()
                for (l in loops) {
                    if (l.circle) { if (l.cx >= edge) l.cx += d; continue }
                    for (k in l.xs.indices) if (l.xs[k] >= edge) l.xs[k] += d
                }
            }
            ang > 89.0 -> {
                // Dikey: resimde üstte kalan uç (küçük y) ve onun üstündekiler yukarı kayar
                val edge = min(loop.ys[i], loop.ys[j]) + 0.5f
                val d = (delta / sy).toFloat()
                for (l in loops) {
                    if (l.circle) { if (l.cy <= edge) l.cy -= d; continue }
                    for (k in l.ys.indices) if (l.ys[k] <= edge) l.ys[k] -= d
                }
            }
            else -> {
                val f = mm / len
                loop.xs[j] = ((ax + (bx - ax) * f) / sx).toFloat()
                loop.ys[j] = ((ay + (by - ay) * f) / sy).toFloat()
            }
        }
    }

    fun setDiameter(loop: Loop, mm: Double) {
        if (mm > 0) loop.r = (mm / 2.0 / sx).toFloat()
    }

    /** Yataya/dikeye yakın (±[tolDeg]) kenarları tam yatay/dikey yapar, aynı doğrudaki noktaları siler. */
    fun straighten(tolDeg: Double = 8.0) {
        for (l in loops) {
            if (l.circle || l.size < 3) continue
            repeat(3) {
                for (i in 0 until l.size) {
                    val j = (i + 1) % l.size
                    val dx = (l.xs[j] - l.xs[i]) * sx
                    val dy = (l.ys[j] - l.ys[i]) * sy
                    val ang = Math.toDegrees(atan2(abs(dy), abs(dx)))
                    if (ang < tolDeg) {
                        val m = (l.ys[i] + l.ys[j]) / 2f
                        l.ys[i] = m; l.ys[j] = m
                    } else if (ang > 90 - tolDeg) {
                        val m = (l.xs[i] + l.xs[j]) / 2f
                        l.xs[i] = m; l.xs[j] = m
                    }
                }
            }
            removeCollinear(l)
        }
    }

    private fun removeCollinear(l: Loop) {
        var changed = true
        while (changed && l.size > 3) {
            changed = false
            for (i in 0 until l.size) {
                val p = (i - 1 + l.size) % l.size
                val n = (i + 1) % l.size
                val ux = (l.xs[i] - l.xs[p]) * sx; val uy = (l.ys[i] - l.ys[p]) * sy
                val vx = (l.xs[n] - l.xs[i]) * sx; val vy = (l.ys[n] - l.ys[i]) * sy
                val lu = hypot(ux, uy); val lv = hypot(vx, vy)
                val cross = if (lu < 1e-6 || lv < 1e-6) 0.0 else abs(ux * vy - uy * vx) / (lu * lv)
                val dot = ux * vx + uy * vy
                if (lu < 1e-6 || lv < 1e-6 || (cross < 0.01 && dot > 0)) {
                    l.xs.removeAt(i); l.ys.removeAt(i)
                    changed = true
                    break
                }
            }
        }
    }

    // -- Yerel tarafa aktarma ---------------------------------------------------------

    /** C++ DecodeSketch biçimi; mm, y yukarı. Boşsa null. */
    fun encode(): DoubleArray? {
        if (isEmpty) return null
        val out = ArrayList<Double>()
        out.add(loops.size.toDouble())
        for (l in loops) {
            if (l.circle) {
                out.add(1.0); out.add(l.cx * sx); out.add(-l.cy * sy); out.add(l.r * sx)
            } else {
                out.add(0.0); out.add(l.size.toDouble())
                for (k in 0 until l.size) { out.add(l.xs[k] * sx); out.add(-l.ys[k] * sy) }
            }
        }
        return out.toDoubleArray()
    }

    // -- JSON ------------------------------------------------------------------------

    fun toJson(): JSONObject = JSONObject().apply {
        put("sx", sx); put("sy", sy); put("scaled", scaled)
        image?.let { put("image", it) }
        put("w", imageW); put("h", imageH)
        put("loops", JSONArray().apply {
            for (l in loops) put(JSONObject().apply {
                put("circle", l.circle)
                if (l.circle) { put("cx", l.cx.toDouble()); put("cy", l.cy.toDouble()); put("r", l.r.toDouble()) }
                else put("p", JSONArray().apply {
                    for (k in 0 until l.size) { put(l.xs[k].toDouble()); put(l.ys[k].toDouble()) }
                })
            })
        })
    }

    companion object {
        fun fromJson(o: JSONObject): Sketch = Sketch().apply {
            sx = o.optDouble("sx", 1.0); sy = o.optDouble("sy", 1.0)
            scaled = o.optBoolean("scaled", false)
            image = if (o.has("image")) o.getString("image") else null
            imageW = o.optInt("w"); imageH = o.optInt("h")
            val arr = o.optJSONArray("loops") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val lo = arr.getJSONObject(i)
                val l = Loop()
                if (lo.optBoolean("circle")) {
                    l.circle = true
                    l.cx = lo.getDouble("cx").toFloat(); l.cy = lo.getDouble("cy").toFloat()
                    l.r = lo.getDouble("r").toFloat()
                } else {
                    val p = lo.getJSONArray("p")
                    var k = 0
                    while (k + 1 < p.length()) {
                        l.xs.add(p.getDouble(k).toFloat()); l.ys.add(p.getDouble(k + 1).toFloat()); k += 2
                    }
                }
                loops.add(l)
            }
        }
    }
}
