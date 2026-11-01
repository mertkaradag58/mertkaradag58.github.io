package com.mertkaradag.stepviewer

/**
 * Yerel (C++) taraftan dönen üçgen ağı.
 *
 * @property vertices köşe başına 9 float: x, y, z, nx, ny, nz, r, g, b
 * @property indices üçgen indeksleri (3 * üçgen sayısı)
 * @property edges kenar çizgileri: segment başına 2 nokta * 3 float
 * @property edgeOffsets i numaralı kenarın segmentleri: edgeOffsets[i] until edgeOffsets[i+1]
 * @property bbox minX, minY, minZ, maxX, maxY, maxZ
 *
 * Yapıcı imzası jni_bridge.cpp içindeki "([F[I[F[I[FII)V" ile aynı kalmalıdır.
 */
class StepMesh(
    val vertices: FloatArray,
    val indices: IntArray,
    val edges: FloatArray,
    val edgeOffsets: IntArray,
    val bbox: FloatArray,
    val partCount: Int,
    val faceCount: Int,
) {
    val vertexCount: Int get() = vertices.size / FLOATS_PER_VERTEX
    val triangleCount: Int get() = indices.size / 3
    val edgeCount: Int get() = edgeOffsets.size - 1

    /** Segment indeksinden kenar kimliğini bulur (ikili arama). */
    fun edgeOfSegment(segment: Int): Int {
        var lo = 0
        var hi = edgeCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                segment < edgeOffsets[mid] -> hi = mid - 1
                segment >= edgeOffsets[mid + 1] -> lo = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    /** Bir kenarın segment koordinatlarını (çizim için) döndürür. */
    fun edgeSegments(edgeId: Int): FloatArray {
        if (edgeId < 0 || edgeId >= edgeCount) return FloatArray(0)
        return edges.copyOfRange(edgeOffsets[edgeId] * 6, edgeOffsets[edgeId + 1] * 6)
    }

    companion object {
        const val FLOATS_PER_VERTEX = 9
    }
}
