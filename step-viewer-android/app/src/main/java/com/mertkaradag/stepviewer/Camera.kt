package com.mertkaradag.stepviewer

import android.opengl.Matrix
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Modelin etrafında dönen kamera.
 *
 * - Sürükleme "döner tabla" gibi çalışır: yatay hareket modeli yukarı ekseni etrafında,
 *   dikey hareket ekran yatay ekseni etrafında döndürür (model yan yatmaz).
 * - Standart görünümlere ve sığdırmaya yumuşak animasyonla geçer.
 * - Çift dokunma ile 360° tur, parmak savrulunca ataletle dönmeye devam eder.
 *
 * Dokunma iş parçacığından güncellenir, GL iş parçacığından okunur; tüm erişimler senkronizedir.
 */
class Camera {

    enum class View { FRONT, BACK, TOP, BOTTOM, LEFT, RIGHT, ISO }

    /** Modelin yukarı ekseni (CAD programına göre değişir). */
    enum class UpAxis { Z, Y }

    private var q = Quat.IDENTITY
    private var center = floatArrayOf(0f, 0f, 0f)
    private var radius = 1f
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var viewWidth = 1
    private var viewHeight = 1

    var orthographic = false
        @Synchronized get
        @Synchronized set

    var upAxis = UpAxis.Z
        @Synchronized get
        @Synchronized set(value) {
            field = value
            animateTo(View.ISO)
        }

    // --- Animasyon durumu ---
    private var animFromQ = q
    private var animToQ = q
    private var animFromZoom = 1f
    private var animToZoom = 1f
    private var animFromPanX = 0f
    private var animFromPanY = 0f
    private var animToPanX = 0f
    private var animToPanY = 0f
    private var animStart = 0L
    private var animDuration = 0L
    private var animating = false

    // 360° tur
    private var spinStart = 0L
    private var spinApplied = 0f
    private var spinning = false

    // Savurma ataleti (piksel/sn)
    private var flingVx = 0f
    private var flingVy = 0f
    private var lastUpdate = 0L

    private val tmp = FloatArray(16)
    private val tmp2 = FloatArray(16)
    private val rot = FloatArray(16)
    private val proj = FloatArray(16)

    init {
        q = viewQuat(View.ISO)
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
        stopMotion()
        q = viewQuat(View.ISO)
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    @Synchronized
    fun setViewport(width: Int, height: Int) {
        viewWidth = max(width, 1)
        viewHeight = max(height, 1)
    }

    /** Görünüme (ve sığdırmaya) yumuşak geçiş. */
    @Synchronized
    fun animateTo(view: View, durationMs: Long = 450) {
        stopMotion()
        animFromQ = q
        animToQ = viewQuat(view)
        animFromZoom = zoom
        animToZoom = 1f
        animFromPanX = panX
        animFromPanY = panY
        animToPanX = 0f
        animToPanY = 0f
        animStart = now()
        animDuration = durationMs
        animating = true
    }

    /** Yönü koruyarak modeli ekrana sığdırır. */
    @Synchronized
    fun animateFit(durationMs: Long = 350) {
        stopMotion()
        animFromQ = q
        animToQ = q
        animFromZoom = zoom
        animToZoom = 1f
        animFromPanX = panX
        animFromPanY = panY
        animToPanX = 0f
        animToPanY = 0f
        animStart = now()
        animDuration = durationMs
        animating = true
    }

    /** Döner tabla gibi tam bir tur (360°). */
    @Synchronized
    fun spin360() {
        stopMotion()
        spinStart = now()
        spinApplied = 0f
        spinning = true
    }

    @Synchronized
    fun fling(vx: Float, vy: Float) {
        stopMotion()
        flingVx = vx
        flingVy = vy
        lastUpdate = now()
    }

    /** Kullanıcı ekrana dokununca tüm hareketler durur. */
    @Synchronized
    fun stopMotion() {
        animating = false
        spinning = false
        flingVx = 0f
        flingVy = 0f
    }

    @get:Synchronized
    val isMoving: Boolean get() = animating || spinning || flingVx != 0f || flingVy != 0f

    /** Her karede GL iş parçacığından çağrılır. Hareket sürüyorsa true döner. */
    @Synchronized
    fun update(): Boolean {
        val t = now()
        if (animating) {
            val f = ((t - animStart).toFloat() / animDuration).coerceIn(0f, 1f)
            val e = easeInOut(f)
            q = Quat.slerp(animFromQ, animToQ, e)
            zoom = animFromZoom + (animToZoom - animFromZoom) * e
            panX = animFromPanX + (animToPanX - animFromPanX) * e
            panY = animFromPanY + (animToPanY - animFromPanY) * e
            if (f >= 1f) animating = false
        }
        if (spinning) {
            val f = ((t - spinStart).toFloat() / SPIN_DURATION_MS).coerceIn(0f, 1f)
            val target = 360f * easeInOut(f)
            turntable(target - spinApplied)
            spinApplied = target
            if (f >= 1f) spinning = false
        }
        if (flingVx != 0f || flingVy != 0f) {
            val dt = ((t - lastUpdate) / 1000f).coerceIn(0f, 0.05f)
            lastUpdate = t
            rotateByPixels(flingVx * dt, flingVy * dt)
            val decay = exp(-4f * dt)
            flingVx *= decay
            flingVy *= decay
            if (abs(flingVx) < 15f && abs(flingVy) < 15f) {
                flingVx = 0f
                flingVy = 0f
            }
        }
        return isMoving
    }

    /** Tek parmak sürükleme. */
    @Synchronized
    fun rotate(dxPixels: Float, dyPixels: Float) = rotateByPixels(dxPixels, dyPixels)

    private fun rotateByPixels(dxPixels: Float, dyPixels: Float) {
        val degPerPixel = 200f / max(viewWidth, viewHeight)
        if (dxPixels != 0f) turntable(dxPixels * degPerPixel)
        if (dyPixels != 0f) {
            // Ekran yatay ekseni etrafında (soldan çarpım)
            q = (Quat.axisAngle(1f, 0f, 0f, dyPixels * degPerPixel) * q).normalized()
        }
    }

    /** Modelin yukarı ekseni etrafında döndür (sağdan çarpım: model uzayında). */
    private fun turntable(angleDeg: Float) {
        val axis = if (upAxis == UpAxis.Z) floatArrayOf(0f, 0f, 1f) else floatArrayOf(0f, 1f, 0f)
        q = (q * Quat.axisAngle(axis[0], axis[1], axis[2], angleDeg)).normalized()
    }

    /** İki parmak sürükleme: ekran düzleminde kaydır. */
    @Synchronized
    fun pan(dxPixels: Float, dyPixels: Float) {
        val unitsPerPixel = 2f * halfHeightAtFocus() / viewHeight
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
        // Dikey ekranda da modelin sığması için dar kenara göre ayarla.
        val aspect = viewWidth.toFloat() / viewHeight
        val fitAdjusted = if (aspect < 1f) fit / aspect else fit
        return fitAdjusted * 1.05f / zoom
    }

    private fun halfHeightAtFocus(): Float =
        distance() * tan(Math.toRadians(FOV_Y / 2.0)).toFloat()

    /** modelView ve mvp matrislerini doldurur. */
    @Synchronized
    fun computeMatrices(modelView: FloatArray, mvp: FloatArray) {
        val dist = distance()
        // modelView = T(pan, -dist) * R * T(-center)
        q.toMatrix(rot)
        Matrix.setIdentityM(tmp, 0)
        Matrix.translateM(tmp, 0, -center[0], -center[1], -center[2])
        Matrix.multiplyMM(tmp2, 0, rot, 0, tmp, 0)
        Matrix.setIdentityM(tmp, 0)
        Matrix.translateM(tmp, 0, panX, panY, -dist)
        Matrix.multiplyMM(modelView, 0, tmp, 0, tmp2, 0)

        val near = max(dist - radius * 2f, dist * 0.01f)
        val far = dist + radius * 2f
        val aspect = viewWidth.toFloat() / viewHeight
        if (orthographic) {
            val h = halfHeightAtFocus()
            Matrix.orthoM(proj, 0, -h * aspect, h * aspect, -h, h, near, far)
        } else {
            Matrix.perspectiveM(proj, 0, FOV_Y.toFloat(), aspect, near, far)
        }
        Matrix.multiplyMM(mvp, 0, proj, 0, modelView, 0)
    }

    /** Görünüm için dünya→kamera dönüşü. */
    private fun viewQuat(view: View): Quat {
        // Önce Z-yukarı çerçevede tanımla: (arka = kameraya doğru, yukarı)
        val (back, up) = when (view) {
            View.FRONT -> floatArrayOf(0f, -1f, 0f) to floatArrayOf(0f, 0f, 1f)
            View.BACK -> floatArrayOf(0f, 1f, 0f) to floatArrayOf(0f, 0f, 1f)
            View.TOP -> floatArrayOf(0f, 0f, 1f) to floatArrayOf(0f, 1f, 0f)
            View.BOTTOM -> floatArrayOf(0f, 0f, -1f) to floatArrayOf(0f, -1f, 0f)
            View.RIGHT -> floatArrayOf(1f, 0f, 0f) to floatArrayOf(0f, 0f, 1f)
            View.LEFT -> floatArrayOf(-1f, 0f, 0f) to floatArrayOf(0f, 0f, 1f)
            View.ISO -> {
                val s = 1f / sqrt(3f)
                val b = floatArrayOf(s, -s, s)
                // Z eksenini arka vektörüne dik hale getir
                val d = b[2]
                val u = floatArrayOf(-d * b[0], -d * b[1], 1f - d * b[2])
                val n = sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2])
                b to floatArrayOf(u[0] / n, u[1] / n, u[2] / n)
            }
        }
        val right = cross(up, back)
        val zUp = Quat.fromBasis(right, up, back)
        // Y-yukarı modeller: model koordinatlarını Z-yukarıya çevir (X ekseni etrafında +90°)
        return if (upAxis == UpAxis.Z) zUp else (zUp * Quat.axisAngle(1f, 0f, 0f, 90f)).normalized()
    }

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun easeInOut(t: Float): Float =
        if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f

    private fun now(): Long = System.nanoTime() / 1_000_000L

    companion object {
        const val FOV_Y = 35.0
        const val SPIN_DURATION_MS = 3500f
    }
}
