package com.mertkaradag.stepviewer

import android.opengl.Matrix
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Modelin etrafında dönen (orbit) kamera. Dokunma iş parçacığından güncellenir,
 * GL iş parçacığından okunur; bu yüzden tüm erişimler senkronizedir.
 */
class Camera {
    private val rotation = FloatArray(16)
    private val tmp = FloatArray(16)
    private val tmp2 = FloatArray(16)

    private var center = floatArrayOf(0f, 0f, 0f)
    private var radius = 1f
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var viewWidth = 1
    private var viewHeight = 1

    init {
        resetRotation()
    }

    @Synchronized
    fun setBounds(bbox: FloatArray) {
        center = floatArrayOf(
            (bbox[0] + bbox[3]) * 0.5f,
            (bbox[1] + bbox[4]) * 0.5f,
            (bbox[2] + bbox[5]) * 0.5f,
        )
        val dx = bbox[3] - bbox[0]
        val dy = bbox[4] - bbox[1]
        val dz = bbox[5] - bbox[2]
        radius = max(sqrt(dx * dx + dy * dy + dz * dz) * 0.5f, 1e-6f)
        reset()
    }

    @Synchronized
    fun setViewport(width: Int, height: Int) {
        viewWidth = max(width, 1)
        viewHeight = max(height, 1)
    }

    /** İzometrik görünüme ve modeli ekrana sığdıran yakınlaştırmaya döner. */
    @Synchronized
    fun reset() {
        zoom = 1f
        panX = 0f
        panY = 0f
        resetRotation()
    }

    private fun resetRotation() {
        // CAD modelleri genelde Z-yukarı; önce Z'yi yukarı çevir, sonra izometrik açı.
        Matrix.setIdentityM(rotation, 0)
        rotateScreen(-90f, 1f, 0f, 0f)
        rotateScreen(-45f, 0f, 1f, 0f)
        rotateScreen(35.264f, 1f, 0f, 0f)
    }

    private fun rotateScreen(angleDeg: Float, x: Float, y: Float, z: Float) {
        Matrix.setRotateM(tmp, 0, angleDeg, x, y, z)
        Matrix.multiplyMM(tmp2, 0, tmp, 0, rotation, 0)
        System.arraycopy(tmp2, 0, rotation, 0, 16)
    }

    /** Tek parmak sürükleme: ekran eksenleri etrafında döndür. */
    @Synchronized
    fun rotate(dxPixels: Float, dyPixels: Float) {
        val degPerPixel = 180f / max(viewWidth, viewHeight)
        if (dxPixels != 0f) rotateScreen(dxPixels * degPerPixel, 0f, 1f, 0f)
        if (dyPixels != 0f) rotateScreen(dyPixels * degPerPixel, 1f, 0f, 0f)
    }

    /** İki parmak sürükleme: ekran düzleminde kaydır. */
    @Synchronized
    fun pan(dxPixels: Float, dyPixels: Float) {
        val unitsPerPixel = 2f * distance() * tan(Math.toRadians(FOV_Y / 2.0)).toFloat() / viewHeight
        panX += dxPixels * unitsPerPixel
        panY -= dyPixels * unitsPerPixel
    }

    /** Kıstırma: yakınlaştır / uzaklaştır. */
    @Synchronized
    fun zoomBy(factor: Float) {
        zoom = (zoom * factor).coerceIn(0.05f, 500f)
    }

    private fun distance(): Float {
        val fit = radius / Math.sin(Math.toRadians(FOV_Y / 2.0)).toFloat()
        // Yatay ekranda da modelin sığması için dar kenara göre ayarla.
        val aspect = viewWidth.toFloat() / viewHeight
        val fitAdjusted = if (aspect < 1f) fit / aspect else fit
        return fitAdjusted * 1.05f / zoom
    }

    /** modelView ve mvp matrislerini doldurur. */
    @Synchronized
    fun computeMatrices(modelView: FloatArray, mvp: FloatArray) {
        val dist = distance()
        // modelView = T(pan, -dist) * R * T(-center)
        Matrix.setIdentityM(tmp, 0)
        Matrix.translateM(tmp, 0, -center[0], -center[1], -center[2])
        Matrix.multiplyMM(tmp2, 0, rotation, 0, tmp, 0)
        Matrix.setIdentityM(tmp, 0)
        Matrix.translateM(tmp, 0, panX, panY, -dist)
        Matrix.multiplyMM(modelView, 0, tmp, 0, tmp2, 0)

        val near = max(dist - radius * 2f, dist * 0.01f)
        val far = dist + radius * 2f
        val proj = FloatArray(16)
        Matrix.perspectiveM(proj, 0, FOV_Y.toFloat(), viewWidth.toFloat() / viewHeight, near, far)
        Matrix.multiplyMM(mvp, 0, proj, 0, modelView, 0)
    }

    companion object {
        const val FOV_Y = 35.0
    }
}
