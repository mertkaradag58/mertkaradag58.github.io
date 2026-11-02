package com.mertkaradag.stepviewer

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ArrayBlockingQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Artırılmış gerçeklik: model gerçek ölçüsünde (1:1) masaya / zemine yerleştirilir.
 * Bir düzleme dokununca model oraya konur; tek parmakla döndürülür, iki parmakla ölçeklenir.
 * ARCore yoksa veya desteklenmiyorsa açıklama gösterilip kapanır.
 */
class ArActivity : AppCompatActivity(), GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "StepViewerAR"

        /** Görüntülenecek model (Intent ile taşınamayacak kadar büyük olabilir). */
        @Volatile
        var pendingMesh: StepMesh? = null

        /** Gizli parçalar AR'da da gizli olsun: görünen üçgen aralıkları (başlangıç, adet). */
        @Volatile
        var pendingRanges: IntArray? = null
    }

    private lateinit var surface: GLSurfaceView
    private lateinit var hint: TextView
    private lateinit var scaleText: TextView

    private var session: Session? = null
    private var installRequested = false
    private var mesh: StepMesh? = null
    private var ranges: IntArray? = null

    // Dokunmalar GL iş parçacığında işlenir
    private val taps = ArrayBlockingQueue<FloatArray>(4)
    @Volatile private var userScale = 1f
    @Volatile private var userRotation = 0f // derece, yukarı eksen etrafında
    private var anchor: Anchor? = null
    private var viewportChanged = false
    private var viewWidth = 1
    private var viewHeight = 1

    // GL kaynakları
    private var cameraTexture = 0
    private var bgProgram = 0
    private var meshProgram = 0
    private var planeProgram = 0
    private var vbo = 0
    private var ibo = 0
    private var indexCount = 0
    private val quadCoords: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadTexCoords: FloatBuffer = floatBuffer(FloatArray(8))
    private var uMvp = 0
    private var uModelView = 0
    private var uPlaneMvp = 0

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val tmp = FloatArray(16)
    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(this, R.string.ar_camera_needed, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mesh = pendingMesh
        ranges = pendingRanges
        if (mesh == null) {
            finish()
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_ar)
        surface = findViewById(R.id.ar_surface)
        hint = findViewById(R.id.ar_hint)
        scaleText = findViewById(R.id.ar_scale)
        findViewById<MaterialButton>(R.id.ar_close).setOnClickListener { finish() }
        findViewById<MaterialButton>(R.id.ar_reset).setOnClickListener {
            userScale = 1f
            userRotation = 0f
            updateScaleText()
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.ar_root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            findViewById<View>(R.id.ar_top).updatePadding(top = bars.top, left = bars.left, right = bars.right)
            findViewById<View>(R.id.ar_bottom).updatePadding(bottom = bars.bottom + 16, left = bars.left, right = bars.right)
            insets
        }

        surface.preserveEGLContextOnPause = true
        surface.setEGLContextClientVersion(3)
        surface.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surface.setRenderer(this)
        surface.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                userScale = (userScale * d.scaleFactor).coerceIn(0.01f, 100f)
                runOnUiThread { updateScaleText() }
                return true
            }
        })
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                taps.offer(floatArrayOf(e.x, e.y))
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (e2.pointerCount == 1) userRotation -= dx * 0.4f
                return true
            }
        })
        surface.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            gestures.onTouchEvent(ev)
            true
        }
        updateScaleText()
    }

    private fun updateScaleText() {
        scaleText.text = if (kotlin.math.abs(userScale - 1f) < 0.005f) {
            getString(R.string.ar_scale_real)
        } else {
            getString(R.string.ar_scale_value, fmt(userScale.toDouble(), 2))
        }
    }

    override fun onResume() {
        super.onResume()
        if (mesh == null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCamera.launch(Manifest.permission.CAMERA)
            return
        }
        if (session == null) {
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }
                    else -> Unit
                }
                val s = Session(this)
                val config = Config(s)
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                config.focusMode = Config.FocusMode.AUTO
                s.configure(config)
                session = s
            } catch (e: UnavailableException) {
                Log.w(TAG, "ARCore kullanılamıyor", e)
                Toast.makeText(this, R.string.ar_unsupported, Toast.LENGTH_LONG).show()
                finish()
                return
            } catch (e: Exception) {
                Log.w(TAG, "AR oturumu açılamadı", e)
                Toast.makeText(this, R.string.ar_unsupported, Toast.LENGTH_LONG).show()
                finish()
                return
            }
        }
        try {
            session?.resume()
        } catch (e: CameraNotAvailableException) {
            Toast.makeText(this, R.string.ar_camera_needed, Toast.LENGTH_LONG).show()
            session = null
            finish()
            return
        }
        surface.onResume()
    }

    override fun onPause() {
        super.onPause()
        if (session != null) {
            surface.onPause()
            session?.pause()
        }
    }

    override fun onDestroy() {
        session?.close()
        session = null
        if (isFinishing) {
            pendingMesh = null
            pendingRanges = null
        }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ GL

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        cameraTexture = tex[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        bgProgram = buildProgram(BG_VS, BG_FS)
        meshProgram = buildProgram(MESH_VS, MESH_FS)
        uMvp = GLES30.glGetUniformLocation(meshProgram, "uMvp")
        uModelView = GLES30.glGetUniformLocation(meshProgram, "uModelView")
        planeProgram = buildProgram(PLANE_VS, PLANE_FS)
        uPlaneMvp = GLES30.glGetUniformLocation(planeProgram, "uMvp")

        val ids = IntArray(2)
        GLES30.glGenBuffers(2, ids, 0)
        vbo = ids[0]
        ibo = ids[1]
        val m = mesh ?: return
        try {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, m.vertices.size * 4, floatBuffer(m.vertices), GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
            val ib = ByteBuffer.allocateDirect(m.indices.size * 4).order(ByteOrder.nativeOrder())
            ib.asIntBuffer().put(m.indices)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.indices.size * 4, ib, GLES30.GL_STATIC_DRAW)
            indexCount = m.indices.size
        } catch (e: OutOfMemoryError) {
            indexCount = 0
        }
        session?.setCameraTextureName(cameraTexture)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (viewportChanged) {
            @Suppress("DEPRECATION")
            val rotation = windowManager.defaultDisplay.rotation
            s.setDisplayGeometry(rotation, viewWidth, viewHeight)
            viewportChanged = false
        }
        s.setCameraTextureName(cameraTexture)
        val frame: Frame = try {
            s.update()
        } catch (e: Exception) {
            return
        }
        drawBackground(frame)

        val camera = frame.camera
        if (camera.trackingState != TrackingState.TRACKING) {
            setHint(R.string.ar_hint_move)
            return
        }
        handleTap(frame)

        camera.getProjectionMatrix(projection, 0, 0.01f, 100f)
        camera.getViewMatrix(view, 0)

        val a = anchor
        if (a == null) {
            drawPlanes(s)
            val anyPlane = s.getAllTrackables(Plane::class.java).any { it.trackingState == TrackingState.TRACKING }
            setHint(if (anyPlane) R.string.ar_hint_tap else R.string.ar_hint_move)
            return
        }
        setHint(0)
        if (a.trackingState == TrackingState.TRACKING) drawModel(a)
    }

    private var lastHint = -1
    private fun setHint(res: Int) {
        if (res == lastHint) return
        lastHint = res
        runOnUiThread {
            if (res == 0) {
                hint.visibility = View.GONE
            } else {
                hint.setText(res)
                hint.visibility = View.VISIBLE
            }
        }
    }

    private fun handleTap(frame: Frame) {
        val tap = taps.poll() ?: return
        for (hit in frame.hitTest(tap[0], tap[1])) {
            val t = hit.trackable
            if (t is Plane && t.isPoseInPolygon(hit.hitPose)) {
                anchor?.detach()
                anchor = hit.createAnchor()
                break
            }
        }
    }

    private fun drawBackground(frame: Frame) {
        if (frame.hasDisplayGeometryChanged()) {
            quadCoords.position(0)
            quadTexCoords.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES, quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED, quadTexCoords,
            )
            quadCoords.position(0)
            quadTexCoords.position(0)
        }
        if (frame.timestamp == 0L) return
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(bgProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quadCoords)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, quadTexCoords)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    /** Algılanan düzlemler: yarı saydam turuncu. */
    private fun drawPlanes(s: Session) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(planeProgram)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        for (plane in s.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
            val poly = plane.polygon ?: continue
            val n = poly.limit() / 2
            if (n < 3) continue
            plane.centerPose.toMatrix(model, 0)
            Matrix.multiplyMM(tmp, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, projection, 0, tmp, 0)
            GLES30.glUniformMatrix4fv(uPlaneMvp, 1, false, mvp, 0)
            poly.rewind()
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, poly)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, n)
            GLES30.glDisableVertexAttribArray(0)
        }
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawModel(a: Anchor) {
        val m = mesh ?: return
        if (indexCount == 0) return
        val b = m.bbox
        // Model mm ve Z-yukarı; AR metre ve Y-yukarı. Taban düzleme oturur, merkez çapada.
        a.pose.toMatrix(model, 0)
        Matrix.rotateM(model, 0, userRotation, 0f, 1f, 0f)
        val s = 0.001f * userScale
        Matrix.scaleM(model, 0, s, s, s)
        Matrix.rotateM(model, 0, -90f, 1f, 0f, 0f)
        Matrix.translateM(model, 0, -(b[0] + b[3]) / 2f, -(b[1] + b[4]) / 2f, -b[2])

        Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)

        GLES30.glUseProgram(meshProgram)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(uModelView, 1, false, modelView, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        val stride = StepMesh.FLOATS_PER_VERTEX * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, 24)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        val r = ranges
        if (r == null) {
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
        } else {
            var i = 0
            while (i + 1 < r.size) {
                if (r[i + 1] > 0) GLES30.glDrawElements(GLES30.GL_TRIANGLES, r[i + 1] * 3, GLES30.GL_UNSIGNED_INT, r[i] * 12)
                i += 2
            }
        }
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun floatBuffer(data: FloatArray): FloatBuffer {
        val fb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(data)
        fb.position(0)
        return fb
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val sh = GLES30.glCreateShader(type)
            GLES30.glShaderSource(sh, src)
            GLES30.glCompileShader(sh)
            val st = IntArray(1)
            GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, st, 0)
            if (st[0] == 0) Log.e(TAG, "Shader: " + GLES30.glGetShaderInfoLog(sh))
            return sh
        }
        val p = GLES30.glCreateProgram()
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        return p
    }

    private val BG_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aTex;
out vec2 vTex;
void main() {
    vTex = aTex;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

    private val BG_FS = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 vTex;
uniform samplerExternalOES uTex;
out vec4 fragColor;
void main() {
    fragColor = texture(uTex, vTex);
}
"""

    private val MESH_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec3 aColor;
uniform mat4 uMvp;
uniform mat4 uModelView;
out vec3 vNormal;
out vec3 vColor;
out vec3 vViewPos;
void main() {
    vViewPos = (uModelView * vec4(aPos, 1.0)).xyz;
    vNormal = mat3(uModelView) * aNormal;
    vColor = aColor;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

    private val MESH_FS = """#version 300 es
precision highp float;
in vec3 vNormal;
in vec3 vColor;
in vec3 vViewPos;
out vec4 fragColor;
void main() {
    vec3 n = normalize(vNormal);
    vec3 v = normalize(-vViewPos);
    if (dot(n, v) < 0.0) n = -n;
    vec3 key = normalize(vec3(-0.3, 0.8, 0.5));
    float diffuse = 0.55 * max(dot(n, key), 0.0) + 0.30 * max(dot(n, v), 0.0);
    vec3 h = normalize(key + v);
    float spec = 0.2 * pow(max(dot(n, h), 0.0), 40.0);
    fragColor = vec4(vColor * (0.30 + diffuse) + vec3(spec), 1.0);
}
"""

    private val PLANE_VS = """#version 300 es
layout(location = 0) in vec2 aXZ;
uniform mat4 uMvp;
void main() {
    gl_Position = uMvp * vec4(aXZ.x, 0.0, aXZ.y, 1.0);
}
"""

    private val PLANE_FS = """#version 300 es
precision mediump float;
out vec4 fragColor;
void main() {
    fragColor = vec4(1.0, 0.55, 0.1, 0.28);
}
"""

}
