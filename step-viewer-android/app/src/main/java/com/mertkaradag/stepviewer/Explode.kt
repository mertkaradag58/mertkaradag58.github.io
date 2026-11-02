package com.mertkaradag.stepviewer

/**
 * Patlatılmış görünüm için kayma yönleri: her parça, model merkezinden kendi merkezine
 * doğru itilir. Çizicide konum = köşe + yön * çarpan.
 */
class Explode private constructor(
    /** Köşe başına (x, y, z). */
    val vertexDirs: FloatArray,
    /** Kenar çizgisi köşesi başına (x, y, z): mesh.edges ile aynı sıra. */
    val edgeDirs: FloatArray,
    /** Parça başına (x, y, z). */
    val partDirs: FloatArray,
) {
    /** Çarpan uygulanmış parça kaymaları (seçim için). */
    fun partOffsets(factor: Float): FloatArray = FloatArray(partDirs.size) { partDirs[it] * factor }

    companion object {
        /** Kaydırıcının en sağında parçaların merkezden uzaklığı bu oranda artar. */
        const val MAX_FACTOR = 1.5f

        fun compute(mesh: StepMesh): Explode {
            val k = StepMesh.FLOATS_PER_VERTEX
            val v = mesh.vertices
            val idx = mesh.indices
            val b = mesh.bbox
            val mc = floatArrayOf((b[0] + b[3]) * 0.5f, (b[1] + b[4]) * 0.5f, (b[2] + b[5]) * 0.5f)
            val parts = mesh.partCount
            val partDirs = FloatArray(parts * 3)
            val vertexDirs = FloatArray(mesh.vertexCount * 3)
            val edgeDirs = FloatArray(mesh.edges.size)
            val e = mesh.edges

            for (p in 0 until parts) {
                // Parça merkezi: sınır kutusunun ortası (yüzey yoksa kenarlardan)
                var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
                val tris = mesh.partTriangleRange(p)
                for (t in tris) {
                    for (c in 0 until 3) {
                        val o = idx[t * 3 + c] * k
                        val x = v[o]; val y = v[o + 1]; val z = v[o + 2]
                        if (x < minX) minX = x; if (x > maxX) maxX = x
                        if (y < minY) minY = y; if (y > maxY) maxY = y
                        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                    }
                }
                val segs = mesh.partSegmentRange(p)
                if (minX > maxX) {
                    for (s in segs) {
                        for (c in 0 until 2) {
                            val o = (s * 2 + c) * 3
                            val x = e[o]; val y = e[o + 1]; val z = e[o + 2]
                            if (x < minX) minX = x; if (x > maxX) maxX = x
                            if (y < minY) minY = y; if (y > maxY) maxY = y
                            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                        }
                    }
                }
                if (minX > maxX) continue
                val dx = (minX + maxX) * 0.5f - mc[0]
                val dy = (minY + maxY) * 0.5f - mc[1]
                val dz = (minZ + maxZ) * 0.5f - mc[2]
                partDirs[p * 3] = dx
                partDirs[p * 3 + 1] = dy
                partDirs[p * 3 + 2] = dz
                for (t in tris) {
                    for (c in 0 until 3) {
                        val o = idx[t * 3 + c] * 3
                        vertexDirs[o] = dx
                        vertexDirs[o + 1] = dy
                        vertexDirs[o + 2] = dz
                    }
                }
                for (s in segs) {
                    for (c in 0 until 2) {
                        val o = (s * 2 + c) * 3
                        edgeDirs[o] = dx
                        edgeDirs[o + 1] = dy
                        edgeDirs[o + 2] = dz
                    }
                }
            }
            return Explode(vertexDirs, edgeDirs, partDirs)
        }
    }
}
