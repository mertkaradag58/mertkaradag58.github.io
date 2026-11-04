package com.mertkaradag.stepviewer

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Parça listesi (BOM) CSV: aynı adlı ve aynı boyuttaki parçalar tek satırda adetle toplanır.
 * Excel'in Türkçe ayarlarında açılsın diye ondalık ayırıcı virgülse alan ayırıcı noktalı virgüldür.
 */
object Bom {

    class Headers(
        val no: String,
        val name: String,
        val path: String,
        val qty: String,
        val sizeX: String,
        val sizeY: String,
        val sizeZ: String,
        val volume: String,
        val unitMass: String,
        val totalMass: String,
        val total: String,
    )

    private class Row(
        val name: String,
        val path: String,
        val size: FloatArray,
        val volumeMm3: Double, // < 0: katı yok
        var qty: Int,
    )

    /**
     * @param massProps parça indeksi -> [hacim mm³, alan, cx, cy, cz, katı var mı] (arka planda çağrılır)
     * @param density g/cm³; <= 0 ise kütle sütunları boş kalır
     */
    fun build(
        mesh: StepMesh,
        partVisible: BooleanArray,
        partName: (Int) -> String,
        massProps: (Int) -> DoubleArray?,
        density: Double,
        headers: Headers,
        locale: Locale = Locale.getDefault(),
    ): String {
        val symbols = DecimalFormatSymbols.getInstance(locale)
        val sep = if (symbols.decimalSeparator == ',') ';' else ','
        val f2 = DecimalFormat("0.##", symbols)
        val f3 = DecimalFormat("0.###", symbols)

        val rows = ArrayList<Row>()
        val index = HashMap<String, Row>()
        for (p in 0 until mesh.partCount) {
            if (p < partVisible.size && !partVisible[p]) continue
            val name = partName(p)
            val path = mesh.partPath(p).joinToString(" > ")
            val size = partSize(mesh, p)
            // Aynı parça: ad, montaj yolu ve boyutlar (0,01 mm hassasiyetle) aynı
            val key = name + "\u0000" + path + "\u0000" +
                size.joinToString("x") { Math.round(it * 100.0).toString() }
            val existing = index[key]
            if (existing != null) {
                existing.qty++
                continue
            }
            val mp = massProps(p)
            val vol = if (mp != null && mp.size >= 6 && mp[5] > 0.5) mp[0] else -1.0
            val row = Row(name, path, size, vol, 1)
            index[key] = row
            rows += row
        }

        val sb = StringBuilder()
        sb.append('﻿') // Excel UTF-8'i tanısın
        val head = mutableListOf(
            headers.no, headers.name, headers.path, headers.qty,
            headers.sizeX, headers.sizeY, headers.sizeZ, headers.volume,
        )
        if (density > 0) {
            head += headers.unitMass
            head += headers.totalMass
        }
        sb.append(head.joinToString(sep.toString()) { csv(it, sep) }).append("\r\n")

        var totalQty = 0
        var totalKg = 0.0
        rows.forEachIndexed { i, r ->
            val cells = mutableListOf(
                (i + 1).toString(), r.name, r.path, r.qty.toString(),
                f2.format(r.size[0]), f2.format(r.size[1]), f2.format(r.size[2]),
                if (r.volumeMm3 >= 0) f3.format(r.volumeMm3 / 1000.0) else "",
            )
            if (density > 0) {
                if (r.volumeMm3 >= 0) {
                    val kg = r.volumeMm3 / 1000.0 * density / 1000.0
                    cells += f3.format(kg)
                    cells += f3.format(kg * r.qty)
                    totalKg += kg * r.qty
                } else {
                    cells += ""
                    cells += ""
                }
            }
            totalQty += r.qty
            sb.append(cells.joinToString(sep.toString()) { csv(it, sep) }).append("\r\n")
        }
        val totalRow = mutableListOf("", headers.total, "", totalQty.toString(), "", "", "", "")
        if (density > 0) {
            totalRow += ""
            totalRow += f3.format(totalKg)
        }
        sb.append(totalRow.joinToString(sep.toString()) { csv(it, sep) }).append("\r\n")
        return sb.toString()
    }

    private fun csv(s: String, sep: Char): String =
        if (s.any { it == sep || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** Parçanın eksen hizalı boyutları (mm). */
    fun partSize(mesh: StepMesh, p: Int): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        val v = mesh.vertices
        val k = StepMesh.FLOATS_PER_VERTEX
        for (t in mesh.partTriangleRange(p)) {
            for (c in 0 until 3) {
                val o = mesh.indices[t * 3 + c] * k
                for (a in 0 until 3) {
                    if (v[o + a] < b[a]) b[a] = v[o + a]
                    if (v[o + a] > b[a + 3]) b[a + 3] = v[o + a]
                }
            }
        }
        if (b[0] > b[3]) return FloatArray(3)
        return floatArrayOf(b[3] - b[0], b[4] - b[1], b[5] - b[2])
    }
}
