package com.mertkaradag.stepviewer

import android.annotation.SuppressLint
import android.content.Context
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
 * - Tek parmak: döndür
 * - İki parmak: kıstırarak yakınlaştır, sürükleyerek kaydır
 * - Çift dokunma: görünümü sıfırla
 */
class ModelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    val camera = Camera()
    private val renderer = ModelRenderer(camera)

    private var lastX = 0f
    private var lastY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var activePointers = 0

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                camera.zoomBy(detector.scaleFactor)
                requestRender()
                return true
            }
        })

    private val tapDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                resetView()
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

    fun setShowEdges(show: Boolean) {
        renderer.showEdges = show
        requestRender()
    }

    fun resetView() {
        camera.reset()
        requestRender()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        tapDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        // Parmak sayısı değişince referans noktalarını yenile; aksi halde sıçrama olur.
        val pointerCount = event.pointerCount
        val focusX = (0 until pointerCount).map { event.getX(it) }.average().toFloat()
        val focusY = (0 until pointerCount).map { event.getY(it) }.average().toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> {
                activePointers = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    pointerCount - 1
                } else {
                    pointerCount
                }
                val upIndex = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
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
                    lastFocusX = lastX
                    lastFocusY = lastY
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointers == 1 && pointerCount == 1) {
                    camera.rotate(focusX - lastX, focusY - lastY)
                    lastX = focusX
                    lastY = focusY
                } else if (activePointers >= 2) {
                    camera.pan(focusX - lastFocusX, focusY - lastFocusY)
                    lastFocusX = focusX
                    lastFocusY = focusY
                }
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
            throw IllegalStateException("Uygun bir OpenGL ES 3 yapılandırması bulunamadı")
        }
    }
}
