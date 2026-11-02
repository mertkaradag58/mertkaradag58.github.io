package com.mertkaradag.stepviewer

import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Görünen parçaları üçgen ağı olarak dışa aktarır: STL (ikili), OBJ ve GLB (ikili glTF 2.0).
 *
 * Birimler: STL ve OBJ milimetre (3D yazıcı programları mm bekler); GLB metre ve Y-yukarı
 * (glTF standardı). Modelimiz Z-yukarıdır, GLB için (x, y, z) -> (x, z, -y) döndürülür.
 */
object Exporter {

    enum class Format(val extension: String, val mime: String) {
        STL("stl", "model/stl"),
        OBJ("obj", "model/obj"),
        GLB("glb", "model/gltf-binary"),
    }

    /** Bir parçanın kendi köşe listesine indirgenmiş üçgenleri. */
    private class PartMesh(
        val name: String,
        val positions: FloatArray, // x, y, z
        val normals: FloatArray,
        val colors: FloatArray,    // r, g, b
        val indices: IntArray,
    )

    /** @return yazılan üçgen sayısı */
    fun write(
        mesh: StepMesh,
        partVisible: BooleanArray,
        partName: (Int) -> String,
        format: Format,
        out: OutputStream,
    ): Int {
        val parts = collect(mesh, partVisible, partName)
        return when (format) {
            Format.STL -> writeStl(parts, out)
            Format.OBJ -> writeObj(parts, out)
            Format.GLB -> writeGlb(parts, out)
        }
    }

    private fun collect(mesh: StepMesh, partVisible: BooleanArray, partName: (Int) -> String): List<PartMesh> {
        val k = StepMesh.FLOATS_PER_VERTEX
        val v = mesh.vertices
        val idx = mesh.indices
        val remap = IntArray(mesh.vertexCount) { -1 }
        val result = ArrayList<PartMesh>()
        for (p in 0 until mesh.partCount) {
            if (p < partVisible.size && !partVisible[p]) continue
            val range = mesh.partTriangleRange(p)
            if (range.isEmpty()) continue
            val used = ArrayList<Int>()
            val ind = IntArray((range.last - range.first + 1) * 3)
            var n = 0
            for (t in range) {
                for (c in 0 until 3) {
                    val old = idx[t * 3 + c]
                    var m = remap[old]
                    if (m < 0) {
                        m = used.size
                        remap[old] = m
                        used += old
                    }
                    ind[n++] = m
                }
            }
            val pos = FloatArray(used.size * 3)
            val nor = FloatArray(used.size * 3)
            val col = FloatArray(used.size * 3)
            for ((i, old) in used.withIndex()) {
                val o = old * k
                for (a in 0 until 3) {
                    pos[i * 3 + a] = v[o + a]
                    nor[i * 3 + a] = v[o + 3 + a]
                    col[i * 3 + a] = v[o + 6 + a]
                }
                remap[old] = -1
            }
            result += PartMesh(partName(p), pos, nor, col, ind)
        }
        return result
    }

    // ------------------------------------------------------------------ STL

    private fun writeStl(parts: List<PartMesh>, out: OutputStream): Int {
        val total = parts.sumOf { it.indices.size / 3 }
        val bos = BufferedOutputStream(out, 1 shl 16)
        val header = ByteArray(80)
        val text = "Cepte CAD export (mm)".toByteArray(Charsets.US_ASCII)
        text.copyInto(header)
        bos.write(header)
        val buf = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN)
        bos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(total).array())
        for (part in parts) {
            val p = part.positions
            val ind = part.indices
            var t = 0
            while (t < ind.size) {
                val a = ind[t] * 3; val b = ind[t + 1] * 3; val c = ind[t + 2] * 3
                val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
                val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
                var nx = uy * vz - uz * vy
                var ny = uz * vx - ux * vz
                var nz = ux * vy - uy * vx
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                if (len > 0f) { nx /= len; ny /= len; nz /= len }
                buf.clear()
                buf.putFloat(nx).putFloat(ny).putFloat(nz)
                for (o in intArrayOf(a, b, c)) buf.putFloat(p[o]).putFloat(p[o + 1]).putFloat(p[o + 2])
                buf.putShort(0)
                bos.write(buf.array(), 0, 50)
                t += 3
            }
        }
        bos.flush()
        return total
    }

    // ------------------------------------------------------------------ OBJ

    private fun writeObj(parts: List<PartMesh>, out: OutputStream): Int {
        val w = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 1 shl 16)
        w.write("# Cepte CAD export, units: mm\n")
        var base = 1
        var total = 0
        val sb = StringBuilder(64)
        for ((i, part) in parts.withIndex()) {
            val name = part.name.replace(Regex("\\s+"), "_").ifBlank { "part_${i + 1}" }
            w.write("o $name\n")
            val p = part.positions
            val n = part.normals
            val count = p.size / 3
            for (j in 0 until count) {
                sb.setLength(0)
                sb.append("v ").append(p[j * 3]).append(' ').append(p[j * 3 + 1]).append(' ').append(p[j * 3 + 2]).append('\n')
                w.write(sb.toString())
            }
            for (j in 0 until count) {
                sb.setLength(0)
                sb.append("vn ").append(n[j * 3]).append(' ').append(n[j * 3 + 1]).append(' ').append(n[j * 3 + 2]).append('\n')
                w.write(sb.toString())
            }
            val ind = part.indices
            var t = 0
            while (t < ind.size) {
                val a = ind[t] + base; val b = ind[t + 1] + base; val c = ind[t + 2] + base
                sb.setLength(0)
                sb.append("f ").append(a).append("//").append(a).append(' ')
                    .append(b).append("//").append(b).append(' ')
                    .append(c).append("//").append(c).append('\n')
                w.write(sb.toString())
                t += 3
            }
            base += count
            total += ind.size / 3
        }
        w.flush()
        return total
    }

    // ------------------------------------------------------------------ GLB

    private fun writeGlb(parts: List<PartMesh>, out: OutputStream): Int {
        // İkili tampon düzeni: her parça için konum, normal, renk, indeks (hepsi 4 bayt hizalı)
        var binSize = 0L
        for (p in parts) binSize += (p.positions.size + p.normals.size + p.colors.size + p.indices.size) * 4L
        require(binSize < Int.MAX_VALUE - 16) { "too_large" }

        val json = StringBuilder()
        json.append("{\"asset\":{\"version\":\"2.0\",\"generator\":\"Cepte CAD\"},")
        json.append("\"scene\":0,\"scenes\":[{\"nodes\":[")
        json.append(parts.indices.joinToString(","))
        json.append("]}],")
        json.append("\"nodes\":[")
        parts.forEachIndexed { i, p ->
            if (i > 0) json.append(',')
            json.append("{\"name\":").append(jsonString(p.name)).append(",\"mesh\":").append(i).append('}')
        }
        json.append("],\"meshes\":[")
        parts.forEachIndexed { i, p ->
            if (i > 0) json.append(',')
            val a = i * 4
            json.append("{\"name\":").append(jsonString(p.name))
                .append(",\"primitives\":[{\"attributes\":{\"POSITION\":").append(a)
                .append(",\"NORMAL\":").append(a + 1)
                .append(",\"COLOR_0\":").append(a + 2)
                .append("},\"indices\":").append(a + 3).append(",\"material\":0}]}")
        }
        json.append("],\"materials\":[{\"name\":\"default\",\"pbrMetallicRoughness\":{\"baseColorFactor\":[1,1,1,1],\"metallicFactor\":0.1,\"roughnessFactor\":0.6},\"doubleSided\":true}],")

        val accessors = StringBuilder()
        val views = StringBuilder()
        var offset = 0L
        var viewIndex = 0
        fun addView(bytes: Long, target: Int) {
            if (viewIndex > 0) views.append(',')
            views.append("{\"buffer\":0,\"byteOffset\":").append(offset)
                .append(",\"byteLength\":").append(bytes).append(",\"target\":").append(target).append('}')
            offset += bytes
            viewIndex++
        }
        parts.forEachIndexed { i, p ->
            val count = p.positions.size / 3
            val min = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            val max = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (j in 0 until count) {
                val g = toGltf(p.positions, j, 0.001f)
                for (a in 0 until 3) {
                    if (g[a] < min[a]) min[a] = g[a]
                    if (g[a] > max[a]) max[a] = g[a]
                }
            }
            val base = viewIndex
            addView(count * 12L, 34962)
            addView(count * 12L, 34962)
            addView(count * 12L, 34962)
            addView(p.indices.size * 4L, 34963)
            if (i > 0) accessors.append(',')
            accessors.append("{\"bufferView\":").append(base).append(",\"componentType\":5126,\"count\":").append(count)
                .append(",\"type\":\"VEC3\",\"min\":[").append(min.joinToString(",")).append("],\"max\":[")
                .append(max.joinToString(",")).append("]},")
            accessors.append("{\"bufferView\":").append(base + 1).append(",\"componentType\":5126,\"count\":").append(count)
                .append(",\"type\":\"VEC3\"},")
            accessors.append("{\"bufferView\":").append(base + 2).append(",\"componentType\":5126,\"count\":").append(count)
                .append(",\"type\":\"VEC3\"},")
            accessors.append("{\"bufferView\":").append(base + 3).append(",\"componentType\":5125,\"count\":").append(p.indices.size)
                .append(",\"type\":\"SCALAR\"}")
        }
        json.append("\"accessors\":[").append(accessors).append("],")
        json.append("\"bufferViews\":[").append(views).append("],")
        json.append("\"buffers\":[{\"byteLength\":").append(binSize).append("}]}")

        var jsonBytes = json.toString().toByteArray(Charsets.UTF_8)
        val jsonPad = (4 - jsonBytes.size % 4) % 4
        if (jsonPad > 0) jsonBytes = jsonBytes + ByteArray(jsonPad) { ' '.code.toByte() }
        val total = 12 + 8 + jsonBytes.size + 8 + binSize

        val bos = BufferedOutputStream(out, 1 shl 16)
        val head = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        head.putInt(0x46546C67).putInt(2).putInt(total.toInt())
        head.putInt(jsonBytes.size).putInt(0x4E4F534A)
        bos.write(head.array())
        bos.write(jsonBytes)
        val binHead = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        binHead.putInt(binSize.toInt()).putInt(0x004E4942)
        bos.write(binHead.array())

        val buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        var tris = 0
        for (p in parts) {
            val count = p.positions.size / 3
            for (j in 0 until count) {
                val g = toGltf(p.positions, j, 0.001f)
                buf.clear(); buf.putFloat(g[0]).putFloat(g[1]).putFloat(g[2]); bos.write(buf.array(), 0, 12)
            }
            for (j in 0 until count) {
                val g = toGltf(p.normals, j, 1f)
                var len = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
                if (len < 1e-12f) { g[0] = 0f; g[1] = 1f; g[2] = 0f; len = 1f }
                buf.clear(); buf.putFloat(g[0] / len).putFloat(g[1] / len).putFloat(g[2] / len); bos.write(buf.array(), 0, 12)
            }
            for (j in 0 until count) {
                buf.clear()
                for (a in 0 until 3) buf.putFloat(p.colors[j * 3 + a].coerceIn(0f, 1f))
                bos.write(buf.array(), 0, 12)
            }
            val ib = ByteBuffer.allocate(p.indices.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            ib.asIntBuffer().put(p.indices)
            bos.write(ib.array())
            tris += p.indices.size / 3
        }
        bos.flush()
        return tris
    }

    /** Z-yukarı -> Y-yukarı: (x, y, z) -> (x, z, -y), ölçekle. */
    private fun toGltf(a: FloatArray, j: Int, scale: Float): FloatArray =
        floatArrayOf(a[j * 3] * scale, a[j * 3 + 2] * scale, -a[j * 3 + 1] * scale)

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch < ' ' -> sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }
}
