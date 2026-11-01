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
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var modelView: ModelView
    private lateinit var titleText: TextView
    private lateinit var infoText: TextView
    private lateinit var emptyText: TextView
    private lateinit var progressOverlay: View
    private lateinit var progressText: TextView
    private lateinit var edgesButton: Button

    private val executor = Executors.newSingleThreadExecutor()
    private var loading = false
    private var showEdges = true

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

        findViewById<Button>(R.id.open_button).setOnClickListener { pickFile() }
        emptyText.setOnClickListener { pickFile() }
        findViewById<Button>(R.id.reset_button).setOnClickListener { modelView.resetView() }
        edgesButton.setOnClickListener {
            showEdges = !showEdges
            modelView.setShowEdges(showEdges)
            edgesButton.alpha = if (showEdges) 1f else 0.5f
        }

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
                    modelView.setMesh(mesh)
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
