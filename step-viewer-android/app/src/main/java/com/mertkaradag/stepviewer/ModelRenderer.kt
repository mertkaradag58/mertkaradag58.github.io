package com.mertkaradag.stepviewer

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** OpenGL ES 3.0 ile üçgen ağını ve kenar çizgilerini çizer. */
class ModelRenderer(private val camera: Camera) : GLSurfaceView.Renderer {

    @Volatile
    var showEdges: Boolean = true

    // GL iş parçacığında erişilir. Bağlam kaybolursa yeniden yüklemek için saklanır.
    private var mesh: StepMesh? = null
    private var meshDirty = false

    private var meshProgram = 0
    private var lineProgram = 0
    private var vbo = 0
    private var ibo = 0
    private var edgeVbo = 0
    private var indexCount = 0
    private var edgeVertexCount = 0

    // Ölçüm: seçili kenarlar ve ölçü çizgisi
    private var highlightVbo = 0
    private var measureVbo = 0
    private var highlightData: FloatArray? = null
    private var measureData: FloatArray? = null
    private var highlightDirty = false
    private var highlightVertexCount = 0
    private var measureVertexCount = 0

    private var uMvp = 0
    private var uModelView = 0
    private var uLineMvp = 0
    private var uLineColor = 0
    private var uPointSize = 0

    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)

    /** GL iş parçacığında çağrılmalıdır (queueEvent ile). */
    fun setMesh(newMesh: StepMesh?) {
        mesh = newMesh
        meshDirty = true
        setSelection(null, null)
    }

    /**
     * GL iş parçacığında çağrılmalıdır.
     * @param highlight seçili kenarların segmentleri (xyz çiftleri) veya null
     * @param measure ölçü çizgisinin iki ucu [x1,y1,z1,x2,y2,z2] veya null
     */
    fun setSelection(highlight: FloatArray?, measure: FloatArray?) {
        highlightData = highlight
        measureData = measure
        highlightDirty = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.15f, 0.17f, 0.20f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)

        meshProgram = buildProgram(MESH_VS, MESH_FS)
        uMvp = GLES30.glGetUniformLocation(meshProgram, "uMvp")
        uModelView = GLES30.glGetUniformLocation(meshProgram, "uModelView")

        lineProgram = buildProgram(LINE_VS, LINE_FS)
        uLineMvp = GLES30.glGetUniformLocation(lineProgram, "uMvp")
        uLineColor = GLES30.glGetUniformLocation(lineProgram, "uColor")
        uPointSize = GLES30.glGetUniformLocation(lineProgram, "uPointSize")

        val ids = IntArray(5)
        GLES30.glGenBuffers(5, ids, 0)
        vbo = ids[0]
        ibo = ids[1]
        edgeVbo = ids[2]
        highlightVbo = ids[3]
        measureVbo = ids[4]

        // Yeni bağlam: GPU tamponları boş, ağı tekrar yükle.
        meshDirty = true
        highlightDirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        camera.setViewport(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (meshDirty) {
            uploadMesh()
            meshDirty = false
        }
        if (highlightDirty) {
            highlightVertexCount = upload(highlightVbo, highlightData)
            measureVertexCount = upload(measureVbo, measureData)
            highlightDirty = false
        }

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (indexCount == 0) return

        camera.computeMatrices(modelView, mvp)

        // Yüzeyler: kenar çizgileri üstte görünsün diye biraz geriye itilir.
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(1f, 1f)
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
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)

        if (showEdges && edgeVertexCount > 0) {
            GLES30.glUseProgram(lineProgram)
            GLES30.glUniformMatrix4fv(uLineMvp, 1, false, mvp, 0)
            GLES30.glUniform4f(uLineColor, 0.08f, 0.09f, 0.10f, 1f)
            GLES30.glUniform1f(uPointSize, 1f)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, edgeVbo)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, edgeVertexCount)
        }

        if (highlightVertexCount > 0 || measureVertexCount > 0) {
            GLES30.glUseProgram(lineProgram)
            GLES30.glUniformMatrix4fv(uLineMvp, 1, false, mvp, 0)
            GLES30.glEnableVertexAttribArray(0)
        }
        if (highlightVertexCount > 0) {
            // Seçili kenarlar: turuncu, kalın
            GLES30.glLineWidth(5f)
            GLES30.glUniform4f(uLineColor, 1.0f, 0.55f, 0.0f, 1f)
            GLES30.glUniform1f(uPointSize, 1f)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, highlightVbo)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, highlightVertexCount)
        }
        if (measureVertexCount >= 2) {
            // Ölçü çizgisi ve uç noktaları: her zaman görünür (derinlik testi kapalı)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glLineWidth(3f)
            GLES30.glUniform4f(uLineColor, 1.0f, 0.9f, 0.1f, 1f)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, measureVbo)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
            GLES30.glUniform1f(uPointSize, 1f)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, 2)
            GLES30.glUniform1f(uPointSize, 14f)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, 2)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        }
        GLES30.glLineWidth(1f)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun uploadMesh() {
        val m = mesh
        if (m == null) {
            indexCount = 0
            edgeVertexCount = 0
            return
        }
        try {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            val vbuf = ByteBuffer.allocateDirect(m.vertices.size * 4).order(ByteOrder.nativeOrder())
            vbuf.asFloatBuffer().put(m.vertices)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, m.vertices.size * 4, vbuf, GLES30.GL_STATIC_DRAW)

            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
            val ibuf = ByteBuffer.allocateDirect(m.indices.size * 4).order(ByteOrder.nativeOrder())
            ibuf.asIntBuffer().put(m.indices)
            GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, m.indices.size * 4, ibuf, GLES30.GL_STATIC_DRAW)
            indexCount = m.indices.size

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, edgeVbo)
            if (m.edges.isNotEmpty()) {
                val ebuf = ByteBuffer.allocateDirect(m.edges.size * 4).order(ByteOrder.nativeOrder())
                ebuf.asFloatBuffer().put(m.edges)
                GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, m.edges.size * 4, ebuf, GLES30.GL_STATIC_DRAW)
            }
            edgeVertexCount = m.edges.size / 3
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "GPU'ya yükleme için bellek yetersiz", e)
            indexCount = 0
            edgeVertexCount = 0
        }
    }

    /** Küçük bir çizgi tamponunu yükler; köşe sayısını döndürür. */
    private fun upload(buffer: Int, data: FloatArray?): Int {
        if (data == null || data.isEmpty()) return 0
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(data)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buf, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        return data.size / 3
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v)
        GLES30.glAttachShader(p, f)
        GLES30.glLinkProgram(p)
        val status = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader bağlanamadı: " + GLES30.glGetProgramInfoLog(p))
        }
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val status = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader derlenemedi: " + GLES30.glGetShaderInfoLog(s))
        }
        return s
    }

    companion object {
        private const val TAG = "StepViewer"

        private const val MESH_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec3 aColor;
uniform mat4 uMvp;
uniform mat4 uModelView;
out vec3 vNormal;
out vec3 vColor;
out vec3 vViewPos;
void main() {
    vec4 viewPos = uModelView * vec4(aPos, 1.0);
    vViewPos = viewPos.xyz;
    vNormal = mat3(uModelView) * aNormal;
    vColor = aColor;
    gl_Position = uMvp * vec4(aPos, 1.0);
}
"""

        private const val MESH_FS = """#version 300 es
precision mediump float;
in vec3 vNormal;
in vec3 vColor;
in vec3 vViewPos;
out vec4 fragColor;
void main() {
    vec3 n = normalize(vNormal);
    vec3 v = normalize(-vViewPos);
    // İki yüzlü aydınlatma: içi görünen yüzeyler de aydınlık olsun.
    if (dot(n, v) < 0.0) n = -n;
    vec3 key = normalize(vec3(-0.4, 0.6, 1.0));
    float diffuse = 0.55 * max(dot(n, key), 0.0) + 0.35 * max(dot(n, v), 0.0);
    vec3 h = normalize(key + v);
    float spec = 0.25 * pow(max(dot(n, h), 0.0), 48.0);
    vec3 c = vColor * (0.28 + diffuse) + vec3(spec);
    fragColor = vec4(c, 1.0);
}
"""

        private const val LINE_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uMvp;
uniform highp float uPointSize;
void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
    gl_PointSize = uPointSize;
}
"""

        private const val LINE_FS = """#version 300 es
precision mediump float;
uniform vec4 uColor;
uniform highp float uPointSize;
out vec4 fragColor;
void main() {
    // Noktaları yuvarlak çiz
    if (uPointSize > 1.5) {
        vec2 c = gl_PointCoord - vec2(0.5);
        if (dot(c, c) > 0.25) discard;
    }
    fragColor = uColor;
}
"""
    }
}
