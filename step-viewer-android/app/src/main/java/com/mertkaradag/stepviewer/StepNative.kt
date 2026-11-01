package com.mertkaradag.stepviewer

import java.io.IOException

/** OCCT tabanlı yerel kütüphaneye erişim. */
object StepNative {
    init {
        System.loadLibrary("stepviewer")
    }

    /**
     * STEP dosyasını okur ve üçgenler. Uzun sürebilir; arka plan iş parçacığında çağırın.
     *
     * @param path dosyanın yerel yolu
     * @param relativeDeflection üçgenleme hassasiyeti (model köşegenine oranla); 0 = varsayılan
     * @throws IOException dosya okunamazsa veya geometri yoksa
     */
    @Throws(IOException::class)
    external fun loadStep(path: String, relativeDeflection: Double): StepMesh

    /**
     * Kenar bilgisi: [tip, uzunluk, yarıçap, merkezX, merkezY, merkezZ]; bulunamazsa null.
     * Tip: 0 diğer, 1 doğru, 2 çember, 3 elips, 4 eğri (B-spline).
     */
    external fun edgeInfo(edgeId: Int): DoubleArray?

    /**
     * İki kenar arası en kısa mesafe:
     * [mesafe, p1x, p1y, p1z, p2x, p2y, p2z, açı° (yoksa -1), çember merkezleri arası (yoksa -1)]
     */
    external fun measureEdges(edgeA: Int, edgeB: Int): DoubleArray?
}
