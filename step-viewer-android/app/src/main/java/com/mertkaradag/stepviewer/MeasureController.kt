package com.mertkaradag.stepviewer

import android.content.Context
import kotlin.math.PI
import kotlin.math.abs

/**
 * Ölçüm seçimleri (en çok iki kenar/yüzey), panel metni ve çizim katmanı.
 * Yerel çağrılar [runInBackground] ile arka planda yapılır, sonuç [onResult] ile ana iş
 * parçacığında bildirilir.
 */
class MeasureController(
    private val context: Context,
    private val runInBackground: (() -> Unit) -> Unit,
    private val runOnUi: (() -> Unit) -> Unit,
    private val onResult: (text: String, overlay: ModelRenderer.Overlay) -> Unit,
) {
    private var mesh: StepMesh? = null
    private var first: Picker.Hit? = null
    private var second: Picker.Hit? = null
    private var request = 0

    /** Son hesaplanan panel metni (ekran görüntüsüne yazmak için). */
    var lastText: String = ""
        private set

    /** Kalite kontrol raporuna / tolerans hesabına aktarılabilecek ölçüler. */
    class Candidate(val label: String, val value: Double, val unit: Inspection.Dim, val anchor: FloatArray?, val isDiameter: Boolean = false)

    var candidates: List<Candidate> = emptyList()
        private set

    fun setMesh(m: StepMesh?) {
        mesh = m
        clear()
    }

    fun clear() {
        first = null
        second = null
        refresh()
    }

    /** Dokunma sonucu: null = boşluğa dokunuldu. */
    fun onPicked(hit: Picker.Hit?) {
        val a = first
        val b = second
        when {
            hit == null -> { first = null; second = null }
            a == null -> first = hit
            b == null && hit == a -> first = null           // aynı öğe: seçimi kaldır
            b == null -> second = hit
            else -> { first = hit; second = null }          // üçüncü dokunuş: yeniden başla
        }
        refresh()
    }

    private fun refresh() {
        val m = mesh ?: return
        val a = first
        val b = second
        candidates = emptyList()
        if (a == null) {
            lastText = context.getString(R.string.measure_hint_first)
            onResult(lastText, ModelRenderer.Overlay())
            return
        }
        val baseOverlay = buildOverlay(m, listOfNotNull(a, b), null)
        onResult(context.getString(R.string.measure_busy), baseOverlay)

        val req = ++request
        runInBackground {
            val infoA = StepNative.shapeInfo(a.kind, a.id)
            val infoB = b?.let { StepNative.shapeInfo(it.kind, it.id) }
            val pair = b?.let { StepNative.measurePair(a.kind, a.id, it.kind, it.id) }
            runOnUi(fun() {
                if (req != request) return  // daha yeni bir seçim var
                val lines = mutableListOf(context.getString(R.string.sel_label, 1, describe(infoA)))
                var line: FloatArray? = null
                val cands = ArrayList<Candidate>()
                singleCandidates(infoA, anchorOf(m, a), cands)
                if (b == null) {
                    lines += context.getString(R.string.measure_hint_second)
                } else {
                    singleCandidates(infoB, anchorOf(m, b), cands)
                    lines += context.getString(R.string.sel_label, 2, describe(infoB))
                    if (pair != null) {
                        lines += context.getString(R.string.measure_distance, fmt(pair[0]))
                        if (pair[9] >= 0) lines += context.getString(R.string.measure_parallel, fmt(pair[9]))
                        if (pair[7] >= 0) lines += context.getString(R.string.measure_angle, fmt(pair[7], 2))
                        if (pair[8] >= 0) lines += context.getString(R.string.measure_center, fmt(pair[8]))
                        val mid = if (pair[0] > 1e-9) {
                            floatArrayOf(
                                ((pair[1] + pair[4]) / 2).toFloat(), ((pair[2] + pair[5]) / 2).toFloat(), ((pair[3] + pair[6]) / 2).toFloat(),
                            )
                        } else {
                            anchorOf(m, a)
                        }
                        val pairCands = ArrayList<Candidate>()
                        if (pair[9] >= 0) pairCands += Candidate(context.getString(R.string.qc_l_parallel), pair[9], Inspection.Dim.MM, mid)
                        if (pair[8] >= 0) pairCands += Candidate(context.getString(R.string.qc_l_center), pair[8], Inspection.Dim.MM, mid)
                        if (pair[0] > 1e-9) pairCands += Candidate(context.getString(R.string.qc_l_distance), pair[0], Inspection.Dim.MM, mid)
                        if (pair[7] >= 0) pairCands += Candidate(context.getString(R.string.qc_l_angle), pair[7], Inspection.Dim.DEG, mid)
                        cands.addAll(0, pairCands)
                        if (pair[0] > 1e-9) {
                            lines += context.getString(
                                R.string.measure_delta,
                                fmt(abs(pair[4] - pair[1])), fmt(abs(pair[5] - pair[2])), fmt(abs(pair[6] - pair[3])),
                            )
                            line = FloatArray(6) { pair[1 + it].toFloat() }
                        }
                    }
                }
                lastText = lines.joinToString("\n")
                candidates = cands
                onResult(lastText, buildOverlay(m, listOfNotNull(a, b), line))
            })
        }
    }

    private fun buildOverlay(m: StepMesh, hits: List<Picker.Hit>, line: FloatArray?): ModelRenderer.Overlay {
        val faceRanges = ArrayList<Int>()
        var segments = FloatArray(0)
        for (h in hits) {
            if (h.kind == StepNative.KIND_EDGE) {
                segments += m.edgeSegments(h.id)
            } else if (h.id >= 0 && h.id < m.faceCount) {
                faceRanges += m.faceOffsets[h.id]
                faceRanges += m.faceOffsets[h.id + 1] - m.faceOffsets[h.id]
            }
        }
        return ModelRenderer.Overlay(
            faceRanges = faceRanges.toIntArray(),
            edgeSegments = segments,
            measureLine = line,
        )
    }

    private fun singleCandidates(i: DoubleArray?, anchor: FloatArray?, out: MutableList<Candidate>) {
        if (i == null) return
        val length = i[1]
        val r = i[3]
        when (i[0].toInt()) {
            GeomKind.LINE -> out += Candidate(context.getString(R.string.qc_l_length), length, Inspection.Dim.MM, anchor)
            GeomKind.CIRCLE -> {
                val full = abs(length - 2 * PI * r) < 1e-6 * maxOf(1.0, length)
                if (full) {
                    out += Candidate(context.getString(R.string.qc_l_diameter), r * 2, Inspection.Dim.MM, anchor, isDiameter = true)
                } else {
                    out += Candidate(context.getString(R.string.qc_l_radius), r, Inspection.Dim.MM, anchor)
                    out += Candidate(context.getString(R.string.qc_l_diameter), r * 2, Inspection.Dim.MM, anchor, isDiameter = true)
                }
            }
            GeomKind.CYLINDER, GeomKind.SPHERE ->
                out += Candidate(context.getString(R.string.qc_l_diameter), r * 2, Inspection.Dim.MM, anchor, isDiameter = true)
            GeomKind.CONE -> out += Candidate(context.getString(R.string.qc_l_cone), i[5] * 2, Inspection.Dim.DEG, anchor)
            GeomKind.CURVE, GeomKind.ELLIPSE -> out += Candidate(context.getString(R.string.qc_l_length), length, Inspection.Dim.MM, anchor)
        }
    }

    /** Seçilen öğenin modeldeki yaklaşık orta noktası (rapor balonu için). */
    private fun anchorOf(m: StepMesh, h: Picker.Hit): FloatArray? {
        if (h.kind == StepNative.KIND_EDGE) {
            val seg = m.edgeSegments(h.id)
            if (seg.size < 6) return null
            val n = seg.size / 6
            val k = (n / 2) * 6
            return floatArrayOf((seg[k] + seg[k + 3]) / 2, (seg[k + 1] + seg[k + 4]) / 2, (seg[k + 2] + seg[k + 5]) / 2)
        }
        if (h.id < 0 || h.id + 1 >= m.faceOffsets.size) return null
        val t0 = m.faceOffsets[h.id]
        val t1 = m.faceOffsets[h.id + 1]
        if (t1 <= t0) return null
        // En büyük üçgenin ağırlık merkezi: yüzeyin üstünde kalır (ortalama deliklerin içine düşebilir)
        var best = -1f
        var out: FloatArray? = null
        val step = maxOf(1, (t1 - t0) / 400)
        var t = t0
        while (t < t1) {
            val p = FloatArray(9)
            for (c in 0 until 3) {
                val v = m.indices[t * 3 + c] * StepMesh.FLOATS_PER_VERTEX
                p[c * 3] = m.vertices[v]
                p[c * 3 + 1] = m.vertices[v + 1]
                p[c * 3 + 2] = m.vertices[v + 2]
            }
            val ux = p[3] - p[0]; val uy = p[4] - p[1]; val uz = p[5] - p[2]
            val wx = p[6] - p[0]; val wy = p[7] - p[1]; val wz = p[8] - p[2]
            val cx = uy * wz - uz * wy; val cy = uz * wx - ux * wz; val cz = ux * wy - uy * wx
            val area = cx * cx + cy * cy + cz * cz
            if (area > best) {
                best = area
                out = floatArrayOf((p[0] + p[3] + p[6]) / 3, (p[1] + p[4] + p[7]) / 3, (p[2] + p[5] + p[8]) / 3)
            }
            t += step
        }
        return out
    }

    private fun describe(i: DoubleArray?): String {
        if (i == null) return context.getString(R.string.geom_unknown)
        val kind = i[0].toInt()
        val length = i[1]
        val area = i[2]
        val r = i[3]
        val r2 = i[4]
        val angle = i[5]
        return when (kind) {
            GeomKind.LINE -> context.getString(R.string.geom_line, fmt(length))
            GeomKind.CIRCLE -> {
                val full = abs(length - 2 * PI * r) < 1e-6 * maxOf(1.0, length)
                if (full) context.getString(R.string.geom_circle, fmt(r * 2), fmt(r))
                else context.getString(R.string.geom_arc, fmt(r * 2), fmt(r), fmt(length))
            }
            GeomKind.ELLIPSE -> context.getString(R.string.geom_ellipse, fmt(r * 2), fmt(r2 * 2), fmt(length))
            GeomKind.CURVE -> context.getString(R.string.geom_curve, fmt(length))
            GeomKind.PLANE -> context.getString(R.string.geom_plane, fmt(area, 2))
            GeomKind.CYLINDER -> context.getString(R.string.geom_cylinder, fmt(r * 2), fmt(r), fmt(area, 2))
            GeomKind.CONE -> context.getString(R.string.geom_cone, fmt(angle, 2), fmt(area, 2))
            GeomKind.SPHERE -> context.getString(R.string.geom_sphere, fmt(r * 2), fmt(area, 2))
            GeomKind.TORUS -> context.getString(R.string.geom_torus, fmt(r), fmt(r2))
            GeomKind.SURFACE -> context.getString(R.string.geom_surface, fmt(area, 2))
            else -> context.getString(R.string.geom_curve, fmt(length))
        }
    }
}
