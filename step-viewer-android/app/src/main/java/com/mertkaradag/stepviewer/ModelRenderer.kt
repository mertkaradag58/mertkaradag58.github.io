package com.mertkaradag.stepviewer

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 3.0 çizici: degrade arka plan, gölgeli yüzeyler, kenarlar, kesit düzlemi,
 * parça gizleme, seçim vurgusu ve ölçü çizgisi.
 *
 * Tüm set* çağrıları GL iş parçacığında yapılmalıdır (GLSurfaceView.queueEvent).
 */
class ModelRenderer(
    private val camera: Camera,
    private val requestRender: () -> Unit,
) : GLSurfaceView.Renderer {

    enum class DisplayMode { SHADED_EDGES, SHADED, WIREFRAME }

    /** Seçim/ölçüm katmanı. Aralıklar (başlangıç, adet) çiftleridir. */
    class Overlay(
        val faceRanges: IntArray = IntArray(0),
        val edgeSegments: FloatArray = FloatArray(0),
        val measureLine: FloatArray? = null,
        val partRanges: IntArray = IntArray(0),
    )

    // GL iş parçacığı durumu
    private var mesh: StepMesh? = null
    private var meshDirty = false
    private var overlay = Overlay()
    private var overlayDirty = false
    private var visibleTriRanges: IntArray? = null   // null = hepsi
    private var visibleSegRanges: IntArray? = null
    private var displayMode = DisplayMode.SHADED_EDGES
    private var clipPlane: FloatArray? = null        // [nx, ny, nz, d]: n·p + d >= 0 görünür
    // Patlatılmış görünüm: köşe başına kayma yönü (parça merkezi - model merkezi) ve çarpan
    private var explodeVertexDirs: FloatArray? = null
    private var explodeEdgeDirs: FloatArray? = null
    private var explodeDirty = false
    private var explodeFactor = 0f
    // PMI çizimleri
    private var pmiSegments: FloatArray? = null
    private var pmiDirty = false
    private var pmiVisible = false
    private var pmiHighlight = IntArray(0) // (başlangıç segment, adet)
    private var screenshotCallback: ((Bitmap?) -> Unit)? = null

    private var bgProgram = 0
    private var meshProgram = 0
    private var lineProgram = 0
    private var vbo = 0
    private var ibo = 0
    private var edgeVbo = 0
    private var highlightVbo = 0
    private var measureVbo = 0
    private var explodeVbo = 0
    private var explodeEdgeVbo = 0
    private var pmiVbo = 0
    private var pmiVertexCount = 0
    private var hasExplodeData = false
    private var indexCount = 0
    private var edgeVertexCount = 0
    private var highlightVertexCount = 0
    private var measureVertexCount = 0
    private var viewWidth = 1
    private var viewHeight = 1

    // Uniform konumları
    private var uMvp = 0
    private var uModelView = 0
    private var uMeshClip = 0
    private var uMeshClipOn = 0
    private var uOverride = 0
    private var uLineMvp = 0
    private var uLineColor = 0
    private var uPointSize = 0
    private var uLineClip = 0
    private var uLineClipOn = 0
    private var uMeshExplode = 0
    private var uLineExplode = 0

    private val modelView = FloatArray(16)
    private val mvp = FloatArray(16)

    fun setMesh(newMesh: StepMesh?) {
        mesh = newMesh
        meshDirty = true
        overlay = Overlay()
        overlayDirty = true
        visibleTriRanges = null
        visibleSegRanges = null
        clipPlane = null
        explodeVertexDirs = null
        explodeEdgeDirs = null
        explodeDirty = true
        explodeFactor = 0f
        pmiSegments = null
        pmiDirty = true
        pmiVisible = false
        pmiHighlight = IntArray(0)
    }

    fun setPmi(segments: FloatArray?) {
        pmiSegments = segments
        pmiDirty = true
    }

    fun setPmiVisible(visible: Boolean, highlight: IntArray) {
        pmiVisible = visible
        pmiHighlight = highlight
    }

    /** Patlatma yönleri (köşe ve kenar köşesi başına x, y, z); bir kez verilir. */
    fun setExplodeDirections(vertexDirs: FloatArray?, edgeDirs: FloatArray?) {
        explodeVertexDirs = vertexDirs
        explodeEdgeDirs = edgeDirs
        explodeDirty = true
    }

    fun setExplodeFactor(f: Float) {
        explodeFactor = f
    }

    fun setOverlay(o: Overlay) {
        overlay = o
        overlayDirty = true
    }

    fun setVisibility(triRanges: IntArray?, segRanges: IntArray?) {
        visibleTriRanges = triRanges
        visibleSegRanges = segRanges
    }

    fun setDisplayMode(mode: DisplayMode) {
        displayMode = mode
    }

    fun setClipPlane(plane: FloatArray?) {
        clipPlane = plane
    }

    /** Bir sonraki karenin görüntüsünü alır (callback GL iş parçacığında çağrılır). */
    fun captureScreenshot(callback: (Bitmap?) -> Unit) {
        screenshotCallback = callback
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)

        bgProgram = buildProgram(BG_VS, BG_FS)

        meshProgram = buildProgram(MESH_VS, MESH_FS)
        uMvp = GLES30.glGetUniformLocation(meshProgram, "uMvp")
        uModelView = GLES30.glGetUniformLocation(meshProgram, "uModelView")
        uMeshClip = GLES30.glGetUniformLocation(meshProgram, "uClip")
        uMeshClipOn = GLES30.glGetUniformLocation(meshProgram, "uClipOn")
        uOverride = GLES30.glGetUniformLocation(meshProgram, "uOverride")
        uMeshExplode = GLES30.glGetUniformLocation(meshProgram, "uExplode")

        lineProgram = buildProgram(LINE_VS, LINE_FS)
        uLineMvp = GLES30.glGetUniformLocation(lineProgram, "uMvp")
        uLineColor = GLES30.glGetUniformLocation(lineProgram, "uColor")
        uPointSize = GLES30.glGetUniformLocation(lineProgram, "uPointSize")
        uLineClip = GLES30.glGetUniformLocation(lineProgram, "uClip")
        uLineClipOn = GLES30.glGetUniformLocation(lineProgram, "uClipOn")
        uLineExplode = GLES30.glGetUniformLocation(lineProgram, "uExplode")

        val ids = IntArray(8)
        GLES30.glGenBuffers(8, ids, 0)
        vbo = ids[0]
        ibo = ids[1]
        edgeVbo = ids[2]
        highlightVbo = ids[3]
        measureVbo = ids[4]
        explodeVbo = ids[5]
        explodeEdgeVbo = ids[6]
        pmiVbo = ids[7]

        // Yeni bağlam: GPU tamponları boş, her şeyi tekrar yükle.
        meshDirty = true
        overlayDirty = true
        explodeDirty = true
        pmiDirty = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height
        camera.setViewport(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (meshDirty) {
            uploadMesh()
            meshDirty = false
        }
        if (overlayDirty) {
            highlightVertexCount = upload(highlightVbo, overlay.edgeSegments)
            measureVertexCount = upload(measureVbo, overlay.measureLine)
            overlayDirty = false
        }
        if (pmiDirty) {
            pmiVertexCount = upload(pmiVbo, pmiSegments)
            pmiDirty = false
        }
        if (explodeDirty) {
            val v = explodeVertexDirs
            val e = explodeEdgeDirs
            hasExplodeData = v != null && v.isNotEmpty()
            if (hasExplodeData) {
                upload(explodeVbo, v)
                upload(explodeEdgeVbo, e)
            }
            explodeDirty = false
        }

        val moving = camera.update()

        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        drawBackground()

        if (indexCount > 0) {
            camera.computeMatrices(modelView, mvp)
            val clip = clipPlane
            if (displayMode != DisplayMode.WIREFRAME) drawFaces(clip)
            if (displayMode != DisplayMode.SHADED) drawEdges(clip)
            drawOverlay(clip)
            drawPmi()
        }

        screenshotCallback?.let { cb ->
            screenshotCallback = null
            cb(readPixels())
        }

        if (moving) requestRender()
    }

    private fun drawBackground() {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(bgProgram)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun bindMeshAttributes() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        val stride = StepMesh.FLOATS_PER_VERTEX * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, 24)
        bindExplode(3, explodeVbo, uMeshExplode)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
    }

    /** Patlatma yönü niteliği; veri yoksa sabit (0, 0, 0). */
    private fun bindExplode(location: Int, buffer: Int, uniform: Int) {
        if (hasExplodeData && explodeFactor != 0f) {
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
            GLES30.glEnableVertexAttribArray(location)
            GLES30.glVertexAttribPointer(location, 3, GLES30.GL_FLOAT, false, 12, 0)
            GLES30.glUniform1f(uniform, explodeFactor)
        } else {
            GLES30.glDisableVertexAttribArray(location)
            GLES30.glVertexAttrib3f(location, 0f, 0f, 0f)
            GLES30.glUniform1f(uniform, 0f)
        }
    }

    private fun noExplode(location: Int, uniform: Int) {
        GLES30.glDisableVertexAttribArray(location)
        GLES30.glVertexAttrib3f(location, 0f, 0f, 0f)
        GLES30.glUniform1f(uniform, 0f)
    }

    private fun unbindMeshAttributes() {
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glDisableVertexAttribArray(2)
        GLES30.glDisableVertexAttribArray(3)
    }

    private fun setMeshClip(clip: FloatArray?) {
        if (clip != null) {
            GLES30.glUniform4f(uMeshClip, clip[0], clip[1], clip[2], clip[3])
            GLES30.glUniform1f(uMeshClipOn, 1f)
        } else {
            GLES30.glUniform1f(uMeshClipOn, 0f)
        }
    }

    private fun drawTriangleRanges(ranges: IntArray?) {
        if (ranges == null) {
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
            return
        }
        var i = 0
        while (i + 1 < ranges.size) {
            val start = ranges[i]
            val count = ranges[i + 1]
            if (count > 0) {
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, count * 3, GLES30.GL_UNSIGNED_INT, start * 3 * 4)
            }
            i += 2
        }
    }

    private fun drawFaces(clip: FloatArray?) {
        // Yüzeyler, kenar çizgileri üstte görünsün diye biraz geriye itilir.
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(1f, 1f)
        GLES30.glUseProgram(meshProgram)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(uModelView, 1, false, modelView, 0)
        GLES30.glUniform4f(uOverride, 0f, 0f, 0f, 0f)
        setMeshClip(clip)
        bindMeshAttributes()
        drawTriangleRanges(visibleTriRanges)
        unbindMeshAttributes()
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
    }

    private fun drawEdges(clip: FloatArray?) {
        if (edgeVertexCount == 0) return
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniformMatrix4fv(uLineMvp, 1, false, mvp, 0)
        if (displayMode == DisplayMode.WIREFRAME) {
            GLES30.glUniform4f(uLineColor, 0.80f, 0.86f, 0.95f, 1f)
        } else {
            GLES30.glUniform4f(uLineColor, 0.07f, 0.08f, 0.10f, 1f)
        }
        GLES30.glUniform1f(uPointSize, 1f)
        setLineClip(clip)
        bindExplode(1, explodeEdgeVbo, uLineExplode)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, edgeVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        val ranges = visibleSegRanges
        if (ranges == null) {
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, edgeVertexCount)
        } else {
            var i = 0
            while (i + 1 < ranges.size) {
                if (ranges[i + 1] > 0) GLES30.glDrawArrays(GLES30.GL_LINES, ranges[i] * 2, ranges[i + 1] * 2)
                i += 2
            }
        }
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }

    private fun setLineClip(clip: FloatArray?) {
        if (clip != null) {
            GLES30.glUniform4f(uLineClip, clip[0], clip[1], clip[2], clip[3])
            GLES30.glUniform1f(uLineClipOn, 1f)
        } else {
            GLES30.glUniform1f(uLineClipOn, 0f)
        }
    }

    private fun drawOverlay(clip: FloatArray?) {
        val o = overlay
        // Seçili parça (parça listesinden) ve seçili yüzeyler: renk üzerine yazılır
        if (o.partRanges.isNotEmpty() || o.faceRanges.isNotEmpty()) {
            GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
            GLES30.glPolygonOffset(0.5f, 0.5f)
            GLES30.glUseProgram(meshProgram)
            GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES30.glUniformMatrix4fv(uModelView, 1, false, modelView, 0)
            setMeshClip(clip)
            bindMeshAttributes()
            if (o.partRanges.isNotEmpty()) {
                GLES30.glUniform4f(uOverride, 0.35f, 0.69f, 1.0f, 0.55f)
                drawTriangleRanges(o.partRanges)
            }
            if (o.faceRanges.isNotEmpty()) {
                GLES30.glUniform4f(uOverride, 1.0f, 0.55f, 0.0f, 0.85f)
                drawTriangleRanges(o.faceRanges)
            }
            unbindMeshAttributes()
            GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        }

        if (highlightVertexCount == 0 && measureVertexCount < 2) return
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniformMatrix4fv(uLineMvp, 1, false, mvp, 0)
        // Seçili kenar ve ölçü çizgileri patlatılmaz (ölçüm patlatma kapalıyken yapılır)
        noExplode(1, uLineExplode)
        GLES30.glEnableVertexAttribArray(0)
        if (highlightVertexCount > 0) {
            // Seçili kenarlar: turuncu, kalın
            setLineClip(clip)
            GLES30.glLineWidth(5f)
            GLES30.glUniform4f(uLineColor, 1.0f, 0.55f, 0.0f, 1f)
            GLES30.glUniform1f(uPointSize, 1f)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, highlightVbo)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, highlightVertexCount)
        }
        if (measureVertexCount >= 2) {
            // Ölçü çizgisi ve uç noktaları: her zaman görünür (derinlik testi ve kesit kapalı)
            setLineClip(null)
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

    /** PMI çizgileri: yeşil; seçili öğe sarı, kalın ve her zaman önde. */
    private fun drawPmi() {
        if (!pmiVisible || pmiVertexCount == 0) return
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniformMatrix4fv(uLineMvp, 1, false, mvp, 0)
        noExplode(1, uLineExplode)
        setLineClip(null)
        GLES30.glUniform1f(uPointSize, 1f)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pmiVbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glLineWidth(2f)
        GLES30.glUniform4f(uLineColor, 0.30f, 0.90f, 0.55f, 1f)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, pmiVertexCount)
        val h = pmiHighlight
        if (h.size >= 2 && h[1] > 0) {
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glLineWidth(4f)
            GLES30.glUniform4f(uLineColor, 1.0f, 0.85f, 0.1f, 1f)
            val start = h[0] * 2
            val count = minOf(h[1] * 2, pmiVertexCount - start)
            if (start >= 0 && count > 0) GLES30.glDrawArrays(GLES30.GL_LINES, start, count)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        }
        GLES30.glLineWidth(1f)
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
    }

    private fun readPixels(): Bitmap? {
        return try {
            val w = viewWidth
            val h = viewHeight
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buf.rewind()
            raw.copyPixelsFromBuffer(buf)
            // OpenGL görüntüsü alttan üste; dikey çevir
            val m = android.graphics.Matrix().apply { preScale(1f, -1f) }
            val flipped = Bitmap.createBitmap(raw, 0, 0, w, h, m, false)
            raw.recycle()
            flipped
        } catch (e: Throwable) {
            Log.e(TAG, "Ekran görüntüsü alınamadı", e)
            null
        }
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

            edgeVertexCount = upload(edgeVbo, m.edges)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "GPU'ya yükleme için bellek yetersiz", e)
            indexCount = 0
            edgeVertexCount = 0
        }
    }

    /** Bir çizgi tamponunu yükler; köşe sayısını döndürür. */
    private fun upload(buffer: Int, data: FloatArray?): Int {
        if (data == null || data.isEmpty()) return 0
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(data)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buf, GLES30.GL_STATIC_DRAW)
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

        // Tam ekran üçgen ile dikey degrade arka plan (köşe tamponu gerekmez)
        private const val BG_VS = """#version 300 es
out float vT;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vT = p.y * 0.5;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

        private const val BG_FS = """#version 300 es
precision mediump float;
in float vT;
out vec4 fragColor;
void main() {
    vec3 bottom = vec3(0.075, 0.085, 0.105);
    vec3 top = vec3(0.235, 0.265, 0.320);
    fragColor = vec4(mix(bottom, top, clamp(vT, 0.0, 1.0)), 1.0);
}
"""

        private const val MESH_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec3 aColor;
layout(location = 3) in vec3 aExplode;
uniform mat4 uMvp;
uniform mat4 uModelView;
uniform float uExplode;
out vec3 vNormal;
out vec3 vColor;
out vec3 vViewPos;
out vec3 vWorld;
void main() {
    vec3 p = aPos + aExplode * uExplode;
    vec4 viewPos = uModelView * vec4(p, 1.0);
    vViewPos = viewPos.xyz;
    vNormal = mat3(uModelView) * aNormal;
    vColor = aColor;
    vWorld = p;
    gl_Position = uMvp * vec4(p, 1.0);
}
"""

        private const val MESH_FS = """#version 300 es
precision highp float;
in vec3 vNormal;
in vec3 vColor;
in vec3 vViewPos;
in vec3 vWorld;
uniform vec4 uClip;
uniform float uClipOn;
uniform vec4 uOverride;
out vec4 fragColor;
void main() {
    if (uClipOn > 0.5 && dot(uClip.xyz, vWorld) + uClip.w < 0.0) discard;

    vec3 base = mix(vColor, uOverride.rgb, uOverride.a);

    // Kesit açıkken modelin içi (arka yüzler) kesit rengiyle boyanır
    if (uClipOn > 0.5 && !gl_FrontFacing) {
        fragColor = vec4(0.86, 0.32, 0.26, 1.0);
        return;
    }

    vec3 n = normalize(vNormal);
    vec3 v = normalize(-vViewPos);
    // İki yüzlü aydınlatma: içi görünen yüzeyler de aydınlık olsun.
    if (dot(n, v) < 0.0) n = -n;
    vec3 key = normalize(vec3(-0.4, 0.6, 1.0));
    vec3 fill = normalize(vec3(0.6, -0.2, 0.6));
    float diffuse = 0.50 * max(dot(n, key), 0.0) + 0.20 * max(dot(n, fill), 0.0)
                  + 0.25 * max(dot(n, v), 0.0);
    vec3 h = normalize(key + v);
    float spec = 0.22 * pow(max(dot(n, h), 0.0), 48.0);
    // Kenar aydınlatması (rim): siluet daha belirgin
    float rim = 0.10 * pow(1.0 - max(dot(n, v), 0.0), 3.0);
    vec3 c = base * (0.26 + diffuse) + vec3(spec + rim);
    fragColor = vec4(c, 1.0);
}
"""

        private const val LINE_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aExplode;
uniform mat4 uMvp;
uniform highp float uPointSize;
uniform float uExplode;
out vec3 vWorld;
void main() {
    vec3 p = aPos + aExplode * uExplode;
    vWorld = p;
    gl_Position = uMvp * vec4(p, 1.0);
    gl_PointSize = uPointSize;
}
"""

        private const val LINE_FS = """#version 300 es
precision highp float;
in vec3 vWorld;
uniform vec4 uColor;
uniform highp float uPointSize;
uniform vec4 uClip;
uniform float uClipOn;
out vec4 fragColor;
void main() {
    if (uClipOn > 0.5 && dot(uClip.xyz, vWorld) + uClip.w < 0.0) discard;
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
