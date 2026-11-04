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

    enum class DisplayMode { SHADED_EDGES, SHADED, WIREFRAME, XRAY }

    /**
     * Seçim/ölçüm katmanı. Aralıklar (başlangıç, adet) çiftleridir.
     * [measureLine]: ölçü çizgileri (segment başına 2 nokta * 3 float),
     * [points]: işaret noktaları (nokta başına 3 float).
     */
    class Overlay(
        val faceRanges: IntArray = IntArray(0),
        val edgeSegments: FloatArray = FloatArray(0),
        val measureLine: FloatArray? = null,
        val partRanges: IntArray = IntArray(0),
        val points: FloatArray? = null,
    )

    /** Parça renk / şeffaflık grubu: üçgen aralıkları, renk (null = kendi rengi), opaklık. */
    class StyleGroup(val ranges: IntArray, val color: FloatArray?, val alpha: Float)

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
    // Renkli / şeffaf parçalar (normal parçalar visibleTriRanges ile çizilir)
    private var styleGroups: List<StyleGroup> = emptyList()
    private var palette: Palette? = null
    // Büyüteç: her karede (x, y) çevresindeki kare bölge okunur
    private var loupeX = -1f
    private var loupeY = -1f
    private var loupeSize = 0
    private var loupeCallback: ((Bitmap?) -> Unit)? = null

    private var bgProgram = 0
    private var meshProgram = 0
    private var lineProgram = 0
    private var vbo = 0
    private var ibo = 0
    private var edgeVbo = 0
    private var highlightVbo = 0
    private var measureVbo = 0
    private var pointsVbo = 0
    private var pointsVertexCount = 0
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
    private var uAlpha = 0
    private var uContrast = 0
    private var uBgTop = 0
    private var uBgBottom = 0
    private var uBgRadial = 0
    private var uBgAspect = 0
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

    fun setVisibility(triRanges: IntArray?, segRanges: IntArray?, groups: List<StyleGroup> = emptyList()) {
        visibleTriRanges = triRanges
        visibleSegRanges = segRanges
        styleGroups = groups
    }

    fun setPalette(p: Palette) {
        palette = p
    }

    /** Büyüteç bölgesi (piksel); size <= 0 ise kapalı. Callback GL iş parçacığında çağrılır. */
    fun setLoupe(x: Float, y: Float, size: Int, callback: ((Bitmap?) -> Unit)?) {
        loupeX = x
        loupeY = y
        loupeSize = size
        loupeCallback = callback
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
        uBgTop = GLES30.glGetUniformLocation(bgProgram, "uTop")
        uBgBottom = GLES30.glGetUniformLocation(bgProgram, "uBottom")
        uBgRadial = GLES30.glGetUniformLocation(bgProgram, "uRadial")
        uBgAspect = GLES30.glGetUniformLocation(bgProgram, "uAspect")

        meshProgram = buildProgram(MESH_VS, MESH_FS)
        uMvp = GLES30.glGetUniformLocation(meshProgram, "uMvp")
        uModelView = GLES30.glGetUniformLocation(meshProgram, "uModelView")
        uMeshClip = GLES30.glGetUniformLocation(meshProgram, "uClip")
        uMeshClipOn = GLES30.glGetUniformLocation(meshProgram, "uClipOn")
        uOverride = GLES30.glGetUniformLocation(meshProgram, "uOverride")
        uAlpha = GLES30.glGetUniformLocation(meshProgram, "uAlpha")
        uContrast = GLES30.glGetUniformLocation(meshProgram, "uContrast")
        uMeshExplode = GLES30.glGetUniformLocation(meshProgram, "uExplode")

        lineProgram = buildProgram(LINE_VS, LINE_FS)
        uLineMvp = GLES30.glGetUniformLocation(lineProgram, "uMvp")
        uLineColor = GLES30.glGetUniformLocation(lineProgram, "uColor")
        uPointSize = GLES30.glGetUniformLocation(lineProgram, "uPointSize")
        uLineClip = GLES30.glGetUniformLocation(lineProgram, "uClip")
        uLineClipOn = GLES30.glGetUniformLocation(lineProgram, "uClipOn")
        uLineExplode = GLES30.glGetUniformLocation(lineProgram, "uExplode")

        val ids = IntArray(9)
        GLES30.glGenBuffers(9, ids, 0)
        vbo = ids[0]
        ibo = ids[1]
        edgeVbo = ids[2]
        highlightVbo = ids[3]
        measureVbo = ids[4]
        explodeVbo = ids[5]
        explodeEdgeVbo = ids[6]
        pmiVbo = ids[7]
        pointsVbo = ids[8]

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
            pointsVertexCount = upload(pointsVbo, overlay.points)
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
            when (displayMode) {
                DisplayMode.WIREFRAME -> drawEdges(clip)
                DisplayMode.XRAY -> {
                    drawXray(clip)
                    drawEdges(clip)
                }
                DisplayMode.SHADED -> {
                    drawFaces(clip)
                    drawTransparent(clip)
                }
                DisplayMode.SHADED_EDGES -> {
                    drawFaces(clip)
                    drawEdges(clip)
                    drawTransparent(clip)
                }
            }
            drawOverlay(clip)
            drawPmi()
        }

        screenshotCallback?.let { cb ->
            screenshotCallback = null
            cb(readPixels())
        }
        loupeCallback?.let { cb ->
            if (loupeSize > 0) cb(readRegion(loupeX, loupeY, loupeSize))
        }

        if (moving) requestRender()
    }

    private fun drawBackground() {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(bgProgram)
        val p = palette
        if (p != null) {
            GLES30.glUniform3f(uBgTop, p.bgTop[0], p.bgTop[1], p.bgTop[2])
            GLES30.glUniform3f(uBgBottom, p.bgBottom[0], p.bgBottom[1], p.bgBottom[2])
            GLES30.glUniform1f(uBgRadial, if (p.radial) 1f else 0f)
        } else {
            GLES30.glUniform3f(uBgTop, 0.97f, 0.98f, 0.99f)
            GLES30.glUniform3f(uBgBottom, 0.86f, 0.89f, 0.92f)
            GLES30.glUniform1f(uBgRadial, 0f)
        }
        GLES30.glUniform1f(uBgAspect, viewWidth.toFloat() / maxOf(1, viewHeight))
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
        GLES30.glUniform1f(uAlpha, 1f)
        GLES30.glUniform1f(uContrast, palette?.contrast ?: 1f)
        setMeshClip(clip)
        bindMeshAttributes()
        drawTriangleRanges(visibleTriRanges)
        // Renk verilmiş (opak) parçalar
        for (g in styleGroups) {
            if (g.alpha < 0.999f) continue
            val c = g.color
            if (c != null) GLES30.glUniform4f(uOverride, c[0], c[1], c[2], 1f)
            else GLES30.glUniform4f(uOverride, 0f, 0f, 0f, 0f)
            drawTriangleRanges(g.ranges)
        }
        unbindMeshAttributes()
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
    }

    /** Şeffaf parçalar: opak her şeyden sonra, derinlik yazmadan karıştırılarak çizilir. */
    private fun drawTransparent(clip: FloatArray?) {
        if (styleGroups.none { it.alpha < 0.999f }) return
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(meshProgram)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(uModelView, 1, false, modelView, 0)
        GLES30.glUniform1f(uContrast, palette?.contrast ?: 1f)
        setMeshClip(clip)
        bindMeshAttributes()
        for (g in styleGroups) {
            if (g.alpha >= 0.999f) continue
            val c = g.color
            if (c != null) GLES30.glUniform4f(uOverride, c[0], c[1], c[2], 1f)
            else GLES30.glUniform4f(uOverride, 0f, 0f, 0f, 0f)
            GLES30.glUniform1f(uAlpha, g.alpha)
            drawTriangleRanges(g.ranges)
        }
        unbindMeshAttributes()
        GLES30.glUniform1f(uAlpha, 1f)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** X-ışını: tüm görünen yüzeyler yarı saydam, kenarlar her yerden görünür. */
    private fun drawXray(clip: FloatArray?) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glUseProgram(meshProgram)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(uModelView, 1, false, modelView, 0)
        GLES30.glUniform1f(uContrast, palette?.contrast ?: 1f)
        GLES30.glUniform4f(uOverride, 0f, 0f, 0f, 0f)
        GLES30.glUniform1f(uAlpha, 0.22f)
        setMeshClip(clip)
        bindMeshAttributes()
        drawTriangleRanges(visibleTriRanges)
        for (g in styleGroups) {
            val c = g.color
            if (c != null) GLES30.glUniform4f(uOverride, c[0], c[1], c[2], 1f)
            else GLES30.glUniform4f(uOverride, 0f, 0f, 0f, 0f)
            drawTriangleRanges(g.ranges)
        }
        unbindMeshAttributes()
        GLES30.glUniform1f(uAlpha, 1f)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawEdges(clip: FloatArray?) {
        if (edgeVertexCount == 0) return
        GLES30.glUseProgram(lineProgram)
        GLES30.glUniformMatrix4fv(uLineMvp, 1, false, mvp, 0)
        val p = palette
        val c = when {
            displayMode == DisplayMode.WIREFRAME || displayMode == DisplayMode.XRAY ->
                p?.wire ?: floatArrayOf(0.16f, 0.22f, 0.34f)
            else -> p?.edge ?: floatArrayOf(0.12f, 0.15f, 0.21f)
        }
        GLES30.glUniform4f(uLineColor, c[0], c[1], c[2], 1f)
        GLES30.glLineWidth(p?.edgeWidth ?: 1f)
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
        GLES30.glLineWidth(1f)
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
            GLES30.glUniform1f(uAlpha, 1f)
            GLES30.glUniform1f(uContrast, palette?.contrast ?: 1f)
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

        if (highlightVertexCount == 0 && measureVertexCount < 2 && pointsVertexCount == 0) return
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
        val mc = palette?.measure ?: floatArrayOf(1.0f, 0.9f, 0.1f)
        if (measureVertexCount >= 2) {
            // Ölçü çizgileri ve uç noktaları: her zaman görünür (derinlik testi ve kesit kapalı)
            setLineClip(null)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glLineWidth(3f)
            GLES30.glUniform4f(uLineColor, mc[0], mc[1], mc[2], 1f)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, measureVbo)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
            GLES30.glUniform1f(uPointSize, 1f)
            val n = measureVertexCount - measureVertexCount % 2
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, n)
            GLES30.glUniform1f(uPointSize, 14f)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, n)
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        }
        if (pointsVertexCount > 0) {
            // İşaret noktaları (nokta ölçümü): beyaz halka + renkli iç
            setLineClip(null)
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pointsVbo)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
            val ring = if (palette?.light == false) 0.08f else 1f
            GLES30.glUniform4f(uLineColor, ring, ring, ring, 1f)
            GLES30.glUniform1f(uPointSize, 22f)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, pointsVertexCount)
            GLES30.glUniform4f(uLineColor, mc[0], mc[1], mc[2], 1f)
            GLES30.glUniform1f(uPointSize, 14f)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, pointsVertexCount)
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

    /** Ekran (x, y) noktası (üstten) çevresindeki kare bölgeyi okur. */
    private fun readRegion(cx: Float, cy: Float, size: Int): Bitmap? {
        return try {
            val half = size / 2
            val x0 = (cx.toInt() - half).coerceIn(0, maxOf(0, viewWidth - size))
            val yTop = (cy.toInt() - half).coerceIn(0, maxOf(0, viewHeight - size))
            val w = minOf(size, viewWidth)
            val h = minOf(size, viewHeight)
            val glY = viewHeight - yTop - h
            val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(x0, glY, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
            val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buf.rewind()
            raw.copyPixelsFromBuffer(buf)
            val m = android.graphics.Matrix().apply { preScale(1f, -1f) }
            val flipped = Bitmap.createBitmap(raw, 0, 0, w, h, m, false)
            raw.recycle()
            flipped
        } catch (e: Throwable) {
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
out vec2 vUv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vT = p.y * 0.5;
    vUv = p * 0.5;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

        private const val BG_FS = """#version 300 es
precision mediump float;
in float vT;
in vec2 vUv;
uniform vec3 uTop;
uniform vec3 uBottom;
uniform float uRadial;
uniform float uAspect;
out vec4 fragColor;
void main() {
    float t;
    if (uRadial > 0.5) {
        // Ortası açık, kenarları koyu: merkez üstte (%35)
        vec2 d = vUv - vec2(0.5, 0.62);
        d.x *= uAspect;
        t = 1.0 - clamp(length(d) * 1.35, 0.0, 1.0);
    } else {
        t = clamp(vT, 0.0, 1.0);
    }
    fragColor = vec4(mix(uBottom, uTop, t), 1.0);
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
uniform float uAlpha;
uniform float uContrast;
out vec4 fragColor;
void main() {
    if (uClipOn > 0.5 && dot(uClip.xyz, vWorld) + uClip.w < 0.0) discard;

    vec3 base = mix(vColor, uOverride.rgb, uOverride.a);

    // Kesit açıkken modelin içi (arka yüzler) kesit rengiyle boyanır
    if (uClipOn > 0.5 && !gl_FrontFacing) {
        fragColor = vec4(0.86, 0.32, 0.26, uAlpha);
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
    c = clamp((c - 0.5) * uContrast + 0.5, 0.0, 1.0);
    fragColor = vec4(c, uAlpha);
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
