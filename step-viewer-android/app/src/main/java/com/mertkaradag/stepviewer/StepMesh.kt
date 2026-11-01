package com.mertkaradag.stepviewer

/**
 * Yerel (C++) taraftan dönen model.
 *
 * Yapıcı imzası jni_bridge.cpp içindeki "([F[I[F[I[I[I[I[F[B[I[I[I[II)V" ile aynı kalmalıdır.
 *
 * @property vertices köşe başına 9 float: x, y, z, nx, ny, nz, r, g, b
 * @property indices üçgen indeksleri (3 * üçgen sayısı)
 * @property edges kenar çizgileri: segment başına 2 nokta * 3 float
 * @property faceOffsets yüzey i -> üçgenler [faceOffsets[i], faceOffsets[i+1])
 * @property edgeOffsets kenar i -> segmentler
 * @property partFaceOffsets parça i -> yüzey kimlikleri
 * @property partEdgeOffsets parça i -> kenar kimlikleri
 * @property bbox minX, minY, minZ, maxX, maxY, maxZ
 */
class StepMesh(
    val vertices: FloatArray,
    val indices: IntArray,
    val edges: FloatArray,
    val faceOffsets: IntArray,
    val edgeOffsets: IntArray,
    val partFaceOffsets: IntArray,
    val partEdgeOffsets: IntArray,
    val bbox: FloatArray,
    treeNamesUtf8: ByteArray,
    treeDepth: IntArray,
    treeLeafStart: IntArray,
    treeLeafEnd: IntArray,
    treeIsAssembly: IntArray,
    val format: Int,
) {
    /** Montaj ağacı düğümü; yaprak düğümler parçalardır. */
    class TreeNode(
        val name: String,
        val depth: Int,
        val leafStart: Int,
        val leafEnd: Int,
        val isAssembly: Boolean,
    )

    val tree: List<TreeNode>

    /** Parça i için ağaç düğümü (isim için). */
    val partNodes: Array<TreeNode?>

    init {
        val names = String(treeNamesUtf8, Charsets.UTF_8).split('\n')
        tree = treeDepth.indices.map { i ->
            TreeNode(
                name = names.getOrElse(i) { "" },
                depth = treeDepth[i],
                leafStart = treeLeafStart[i],
                leafEnd = treeLeafEnd[i],
                isAssembly = treeIsAssembly[i] != 0,
            )
        }
        partNodes = arrayOfNulls(partCount)
        for (n in tree) {
            if (!n.isAssembly && n.leafStart < partCount) partNodes[n.leafStart] = n
        }
    }

    val vertexCount: Int get() = vertices.size / FLOATS_PER_VERTEX
    val triangleCount: Int get() = indices.size / 3
    val edgeCount: Int get() = edgeOffsets.size - 1
    val faceCount: Int get() = faceOffsets.size - 1
    val partCount: Int get() = partFaceOffsets.size - 1

    val formatName: String
        get() = when (format) {
            FORMAT_STEP -> "STEP"
            FORMAT_IGES -> "IGES"
            FORMAT_BREP -> "BREP"
            else -> "?"
        }

    // --- Kimlik eşlemeleri (ikili arama) ---

    fun edgeOfSegment(segment: Int): Int = rangeIndex(edgeOffsets, segment)
    fun faceOfTriangle(triangle: Int): Int = rangeIndex(faceOffsets, triangle)
    fun partOfFace(face: Int): Int = rangeIndex(partFaceOffsets, face)
    fun partOfEdge(edge: Int): Int = rangeIndex(partEdgeOffsets, edge)

    /** Bir kenarın segment koordinatlarını (çizim için) döndürür. */
    fun edgeSegments(edgeId: Int): FloatArray {
        if (edgeId < 0 || edgeId >= edgeCount) return FloatArray(0)
        return edges.copyOfRange(edgeOffsets[edgeId] * 6, edgeOffsets[edgeId + 1] * 6)
    }

    /** Parçanın üçgen aralığı [ilk, son). Yüzeyler ve parçalar ardışık sıralıdır. */
    fun partTriangleRange(part: Int): IntRange {
        val f0 = partFaceOffsets[part]
        val f1 = partFaceOffsets[part + 1]
        return faceOffsets[f0] until faceOffsets[f1]
    }

    /** Parçanın kenar segment aralığı [ilk, son). */
    fun partSegmentRange(part: Int): IntRange {
        val e0 = partEdgeOffsets[part]
        val e1 = partEdgeOffsets[part + 1]
        return edgeOffsets[e0] until edgeOffsets[e1]
    }

    fun partName(part: Int, fallback: String): String {
        val n = partNodes.getOrNull(part)?.name
        return if (n.isNullOrBlank()) fallback else n
    }

    /** Parçayı içeren montajların adları (dıştan içe; adsız montajlar atlanır). */
    fun partPath(part: Int): List<String> =
        tree.filter { it.isAssembly && part >= it.leafStart && part < it.leafEnd && it.name.isNotBlank() }
            .sortedBy { it.depth }
            .map { it.name }

    private fun rangeIndex(offsets: IntArray, value: Int): Int {
        var lo = 0
        var hi = offsets.size - 2
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                value < offsets[mid] -> hi = mid - 1
                value >= offsets[mid + 1] -> lo = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    companion object {
        const val FLOATS_PER_VERTEX = 9
        const val FORMAT_STEP = 1
        const val FORMAT_IGES = 2
        const val FORMAT_BREP = 3
    }
}
