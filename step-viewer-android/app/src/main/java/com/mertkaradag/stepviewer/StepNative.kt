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
}
