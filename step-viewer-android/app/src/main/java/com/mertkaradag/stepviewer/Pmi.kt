package com.mertkaradag.stepviewer

/** STEP AP242 PMI: ölçüler, geometrik toleranslar ve referanslar (yerel taraftan okunur). */
class Pmi private constructor(
    /** Çizim çizgileri: segment başına 2 nokta * 3 float. */
    val segments: FloatArray,
    val items: List<Item>,
) {
    class Item(
        val kind: Int,
        val text: String,
        val segStart: Int,
        val segEnd: Int,
        /** Yazı konumu veya çizimin merkezi; yoksa null. */
        val anchor: FloatArray?,
    ) {
        val segCount: Int get() = segEnd - segStart
    }

    companion object {
        const val KIND_DIMENSION = 0
        const val KIND_TOLERANCE = 1
        const val KIND_DATUM = 2

        /** Yüklü modelin PMI'si; yoksa null. Arka plan iş parçacığında çağrılmalı. */
        fun load(): Pmi? {
            val raw = StepNative.pmiData() ?: return null
            if (raw.size < 4) return null
            val segs = raw[0] as? FloatArray ?: return null
            val info = raw[1] as? IntArray ?: return null
            val anchors = raw[2] as? FloatArray ?: return null
            val texts = String(raw[3] as? ByteArray ?: return null, Charsets.UTF_8).split('\n')
            val n = info.size / 4
            val items = (0 until n).map { i ->
                Item(
                    kind = info[i * 4],
                    text = texts.getOrElse(i) { "" },
                    segStart = info[i * 4 + 1],
                    segEnd = info[i * 4 + 2],
                    anchor = if (info[i * 4 + 3] != 0) anchors.copyOfRange(i * 3, i * 3 + 3) else null,
                )
            }
            return if (items.isEmpty()) null else Pmi(segs, items)
        }
    }
}
