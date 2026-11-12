package com.mertkaradag.stepviewer.photocad

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Fotoğraftaki parçayı arka plandan ayırıp dış kontur + delikleri çıkarır.
 *
 * Yöntem: kenar şeridinden arka plan rengi tahmini -> renk uzaklığı -> Otsu eşiği ->
 * kapama/açma -> dokunulan bileşen -> iç boşluklar (delikler) -> Moore kontur izleme ->
 * Douglas-Peucker sadeleştirme -> daireye benzeyen döngüler gerçek daireye çevrilir.
 */
object ContourExtractor {

    class Result(val loops: List<Sketch.Loop>, val maskW: Int, val maskH: Int)

    /**
     * @param bmp çözümleme resmi (uzun kenarı ~900 px yeterli)
     * @param seedX,seedY dokunulan nokta (bmp pikseli), negatifse en büyük bileşen
     * @param sensitivity 0..1 (0.5 = Otsu eşiği; büyüdükçe daha çok piksel parça sayılır)
     * @param detail 0..1 (büyüdükçe daha çok köşe noktası)
     * @param outScale çıkan noktaların çarpanı (bmp -> asıl resim pikseli)
     */
    fun extract(
        bmp: Bitmap, seedX: Float, seedY: Float, sensitivity: Float, detail: Float, outScale: Float,
    ): Result? {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        // YCbCr, 3x3 kutu bulanıklaştırma ile
        val yy = FloatArray(w * h); val cb = FloatArray(w * h); val cr = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16 and 255).toFloat(); val g = (c shr 8 and 255).toFloat(); val b = (c and 255).toFloat()
            yy[i] = 0.299f * r + 0.587f * g + 0.114f * b
            cb[i] = -0.1687f * r - 0.3313f * g + 0.5f * b
            cr[i] = 0.5f * r - 0.4187f * g - 0.0813f * b
        }
        blur(yy, w, h); blur(cb, w, h); blur(cr, w, h)

        // Arka plan: kenar şeridinin medyanı
        val band = max(2, min(w, h) / 25)
        val sy = ArrayList<Float>(); val sb = ArrayList<Float>(); val sr = ArrayList<Float>()
        for (y in 0 until h) for (x in 0 until w) {
            if (x >= band && x < w - band && y >= band && y < h - band) continue
            if ((x + y) % 3 != 0) continue
            val i = y * w + x
            sy.add(yy[i]); sb.add(cb[i]); sr.add(cr[i])
        }
        val bgY = median(sy); val bgB = median(sb); val bgR = median(sr)

        // Renk uzaklığı (renk farkı aydınlıktan biraz daha ağırlıklı: gölgeler daha az etkiler)
        val dist = FloatArray(w * h)
        var maxD = 1f
        for (i in dist.indices) {
            val d = sqrt(sq(0.8f * (yy[i] - bgY)) + sq(1.3f * (cb[i] - bgB)) + sq(1.3f * (cr[i] - bgR)))
            dist[i] = d
            if (d > maxD) maxD = d
        }
        var t = otsu(dist, maxD)
        // Hassasiyet: 0.5 -> Otsu, 1 -> eşiğin %40'ı, 0 -> eşiğin %160'ı
        t *= (1.6f - 1.2f * sensitivity.coerceIn(0f, 1f))
        val mask = BooleanArray(w * h) { dist[it] > t }

        val rad = max(1, min(w, h) / 300)
        close(mask, w, h, rad + 1)
        open(mask, w, h, rad)

        // Bileşen seçimi
        val labels = IntArray(w * h) { -1 }
        val sizes = ArrayList<Int>()
        val stack = IntArray(w * h)
        for (i in mask.indices) {
            if (!mask[i] || labels[i] >= 0) continue
            sizes.add(flood(mask, true, labels, sizes.size, i, w, h, stack))
        }
        if (sizes.isEmpty()) return null
        var comp = -1
        if (seedX >= 0 && seedY >= 0) {
            val sx0 = seedX.toInt().coerceIn(0, w - 1); val sy0 = seedY.toInt().coerceIn(0, h - 1)
            var best = Float.MAX_VALUE
            val r = max(8, min(w, h) / 30)
            for (y in max(0, sy0 - r)..min(h - 1, sy0 + r)) for (x in max(0, sx0 - r)..min(w - 1, sx0 + r)) {
                val l = labels[y * w + x]
                if (l < 0) continue
                val d = hypot((x - sx0).toFloat(), (y - sy0).toFloat())
                if (d < best) { best = d; comp = l }
            }
        }
        if (comp < 0) comp = sizes.indices.maxBy { sizes[it] }
        val compArea = sizes[comp]
        if (compArea < 50) return null

        val part = BooleanArray(w * h) { labels[it] == comp }
        // Dışarıdan ulaşılabilen arka plan; kalan boşluklar delik
        val outside = IntArray(w * h) { -1 }
        val notPart = BooleanArray(w * h) { !part[it] }
        for (x in 0 until w) for (y in intArrayOf(0, h - 1)) {
            val i = y * w + x
            if (notPart[i] && outside[i] < 0) flood(notPart, true, outside, 0, i, w, h, stack)
        }
        for (y in 0 until h) for (x in intArrayOf(0, w - 1)) {
            val i = y * w + x
            if (notPart[i] && outside[i] < 0) flood(notPart, true, outside, 0, i, w, h, stack)
        }
        val holeLabels = IntArray(w * h) { -1 }
        val holeSizes = ArrayList<Int>()
        val holeStarts = ArrayList<Int>()
        for (i in notPart.indices) {
            if (!notPart[i] || outside[i] >= 0 || holeLabels[i] >= 0) continue
            holeStarts.add(i)
            holeSizes.add(flood(notPart, true, holeLabels, holeSizes.size, i, w, h, stack))
        }
        val minHole = max(30, compArea / 2000)

        val eps = max(0.8f, min(w, h) * (0.012f - 0.011f * detail.coerceIn(0f, 1f)))
        val loops = ArrayList<Sketch.Loop>()
        val outerStart = part.indexOfFirst { it }
        val outerPts = trace(part, w, h, outerStart) ?: return null
        loops.add(toLoop(outerPts, eps, outScale, allowCircle = true) ?: return null)
        for (k in holeSizes.indices) {
            if (holeSizes[k] < minHole) continue
            val hm = BooleanArray(w * h) { holeLabels[it] == k }
            val pts = trace(hm, w, h, holeStarts[k]) ?: continue
            toLoop(pts, eps, outScale, allowCircle = true)?.let { loops.add(it) }
        }
        return Result(loops, w, h)
    }

    private fun sq(v: Float) = v * v

    private fun median(v: ArrayList<Float>): Float {
        if (v.isEmpty()) return 0f
        v.sort()
        return v[v.size / 2]
    }

    private fun blur(a: FloatArray, w: Int, h: Int) {
        val tmp = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            val l = a[y * w + max(0, x - 1)]; val r = a[y * w + min(w - 1, x + 1)]
            tmp[y * w + x] = (l + a[y * w + x] + r) / 3f
        }
        for (y in 0 until h) for (x in 0 until w) {
            val u = tmp[max(0, y - 1) * w + x]; val d = tmp[min(h - 1, y + 1) * w + x]
            a[y * w + x] = (u + tmp[y * w + x] + d) / 3f
        }
    }

    private fun otsu(v: FloatArray, maxV: Float): Float {
        val bins = 256
        val hist = IntArray(bins)
        for (d in v) hist[min(bins - 1, (d / maxV * (bins - 1)).toInt())]++
        val total = v.size.toDouble()
        var sumAll = 0.0
        for (i in 0 until bins) sumAll += i * hist[i].toDouble()
        var sumB = 0.0; var wB = 0.0; var best = 0.0; var thr = bins / 4
        for (i in 0 until bins) {
            wB += hist[i]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB; val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; thr = i }
        }
        return (thr + 0.5f) / (bins - 1) * maxV
    }

    private fun dilate(m: BooleanArray, w: Int, h: Int, r: Int, value: Boolean) {
        // Ayrık kare yapı elemanı: önce yatay, sonra dikey geçiş
        val tmp = BooleanArray(m.size)
        for (y in 0 until h) {
            var run = -1 // son 'value' görülen x
            for (x in 0 until w) {
                if (m[y * w + x] == value) run = x
                tmp[y * w + x] = run >= 0 && x - run <= r
            }
            run = -1
            for (x in w - 1 downTo 0) {
                if (m[y * w + x] == value) run = x
                if (run >= 0 && run - x <= r) tmp[y * w + x] = true
            }
        }
        for (x in 0 until w) {
            var run = -1
            val col = BooleanArray(h)
            for (y in 0 until h) {
                if (tmp[y * w + x]) run = y
                col[y] = run >= 0 && y - run <= r
            }
            run = -1
            for (y in h - 1 downTo 0) {
                if (tmp[y * w + x]) run = y
                if (run >= 0 && run - y <= r) col[y] = true
            }
            for (y in 0 until h) m[y * w + x] = if (col[y]) value else !value
        }
    }

    private fun close(m: BooleanArray, w: Int, h: Int, r: Int) {
        dilate(m, w, h, r, true)
        dilate(m, w, h, r, false)
    }

    private fun open(m: BooleanArray, w: Int, h: Int, r: Int) {
        dilate(m, w, h, r, false)
        dilate(m, w, h, r, true)
    }

    /** 4-komşu dolgu; etiketlenen piksel sayısını döndürür. */
    private fun flood(
        m: BooleanArray, value: Boolean, labels: IntArray, label: Int, start: Int, w: Int, h: Int, stack: IntArray,
    ): Int {
        var sp = 0
        stack[sp++] = start
        labels[start] = label
        var count = 0
        while (sp > 0) {
            val i = stack[--sp]
            count++
            val x = i % w; val y = i / w
            if (x > 0) push(m, value, labels, label, i - 1, stack, sp).let { if (it) sp++ }
            if (x < w - 1) push(m, value, labels, label, i + 1, stack, sp).let { if (it) sp++ }
            if (y > 0) push(m, value, labels, label, i - w, stack, sp).let { if (it) sp++ }
            if (y < h - 1) push(m, value, labels, label, i + w, stack, sp).let { if (it) sp++ }
        }
        return count
    }

    private fun push(m: BooleanArray, value: Boolean, labels: IntArray, label: Int, j: Int, stack: IntArray, sp: Int): Boolean {
        if (m[j] != value || labels[j] >= 0) return false
        labels[j] = label
        stack[sp] = j
        return true
    }

    private val DX = intArrayOf(-1, -1, 0, 1, 1, 1, 0, -1)
    private val DY = intArrayOf(0, -1, -1, -1, 0, 1, 1, 1)

    private fun dirOf(dx: Int, dy: Int): Int {
        for (k in 0 until 8) if (DX[k] == dx && DY[k] == dy) return k
        return 0
    }

    /** Moore komşuluk izleme (Jacob durdurma ölçütü). start: tarama sırasındaki ilk piksel. */
    private fun trace(m: BooleanArray, w: Int, h: Int, start: Int): FloatArray? {
        if (start < 0) return null
        fun fg(x: Int, y: Int) = x in 0 until w && y in 0 until h && m[y * w + x]
        val out = ArrayList<Float>()
        var x = start % w; var y = start / w
        val x0 = x; val y0 = y
        var back = 0 // batı komşusu arka plan
        val startBack = back
        out.add(x + 0.5f); out.add(y + 0.5f)
        val maxSteps = 4 * w * h
        var steps = 0
        while (steps++ < maxSteps) {
            var found = -1
            for (k in 1..8) {
                val d = (back + k) % 8
                if (fg(x + DX[d], y + DY[d])) { found = d; break }
            }
            if (found < 0) return null // tek piksel
            val prev = (found + 7) % 8
            val px = x + DX[prev]; val py = y + DY[prev]
            x += DX[found]; y += DY[found]
            back = dirOf(px - x, py - y)
            if (x == x0 && y == y0 && back == startBack) break
            if (x == x0 && y == y0) continue
            out.add(x + 0.5f); out.add(y + 0.5f)
        }
        return if (out.size >= 6) out.toFloatArray() else null
    }

    private fun toLoop(pts: FloatArray, eps: Float, scale: Float, allowCircle: Boolean): Sketch.Loop? {
        val n = pts.size / 2
        if (n < 3) return null
        if (allowCircle && n >= 16) fitCircle(pts)?.let { (cx, cy, r) ->
            return Sketch.Loop(circle = true, cx = cx * scale, cy = cy * scale, r = r * scale)
        }
        val keep = simplifyClosed(pts, eps)
        if (keep.size < 3) return null
        val l = Sketch.Loop()
        for (i in keep) { l.xs.add(pts[2 * i] * scale); l.ys.add(pts[2 * i + 1] * scale) }
        return l
    }

    /** Kasa daire uydurma; nokta-merkez uzaklığı yarıçaptan %6'dan fazla sapmıyorsa daire. */
    private fun fitCircle(p: FloatArray): Triple<Float, Float, Float>? {
        val n = p.size / 2
        var mx = 0.0; var my = 0.0
        for (i in 0 until n) { mx += p[2 * i]; my += p[2 * i + 1] }
        mx /= n; my /= n
        var suu = 0.0; var svv = 0.0; var suv = 0.0; var suuu = 0.0; var svvv = 0.0; var suvv = 0.0; var svuu = 0.0
        for (i in 0 until n) {
            val u = p[2 * i] - mx; val v = p[2 * i + 1] - my
            suu += u * u; svv += v * v; suv += u * v
            suuu += u * u * u; svvv += v * v * v; suvv += u * v * v; svuu += v * u * u
        }
        val det = suu * svv - suv * suv
        if (abs(det) < 1e-9) return null
        val a = 0.5 * (suuu + suvv); val b = 0.5 * (svvv + svuu)
        val uc = (a * svv - b * suv) / det
        val vc = (b * suu - a * suv) / det
        val r = sqrt(uc * uc + vc * vc + (suu + svv) / n)
        if (r < 3) return null
        var maxDev = 0.0
        for (i in 0 until n) {
            val d = hypot(p[2 * i] - mx - uc, p[2 * i + 1] - my - vc)
            maxDev = max(maxDev, abs(d - r))
        }
        return if (maxDev < 0.06 * r + 0.75) Triple((mx + uc).toFloat(), (my + vc).toFloat(), r.toFloat()) else null
    }

    /** Kapalı çokgen için Douglas-Peucker; tutulan nokta indeksleri. */
    private fun simplifyClosed(p: FloatArray, eps: Float): List<Int> {
        val n = p.size / 2
        // En uzak iki nokta arasından ikiye böl
        var far = 0; var best = -1f
        for (i in 0 until n) {
            val d = hypot(p[2 * i] - p[0], p[2 * i + 1] - p[1])
            if (d > best) { best = d; far = i }
        }
        val keep = BooleanArray(n)
        keep[0] = true; keep[far] = true
        dp(p, 0, far, eps, keep)
        dp(p, far, n, eps, keep) // n = 0 (kapanış)
        return (0 until n).filter { keep[it] }
    }

    private fun dp(p: FloatArray, a: Int, b: Int, eps: Float, keep: BooleanArray) {
        val n = p.size / 2
        if (b - a < 2) return
        val ax = p[2 * a]; val ay = p[2 * a + 1]
        val bx = p[2 * (b % n)]; val by = p[2 * (b % n) + 1]
        val len = hypot(bx - ax, by - ay)
        var idx = -1; var dmax = 0f
        for (i in a + 1 until b) {
            val d = if (len < 1e-6) hypot(p[2 * i] - ax, p[2 * i + 1] - ay)
            else abs((bx - ax) * (ay - p[2 * i + 1]) - (ax - p[2 * i]) * (by - ay)) / len
            if (d > dmax) { dmax = d; idx = i }
        }
        if (idx >= 0 && dmax > eps) {
            keep[idx] = true
            dp(p, a, idx, eps, keep)
            dp(p, idx, b, eps, keep)
        }
    }
}
