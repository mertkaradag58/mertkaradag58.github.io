package com.mertkaradag.stepviewer

import android.content.Context
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Ölçüm seçimleri, panel metni ve çizim katmanı. Üç kip vardır:
 * - [Mode.SMART]: kenar, yüzey veya köşe seç; iki seçim arası mesafe, açı, merkez mesafesi.
 * - [Mode.POINT]: köşe / orta nokta / merkez / kenar veya yüzey üzerindeki nokta; koordinat ve
 *   iki nokta arası mesafe.
 * - [Mode.CHAIN]: dokunulan kenarların uzunluk toplamı ve yüzeylerin alan toplamı.
 *
 * Yerel çağrılar [runInBackground] ile arka planda yapılır, sonuç [onResult] ile ana iş
 * parçacığında bildirilir. Tüm değerler mm'dir; ekranda [Units] ile çevrilir.
 */
class MeasureController(
    private val context: Context,
    private val runInBackground: (() -> Unit) -> Unit,
    private val runOnUi: (() -> Unit) -> Unit,
    private val onResult: (text: String, overlay: ModelRenderer.Overlay) -> Unit,
) {
    enum class Mode { SMART, POINT, CHAIN }

    /** Ölçüm seçimi: kenar / yüzey (kind, id) veya nokta ([point], [snap]). */
    class Sel(
        val kind: Int,
        val id: Int,
        val point: FloatArray? = null,
        val snap: Picker.SnapKind? = null,
    ) {
        val isPoint: Boolean get() = kind == StepNative.KIND_POINT

        fun sameAs(o: Sel): Boolean =
            if (isPoint || o.isPoint) {
                isPoint && o.isPoint && point!!.contentEquals(o.point)
            } else {
                kind == o.kind && id == o.id
            }

        companion object {
            fun of(hit: Picker.Hit) = Sel(hit.kind, hit.id)
            fun of(s: Picker.Snap) = Sel(StepNative.KIND_POINT, s.edge, s.point, s.kind)
        }
    }

    private var mesh: StepMesh? = null
    private val sels = ArrayList<Sel>()
    private var request = 0

    var mode: Mode = Mode.SMART
        private set

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

    fun setMode(m: Mode) {
        if (mode == m) return
        mode = m
        clear()
    }

    fun clear() {
        sels.clear()
        refresh()
    }

    /** Birim veya ondalık değişince metni yeniden yazar. */
    fun redraw() = refresh()

    /** Dokunma sonucu: null = boşluğa dokunuldu. */
    fun onPicked(sel: Sel?) {
        if (sel == null) {
            // Toplam kipinde boşluğa dokunmak seçimi silmesin (yanlışlıkla kaybolmasın)
            if (mode != Mode.CHAIN) sels.clear()
            refresh()
            return
        }
        when (mode) {
            Mode.CHAIN -> {
                val existing = sels.indexOfFirst { it.sameAs(sel) }
                if (existing >= 0) sels.removeAt(existing) else sels += sel
            }
            else -> {
                when {
                    sels.isEmpty() -> sels += sel
                    sels.size == 1 && sels[0].sameAs(sel) -> sels.clear()  // aynı öğe: seçimi kaldır
                    sels.size == 1 -> sels += sel
                    else -> {                                                // üçüncü dokunuş: yeniden başla
                        sels.clear()
                        sels += sel
                    }
                }
            }
        }
        refresh()
    }

    /** Toplam kipinde son eklenen öğeyi geri alır. */
    fun undoLast(): Boolean {
        if (sels.isEmpty()) return false
        sels.removeAt(sels.size - 1)
        refresh()
        return true
    }

    val selectionCount: Int get() = sels.size

    private fun refresh() {
        val m = mesh ?: return
        candidates = emptyList()
        if (sels.isEmpty()) {
            lastText = context.getString(
                when (mode) {
                    Mode.SMART -> R.string.measure_hint_first
                    Mode.POINT -> R.string.measure_hint_point
                    Mode.CHAIN -> R.string.measure_hint_chain
                }
            )
            onResult(lastText, ModelRenderer.Overlay())
            return
        }
        when (mode) {
            Mode.CHAIN -> refreshChain(m)
            else -> refreshPair(m)
        }
    }

    // -----------------------------------------------------------------------
    // Akıllı ve nokta kipleri: bir veya iki seçim
    // -----------------------------------------------------------------------

    private fun refreshPair(m: StepMesh) {
        val a = sels[0]
        val b = sels.getOrNull(1)
        onResult(context.getString(R.string.measure_busy), buildOverlay(m, sels, null))

        val req = ++request
        val snapshot = ArrayList(sels)
        runInBackground {
            val infoA = if (a.isPoint) null else StepNative.shapeInfo(a.kind, a.id)
            val infoB = if (b == null || b.isPoint) null else StepNative.shapeInfo(b.kind, b.id)
            // Sonuç: [mesafe, p1 xyz, p2 xyz, açı, merkez mesafesi, paralel mesafe]
            val pair: DoubleArray? = when {
                b == null -> null
                a.isPoint && b.isPoint -> pointPoint(a.point!!, b.point!!)
                a.isPoint -> padPair(StepNative.measurePoint(
                    a.point!![0].toDouble(), a.point[1].toDouble(), a.point[2].toDouble(), b.kind, b.id))
                b.isPoint -> padPair(StepNative.measurePoint(
                    b.point!![0].toDouble(), b.point[1].toDouble(), b.point[2].toDouble(), a.kind, a.id))
                    ?.let { swapEnds(it) }
                else -> StepNative.measurePair(a.kind, a.id, b.kind, b.id)
            }
            runOnUi(fun() {
                if (req != request) return  // daha yeni bir seçim var
                val lines = mutableListOf<String>()
                lines += context.getString(R.string.sel_label, 1, describe(a, infoA))
                var line: FloatArray? = null
                val cands = ArrayList<Candidate>()
                singleCandidates(infoA, anchorOf(m, a), cands)
                if (b == null) {
                    lines += context.getString(
                        if (mode == Mode.POINT) R.string.measure_hint_point_second else R.string.measure_hint_second
                    )
                } else {
                    singleCandidates(infoB, anchorOf(m, b), cands)
                    lines += context.getString(R.string.sel_label, 2, describe(b, infoB))
                    if (pair != null) {
                        lines += context.getString(R.string.measure_distance, Units.fl(pair[0]))
                        if (pair[9] >= 0) lines += context.getString(R.string.measure_parallel, Units.fl(pair[9]))
                        if (pair[7] >= 0) lines += context.getString(R.string.measure_angle, fmt(pair[7], 2))
                        if (pair[8] >= 0) lines += context.getString(R.string.measure_center, Units.fl(pair[8]))
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
                                Units.fl(abs(pair[4] - pair[1])), Units.fl(abs(pair[5] - pair[2])),
                                Units.fl(abs(pair[6] - pair[3])),
                            )
                            line = FloatArray(6) { pair[1 + it].toFloat() }
                        }
                    } else {
                        lines += context.getString(R.string.measure_failed)
                    }
                }
                lastText = Units.localize(lines.joinToString("\n"))
                candidates = cands
                onResult(lastText, buildOverlay(m, snapshot, line))
            })
        }
    }

    private fun pointPoint(p: FloatArray, q: FloatArray): DoubleArray {
        val dx = (q[0] - p[0]).toDouble()
        val dy = (q[1] - p[1]).toDouble()
        val dz = (q[2] - p[2]).toDouble()
        return doubleArrayOf(
            sqrt(dx * dx + dy * dy + dz * dz),
            p[0].toDouble(), p[1].toDouble(), p[2].toDouble(),
            q[0].toDouble(), q[1].toDouble(), q[2].toDouble(),
            -1.0, -1.0, -1.0,
        )
    }

    /** measurePoint sonucunu (7 değer) measurePair biçimine (10 değer) genişletir. */
    private fun padPair(r: DoubleArray?): DoubleArray? {
        if (r == null || r.size < 7) return null
        return doubleArrayOf(r[0], r[1], r[2], r[3], r[4], r[5], r[6], -1.0, -1.0, -1.0)
    }

    private fun swapEnds(r: DoubleArray): DoubleArray =
        doubleArrayOf(r[0], r[4], r[5], r[6], r[1], r[2], r[3], r[7], r[8], r[9])

    // -----------------------------------------------------------------------
    // Toplam kipi: uzunluk ve alan toplamı
    // -----------------------------------------------------------------------

    private fun refreshChain(m: StepMesh) {
        onResult(context.getString(R.string.measure_busy), buildOverlay(m, sels, null))
        val req = ++request
        val snapshot = ArrayList(sels)
        runInBackground {
            var length = 0.0
            var area = 0.0
            var edges = 0
            var faces = 0
            var failed = 0
            for (s in snapshot) {
                val info = StepNative.shapeInfo(s.kind, s.id)
                if (info == null) {
                    failed++
                    continue
                }
                if (s.kind == StepNative.KIND_EDGE) {
                    length += info[1]
                    edges++
                } else {
                    area += info[2]
                    faces++
                }
            }
            runOnUi(fun() {
                if (req != request) return
                val lines = mutableListOf(context.getString(R.string.chain_count, snapshot.size))
                if (edges > 0) lines += context.getString(R.string.chain_length, Units.fl(length), edges)
                if (faces > 0) lines += context.getString(R.string.chain_area, Units.fa(area), faces)
                if (failed > 0) lines += context.getString(R.string.measure_failed)
                lines += context.getString(R.string.chain_hint_more)
                lastText = Units.localize(lines.joinToString("\n"))
                onResult(lastText, buildOverlay(m, snapshot, null))
            })
        }
    }

    // -----------------------------------------------------------------------

    private fun buildOverlay(m: StepMesh, hits: List<Sel>, line: FloatArray?): ModelRenderer.Overlay {
        val faceRanges = ArrayList<Int>()
        var segments = FloatArray(0)
        val points = ArrayList<Float>()
        for (h in hits) {
            when {
                h.isPoint -> h.point?.let { points += it[0]; points += it[1]; points += it[2] }
                h.kind == StepNative.KIND_EDGE -> segments += m.edgeSegments(h.id)
                h.id >= 0 && h.id < m.faceCount -> {
                    faceRanges += m.faceOffsets[h.id]
                    faceRanges += m.faceOffsets[h.id + 1] - m.faceOffsets[h.id]
                }
            }
        }
        return ModelRenderer.Overlay(
            faceRanges = faceRanges.toIntArray(),
            edgeSegments = segments,
            measureLine = line,
            points = if (points.isEmpty()) null else points.toFloatArray(),
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
    private fun anchorOf(m: StepMesh, h: Sel): FloatArray? {
        if (h.isPoint) return h.point
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

    fun snapName(k: Picker.SnapKind?): String = context.getString(
        when (k) {
            Picker.SnapKind.VERTEX -> R.string.snap_vertex
            Picker.SnapKind.MIDPOINT -> R.string.snap_midpoint
            Picker.SnapKind.CENTER -> R.string.snap_center
            Picker.SnapKind.ON_EDGE -> R.string.snap_on_edge
            else -> R.string.snap_on_surface
        }
    )

    private fun describe(s: Sel, i: DoubleArray?): String {
        if (s.isPoint) {
            val p = s.point!!
            return context.getString(
                R.string.point_label, snapName(s.snap),
                Units.fl(p[0].toDouble()), Units.fl(p[1].toDouble()), Units.fl(p[2].toDouble()),
            )
        }
        if (i == null) return context.getString(R.string.geom_unknown)
        val kind = i[0].toInt()
        val length = i[1]
        val area = i[2]
        val r = i[3]
        val r2 = i[4]
        val angle = i[5]
        val perimeter = if (i.size > 12) i[12] else 0.0
        val base = when (kind) {
            GeomKind.LINE -> context.getString(R.string.geom_line, Units.fl(length))
            GeomKind.CIRCLE -> {
                val full = abs(length - 2 * PI * r) < 1e-6 * maxOf(1.0, length)
                if (full) context.getString(R.string.geom_circle, Units.fl(r * 2), Units.fl(r))
                else context.getString(R.string.geom_arc, Units.fl(r * 2), Units.fl(r), Units.fl(length))
            }
            GeomKind.ELLIPSE -> context.getString(R.string.geom_ellipse, Units.fl(r * 2), Units.fl(r2 * 2), Units.fl(length))
            GeomKind.CURVE -> context.getString(R.string.geom_curve, Units.fl(length))
            GeomKind.PLANE -> context.getString(R.string.geom_plane, Units.fa(area))
            GeomKind.CYLINDER -> context.getString(R.string.geom_cylinder, Units.fl(r * 2), Units.fl(r), Units.fa(area))
            GeomKind.CONE -> context.getString(R.string.geom_cone, fmt(angle, 2), Units.fa(area))
            GeomKind.SPHERE -> context.getString(R.string.geom_sphere, Units.fl(r * 2), Units.fa(area))
            GeomKind.TORUS -> context.getString(R.string.geom_torus, Units.fl(r), Units.fl(r2))
            GeomKind.SURFACE -> context.getString(R.string.geom_surface, Units.fa(area))
            else -> context.getString(R.string.geom_curve, Units.fl(length))
        }
        return if (kind >= GeomKind.PLANE && perimeter > 1e-9) {
            base + " · " + context.getString(R.string.geom_perimeter, Units.fl(perimeter))
        } else {
            base
        }
    }
}
