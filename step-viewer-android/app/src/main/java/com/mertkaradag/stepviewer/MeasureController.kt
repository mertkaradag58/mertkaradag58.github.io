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
            runOnUi {
                if (req != request) return@runOnUi  // daha yeni bir seçim var
                val lines = mutableListOf(context.getString(R.string.sel_label, 1, describe(infoA)))
                var line: FloatArray? = null
                if (b == null) {
                    lines += context.getString(R.string.measure_hint_second)
                } else {
                    lines += context.getString(R.string.sel_label, 2, describe(infoB))
                    if (pair != null) {
                        lines += context.getString(R.string.measure_distance, fmt(pair[0]))
                        if (pair[9] >= 0) lines += context.getString(R.string.measure_parallel, fmt(pair[9]))
                        if (pair[7] >= 0) lines += context.getString(R.string.measure_angle, fmt(pair[7], 2))
                        if (pair[8] >= 0) lines += context.getString(R.string.measure_center, fmt(pair[8]))
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
                onResult(lastText, buildOverlay(m, listOfNotNull(a, b), line))
            }
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
