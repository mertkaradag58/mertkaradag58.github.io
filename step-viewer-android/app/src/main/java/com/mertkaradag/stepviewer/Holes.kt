package com.mertkaradag.stepviewer

import android.content.Context
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Delik tablosu: C++ tarafının bulduğu delikler, aynı tipteki deliklerin gruplanması,
 * kılavuz / geçiş deliği tahmini ve CSV.
 */
object Holes {

    private const val STRIDE = 17

    class Hole(
        val part: Int,
        val diameter: Double,
        val depth: Double,
        val through: Boolean,
        val drillPoint: Boolean,
        val cboreDiameter: Double,
        val cboreDepth: Double,
        val csinkDiameter: Double,
        val csinkAngle: Double,
        val entry: FloatArray,
        val faces: IntArray,
    )

    class Group(val label: String, val holes: List<Hole>) {
        val first: Hole get() = holes[0]
    }

    /** Arka planda çağrılmalı. CAD dışı dosyalarda veya hata durumunda null. */
    fun load(): List<Hole>? {
        val data = StepNative.holes() ?: return null
        val v = data[0] as DoubleArray
        val f = data[1] as IntArray
        val out = ArrayList<Hole>(v.size / STRIDE)
        var i = 0
        while (i + STRIDE <= v.size) {
            val fs = v[i + 15].toInt()
            val fc = v[i + 16].toInt()
            out += Hole(
                part = v[i].toInt(),
                diameter = v[i + 1],
                depth = v[i + 2],
                through = v[i + 3] > 0.5,
                drillPoint = v[i + 4] > 0.5,
                cboreDiameter = v[i + 5],
                cboreDepth = v[i + 6],
                csinkDiameter = v[i + 7],
                csinkAngle = v[i + 8],
                entry = floatArrayOf(v[i + 9].toFloat(), v[i + 10].toFloat(), v[i + 11].toFloat()),
                faces = f.copyOfRange(fs.coerceIn(0, f.size), (fs + fc).coerceIn(0, f.size)),
            )
            i += STRIDE
        }
        return out
    }

    private fun r2(x: Double) = (x * 100).roundToLong()

    /** Aynı çap, derinlik (kör deliklerde), havşa ve kaçak -> aynı tip. Küçük çaptan büyüğe. */
    fun group(holes: List<Hole>, visiblePart: (Int) -> Boolean): List<Group> {
        val map = LinkedHashMap<String, MutableList<Hole>>()
        for (h in holes) {
            if (!visiblePart(h.part)) continue
            val key = listOf(
                r2(h.diameter), if (h.through) -1L else r2(h.depth), if (h.through) 1L else 0L,
                r2(h.cboreDiameter), r2(h.cboreDepth), r2(h.csinkDiameter), h.csinkAngle.roundToLong(),
            ).joinToString("|")
            map.getOrPut(key) { ArrayList() } += h
        }
        val sorted = map.values.sortedWith(
            compareBy<MutableList<Hole>>({ it[0].diameter }, { if (it[0].through) 0.0 else it[0].depth }, { it[0].cboreDiameter }),
        )
        return sorted.mapIndexed { i, list ->
            // Konuma göre sırala (önce X, sonra Y, sonra Z) ki A1, A2... düzenli olsun
            val ordered = list.sortedWith(compareBy<Hole>({ it.entry[0] }, { it.entry[1] }, { it.entry[2] }))
            Group(label(i), ordered)
        }
    }

    private fun label(i: Int): String {
        var n = i
        val sb = StringBuilder()
        do {
            sb.insert(0, ('A' + n % 26))
            n = n / 26 - 1
        } while (n >= 0)
        return sb.toString()
    }

    // ISO metrik kaba diş: (ad, anma çapı, adım, kılavuz matkabı)
    private class Thread(val name: String, val d: Double, val pitch: Double, val drill: Double) {
        /** İç diş diş dibi çapı D1 = d - 1,0825 P (bazı CAD programları deliği bu çapta çizer). */
        val minor: Double get() = d - 1.0825 * pitch
    }
    private val THREADS = listOf(
        Thread("M1,6", 1.6, 0.35, 1.25), Thread("M2", 2.0, 0.4, 1.6), Thread("M2,5", 2.5, 0.45, 2.05),
        Thread("M3", 3.0, 0.5, 2.5), Thread("M4", 4.0, 0.7, 3.3), Thread("M5", 5.0, 0.8, 4.2),
        Thread("M6", 6.0, 1.0, 5.0), Thread("M8", 8.0, 1.25, 6.8), Thread("M10", 10.0, 1.5, 8.5),
        Thread("M12", 12.0, 1.75, 10.2), Thread("M14", 14.0, 2.0, 12.0), Thread("M16", 16.0, 2.0, 14.0),
        Thread("M18", 18.0, 2.5, 15.5), Thread("M20", 20.0, 2.5, 17.5), Thread("M22", 22.0, 2.5, 19.5),
        Thread("M24", 24.0, 3.0, 21.0), Thread("M27", 27.0, 3.0, 24.0), Thread("M30", 30.0, 3.5, 26.5),
        Thread("M33", 33.0, 3.5, 29.5), Thread("M36", 36.0, 4.0, 32.0),
    )
    // ISO metrik ince diş (yaygın olanlar)
    private val FINE = listOf(
        Thread("M8×1", 8.0, 1.0, 7.0), Thread("M10×1", 10.0, 1.0, 9.0), Thread("M10×1,25", 10.0, 1.25, 8.8),
        Thread("M12×1,25", 12.0, 1.25, 10.8), Thread("M12×1,5", 12.0, 1.5, 10.5), Thread("M14×1,5", 14.0, 1.5, 12.5),
        Thread("M16×1,5", 16.0, 1.5, 14.5), Thread("M18×1,5", 18.0, 1.5, 16.5), Thread("M20×1,5", 20.0, 1.5, 18.5),
        Thread("M22×1,5", 22.0, 1.5, 20.5), Thread("M24×2", 24.0, 2.0, 22.0),
    )
    private val CLEAR = listOf(
        "M1,6" to listOf(1.7, 1.8), "M2" to listOf(2.2, 2.4), "M2,5" to listOf(2.7, 2.9), "M3" to listOf(3.2, 3.4),
        "M4" to listOf(4.3, 4.5), "M5" to listOf(5.3, 5.5), "M6" to listOf(6.4, 6.6),
        "M8" to listOf(8.4, 9.0), "M10" to listOf(10.5, 11.0), "M12" to listOf(13.0, 13.5),
        "M14" to listOf(15.0, 15.5), "M16" to listOf(17.0, 17.5), "M18" to listOf(19.0, 20.0),
        "M20" to listOf(21.0, 22.0), "M22" to listOf(23.0, 24.0), "M24" to listOf(25.0, 26.0),
        "M27" to listOf(28.0, 30.0), "M30" to listOf(31.0, 33.0), "M36" to listOf(37.0, 39.0),
    )
    // DIN 974 imbus (ISO 4762) kaçak çapları
    private val SHCS_CBORE = mapOf(
        "M3" to 6.5, "M4" to 8.0, "M5" to 10.0, "M6" to 11.0, "M8" to 15.0, "M10" to 18.0,
        "M12" to 20.0, "M14" to 24.0, "M16" to 26.0, "M20" to 33.0, "M24" to 40.0,
    )

    /** Kılavuz matkabı veya diş dibi çapına uyan diş (önce kaba, sonra ince). */
    private fun tapMatch(d: Double): Thread? =
        THREADS.firstOrNull { abs(it.drill - d) <= 0.06 || abs(it.minor - d) <= 0.04 }
            ?: FINE.firstOrNull { abs(it.drill - d) <= 0.04 }

    private fun locale(m: String, loc: Locale): String =
        if (DecimalFormatSymbols.getInstance(loc).decimalSeparator == ',') m else m.replace(',', '.')

    /** Okunur açıklamalar: kılavuz/geçiş tahmini, cıvata yuvası. */
    fun notes(context: Context, h: Hole, loc: Locale = Locale.getDefault()): List<String> {
        val out = ArrayList<String>()
        val d = h.diameter
        val clear = CLEAR.firstOrNull { (_, ds) -> ds.any { abs(it - d) <= 0.06 } }?.first
        val tap = tapMatch(d)
        if (clear != null) {
            val cb = SHCS_CBORE[clear]
            when {
                h.cboreDiameter > 0 && cb != null && abs(h.cboreDiameter - cb) <= 0.8 ->
                    out += context.getString(R.string.holes_shcs, locale(clear, loc))
                h.csinkDiameter > 0 && abs(h.csinkAngle - 90) <= 2 ->
                    out += context.getString(R.string.holes_flat, locale(clear, loc))
                else -> out += context.getString(R.string.holes_clear, locale(clear, loc))
            }
        }
        if (tap != null && h.cboreDiameter <= 0) {
            out += context.getString(
                R.string.holes_tap_info, locale(tap.name, loc), Holes.mm(tap.pitch), Holes.mm(tap.drill),
            )
        }
        return out
    }

    fun title(context: Context, h: Hole): String {
        val dia = "Ø" + mm(h.diameter)
        return if (h.through) {
            dia + " · " + context.getString(R.string.holes_through)
        } else {
            dia + " · " + context.getString(R.string.holes_blind, mm(h.depth))
        }
    }

    fun details(context: Context, h: Hole): List<String> {
        val out = ArrayList<String>()
        if (h.cboreDiameter > 0) out += context.getString(R.string.holes_cbore, mm(h.cboreDiameter), mm(h.cboreDepth))
        if (h.csinkDiameter > 0) out += context.getString(R.string.holes_csink, mm(h.csinkDiameter), fmt(h.csinkAngle, 0))
        out += notes(context, h)
        return out
    }

    /** 0,01 mm hassasiyet, gereksiz sıfırlar atılır. */
    fun mm(x: Double): String {
        val f = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.getDefault()))
        return f.format(x)
    }

    class CsvHeaders(
        val hole: String,
        val type: String,
        val diameter: String,
        val kind: String,
        val depth: String,
        val cbore: String,
        val csink: String,
        val note: String,
        val part: String,
        val through: String,
        val blind: String,
    )

    /** Delik başına bir satır: konumlar CNC / kontrol için. */
    fun csv(context: Context, groups: List<Group>, partName: (Int) -> String, h: CsvHeaders): String {
        val symbols = DecimalFormatSymbols.getInstance(Locale.getDefault())
        val sep = if (symbols.decimalSeparator == ',') ';' else ','
        val f3 = DecimalFormat("0.###", symbols)
        fun cell(s: String) =
            if (s.any { it == sep || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        val sb = StringBuilder()
        sb.append('﻿')
        sb.append(
            listOf(h.hole, h.type, h.diameter, h.kind, h.depth, h.cbore, h.csink, h.note, h.part, "X (mm)", "Y (mm)", "Z (mm)")
                .joinToString(sep.toString()) { cell(it) },
        ).append("\r\n")
        for (g in groups) {
            g.holes.forEachIndexed { i, hole ->
                val row = listOf(
                    g.label + (i + 1),
                    g.label,
                    f3.format(hole.diameter),
                    if (hole.through) h.through else h.blind,
                    f3.format(hole.depth),
                    if (hole.cboreDiameter > 0) "Ø" + f3.format(hole.cboreDiameter) + " x " + f3.format(hole.cboreDepth) else "",
                    if (hole.csinkDiameter > 0) "Ø" + f3.format(hole.csinkDiameter) + " x " + f3.format(hole.csinkAngle) + "°" else "",
                    notes(context, hole).joinToString(", "),
                    partName(hole.part),
                    f3.format(hole.entry[0].toDouble()),
                    f3.format(hole.entry[1].toDouble()),
                    f3.format(hole.entry[2].toDouble()),
                )
                sb.append(row.joinToString(sep.toString()) { cell(it) }).append("\r\n")
            }
        }
        return sb.toString()
    }
}
