package com.mertkaradag.stepviewer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * 3B model görünümü.
 * - Tek parmak: döndür (bırakınca ataletle devam eder)
 * - İki parmak: kıstırarak yakınlaştır, sürükleyerek kaydır
 * - Çift dokunma: [onDoubleTap] (dokunulan noktayı dönme merkezi yapma)
 * - Tek dokunma: [onTap] ile bildirilir (ölçüm / seçim)
 * - Uzun basma: [onLongPress] ile bildirilir (parça seçimi)
 */
class ModelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    val camera = Camera()
    private val renderer = ModelRenderer(camera) { requestRender() }

    /** Sürükleme olmadan yapılan tek dokunuş (piksel koordinatları). */
    var onTap: ((x: Float, y: Float) -> Unit)? = null

    /** Çift dokunma (piksel koordinatları). */
    var onDoubleTap: ((x: Float, y: Float) -> Unit)? = null

    /** Uzun basma (parça seçimi). */
    var onLongPress: ((x: Float, y: Float) -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f
    private var activePointers = 0
    private var multiTouchHappened = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                camera.zoomBy(detector.scaleFactor)
                requestRender()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val cb = onDoubleTap
                if (cb != null) cb(e.x, e.y) else spin360()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTap?.invoke(e.x, e.y)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (!multiTouchHappened) onLongPress?.invoke(e.x, e.y)
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (!multiTouchHappened) {
                    camera.fling(vx, vy)
                    requestRender()
                }
                return true
            }
        })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MultisampleConfigChooser())
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setMesh(mesh: StepMesh) {
        camera.setBounds(mesh.bbox)
        queueEvent { renderer.setMesh(mesh) }
        requestRender()
    }

    fun setOverlay(overlay: ModelRenderer.Overlay) {
        queueEvent { renderer.setOverlay(overlay) }
        requestRender()
    }

    fun setVisibility(triRanges: IntArray?, segRanges: IntArray?) {
        queueEvent { renderer.setVisibility(triRanges, segRanges) }
        requestRender()
    }

    fun setDisplayMode(mode: ModelRenderer.DisplayMode) {
        queueEvent { renderer.setDisplayMode(mode) }
        requestRender()
    }

    fun setPmi(segments: FloatArray?) {
        queueEvent { renderer.setPmi(segments) }
        requestRender()
    }

    fun setPmiVisible(visible: Boolean, highlight: IntArray = IntArray(0)) {
        queueEvent { renderer.setPmiVisible(visible, highlight) }
        requestRender()
    }

    fun setExplodeDirections(vertexDirs: FloatArray?, edgeDirs: FloatArray?) {
        queueEvent { renderer.setExplodeDirections(vertexDirs, edgeDirs) }
        requestRender()
    }

    fun setExplodeFactor(f: Float) {
        queueEvent { renderer.setExplodeFactor(f) }
        requestRender()
    }

    /** Dokunulan noktayı dönme merkezi yapıp ortaya getirir. */
    fun focusOn(point: FloatArray) {
        camera.animateFocus(point)
        requestRender()
    }

    fun setClipPlane(plane: FloatArray?) {
        queueEvent { renderer.setClipPlane(plane) }
        requestRender()
    }

    /** Görüntüyü alır; callback ana iş parçacığında çağrılır. */
    fun captureScreenshot(callback: (Bitmap?) -> Unit) {
        queueEvent {
            renderer.captureScreenshot { bmp -> post { callback(bmp) } }
        }
        requestRender()
    }

    fun animateTo(view: Camera.View) {
        camera.animateTo(view)
        requestRender()
    }

    fun fit() {
        camera.animateFit()
        requestRender()
    }

    fun spin360() {
        camera.spin360()
        requestRender()
    }

    fun setOrthographic(ortho: Boolean) {
        camera.orthographic = ortho
        requestRender()
    }

    fun setUpAxis(axis: Camera.UpAxis) {
        camera.upAxis = axis
        requestRender()
    }

    /** Anlık kamera matrisi (seçim için). */
    fun currentMvp(): FloatArray {
        val mv = FloatArray(16)
        val mvp = FloatArray(16)
        camera.computeMatrices(mv, mvp)
        return mvp
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // Dokunulunca animasyon, tur ve atalet durur
            camera.stopMotion()
            multiTouchHappened = false
        }
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        val pointerCount = event.pointerCount
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> {
                val isUp = event.actionMasked == MotionEvent.ACTION_POINTER_UP
                activePointers = if (isUp) pointerCount - 1 else pointerCount
                if (activePointers > 1) multiTouchHappened = true
                // Parmak sayısı değişince referans noktasını yenile; aksi halde sıçrama olur.
                val upIndex = if (isUp) event.actionIndex else -1
                var sx = 0f
                var sy = 0f
                var n = 0
                for (i in 0 until pointerCount) {
                    if (i == upIndex) continue
                    sx += event.getX(i)
                    sy += event.getY(i)
                    n++
                }
                if (n > 0) {
                    lastX = sx / n
                    lastY = sy / n
                }
            }

            MotionEvent.ACTION_MOVE -> {
                var sx = 0f
                var sy = 0f
                for (i in 0 until pointerCount) {
                    sx += event.getX(i)
                    sy += event.getY(i)
                }
                val fx = sx / pointerCount
                val fy = sy / pointerCount
                if (activePointers == 1 && pointerCount == 1 && !multiTouchHappened) {
                    camera.rotate(fx - lastX, fy - lastY)
                } else if (activePointers >= 2) {
                    camera.pan(fx - lastX, fy - lastY)
                }
                lastX = fx
                lastY = fy
                requestRender()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> activePointers = 0
        }
        return true
    }

    /** 4x MSAA dener (daha düzgün kenarlar); desteklenmezse normal yapılandırmaya düşer. */
    private class MultisampleConfigChooser : EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val es3Bit = 0x40 // EGL_OPENGL_ES3_BIT_KHR
            val attempts = listOf(
                intArrayOf(
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_RENDERABLE_TYPE, es3Bit,
                    EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4, EGL10.EGL_NONE,
                ),
                intArrayOf(
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 24, EGL10.EGL_RENDERABLE_TYPE, es3Bit, EGL10.EGL_NONE,
                ),
                intArrayOf(
                    EGL10.EGL_RED_SIZE, 5, EGL10.EGL_GREEN_SIZE, 6, EGL10.EGL_BLUE_SIZE, 5,
                    EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, es3Bit, EGL10.EGL_NONE,
                ),
            )
            for (attribs in attempts) {
                val configs = arrayOfNulls<EGLConfig>(1)
                val num = IntArray(1)
                if (egl.eglChooseConfig(display, attribs, configs, 1, num) && num[0] > 0) {
                    configs[0]?.let { return it }
                }
            }
            throw IllegalStateException("No suitable OpenGL ES 3 config found")
        }
    }
}
