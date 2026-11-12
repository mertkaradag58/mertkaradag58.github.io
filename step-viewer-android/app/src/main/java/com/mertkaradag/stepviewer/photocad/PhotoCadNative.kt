package com.mertkaradag.stepviewer.photocad

import java.io.IOException

/** C++ photo_cad.cpp köprüsü. Arka plan iş parçacığında çağrılmalı. */
object PhotoCadNative {
    init {
        System.loadLibrary("stepviewer")
    }

    const val MODE_EXTRUDE = 0
    const val MODE_TWO_VIEW = 1
    const val MODE_REVOLVE = 2

    /**
     * Katı oluşturup STEP yazar.
     * @return [hacim mm³, x, y, z boyutları mm]
     * @throws IOException hata kodu ile (open_profile, bad_profile, build_failed, empty_result, write_failed, bad_param)
     */
    @Throws(IOException::class)
    external fun buildSolid(
        mode: Int, sketchA: DoubleArray, sketchB: DoubleArray?, param: Double, axis: Int,
        outPath: String, name: String,
    ): DoubleArray
}
