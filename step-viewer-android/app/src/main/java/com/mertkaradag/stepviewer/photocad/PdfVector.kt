package com.mertkaradag.stepviewer.photocad

import java.util.zip.Inflater
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Vektörel PDF teknik resimlerinden çizgi geometrisini okur (tek sayfalı PDF'ler).
 *
 * Tam bir PDF okuyucu değildir: dosyadaki içerik akışları açılır, yol işleçleri (m, l, c, re, h,
 * S...) ve grafik durumu (q, Q, cm, w, d) izlenir. Sonra "budama" yapılır: bir ucu boşta kalan
 * çizgiler (ölçü, uzatma, eksen, kılavuz çizgileri) tekrar tekrar atılır; geriye kapalı konturlar
 * (görünüş kenarları) kalır. Sonuç, sayfa görüntüsüyle karşılaştırılıp doğrulanarak kullanılır.
 */
object PdfVector {

    /**
     * @param segments budamadan sonra kalan çizgiler: x0, y0, x1, y1 (PDF noktası, y yukarı)
     * @param circles tam daireler: cx, cy, r (PDF noktası)
     */
    class Result(val segments: FloatArray, val circles: List<FloatArray>)

    fun extract(pdf: ByteArray): Result? {
        val content = contentStreams(pdf) ?: return null
        val circles = ArrayList<FloatArray>()
        val raw = parse(content, circles)
        if (raw.size < 4 * 8) return null
        val kept = prune(raw) ?: return null
        return Result(kept, circles)
    }

    // -- İçerik akışları ---------------------------------------------------------------

    private fun indexOf(data: ByteArray, pat: ByteArray, from: Int): Int {
        var i = from
        val last = data.size - pat.size
        while (i <= last) {
            var k = 0
            while (k < pat.size && data[i + k] == pat[k]) k++
            if (k == pat.size) return i
            i++
        }
        return -1
    }

    private fun lastIndexOf(data: ByteArray, pat: ByteArray, from: Int, stop: Int): Int {
        var i = min(from, data.size - pat.size)
        while (i >= stop) {
            var k = 0
            while (k < pat.size && data[i + k] == pat[k]) k++
            if (k == pat.size) return i
            i--
        }
        return -1
    }

    /** Sayfa içerik akışlarını dosya sırasıyla birleştirir (yazı tipi, resim, form akışları atlanır). */
    private fun contentStreams(d: ByteArray): ByteArray? {
        val kwStream = "stream".toByteArray()
        val kwEnd = "endstream".toByteArray()
        val kwObj = "obj".toByteArray()
        val out = java.io.ByteArrayOutputStream()
        var pos = 0
        var pages = 0
        // Birden çok sayfa varsa içerikler karışır: yalnızca tek sayfalı dosyalar
        var p = 0
        val kwPage = "/Type/Page".toByteArray()
        val kwPage2 = "/Type /Page".toByteArray()
        for (kw in arrayOf(kwPage, kwPage2)) {
            p = 0
            while (true) {
                p = indexOf(d, kw, p)
                if (p < 0) break
                val next = if (p + kw.size < d.size) d[p + kw.size].toInt().toChar() else ' '
                if (next != 's') pages++ // /Pages değil
                p += kw.size
            }
        }
        if (pages > 1) return null
        while (true) {
            val s = indexOf(d, kwStream, pos)
            if (s < 0) break
            // "endstream" içindeki "stream" değil
            if (s >= 3 && d[s - 1] == 'd'.code.toByte() && d[s - 2] == 'n'.code.toByte() && d[s - 3] == 'e'.code.toByte()) {
                pos = s + kwStream.size
                continue
            }
            var start = s + kwStream.size
            if (start < d.size && d[start] == '\r'.code.toByte()) start++
            if (start < d.size && d[start] == '\n'.code.toByte()) start++
            val end = indexOf(d, kwEnd, start)
            if (end < 0) break
            val objAt = lastIndexOf(d, kwObj, s, max(0, s - 600))
            val hdr = if (objAt >= 0) String(d, objAt, s - objAt, Charsets.ISO_8859_1) else ""
            pos = end + kwEnd.size
            if (hdr.contains("/Subtype") || hdr.contains("/Length1") || hdr.contains("/Type")) continue
            val bytes: ByteArray? = when {
                hdr.contains("FlateDecode") -> inflate(d, start, end - start)
                hdr.contains("/Filter") -> null
                else -> d.copyOfRange(start, end)
            }
            if (bytes == null) continue
            out.write(bytes)
            out.write('\n'.code)
        }
        return if (out.size() > 0) out.toByteArray() else null
    }

    private fun inflate(d: ByteArray, off: Int, len: Int): ByteArray? = try {
        val inf = Inflater()
        inf.setInput(d, off, len)
        val out = java.io.ByteArrayOutputStream(len * 4)
        val buf = ByteArray(16384)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            out.write(buf, 0, n)
            if (out.size() > 64 * 1024 * 1024) break
        }
        inf.end()
        out.toByteArray()
    } catch (_: Exception) {
        null
    }

    // -- İçerik çözümleme ----------------------------------------------------------------

    private class FloatList {
        var a = FloatArray(4096)
        var n = 0
        fun add(v: Float) {
            if (n == a.size) a = a.copyOf(n * 2)
            a[n++] = v
        }
    }

    private fun isSpace(c: Int) = c == 32 || c == 10 || c == 13 || c == 9 || c == 12 || c == 0
    private fun isDelim(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
        c == '['.code || c == ']'.code || c == '/'.code || c == '%'.code || c == '{'.code || c == '}'.code

    /** Çizilen (stroke) kesiksiz çizgiler: x0, y0, x1, y1 dörtlüleri. */
    private fun parse(c: ByteArray, circles: ArrayList<FloatArray>): FloatArray {
        val segs = FloatList()
        val cur = FloatList()
        // Geçerli yoldaki alt yolların cur içindeki başlangıçları (kapalı daireleri bulmak için)
        val subs = ArrayList<Int>()
        val st = FloatArray(64)
        var sn = 0
        var ctm = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
        val stack = ArrayList<FloatArray>()
        val dashStack = ArrayList<Boolean>()
        var dash = false
        var px = 0f; var py = 0f; var sx = 0f; var sy = 0f
        var havePt = false
        var arrayDepth = 0
        var arrayCount = 0
        var lastArrayCount = 0

        fun tx(x: Float, y: Float) = ctm[0] * x + ctm[2] * y + ctm[4]
        fun ty(x: Float, y: Float) = ctm[1] * x + ctm[3] * y + ctm[5]
        fun line(x: Float, y: Float) {
            if (havePt) { cur.add(px); cur.add(py); cur.add(x); cur.add(y) }
            px = x; py = y; havePt = true
        }
        fun curve(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            val x0 = px; val y0 = py
            for (i in 1..8) {
                val u = i / 8f; val v = 1 - u
                line(v * v * v * x0 + 3 * v * v * u * x1 + 3 * v * u * u * x2 + u * u * u * x3,
                    v * v * v * y0 + 3 * v * v * u * y1 + 3 * v * u * u * y2 + u * u * u * y3)
            }
        }
        fun arg(k: Int): Float = if (sn - k >= 0) st[sn - k] else 0f
        fun stroke() {
            if (!dash) {
                for (k in 0 until cur.n) segs.add(cur.a[k])
                subs.add(cur.n)
                for (q in 0 until subs.size - 1) fitCircle(cur.a, subs[q], subs[q + 1])?.let { circles.add(it) }
            }
            cur.n = 0; subs.clear(); havePt = false
        }

        var i = 0
        val n = c.size
        val sb = StringBuilder()
        while (i < n) {
            val ch = c[i].toInt() and 255
            when {
                isSpace(ch) -> i++
                ch == '%'.code -> { while (i < n && c[i] != '\n'.code.toByte() && c[i] != '\r'.code.toByte()) i++ }
                ch == '('.code -> {
                    var depth = 1; i++
                    while (i < n && depth > 0) {
                        val b = c[i].toInt() and 255
                        if (b == '\\'.code) i++ else if (b == '('.code) depth++ else if (b == ')'.code) depth--
                        i++
                    }
                }
                ch == '<'.code -> {
                    if (i + 1 < n && c[i + 1] == '<'.code.toByte()) i += 2
                    else { while (i < n && c[i] != '>'.code.toByte()) i++; i++ }
                }
                ch == '>'.code -> i++
                ch == '['.code -> { arrayDepth++; arrayCount = 0; i++ }
                ch == ']'.code -> { if (arrayDepth > 0) arrayDepth--; lastArrayCount = arrayCount; i++ }
                ch == '/'.code -> { i++; while (i < n && !isSpace(c[i].toInt() and 255) && !isDelim(c[i].toInt() and 255)) i++ }
                ch == '{'.code || ch == '}'.code || ch == ')'.code -> i++
                (ch >= '0'.code && ch <= '9'.code) || ch == '-'.code || ch == '+'.code || ch == '.'.code -> {
                    sb.setLength(0)
                    while (i < n) {
                        val b = c[i].toInt() and 255
                        if ((b >= '0'.code && b <= '9'.code) || b == '-'.code || b == '+'.code || b == '.'.code) { sb.append(b.toChar()); i++ } else break
                    }
                    if (arrayDepth > 0) arrayCount++
                    else {
                        val v = sb.toString().toFloatOrNull() ?: 0f
                        if (sn == st.size) { System.arraycopy(st, 1, st, 0, sn - 1); sn-- }
                        st[sn++] = v
                    }
                }
                else -> {
                    sb.setLength(0)
                    while (i < n) {
                        val b = c[i].toInt() and 255
                        if (isSpace(b) || isDelim(b)) break
                        sb.append(b.toChar()); i++
                    }
                    if (sb.isEmpty()) { i++; continue }
                    when (sb.toString()) {
                        "q" -> { stack.add(ctm.copyOf()); dashStack.add(dash) }
                        "Q" -> if (stack.isNotEmpty()) {
                            ctm = stack.removeAt(stack.size - 1); dash = dashStack.removeAt(dashStack.size - 1)
                        }
                        "cm" -> if (sn >= 6) {
                            val a = arg(6); val b = arg(5); val cc = arg(4); val d = arg(3); val e = arg(2); val f = arg(1)
                            ctm = floatArrayOf(
                                a * ctm[0] + b * ctm[2], a * ctm[1] + b * ctm[3],
                                cc * ctm[0] + d * ctm[2], cc * ctm[1] + d * ctm[3],
                                e * ctm[0] + f * ctm[2] + ctm[4], e * ctm[1] + f * ctm[3] + ctm[5])
                        }
                        "d" -> dash = lastArrayCount > 0
                        "m" -> if (sn >= 2) {
                            px = tx(arg(2), arg(1)); py = ty(arg(2), arg(1)); sx = px; sy = py; havePt = true
                            subs.add(cur.n)
                        }
                        "l" -> if (sn >= 2) line(tx(arg(2), arg(1)), ty(arg(2), arg(1)))
                        "c" -> if (sn >= 6 && havePt) curve(tx(arg(6), arg(5)), ty(arg(6), arg(5)),
                            tx(arg(4), arg(3)), ty(arg(4), arg(3)), tx(arg(2), arg(1)), ty(arg(2), arg(1)))
                        "v" -> if (sn >= 4 && havePt) curve(px, py, tx(arg(4), arg(3)), ty(arg(4), arg(3)),
                            tx(arg(2), arg(1)), ty(arg(2), arg(1)))
                        "y" -> if (sn >= 4 && havePt) {
                            val ex = tx(arg(2), arg(1)); val ey = ty(arg(2), arg(1))
                            curve(tx(arg(4), arg(3)), ty(arg(4), arg(3)), ex, ey, ex, ey)
                        }
                        "re" -> if (sn >= 4) {
                            val x = arg(4); val y = arg(3); val w = arg(2); val h = arg(1)
                            px = tx(x, y); py = ty(x, y); sx = px; sy = py; havePt = true
                            subs.add(cur.n)
                            line(tx(x + w, y), ty(x + w, y)); line(tx(x + w, y + h), ty(x + w, y + h))
                            line(tx(x, y + h), ty(x, y + h)); line(sx, sy)
                        }
                        "h" -> if (havePt) line(sx, sy)
                        "S", "B", "B*" -> stroke()
                        "s", "b", "b*" -> {
                            if (havePt) line(sx, sy)
                            stroke()
                        }
                        "f", "F", "f*", "n" -> { cur.n = 0; subs.clear(); havePt = false }
                    }
                    sn = 0
                }
            }
        }
        return segs.a.copyOf(segs.n)
    }

    // -- Budama ---------------------------------------------------------------------------

    private fun prune(raw: FloatArray): FloatArray? {
        val tol = 0.4f
        // Uç noktaları düğümlere indir (ızgara ile yakın noktalar birleştirilir)
        val nodeX = FloatList(); val nodeY = FloatList()
        val grid = HashMap<Long, IntArray>()
        fun key(kx: Int, ky: Int) = (kx.toLong() shl 32) xor (ky.toLong() and 0xffffffffL)
        fun node(x: Float, y: Float): Int {
            val kx = floor(x / tol).toInt(); val ky = floor(y / tol).toInt()
            for (dx in -1..1) for (dy in -1..1) {
                val list = grid[key(kx + dx, ky + dy)] ?: continue
                for (id in list) if (hypot(nodeX.a[id] - x, nodeY.a[id] - y) <= tol) return id
            }
            val id = nodeX.n
            nodeX.add(x); nodeY.add(y)
            val k = key(kx, ky)
            val old = grid[k]
            grid[k] = if (old == null) intArrayOf(id) else old + id
            return id
        }
        val count = raw.size / 4
        if (count > 400_000) return null
        val seen = HashSet<Long>()
        var sa = IntArray(count); var sb = IntArray(count)
        var n = 0
        for (i in 0 until count) {
            val a = node(raw[4 * i], raw[4 * i + 1]); val b = node(raw[4 * i + 2], raw[4 * i + 3])
            if (a == b) continue
            val k = (min(a, b).toLong() shl 32) or max(a, b).toLong()
            if (!seen.add(k)) continue
            sa[n] = a; sb[n] = b; n++
        }
        sa = sa.copyOf(n); sb = sb.copyOf(n)
        val nodes = nodeX.n
        val deg = IntArray(nodes)
        for (i in 0 until n) { deg[sa[i]]++; deg[sb[i]]++ }

        // Çizgilerin ızgara dizini (T birleşimleri için)
        val cell = 10f
        val segGrid = HashMap<Long, IntArray>()
        val segGridN = HashMap<Long, Int>()
        for (i in 0 until n) {
            val ax = nodeX.a[sa[i]]; val ay = nodeY.a[sa[i]]; val bx = nodeX.a[sb[i]]; val by = nodeY.a[sb[i]]
            val x0 = floor(min(ax, bx) / cell).toInt(); val x1 = floor(max(ax, bx) / cell).toInt()
            val y0 = floor(min(ay, by) / cell).toInt(); val y1 = floor(max(ay, by) / cell).toInt()
            if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) > 4000) continue // çok uzun eğik çizgi: dizine alma
            for (gx in x0..x1) for (gy in y0..y1) {
                val k = key(gx, gy)
                val cnt = segGridN[k] ?: 0
                var arr = segGrid[k]
                if (arr == null) { arr = IntArray(4); segGrid[k] = arr }
                else if (cnt == arr.size) { arr = arr.copyOf(cnt * 2); segGrid[k] = arr }
                arr[cnt] = i
                segGridN[k] = cnt + 1
            }
        }
        fun dist(px: Float, py: Float, s: Int): Float {
            val ax = nodeX.a[sa[s]]; val ay = nodeY.a[sa[s]]
            val vx = nodeX.a[sb[s]] - ax; val vy = nodeY.a[sb[s]] - ay
            val l2 = vx * vx + vy * vy
            val t = if (l2 <= 0f) 0f else (((px - ax) * vx + (py - ay) * vy) / l2).coerceIn(0f, 1f)
            return hypot(ax + vx * t - px, ay + vy * t - py)
        }
        // Düğümün, ucu olmadığı halde üzerine değdiği çizgiler (ilk istekte hesaplanır)
        val anchors = arrayOfNulls<IntArray>(nodes)
        val empty = IntArray(0)
        fun anchorsOf(nd: Int): IntArray {
            anchors[nd]?.let { return it }
            val x = nodeX.a[nd]; val y = nodeY.a[nd]
            val k = key(floor(x / cell).toInt(), floor(y / cell).toInt())
            val arr = segGrid[k]
            var res = empty
            if (arr != null) {
                val cnt = segGridN[k] ?: 0
                val tmp = IntArray(cnt); var m = 0
                for (q in 0 until cnt) {
                    val s = arr[q]
                    if (sa[s] == nd || sb[s] == nd) continue
                    if (dist(x, y, s) <= tol) tmp[m++] = s
                }
                res = tmp.copyOf(m)
            }
            anchors[nd] = res
            return res
        }
        val alive = BooleanArray(n) { true }
        fun held(nd: Int): Boolean {
            if (deg[nd] >= 2) return true
            for (s in anchorsOf(nd)) if (alive[s]) return true
            return false
        }
        var changed = true
        var passes = 0
        while (changed && passes < 600) {
            changed = false; passes++
            for (i in 0 until n) {
                if (!alive[i]) continue
                if (held(sa[i]) && held(sb[i])) continue
                alive[i] = false; deg[sa[i]]--; deg[sb[i]]--; changed = true
            }
        }
        var kept = 0
        for (i in 0 until n) if (alive[i]) kept++
        if (kept < 3) return null
        val out = FloatArray(kept * 4)
        var o = 0
        for (i in 0 until n) if (alive[i]) {
            out[o++] = nodeX.a[sa[i]]; out[o++] = nodeY.a[sa[i]]; out[o++] = nodeX.a[sb[i]]; out[o++] = nodeY.a[sb[i]]
        }

        return out
    }

    /**
     * cur[from, to) aralığındaki alt yol (4 float = bir çizgi) kapalıysa ve daireye uyuyorsa
     * (cx, cy, r) döndürür.
     */
    private fun fitCircle(a: FloatArray, from: Int, to: Int): FloatArray? {
        val n = (to - from) / 4
        if (n < 10) return null
        if (hypot(a[from] - a[to - 2], a[from + 1] - a[to - 1]) > 0.4f) return null
        var mx = 0.0; var my = 0.0
        for (i in 0 until n) { mx += a[from + 4 * i]; my += a[from + 4 * i + 1] }
        mx /= n; my /= n
        var suu = 0.0; var svv = 0.0; var suv = 0.0; var suuu = 0.0; var svvv = 0.0; var suvv = 0.0; var svuu = 0.0
        for (i in 0 until n) {
            val u = a[from + 4 * i] - mx; val v = a[from + 4 * i + 1] - my
            suu += u * u; svv += v * v; suv += u * v
            suuu += u * u * u; svvv += v * v * v; suvv += u * v * v; svuu += v * u * u
        }
        val det = suu * svv - suv * suv
        if (abs(det) < 1e-9) return null
        val p = 0.5 * (suuu + suvv); val q = 0.5 * (svvv + svuu)
        val uc = (p * svv - q * suv) / det; val vc = (q * suu - p * suv) / det
        val r = sqrt(uc * uc + vc * vc + (suu + svv) / n)
        if (r < 0.5) return null
        var maxDev = 0.0
        for (i in 0 until n) {
            val d = hypot(a[from + 4 * i] - mx - uc, a[from + 4 * i + 1] - my - vc)
            maxDev = max(maxDev, abs(d - r))
        }
        return if (maxDev <= 0.04 * r + 0.05) floatArrayOf((mx + uc).toFloat(), (my + vc).toFloat(), r.toFloat()) else null
    }
}
