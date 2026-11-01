package com.mertkaradag.stepviewer

/**
 * Yerel (C++) taraftan dönen üçgen ağı.
 *
 * @property vertices köşe başına 9 float: x, y, z, nx, ny, nz, r, g, b
 * @property indices üçgen indeksleri (3 * üçgen sayısı)
 * @property edges kenar çizgileri: segment başına 2 nokta * 3 float
 * @property bbox minX, minY, minZ, maxX, maxY, maxZ
 *
 * Yapıcı imzası jni_bridge.cpp içindeki "([F[I[F[FII)V" ile aynı kalmalıdır.
 */
class StepMesh(
    val vertices: FloatArray,
    val indices: IntArray,
    val edges: FloatArray,
    val bbox: FloatArray,
    val partCount: Int,
    val faceCount: Int,
) {
    val vertexCount: Int get() = vertices.size / FLOATS_PER_VERTEX
    val triangleCount: Int get() = indices.size / 3

    companion object {
        const val FLOATS_PER_VERTEX = 9
    }
}
