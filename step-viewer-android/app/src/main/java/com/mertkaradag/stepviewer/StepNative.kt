package com.mertkaradag.stepviewer

import java.io.IOException

/** OCCT tabanlı yerel kütüphaneye erişim. Tüm çağrılar arka plan iş parçacığında yapılmalıdır. */
object StepNative {
    init {
        System.loadLibrary("stepviewer")
    }

    const val KIND_EDGE = 0
    const val KIND_FACE = 1
    /** Yalnız Kotlin tarafında: ölçümde seçilen nokta. */
    const val KIND_POINT = 2

    /**
     * STEP / IGES / BREP dosyasını okur ve üçgenler (biçim içerikten algılanır).
     *
     * @param relativeDeflection üçgenleme hassasiyeti (model köşegenine oranla); 0 = varsayılan
     * @throws IOException dosya okunamazsa veya geometri yoksa
     */
    @Throws(IOException::class)
    external fun loadModel(path: String, relativeDeflection: Double): StepMesh

    /**
     * Kenar veya yüzey bilgisi:
     * [tür, uzunluk, alan, yarıçap, yarıçap2, açı, cx, cy, cz, dx, dy, dz, çevre]; bulunamazsa null.
     * Çevre yalnız CAD yüzeylerinde (yoksa 0).
     * Tür: [GeomKind] sabitleri.
     */
    external fun shapeInfo(kind: Int, id: Int): DoubleArray?

    /**
     * İki şekil arası ölçüm:
     * [mesafe, p1x, p1y, p1z, p2x, p2y, p2z, açı°, merkez/eksen mesafesi, paralel düzlem mesafesi]
     * (olmayan değerler -1); başarısızsa null.
     */
    external fun measurePair(kindA: Int, idA: Int, kindB: Int, idB: Int): DoubleArray?

    /** Nokta ile kenar/yüzey arası: [mesafe, nokta xyz, şekil üzerindeki en yakın nokta xyz] veya null. */
    external fun measurePoint(x: Double, y: Double, z: Double, kind: Int, id: Int): DoubleArray?

    /** Hacim/alan/ağırlık merkezi: [hacim mm³, alan mm², cx, cy, cz, katı var mı]. null = tüm model. */
    external fun massProps(partIds: IntArray?): DoubleArray?

    /**
     * PMI: [FloatArray segmentler, IntArray (tür, segBaş, segSon, konumVar) * n,
     * FloatArray konumlar, ByteArray UTF-8 metinler ('\n' ayrılmış)]; yoksa null. Bkz. [Pmi.load].
     */
    external fun pmiData(): Array<Any>?

    /**
     * Delik tablosu (yalnızca CAD): [DoubleArray delik başına 17 değer, IntArray yüzey kimlikleri];
     * mesh dosyalarında null. Bkz. [Holes.load]. Arka planda çağrılmalı.
     */
    external fun holes(): Array<Any>?

    /**
     * Düz yüzü lazer kesim için DXF'e çevirir (yalnızca CAD):
     * [ByteArray DXF metni, DoubleArray {genişlik mm, yükseklik mm, iç kontur sayısı}];
     * yüz düz değilse null.
     */
    external fun faceDxf(faceId: Int): Array<Any>?

    /**
     * Teknik resim görünüşleri (yalnızca CAD): [FloatArray segmentler, IntArray aralıklar,
     * DoubleArray sınır kutusu]. Bkz. [TechDrawing.load]. Arka planda çağrılmalı.
     */
    external fun drawingViews(partIds: IntArray?, zUp: Boolean, hidden: Boolean): Array<Any>?

    /**
     * Sac açınımı (yalnızca CAD). faceId < 0: parçanın en büyük düz yüzü taban olur.
     * Bkz. [SheetMetal.unfold]. Arka planda çağrılmalı.
     */
    external fun unfoldSheet(partId: Int, faceId: Int, kFactor: Double): Array<Any>?
}

/** C++ GeomKind ile aynı değerler. */
object GeomKind {
    const val OTHER = 0
    const val LINE = 1
    const val CIRCLE = 2
    const val ELLIPSE = 3
    const val CURVE = 4
    const val PLANE = 10
    const val CYLINDER = 11
    const val CONE = 12
    const val SPHERE = 13
    const val TORUS = 14
    const val SURFACE = 15
}
