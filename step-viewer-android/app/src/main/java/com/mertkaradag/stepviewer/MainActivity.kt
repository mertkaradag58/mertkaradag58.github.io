package com.mertkaradag.stepviewer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs

class MainActivity : ComponentActivity() {

    private lateinit var modelView: ModelView
    private lateinit var titleText: TextView
    private lateinit var infoText: TextView
    private lateinit var emptyText: TextView
    private lateinit var progressOverlay: View
    private lateinit var progressText: TextView
    private lateinit var edgesButton: Button
    private lateinit var measureButton: Button
    private lateinit var measurePanel: TextView

    private val executor = Executors.newSingleThreadExecutor()
    private var loading = false
    private var showEdges = true

    // Ölçüm durumu (UI iş parçacığında)
    private var currentMesh: StepMesh? = null
    private var measureMode = false
    private var selectedA = -1
    private var selectedB = -1
    private var measureRequest = 0

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) loadModel(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        modelView = findViewById(R.id.model_view)
        titleText = findViewById(R.id.title_text)
        infoText = findViewById(R.id.info_text)
        emptyText = findViewById(R.id.empty_text)
        progressOverlay = findViewById(R.id.progress_overlay)
        progressText = findViewById(R.id.progress_text)
        edgesButton = findViewById(R.id.edges_button)
        measureButton = findViewById(R.id.measure_button)
        measurePanel = findViewById(R.id.measure_panel)

        findViewById<Button>(R.id.open_button).setOnClickListener { pickFile() }
        emptyText.setOnClickListener { pickFile() }
        findViewById<Button>(R.id.reset_button).setOnClickListener { modelView.resetView() }
        edgesButton.setOnClickListener {
            showEdges = !showEdges
            modelView.setShowEdges(showEdges)
            edgesButton.alpha = if (showEdges) 1f else 0.5f
        }
        measureButton.setOnClickListener { setMeasureMode(!measureMode) }
        modelView.onTap = { x, y -> onModelTap(x, y) }

        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        modelView.onResume()
    }

    override fun onPause() {
        modelView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { loadModel(it) }
        }
    }

    private fun pickFile() {
        if (loading) return
        // STEP için MIME türleri cihazdan cihaza değiştiği için tüm dosyalar gösterilir.
        openDocument.launch(arrayOf("*/*"))
    }

    private fun loadModel(uri: Uri) {
        if (loading) {
            Toast.makeText(this, R.string.busy, Toast.LENGTH_SHORT).show()
            return
        }
        loading = true
        val name = displayName(uri)
        progressText.text = getString(R.string.loading_file, name)
        progressOverlay.visibility = View.VISIBLE

        executor.execute {
            val started = SystemClock.elapsedRealtime()
            val result: Result<StepMesh> = try {
                val file = copyToCache(uri)
                runOnUiThread { progressText.text = getString(R.string.meshing_file, name) }
                Result.success(StepNative.loadStep(file.absolutePath, 0.0))
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: OutOfMemoryError) {
                Result.failure(IOException(getString(R.string.error_oom)))
            } catch (e: SecurityException) {
                Result.failure(IOException(getString(R.string.error_permission)))
            }
            val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0

            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                loading = false
                progressOverlay.visibility = View.GONE
                result.onSuccess { mesh ->
                    currentMesh = mesh
                    selectedA = -1
                    selectedB = -1
                    modelView.setMesh(mesh)
                    updateMeasurement()
                    titleText.text = name
                    infoText.text = getString(
                        R.string.model_info,
                        mesh.partCount,
                        mesh.triangleCount,
                        seconds,
                    )
                    infoText.visibility = View.VISIBLE
                    emptyText.visibility = View.GONE
                }.onFailure { e ->
                    showError(e.message ?: getString(R.string.error_unknown))
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Ölçüm
    // -----------------------------------------------------------------------

    private fun setMeasureMode(enabled: Boolean) {
        measureMode = enabled
        measureButton.setBackgroundColor(if (enabled) 0x405AB0FF else 0)
        selectedA = -1
        selectedB = -1
        updateMeasurement()
    }

    private fun onModelTap(x: Float, y: Float) {
        if (!measureMode || loading) return
        val mesh = currentMesh ?: return
        val mvp = modelView.currentMvp()
        val w = modelView.width
        val h = modelView.height
        val threshold = 28f * resources.displayMetrics.density
        executor.execute {
            val id = EdgePicker.pick(mesh, mvp, w, h, x, y, threshold)
            runOnUiThread { if (mesh === currentMesh) onEdgePicked(id) }
        }
    }

    private fun onEdgePicked(id: Int) {
        when {
            id < 0 -> { selectedA = -1; selectedB = -1 }           // boşluk: temizle
            selectedA < 0 -> selectedA = id
            selectedB < 0 && id == selectedA -> selectedA = -1     // aynı kenar: seçimi kaldır
            selectedB < 0 -> selectedB = id
            else -> { selectedA = id; selectedB = -1 }             // üçüncü dokunuş: yeniden başla
        }
        updateMeasurement()
    }

    /** Seçime göre paneli, vurguyu ve ölçü çizgisini günceller. */
    private fun updateMeasurement() {
        val mesh = currentMesh
        if (!measureMode || mesh == null) {
            measurePanel.visibility = View.GONE
            modelView.setSelection(null, null)
            return
        }
        measurePanel.visibility = View.VISIBLE
        val a = selectedA
        val b = selectedB
        if (a < 0) {
            measurePanel.text = getString(R.string.measure_hint_first)
            modelView.setSelection(null, null)
            return
        }

        val highlight = if (b >= 0) mesh.edgeSegments(a) + mesh.edgeSegments(b) else mesh.edgeSegments(a)
        modelView.setSelection(highlight, null)
        measurePanel.text = getString(R.string.measure_busy)

        val request = ++measureRequest
        executor.execute {
            val infoA = StepNative.edgeInfo(a)
            val infoB = if (b >= 0) StepNative.edgeInfo(b) else null
            val dist = if (b >= 0) StepNative.measureEdges(a, b) else null
            runOnUiThread {
                if (request != measureRequest) return@runOnUiThread  // daha yeni bir seçim var
                val lines = mutableListOf(getString(R.string.edge_label, 1, describeEdge(infoA)))
                if (b < 0) {
                    lines += getString(R.string.measure_hint_second)
                } else {
                    lines += getString(R.string.edge_label, 2, describeEdge(infoB))
                    if (dist != null) {
                        lines += getString(R.string.measure_distance, fmt(dist[0]))
                        if (dist[7] >= 0) lines += getString(R.string.measure_angle, fmt(dist[7], 2))
                        if (dist[8] >= 0) lines += getString(R.string.measure_center_distance, fmt(dist[8]))
                        if (dist[0] > 1e-9) {
                            val line = FloatArray(6) { dist[1 + it].toFloat() }
                            modelView.setSelection(highlight, line)
                        }
                    }
                }
                measurePanel.text = lines.joinToString("\n")
            }
        }
    }

    private fun describeEdge(info: DoubleArray?): String {
        if (info == null) return getString(R.string.edge_unknown)
        val length = info[1]
        val radius = info[2]
        return when (info[0].toInt()) {
            1 -> getString(R.string.edge_line, fmt(length))
            2 -> {
                val fullCircle = abs(length - 2 * PI * radius) < 1e-6 * maxOf(1.0, length)
                if (fullCircle) getString(R.string.edge_circle, fmt(radius * 2), fmt(radius))
                else getString(R.string.edge_arc, fmt(radius * 2), fmt(radius), fmt(length))
            }
            3 -> getString(R.string.edge_ellipse, fmt(radius), fmt(length))
            else -> getString(R.string.edge_curve, fmt(length))
        }
    }

    private fun fmt(value: Double, digits: Int = 3): String =
        String.format(Locale.getDefault(), "%.${digits}f", value)

    private fun showError(message: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** İçerik URI'sini OCCT'nin okuyabileceği gerçek bir dosyaya kopyalar. */
    @Throws(IOException::class)
    private fun copyToCache(uri: Uri): File {
        val target = File(cacheDir, "current_model.step")
        val input = contentResolver.openInputStream(uri)
            ?: throw IOException(getString(R.string.error_open))
        input.use { src -> target.outputStream().use { dst -> src.copyTo(dst, 1 shl 16) } }
        return target
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c ->
                        if (c.moveToFirst()) {
                            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) c.getString(idx)?.let { return it }
                        }
                    }
            } catch (_: Exception) {
                // Bazı sağlayıcılar sorguyu desteklemez; aşağıdaki yedeğe düş.
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: getString(R.string.untitled)
    }
}
