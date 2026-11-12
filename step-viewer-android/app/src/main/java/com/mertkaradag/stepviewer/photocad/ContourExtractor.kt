package com.mertkaradag.stepviewer.photocad

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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
        lineArt: Boolean = false,
    ): Result? {
        if (lineArt) return extractLineArt(bmp, seedX, seedY, sensitivity, detail, outScale)
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

    /**
     * Teknik resim (beyaz zemin üzerinde çizgiler): dokunulan noktayı çevreleyen çizgi bileşeni
     * bulunur, içi doldurulur, ince çizgiler (eksen, ölçü, kesik çizgi) açma ile atılır, dış kontur
     * izlenir. İçerideki kapalı bölgelerden yalnızca daireye benzeyenler delik sayılır.
     */
    private fun extractLineArt(
        bmp: Bitmap, seedX: Float, seedY: Float, sensitivity: Float, detail: Float, outScale: Float,
    ): Result? {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        // Koyuluk (kağıt rengine göre): kağıt = parlak piksellerin medyanı
        val lum = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            lum[i] = 0.299f * (c shr 16 and 255) + 0.587f * (c shr 8 and 255) + 0.114f * (c and 255)
        }
        val sample = ArrayList<Float>()
        var k = 0
        while (k < lum.size) { sample.add(lum[k]); k += 7 }
        val paper = median(sample)
        val dark = FloatArray(w * h) { max(0f, paper - lum[it]) }
        var maxD = 1f
        for (d in dark) if (d > maxD) maxD = d
        var t = otsu(dark, maxD)
        t *= (1.6f - 1.2f * sensitivity.coerceIn(0f, 1f))
        t = max(t, 12f)
        val ink = BooleanArray(w * h) { dark[it] > t }
        close(ink, w, h, 1)

        val labels = IntArray(w * h) { -1 }
        val sizes = ArrayList<Int>()
        val stack = IntArray(w * h)
        for (i in ink.indices) {
            if (!ink[i] || labels[i] >= 0) continue
            sizes.add(flood(ink, true, labels, sizes.size, i, w, h, stack))
        }
        if (sizes.isEmpty()) return null
        val sx0 = seedX.toInt().coerceIn(0, w - 1)
        val sy0 = seedY.toInt().coerceIn(0, h - 1)
        // Dokunulan noktayı çevreleyen bileşen: 8 yöne ışın, en çok vurulan etiket
        var comp = labels[sy0 * w + sx0]
        if (comp < 0 && seedX >= 0) {
            val hits = HashMap<Int, Int>()
            for (dir in 0 until 8) {
                var x = sx0; var y = sy0
                while (x in 0 until w && y in 0 until h) {
                    val l = labels[y * w + x]
                    if (l >= 0) { hits[l] = (hits[l] ?: 0) + 1; break }
                    x += DX[dir]; y += DY[dir]
                }
            }
            comp = hits.maxByOrNull { it.value * 1_000_000L + sizes[it.key] }?.key ?: -1
        }
        if (comp < 0) comp = sizes.indices.maxBy { sizes[it] }

        // Bileşenin içi: dışarıdan ulaşılamayan her şey
        val notComp = BooleanArray(w * h) { labels[it] != comp }
        val outside = IntArray(w * h) { -1 }
        for (x in 0 until w) for (y in intArrayOf(0, h - 1)) {
            val i = y * w + x
            if (notComp[i] && outside[i] < 0) flood(notComp, true, outside, 0, i, w, h, stack)
        }
        for (y in 0 until h) for (x in intArrayOf(0, w - 1)) {
            val i = y * w + x
            if (notComp[i] && outside[i] < 0) flood(notComp, true, outside, 0, i, w, h, stack)
        }
        val filled = BooleanArray(w * h) { outside[it] < 0 }
        // İnce çizgileri (eksen, ölçü, uzatma çizgileri) at
        val rad = max(2, min(w, h) / 250)
        open(filled, w, h, rad)

        val fl = IntArray(w * h) { -1 }
        val fsizes = ArrayList<Int>()
        for (i in filled.indices) {
            if (!filled[i] || fl[i] >= 0) continue
            fsizes.add(flood(filled, true, fl, fsizes.size, i, w, h, stack))
        }
        if (fsizes.isEmpty()) return null
        var part = fl[sy0 * w + sx0]
        if (part < 0) part = fsizes.indices.maxBy { fsizes[it] }
        if (fsizes[part] < 100) return null
        val partMask = BooleanArray(w * h) { fl[it] == part }

        val eps = max(0.8f, min(w, h) * (0.008f - 0.0075f * detail.coerceIn(0f, 1f)))
        val loops = ArrayList<Sketch.Loop>()
        val outerPts = trace(partMask, w, h, partMask.indexOfFirst { it }) ?: return null
        loops.add(toLoop(outerPts, eps, outScale, allowCircle = true) ?: return null)

        // Delikler: çizgilerle kapalı, parçanın içindeki daireye benzer boş bölgeler
        val inner = BooleanArray(w * h) { partMask[it] && notComp[it] && !ink[it] }
        val il = IntArray(w * h) { -1 }
        val circles = ArrayList<FloatArray>() // cx, cy, r (analiz pikseli)
        val minR = max(3f, min(w, h) / 200f)
        val outerB = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until outerPts.size / 2) {
            outerB[0] = min(outerB[0], outerPts[2 * i]); outerB[1] = min(outerB[1], outerPts[2 * i + 1])
            outerB[2] = max(outerB[2], outerPts[2 * i]); outerB[3] = max(outerB[3], outerPts[2 * i + 1])
        }
        var n = 0
        for (i in inner.indices) {
            if (!inner[i] || il[i] >= 0) continue
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            val area = floodBox(inner, il, n++, i, w, h, stack) { x, y ->
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
            }
            val bw = (x1 - x0 + 1).toFloat(); val bh = (y1 - y0 + 1).toFloat()
            if (min(bw, bh) < 2 * minR) continue
            // Dış konturun kendisi olmasın
            if (bw > 0.9f * (outerB[2] - outerB[0]) && bh > 0.9f * (outerB[3] - outerB[1])) continue
            val square = abs(bw - bh) / max(bw, bh) < 0.1f
            val fill = area / (Math.PI.toFloat() * bw * bh / 4f)
            if (!square || fill < 0.72f || fill > 1.08f) continue
            // Çizgi kalınlığının yarısı kadar büyüt (bölge çizginin içinden ölçülür)
            circles.add(floatArrayOf((x0 + x1 + 1) / 2f, (y0 + y1 + 1) / 2f, (bw + bh) / 4f + 1f))
        }
        // Eksen çizgisi daireyi böldüğünde bölge yöntemi kaçırır: çizgi bileşenlerinden daire ara
        circlesFromInk(labels, sizes.size, comp, partMask, w, h, minR, outerB, circles)
        // İç içe dairelerde (havşa, kademe) en küçüğü kalır
        val keep = circles.filter { c ->
            circles.none { o -> o !== c && o[2] < c[2] && hypot(o[0] - c[0], o[1] - c[1]) < c[2] }
        }
        for (c in keep) loops.add(Sketch.Loop(circle = true, cx = c[0] * outScale, cy = c[1] * outScale, r = c[2] * outScale))
        return Result(loops, w, h)
    }

    /**
     * Parçanın içindeki çizgi bileşenlerinde tam daire arar: merkez adayı (sınır kutusu merkezi ve
     * ağırlık merkezi) çevresinde uzaklık histogramının tepe noktası yarıçaptır; o yarıçaptaki
     * pikseller 36 açı diliminin en az 32'sini dolduruyorsa dairedir. Eksen çizgileri simetrik
     * olduğu için merkezi bozmaz.
     */
    private fun circlesFromInk(
        labels: IntArray, labelCount: Int, outlineLabel: Int, partMask: BooleanArray, w: Int, h: Int,
        minR: Float, outerB: FloatArray, out: ArrayList<FloatArray>,
    ) {
        val x0 = IntArray(labelCount) { w }; val y0 = IntArray(labelCount) { h }
        val x1 = IntArray(labelCount) { -1 }; val y1 = IntArray(labelCount) { -1 }
        val cnt = IntArray(labelCount)
        for (y in 0 until h) for (x in 0 until w) {
            val l = labels[y * w + x]
            if (l < 0 || l == outlineLabel) continue
            if (x < x0[l]) x0[l] = x; if (x > x1[l]) x1[l] = x
            if (y < y0[l]) y0[l] = y; if (y > y1[l]) y1[l] = y
            cnt[l]++
        }
        for (l in 0 until labelCount) {
            if (l == outlineLabel || cnt[l] < 20) continue
            val bw = x1[l] - x0[l] + 1; val bh = y1[l] - y0[l] + 1
            if (min(bw, bh) < 2 * minR) continue
            if (bw > 0.9f * (outerB[2] - outerB[0]) && bh > 0.9f * (outerB[3] - outerB[1])) continue
            val mx = (x0[l] + x1[l]) / 2; val my = (y0[l] + y1[l]) / 2
            if (!partMask[my.coerceIn(0, h - 1) * w + mx.coerceIn(0, w - 1)]) continue
            // Pikselleri topla
            val xs = ArrayList<Int>(cnt[l]); val ys = ArrayList<Int>(cnt[l])
            var sx = 0.0; var sy = 0.0
            for (y in y0[l]..y1[l]) for (x in x0[l]..x1[l]) {
                if (labels[y * w + x] != l) continue
                xs.add(x); ys.add(y); sx += x; sy += y
            }
            val cands = arrayOf(
                floatArrayOf((x0[l] + x1[l] + 1) / 2f, (y0[l] + y1[l] + 1) / 2f),
                floatArrayOf((sx / xs.size).toFloat() + 0.5f, (sy / ys.size).toFloat() + 0.5f),
            )
            var found: FloatArray? = null
            for (c in cands) {
                val maxR = max(bw, bh) / 2 + 2
                val hist = IntArray(maxR + 2)
                for (i in xs.indices) {
                    val d = hypot(xs[i] + 0.5f - c[0], ys[i] + 0.5f - c[1]).roundToInt()
                    if (d <= maxR) hist[d]++
                }
                // Küçükten büyüğe: tam çevreyi dolduran ilk tepe
                var r = minR.roundToInt()
                while (r <= maxR) {
                    val around = hist[r] + (if (r > 0) hist[r - 1] else 0) + hist[r + 1]
                    if (around >= 0.7 * 2 * Math.PI * r) {
                        val tol = max(1.5f, 0.06f * r)
                        val bins = BooleanArray(36)
                        var sum = 0.0; var nIn = 0
                        for (i in xs.indices) {
                            val dx = xs[i] + 0.5f - c[0]; val dy = ys[i] + 0.5f - c[1]
                            val d = hypot(dx, dy)
                            if (abs(d - r) > tol) continue
                            val a = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 180.0
                            bins[(a / 10.0).toInt().coerceIn(0, 35)] = true
                            sum += d; nIn++
                        }
                        if (bins.count { it } >= 32 && nIn > 0) {
                            found = floatArrayOf(c[0], c[1], (sum / nIn).toFloat())
                            break
                        }
                    }
                    r++
                }
                if (found != null) break
            }
            val f = found ?: continue
            // Bölge yöntemiyle bulunmuş aynı daire varsa ekleme
            if (out.any { hypot(it[0] - f[0], it[1] - f[1]) < 0.3f * f[2] && abs(it[2] - f[2]) < 0.25f * f[2] }) continue
            out.add(f)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Teknik resmin tam otomatik çözümlenmesi
    // ---------------------------------------------------------------------------------------

    /** Bulunan bir görünüş: sınır kutusu (resim pikseli) ve konturları. */
    class DrawingView(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val loops: List<Sketch.Loop>) {
        val width: Int get() = x1 - x0
        val height: Int get() = y1 - y0
    }

    /**
     * @param front,top,side eşleşen görünüşler (yalnızca tek görünüş bulunduysa top ve side null)
     * @param sideRight yan görünüş sağdan bakış mı
     * @param sideBySide yalnızca yan yana iki görünüş bulundu (front = soldaki, side = sağdaki)
     */
    class DrawingResult(
        val front: DrawingView, val top: DrawingView?, val side: DrawingView?,
        val sideRight: Boolean, val sideBySide: Boolean,
    )

    private class Cand(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val mask: BooleanArray, val area: Int) {
        val bw: Int get() = x1 - x0
        val bh: Int get() = y1 - y0
    }

    /**
     * Teknik resim sayfasını tarar: kalın (görünen kenar) çizgileri ince çizgilerden (ölçü, eksen,
     * gizli kenar, yazı) ayırır, kapalı kontur oluşturan görünüşleri bulur, hizalı olanları
     * ön / üst / yan olarak eşleştirir (ISO E yerleşimi varsayılır). Görünüş yoksa null.
     */
    fun analyzeDrawing(bmp: Bitmap): DrawingResult? {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val lum = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            lum[i] = 0.299f * (c shr 16 and 255) + 0.587f * (c shr 8 and 255) + 0.114f * (c and 255)
        }
        val sample = ArrayList<Float>()
        var k = 0
        while (k < lum.size) { sample.add(lum[k]); k += 7 }
        val paper = median(sample)
        val dark = FloatArray(w * h) { max(0f, paper - lum[it]) }
        var maxD = 1f
        for (d in dark) if (d > maxD) maxD = d
        val t = max(otsu(dark, maxD) * 0.8f, 12f)
        val ink = BooleanArray(w * h) { dark[it] > t }

        // Çizgi kalınlığı = min(yatay koşu, dikey koşu)
        val th = ByteArray(w * h)
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                if (!ink[y * w + x]) { x++; continue }
                var e = x
                while (e < w && ink[y * w + e]) e++
                val len = min(e - x, 120).toByte()
                for (q in x until e) th[y * w + q] = len
                x = e
            }
        }
        for (x in 0 until w) {
            var y = 0
            while (y < h) {
                if (!ink[y * w + x]) { y++; continue }
                var e = y
                while (e < h && ink[e * w + x]) e++
                val len = min(e - y, 120)
                for (q in y until e) if (len < th[q * w + x]) th[q * w + x] = len.toByte()
                y = e
            }
        }
        // Kalınlık dağılımı iki tepeli ise (ince + kalın çizgi) ince olanları at
        val hist = DoubleArray(17)
        for (i in ink.indices) if (ink[i] && th[i] in 1..15) hist[th[i].toInt()] += 1.0
        val total = hist.sum()
        var sumAll = 0.0
        for (i in 0..16) sumAll += i * hist[i]
        var wB = 0.0; var sB = 0.0; var best = 0.0; var thr = 0; var mLow = 0.0; var mHigh = 0.0
        for (i in 0..16) {
            wB += hist[i]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF <= 0.0) break
            sB += i * hist[i]
            val mB = sB / wB; val mF = (sumAll - sB) / wF
            val v = wB * wF * (mB - mF) * (mB - mF)
            if (v > best) { best = v; thr = i; mLow = mB; mHigh = mF }
        }
        var lowShare = 0.0
        for (i in 0..thr) lowShare += hist[i]
        lowShare = if (total > 0) lowShare / total else 0.0
        val split = total > 0 && mHigh / max(mLow, 0.1) >= 1.5 && lowShare in 0.08..0.92
        val thick = if (split) BooleanArray(w * h) { ink[it] && th[it] > thr } else ink.copyOf()
        close(thick, w, h, 1)

        // Bileşenler ve sınır kutuları
        val labels = IntArray(w * h) { -1 }
        val stack = IntArray(w * h)
        val bx0 = ArrayList<Int>(); val by0 = ArrayList<Int>(); val bx1 = ArrayList<Int>(); val by1 = ArrayList<Int>()
        for (i in thick.indices) {
            if (!thick[i] || labels[i] >= 0) continue
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            floodBox(thick, labels, bx0.size, i, w, h, stack) { x, y ->
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
            }
            bx0.add(x0); by0.add(y0); bx1.add(x1 + 1); by1.add(y1 + 1)
        }
        val minSize = 0.03f * min(w, h)
        val order = bx0.indices.filter { l ->
            val bw = bx1[l] - bx0[l]; val bh = by1[l] - by0[l]
            max(bw, bh) >= minSize && min(bw, bh) >= 4 && bw <= 0.85f * w && bh <= 0.85f * h
        }.sortedByDescending { (bx1[it] - bx0[it]).toLong() * (by1[it] - by0[it]) }.take(14)

        // Her adayın içi doldurulmuş maskesi (1 piksel kenar payıyla pencere)
        val cands = ArrayList<Cand>()
        for (l in order) {
            val ww = bx1[l] - bx0[l] + 2; val wh = by1[l] - by0[l] + 2
            val free = BooleanArray(ww * wh) { true }
            for (y in by0[l] until by1[l]) for (x in bx0[l] until bx1[l]) {
                if (labels[y * w + x] == l) free[(y - by0[l] + 1) * ww + (x - bx0[l] + 1)] = false
            }
            val reach = IntArray(ww * wh) { -1 }
            flood(free, true, reach, 0, 0, ww, wh, stack)
            val mask = BooleanArray(ww * wh) { reach[it] < 0 }
            // Kalan ince çıkıntıları at
            open(mask, ww, wh, 2)
            var area = 0
            for (v in mask) if (v) area++
            // Kapalı alan oluşturmayan şekiller (tek çizgi, yazı) görünüş değildir
            if (area < 0.15f * (ww - 2) * (wh - 2) && area < 4 * minSize * minSize) continue
            if (area < minSize * minSize / 4) continue
            cands.add(Cand(bx0[l], by0[l], bx1[l], by1[l], mask, area))
        }
        // Başka bir adayın kutusunun içinde kalanlar görünüş değil (iç ayrıntı)
        val views = cands.filter { c ->
            cands.none { p -> p !== c && p.x0 <= c.x0 && p.y0 <= c.y0 && p.x1 >= c.x1 && p.y1 >= c.y1 }
        }
        if (views.isEmpty()) return null

        fun alignedX(a: Cand, b: Cand): Boolean {
            val tol = 0.025f * max(a.bw, b.bw) + 4
            return abs(a.x0 - b.x0) < tol && abs(a.x1 - b.x1) < tol && (a.y1 <= b.y0 || b.y1 <= a.y0)
        }
        fun alignedY(a: Cand, b: Cand): Boolean {
            val tol = 0.025f * max(a.bh, b.bh) + 4
            return abs(a.y0 - b.y0) < tol && abs(a.y1 - b.y1) < tol && (a.x1 <= b.x0 || b.x1 <= a.x0)
        }

        fun build(c: Cand): DrawingView? {
            val loops = viewLoops(c, thick, w, h, stack) ?: return null
            return DrawingView(c.x0, c.y0, c.x1, c.y1, loops)
        }

        // 1) Alt alta hizalı çift (ön + üst), varsa yanında hizalı üçüncü görünüş
        var bestPair: Pair<Cand, Cand>? = null
        var bestArea = -1L
        for (i in views.indices) for (j in i + 1 until views.size) {
            if (!alignedX(views[i], views[j])) continue
            val a = views[i].area.toLong() + views[j].area
            if (a > bestArea) { bestArea = a; bestPair = views[i] to views[j] }
        }
        if (bestPair != null) {
            val upper = if (bestPair.first.y0 < bestPair.second.y0) bestPair.first else bestPair.second
            val lower = if (upper === bestPair.first) bestPair.second else bestPair.first
            // ISO E: ön üstte, üst görünüş altta. Yan görünüş alttakiyle hizalıysa ISO A (ön altta).
            var front = upper; var top = lower
            var side = views.filter { it !== upper && it !== lower && alignedY(it, upper) }.maxByOrNull { it.area }
            var thirdAngle = false
            if (side == null) {
                side = views.filter { it !== upper && it !== lower && alignedY(it, lower) }.maxByOrNull { it.area }
                if (side != null) { front = lower; top = upper; thirdAngle = true }
            }
            val fv = build(front); val tv = build(top)
            if (fv != null && tv != null) {
                val sv = side?.let { build(it) }
                // ISO E: ön görünüşün sağındaki görünüş soldan bakıştır; ISO A'da tersi
                val onRight = side != null && side.x0 >= front.x1
                val sideRight = if (thirdAngle) onRight else !onRight
                return DrawingResult(fv, tv, sv, sv != null && sideRight, false)
            }
        }
        // 2) Yan yana hizalı çift (ön + yan)
        var bestSide: Pair<Cand, Cand>? = null
        bestArea = -1L
        for (i in views.indices) for (j in i + 1 until views.size) {
            if (!alignedY(views[i], views[j])) continue
            val a = views[i].area.toLong() + views[j].area
            if (a > bestArea) { bestArea = a; bestSide = views[i] to views[j] }
        }
        if (bestSide != null) {
            val left = if (bestSide.first.x0 < bestSide.second.x0) bestSide.first else bestSide.second
            val right = if (left === bestSide.first) bestSide.second else bestSide.first
            val lv = build(left); val rv = build(right)
            if (lv != null && rv != null) return DrawingResult(lv, null, rv, false, true)
        }
        // 3) Tek görünüş: en büyük kapalı şekil
        val single = views.maxByOrNull { it.area } ?: return null
        val sv = build(single) ?: return null
        return DrawingResult(sv, null, null, false, false)
    }

    /** Adayın dış konturu + içindeki daire delikler (resim pikseli). */
    private fun viewLoops(c: Cand, thick: BooleanArray, w: Int, h: Int, stack: IntArray): List<Sketch.Loop>? {
        val ww = c.bw + 2; val wh = c.bh + 2
        // En büyük bileşen
        val lab = IntArray(ww * wh) { -1 }
        var bestLabel = -1; var bestSize = 0; var n = 0; var bestStart = -1
        for (i in c.mask.indices) {
            if (!c.mask[i] || lab[i] >= 0) continue
            val size = flood(c.mask, true, lab, n, i, ww, wh, stack)
            if (size > bestSize) { bestSize = size; bestLabel = n; bestStart = i }
            n++
        }
        if (bestLabel < 0) return null
        val part = BooleanArray(ww * wh) { lab[it] == bestLabel }
        val pts = trace(part, ww, wh, bestStart) ?: return null
        val ox = c.x0 - 1f; val oy = c.y0 - 1f
        for (i in 0 until pts.size / 2) { pts[2 * i] += ox; pts[2 * i + 1] += oy }
        val eps = max(1.2f, min(c.bw, c.bh) * 0.004f)
        val loops = ArrayList<Sketch.Loop>()
        loops.add(toLoop(pts, eps, 1f, allowCircle = true) ?: return null)

        // Delikler: görünüşün içinde, kalın çizgilerle çevrili daire biçimli boş bölgeler
        val inner = BooleanArray(ww * wh) {
            if (!part[it]) false else {
                val x = it % ww + c.x0 - 1; val y = it / ww + c.y0 - 1
                x in 0 until w && y in 0 until h && !thick[y * w + x]
            }
        }
        val il = IntArray(ww * wh) { -1 }
        val circles = ArrayList<FloatArray>()
        val minD = max(6, min(c.bw, c.bh) / 60)
        var m = 0
        for (i in inner.indices) {
            if (!inner[i] || il[i] >= 0) continue
            var x0 = ww; var y0 = wh; var x1 = -1; var y1 = -1
            val area = floodBox(inner, il, m++, i, ww, wh, stack) { x, y ->
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
            }
            val bw = (x1 - x0 + 1).toFloat(); val bh = (y1 - y0 + 1).toFloat()
            if (min(bw, bh) < minD) continue
            if (bw > 0.9f * c.bw && bh > 0.9f * c.bh) continue
            if (abs(bw - bh) / max(bw, bh) > 0.12f) continue
            val fill = area / (Math.PI.toFloat() * bw * bh / 4f)
            if (fill < 0.72f || fill > 1.08f) continue
            circles.add(floatArrayOf((x0 + x1 + 1) / 2f + ox, (y0 + y1 + 1) / 2f + oy, (bw + bh) / 4f + 1.5f))
        }
        val keep = circles.filter { ci ->
            circles.none { o -> o !== ci && o[2] < ci[2] && hypot(o[0] - ci[0], o[1] - ci[1]) < ci[2] }
        }
        for (ci in keep) loops.add(Sketch.Loop(circle = true, cx = ci[0], cy = ci[1], r = ci[2]))
        return loops
    }

    /** 4-komşu dolgu, her piksel için [visit] çağrılır; piksel sayısını döndürür. */
    private inline fun floodBox(
        m: BooleanArray, labels: IntArray, label: Int, start: Int, w: Int, h: Int, stack: IntArray,
        visit: (Int, Int) -> Unit,
    ): Int {
        var sp = 0
        stack[sp++] = start
        labels[start] = label
        var count = 0
        while (sp > 0) {
            val i = stack[--sp]
            count++
            val x = i % w; val y = i / w
            visit(x, y)
            if (x > 0 && m[i - 1] && labels[i - 1] < 0) { labels[i - 1] = label; stack[sp++] = i - 1 }
            if (x < w - 1 && m[i + 1] && labels[i + 1] < 0) { labels[i + 1] = label; stack[sp++] = i + 1 }
            if (y > 0 && m[i - w] && labels[i - w] < 0) { labels[i - w] = label; stack[sp++] = i - w }
            if (y < h - 1 && m[i + w] && labels[i + w] < 0) { labels[i + w] = label; stack[sp++] = i + w }
        }
        return count
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
