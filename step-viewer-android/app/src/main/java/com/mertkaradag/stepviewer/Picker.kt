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

        val surface = raycastSurface(mesh, near, dir, partVisible, clip)

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

    /** En yakın görünür üçgen: (üçgen indeksi, uzaklık) veya null. Möller–Trumbore. */
    private fun raycastSurface(
        mesh: StepMesh, o: FloatArray, d: FloatArray, partVisible: BooleanArray?, clip: FloatArray?,
    ): Pair<Int, Float>? {
        var best = Float.MAX_VALUE
        var bestTri = -1
        val parts = mesh.partCount
        for (p in 0 until parts) {
            if (partVisible != null && !partVisible[p]) continue
            val range = mesh.partTriangleRange(p)
            for (tri in range) {
                val t = intersect(mesh, tri, o, d)
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
