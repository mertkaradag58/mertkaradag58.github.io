package com.mertkaradag.stepviewer

import android.opengl.Matrix
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Ekrana dokunulan noktadaki kenarı veya yüzeyi bulur.
 *
 * 1. Dokunma noktasından modele bir ışın gönderilir ve en yakın görünür üçgen bulunur.
 * 2. Ekranda dokunma noktasına yeterince yakın kenar segmentleri aday olur;
 *    yüzeyin arkasında kalan (gizli) adaylar elenir.
 * 3. Kenar bulunduysa kenar, yoksa ışının çarptığı yüzey seçilir.
 *
 * Gizlenmiş parçalar ve kesit düzleminin kestiği kısımlar seçilmez.
 */
object Picker {

    data class Hit(val kind: Int, val id: Int)

    fun pick(
        mesh: StepMesh,
        mvp: FloatArray,
        viewWidth: Int,
        viewHeight: Int,
        x: Float,
        y: Float,
        thresholdPx: Float,
        partVisible: BooleanArray?,
        clip: FloatArray?,
    ): Hit? {
        // --- Işın: dokunma noktasını yakın ve uzak düzleme geri izdüşür ---
        val inv = FloatArray(16)
        if (!Matrix.invertM(inv, 0, mvp, 0)) return null
        val ndcX = 2f * x / viewWidth - 1f
        val ndcY = 1f - 2f * y / viewHeight
        val near = unproject(inv, ndcX, ndcY, -1f)
        val far = unproject(inv, ndcX, ndcY, 1f)
        val dir = floatArrayOf(far[0] - near[0], far[1] - near[1], far[2] - near[2])
        val dirLen = sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2])
        if (dirLen <= 0f) return null
        dir[0] /= dirLen; dir[1] /= dirLen; dir[2] /= dirLen

        val surface = raycastSurface(mesh, near, dir, partVisible, clip, null)

        val b = mesh.bbox
        val diag = sqrt(
            (b[3] - b[0]) * (b[3] - b[0]) + (b[4] - b[1]) * (b[4] - b[1]) + (b[5] - b[2]) * (b[5] - b[2])
        )
        val depthTolerance = max(diag * 0.01f, 1e-6f)

        // --- Kenar segmentleri ---
        val e = mesh.edges
        val segCount = e.size / 6
        val sa = FloatArray(2)
        val sb = FloatArray(2)
        var bestSeg = -1
        var bestDist = thresholdPx
        var bestDepth = Float.MAX_VALUE

        for (s in 0 until segCount) {
            val o = s * 6
            if (!toScreen(mvp, e[o], e[o + 1], e[o + 2], viewWidth, viewHeight, sa)) continue
            if (!toScreen(mvp, e[o + 3], e[o + 4], e[o + 5], viewWidth, viewHeight, sb)) continue

            val dx = sb[0] - sa[0]
            val dy = sb[1] - sa[1]
            val len2 = dx * dx + dy * dy
            var t = if (len2 > 0f) ((x - sa[0]) * dx + (y - sa[1]) * dy) / len2 else 0f
            t = t.coerceIn(0f, 1f)
            val px = sa[0] + t * dx - x
            val py = sa[1] + t * dy - y
            val dist = sqrt(px * px + py * py)
            if (dist > bestDist + 0.5f) continue

            val wx = e[o] + t * (e[o + 3] - e[o])
            val wy = e[o + 1] + t * (e[o + 4] - e[o + 1])
            val wz = e[o + 2] + t * (e[o + 5] - e[o + 2])
            if (clip != null && clip[0] * wx + clip[1] * wy + clip[2] * wz + clip[3] < 0f) continue
            val depth = (wx - near[0]) * dir[0] + (wy - near[1]) * dir[1] + (wz - near[2]) * dir[2]
            if (surface != null && depth > surface.second + depthTolerance) continue
            if (partVisible != null) {
                val part = mesh.partOfEdge(mesh.edgeOfSegment(s))
                if (part >= 0 && !partVisible[part]) continue
            }

            if (dist < bestDist - 0.5f || depth < bestDepth) {
                bestDist = dist
                bestDepth = depth
                bestSeg = s
            }
        }
        if (bestSeg >= 0) {
            val edge = mesh.edgeOfSegment(bestSeg)
            if (edge >= 0) return Hit(StepNative.KIND_EDGE, edge)
        }
        if (surface != null) {
            val face = mesh.faceOfTriangle(surface.first)
            if (face >= 0) return Hit(StepNative.KIND_FACE, face)
        }
        return null
    }

    /**
     * Dokunulan parçanın indeksi; ışın bir yüzeye çarpmazsa yakındaki kenarın parçası,
     * hiçbiri yoksa -1.
     */
    fun pickPart(
        mesh: StepMesh,
        mvp: FloatArray,
        viewWidth: Int,
        viewHeight: Int,
        x: Float,
        y: Float,
        thresholdPx: Float,
        partVisible: BooleanArray?,
        clip: FloatArray?,
        partOffsets: FloatArray? = null,
    ): Int {
        val ray = ray(mvp, viewWidth, viewHeight, x, y) ?: return -1
        val surface = raycastSurface(mesh, ray[0], ray[1], partVisible, clip, partOffsets)
        if (surface != null) {
            val face = mesh.faceOfTriangle(surface.first)
            if (face >= 0) return mesh.partOfFace(face)
        }
        // Patlatılmış görünümde kenar konumları kaydırılmış olduğundan kenara göre seçilmez
        if (partOffsets != null) return -1
        // Tel kafes görünümü veya yüzeysiz modeller: kenara göre
        val hit = pick(mesh, mvp, viewWidth, viewHeight, x, y, thresholdPx, partVisible, clip) ?: return -1
        return if (hit.kind == StepNative.KIND_EDGE) mesh.partOfEdge(hit.id) else mesh.partOfFace(hit.id)
    }

    /**
     * Dokunulan yüzey noktası (dünya koordinatı); boşluğa dokunulduysa null.
     * Çift dokunma ile dönme merkezini seçmek için.
     */
    fun pickPoint(
        mesh: StepMesh,
        mvp: FloatArray,
        viewWidth: Int,
        viewHeight: Int,
        x: Float,
        y: Float,
        partVisible: BooleanArray?,
        clip: FloatArray?,
        partOffsets: FloatArray? = null,
    ): FloatArray? {
        val ray = ray(mvp, viewWidth, viewHeight, x, y) ?: return null
        val o = ray[0]
        val d = ray[1]
        val hit = raycastSurface(mesh, o, d, partVisible, clip, partOffsets) ?: return null
        val t = hit.second
        return floatArrayOf(o[0] + d[0] * t, o[1] + d[1] * t, o[2] + d[2] * t)
    }

    /** Dokunulan yüzeyin kimliği (kenarlar yok sayılır); boşluk ise -1. */
    fun pickFace(
        mesh: StepMesh,
        mvp: FloatArray,
        viewWidth: Int,
        viewHeight: Int,
        x: Float,
        y: Float,
        partVisible: BooleanArray?,
        clip: FloatArray?,
    ): Int {
        val ray = ray(mvp, viewWidth, viewHeight, x, y) ?: return -1
        val hit = raycastSurface(mesh, ray[0], ray[1], partVisible, clip, null) ?: return -1
        return mesh.faceOfTriangle(hit.first)
    }

    /** Nokta yakalama türü. */
    enum class SnapKind { VERTEX, MIDPOINT, CENTER, ON_EDGE, ON_SURFACE }

    /** Yakalanan nokta (dünya koordinatı, mm), türü ve varsa ait olduğu kenar. */
    class Snap(val point: FloatArray, val kind: SnapKind, val edge: Int = -1)

    /**
     * Nokta ölçümü için dokunulan noktayı bulur. Sırası:
     * 1. Yakındaki kenar uçları (köşeler) ve kenar orta noktaları,
     * 2. Dokunulan kenar çember/yay ise merkezi ([circleCenter] verirse),
     * 3. Kenarın üzerindeki en yakın nokta,
     * 4. Yüzey üzerindeki nokta (üçgenleme üzerinde, yaklaşık).
     * [snap] false ise yalnız 3 ve 4 uygulanır.
     */
    fun snapPoint(
        mesh: StepMesh,
        mvp: FloatArray,
        viewWidth: Int,
        viewHeight: Int,
        x: Float,
        y: Float,
        thresholdPx: Float,
        partVisible: BooleanArray?,
        clip: FloatArray?,
        snap: Boolean,
        circleCenter: (edge: Int) -> FloatArray?,
    ): Snap? {
        val r = ray(mvp, viewWidth, viewHeight, x, y) ?: return null
        val o = r[0]
        val d = r[1]
        val surface = raycastSurface(mesh, o, d, partVisible, clip, null)
        val b = mesh.bbox
        val diag = sqrt(
            (b[3] - b[0]) * (b[3] - b[0]) + (b[4] - b[1]) * (b[4] - b[1]) + (b[5] - b[2]) * (b[5] - b[2])
        )
        val depthTol = max(diag * 0.01f, 1e-6f)
        val sp = FloatArray(2)

        fun visible(px: Float, py: Float, pz: Float): Boolean {
            if (clip != null && clip[0] * px + clip[1] * py + clip[2] * pz + clip[3] < 0f) return false
            // Noktanın önünde bir yüzey var mı: noktaya doğru ışın gönder
            if (!toScreen(mvp, px, py, pz, viewWidth, viewHeight, sp)) return false
            val rr = ray(mvp, viewWidth, viewHeight, sp[0], sp[1]) ?: return false
            val depth = (px - rr[0][0]) * rr[1][0] + (py - rr[0][1]) * rr[1][1] + (pz - rr[0][2]) * rr[1][2]
            val hit = raycastSurface(mesh, rr[0], rr[1], partVisible, clip, null) ?: return true
            return depth <= hit.second + depthTol
        }

        if (snap) {
            // Köşeler ve orta noktalar: ekranda dokunuşa en yakın aday
            data class Cand(val p: FloatArray, val kind: SnapKind, val edge: Int, val dist: Float)
            val cands = ArrayList<Cand>()
            val e = mesh.edges
            for (edge in 0 until mesh.edgeCount) {
                if (partVisible != null) {
                    val part = mesh.partOfEdge(edge)
                    if (part >= 0 && !partVisible[part]) continue
                }
                val s0 = mesh.edgeOffsets[edge]
                val s1 = mesh.edgeOffsets[edge + 1]
                if (s1 <= s0) continue
                val first = s0 * 6
                val last = (s1 - 1) * 6 + 3
                for (idx in intArrayOf(first, last)) {
                    if (!toScreen(mvp, e[idx], e[idx + 1], e[idx + 2], viewWidth, viewHeight, sp)) continue
                    val dist = hypot(sp[0] - x, sp[1] - y)
                    if (dist <= thresholdPx) {
                        cands += Cand(floatArrayOf(e[idx], e[idx + 1], e[idx + 2]), SnapKind.VERTEX, edge, dist)
                    }
                }
                // Kapalı olmayan kenarın orta noktası (yay uzunluğuna göre)
                val closed = e[first] == e[last] && e[first + 1] == e[last + 1] && e[first + 2] == e[last + 2]
                if (!closed) {
                    val mid = polylineMidpoint(e, s0, s1)
                    if (toScreen(mvp, mid[0], mid[1], mid[2], viewWidth, viewHeight, sp)) {
                        val dist = hypot(sp[0] - x, sp[1] - y)
                        // Orta nokta köşeye göre biraz daha az öncelikli
                        if (dist <= thresholdPx) cands += Cand(mid, SnapKind.MIDPOINT, edge, dist + thresholdPx * 0.15f)
                    }
                }
            }
            cands.sortBy { it.dist }
            for (c in cands.take(8)) {
                if (visible(c.p[0], c.p[1], c.p[2])) return Snap(c.p, c.kind, c.edge)
            }
        }

        // Dokunulan kenar
        val hit = pick(mesh, mvp, viewWidth, viewHeight, x, y, thresholdPx, partVisible, clip)
        if (hit != null && hit.kind == StepNative.KIND_EDGE) {
            if (snap) {
                val center = circleCenter(hit.id)
                if (center != null) return Snap(center, SnapKind.CENTER, hit.id)
            }
            val p = closestOnEdge(mesh, hit.id, mvp, viewWidth, viewHeight, x, y)
            if (p != null) return Snap(p, SnapKind.ON_EDGE, hit.id)
        }
        if (surface != null) {
            val t = surface.second
            return Snap(floatArrayOf(o[0] + d[0] * t, o[1] + d[1] * t, o[2] + d[2] * t), SnapKind.ON_SURFACE)
        }
        return null
    }

    private fun hypot(a: Float, b: Float): Float = sqrt(a * a + b * b)

    /** Kenar çizgisinin (segment s0..s1) uzunluk olarak tam ortasındaki nokta. */
    private fun polylineMidpoint(e: FloatArray, s0: Int, s1: Int): FloatArray {
        var total = 0f
        for (s in s0 until s1) total += segLen(e, s)
        var half = total * 0.5f
        for (s in s0 until s1) {
            val l = segLen(e, s)
            if (half <= l && l > 0f) {
                val t = half / l
                val o = s * 6
                return floatArrayOf(
                    e[o] + t * (e[o + 3] - e[o]),
                    e[o + 1] + t * (e[o + 4] - e[o + 1]),
                    e[o + 2] + t * (e[o + 5] - e[o + 2]),
                )
            }
            half -= l
        }
        val o = (s1 - 1) * 6
        return floatArrayOf(e[o + 3], e[o + 4], e[o + 5])
    }

    private fun segLen(e: FloatArray, s: Int): Float {
        val o = s * 6
        val dx = e[o + 3] - e[o]
        val dy = e[o + 4] - e[o + 1]
        val dz = e[o + 5] - e[o + 2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Kenar üzerinde, ekranda dokunma noktasına en yakın nokta. */
    private fun closestOnEdge(
        mesh: StepMesh, edge: Int, mvp: FloatArray, w: Int, h: Int, x: Float, y: Float,
    ): FloatArray? {
        val e = mesh.edges
        val sa = FloatArray(2)
        val sb = FloatArray(2)
        var best: FloatArray? = null
        var bestDist = Float.MAX_VALUE
        for (s in mesh.edgeOffsets[edge] until mesh.edgeOffsets[edge + 1]) {
            val o = s * 6
            if (!toScreen(mvp, e[o], e[o + 1], e[o + 2], w, h, sa)) continue
            if (!toScreen(mvp, e[o + 3], e[o + 4], e[o + 5], w, h, sb)) continue
            val dx = sb[0] - sa[0]
            val dy = sb[1] - sa[1]
            val len2 = dx * dx + dy * dy
            val t = (if (len2 > 0f) ((x - sa[0]) * dx + (y - sa[1]) * dy) / len2 else 0f).coerceIn(0f, 1f)
            val dist = hypot(sa[0] + t * dx - x, sa[1] + t * dy - y)
            if (dist < bestDist) {
                bestDist = dist
                best = floatArrayOf(
                    e[o] + t * (e[o + 3] - e[o]),
                    e[o + 1] + t * (e[o + 4] - e[o + 1]),
                    e[o + 2] + t * (e[o + 5] - e[o + 2]),
                )
            }
        }
        return best
    }

    /** Dokunma noktasından çıkan ışın: [başlangıç, birim yön] veya null. */
    private fun ray(mvp: FloatArray, w: Int, h: Int, x: Float, y: Float): Array<FloatArray>? {
        val inv = FloatArray(16)
        if (!Matrix.invertM(inv, 0, mvp, 0)) return null
        val ndcX = 2f * x / w - 1f
        val ndcY = 1f - 2f * y / h
        val near = unproject(inv, ndcX, ndcY, -1f)
        val far = unproject(inv, ndcX, ndcY, 1f)
        val dir = floatArrayOf(far[0] - near[0], far[1] - near[1], far[2] - near[2])
        val len = sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2])
        if (len <= 0f) return null
        dir[0] /= len; dir[1] /= len; dir[2] /= len
        return arrayOf(near, dir)
    }

    private fun unproject(inv: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        val v = floatArrayOf(x, y, z, 1f)
        val r = FloatArray(4)
        Matrix.multiplyMV(r, 0, inv, 0, v, 0)
        return floatArrayOf(r[0] / r[3], r[1] / r[3], r[2] / r[3])
    }

    /** Dünya koordinatını ekran pikseline çevirir; kameranın arkasındaysa false. */
    private fun toScreen(m: FloatArray, x: Float, y: Float, z: Float, w: Int, h: Int, out: FloatArray): Boolean {
        val cx = m[0] * x + m[4] * y + m[8] * z + m[12]
        val cy = m[1] * x + m[5] * y + m[9] * z + m[13]
        val cw = m[3] * x + m[7] * y + m[11] * z + m[15]
        if (cw <= 1e-6f) return false
        out[0] = (cx / cw + 1f) * 0.5f * w
        out[1] = (1f - cy / cw) * 0.5f * h
        return true
    }

    /**
     * En yakın görünür üçgen: (üçgen indeksi, uzaklık) veya null. Möller–Trumbore.
     * [partOffsets]: patlatılmış görünümde parça başına kaydırma (x, y, z); ışın ters kaydırılır.
     */
    private fun raycastSurface(
        mesh: StepMesh, o: FloatArray, d: FloatArray, partVisible: BooleanArray?, clip: FloatArray?,
        partOffsets: FloatArray?,
    ): Pair<Int, Float>? {
        var best = Float.MAX_VALUE
        var bestTri = -1
        val parts = mesh.partCount
        val local = FloatArray(3)
        for (p in 0 until parts) {
            if (partVisible != null && !partVisible[p]) continue
            val range = mesh.partTriangleRange(p)
            if (partOffsets != null && partOffsets.size >= parts * 3) {
                local[0] = o[0] - partOffsets[p * 3]
                local[1] = o[1] - partOffsets[p * 3 + 1]
                local[2] = o[2] - partOffsets[p * 3 + 2]
            } else {
                local[0] = o[0]; local[1] = o[1]; local[2] = o[2]
            }
            for (tri in range) {
                val t = intersect(mesh, tri, local, d)
                if (t <= 0f || t >= best) continue
                if (clip != null) {
                    val hx = o[0] + d[0] * t; val hy = o[1] + d[1] * t; val hz = o[2] + d[2] * t
                    if (clip[0] * hx + clip[1] * hy + clip[2] * hz + clip[3] < 0f) continue
                }
                best = t
                bestTri = tri
            }
        }
        return if (bestTri >= 0) bestTri to best else null
    }

    /** Işın-üçgen kesişimi; kesişme yoksa -1. */
    private fun intersect(mesh: StepMesh, tri: Int, o: FloatArray, d: FloatArray): Float {
        val v = mesh.vertices
        val idx = mesh.indices
        val k = StepMesh.FLOATS_PER_VERTEX
        val a = idx[tri * 3] * k
        val b = idx[tri * 3 + 1] * k
        val c = idx[tri * 3 + 2] * k
        val e1x = v[b] - v[a]; val e1y = v[b + 1] - v[a + 1]; val e1z = v[b + 2] - v[a + 2]
        val e2x = v[c] - v[a]; val e2y = v[c + 1] - v[a + 1]; val e2z = v[c + 2] - v[a + 2]
        val px = d[1] * e2z - d[2] * e2y
        val py = d[2] * e2x - d[0] * e2z
        val pz = d[0] * e2y - d[1] * e2x
        val det = e1x * px + e1y * py + e1z * pz
        if (det > -1e-12f && det < 1e-12f) return -1f
        val invDet = 1f / det
        val tx = o[0] - v[a]; val ty = o[1] - v[a + 1]; val tz = o[2] - v[a + 2]
        val u = (tx * px + ty * py + tz * pz) * invDet
        if (u < 0f || u > 1f) return -1f
        val qx = ty * e1z - tz * e1y
        val qy = tz * e1x - tx * e1z
        val qz = tx * e1y - ty * e1x
        val w = (d[0] * qx + d[1] * qy + d[2] * qz) * invDet
        if (w < 0f || u + w > 1f) return -1f
        return (e2x * qx + e2y * qy + e2z * qz) * invDet
    }
}
