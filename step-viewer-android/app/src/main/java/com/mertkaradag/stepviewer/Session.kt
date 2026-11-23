package com.mertkaradag.stepviewer

import android.net.Uri

/**
 * Açık model bilgisi süreç boyunca tutulur. Tema veya dil değişince ekran yeniden oluşturulur;
 * model yerel tarafta zaten açık olduğu için yeniden okunmadan buradan geri yüklenir.
 */
object Session {
    var mesh: StepMesh? = null
    var name: String = ""
    var uri: Uri? = null
    var subtitle: String = ""
    var partVisible: BooleanArray? = null
    var partColor: IntArray? = null
    var partTransparent: BooleanArray? = null
    var camera: FloatArray? = null
    var displayMode: ModelRenderer.DisplayMode? = null
    var yUp = false

    fun clear() {
        mesh = null
        name = ""
        uri = null
        subtitle = ""
        partVisible = null
        partColor = null
        partTransparent = null
        camera = null
        displayMode = null
        yUp = false
    }
}
