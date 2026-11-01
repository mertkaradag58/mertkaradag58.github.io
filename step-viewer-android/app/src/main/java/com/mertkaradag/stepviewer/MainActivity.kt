package com.mertkaradag.stepviewer

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.text.util.Linkify
import android.view.LayoutInflater
import android.view.Menu
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var modelView: ModelView
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var emptyState: View
    private lateinit var recentTitle: TextView
    private lateinit var recentList: LinearLayout
    private lateinit var sideButtons: View
    private lateinit var toolbarCard: View
    private lateinit var measureCard: MaterialCardView
    private lateinit var measureText: TextView
    private lateinit var sectionCard: MaterialCardView
    private lateinit var sectionLabel: TextView
    private lateinit var sectionSlider: Slider
    private lateinit var progressOverlay: View
    private lateinit var progressText: TextView
    private lateinit var toolMeasure: View
    private lateinit var toolSection: View
    private lateinit var toolDisplayLabel: TextView

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var recentFiles: RecentFiles
    private lateinit var measure: MeasureController

    private var loading = false
    private var currentMesh: StepMesh? = null
    private var currentName = ""
    private var partVisible = BooleanArray(0)

    private var measureMode = false
    private var measureOverlay = ModelRenderer.Overlay()
    private var partHighlight = IntArray(0)

    private var sectionOn = false
    private var sectionAxis = 0
    private var sectionFlip = false

    private var displayMode = ModelRenderer.DisplayMode.SHADED_EDGES
    private var orthographic = false
    private var yUp = false

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) loadModel(uri, persist = true)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        recentFiles = RecentFiles(this)
        measure = MeasureController(
            context = this,
            runInBackground = { task -> executor.execute(task) },
            runOnUi = { task -> runOnUiThread(task) },
            onResult = { text, overlay ->
                measureText.text = text
                measureOverlay = overlay
                pushOverlay()
            },
        )

        bindViews()
        applyInsets()
        setupToolbar()
        setupSection()
        setupSideButtons()
        setupBackHandling()
        showRecentFiles()

        if (savedInstanceState == null) handleIntent(intent)
    }

    private fun bindViews() {
        modelView = findViewById(R.id.model_view)
        titleText = findViewById(R.id.title_text)
        subtitleText = findViewById(R.id.subtitle_text)
        emptyState = findViewById(R.id.empty_state)
        recentTitle = findViewById(R.id.recent_title)
        recentList = findViewById(R.id.recent_list)
        sideButtons = findViewById(R.id.side_buttons)
        toolbarCard = findViewById(R.id.toolbar_card)
        measureCard = findViewById(R.id.measure_card)
        measureText = findViewById(R.id.measure_text)
        sectionCard = findViewById(R.id.section_card)
        sectionLabel = findViewById(R.id.section_label)
        sectionSlider = findViewById(R.id.section_slider)
        progressOverlay = findViewById(R.id.progress_overlay)
        progressText = findViewById(R.id.progress_text)
        toolMeasure = findViewById(R.id.tool_measure)
        toolSection = findViewById(R.id.tool_section)
        toolDisplayLabel = findViewById(R.id.tool_display_label)

        modelView.onTap = { x, y -> onModelTap(x, y) }
    }

    /** Uygulama kenardan kenara çizilir; çubuklar sistem çubuklarının altında kalmasın. */
    private fun applyInsets() {
        val topBar = findViewById<View>(R.id.top_bar)
        val bottomArea = findViewById<View>(R.id.bottom_area)
        val emptyContent = findViewById<View>(R.id.empty_content)
        val density = resources.displayMetrics.density
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topBar.updatePadding(top = bars.top + (8 * density).toInt(), left = bars.left + (20 * density).toInt(), right = bars.right + (4 * density).toInt())
            bottomArea.updatePadding(bottom = bars.bottom + (12 * density).toInt(), left = bars.left + (12 * density).toInt(), right = bars.right + (12 * density).toInt())
            emptyContent.updatePadding(top = bars.top + (96 * density).toInt(), bottom = bars.bottom + (48 * density).toInt())
            sideButtons.updatePadding(right = bars.right)
            insets
        }
    }

    // -----------------------------------------------------------------------
    // Araç çubuğu ve menüler
    // -----------------------------------------------------------------------

    private fun setupToolbar() {
        findViewById<View>(R.id.empty_open_button).setOnClickListener { pickFile() }
        findViewById<View>(R.id.tool_open).setOnClickListener { pickFile() }
        toolMeasure.setOnClickListener { setMeasureMode(!measureMode) }
        toolSection.setOnClickListener { setSection(!sectionOn) }
        findViewById<View>(R.id.tool_parts).setOnClickListener { showParts() }
        findViewById<View>(R.id.tool_display).setOnClickListener { cycleDisplayMode() }
        findViewById<View>(R.id.measure_close).setOnClickListener { setMeasureMode(false) }
        findViewById<MaterialButton>(R.id.more_button).setOnClickListener { showMoreMenu(it) }
    }

    private fun setupSideButtons() {
        findViewById<View>(R.id.fit_button).setOnClickListener { modelView.fit() }
        findViewById<View>(R.id.spin_button).setOnClickListener { modelView.spin360() }
        findViewById<View>(R.id.views_button).setOnClickListener { showViewsMenu(it) }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this) {
            when {
                measureMode -> setMeasureMode(false)
                sectionOn -> setSection(false)
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }
    }

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val hasModel = currentMesh != null
        popup.menu.add(Menu.NONE, MENU_INFO, 0, R.string.menu_model_info).isEnabled = hasModel
        popup.menu.add(Menu.NONE, MENU_SHARE, 1, R.string.menu_share).isEnabled = hasModel
        popup.menu.add(Menu.NONE, MENU_ABOUT, 2, R.string.menu_about)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_INFO -> showModelInfo()
                MENU_SHARE -> shareScreenshot()
                MENU_ABOUT -> showAbout()
            }
            true
        }
        popup.show()
    }

    private fun showViewsMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val views = listOf(
            R.string.view_iso to Camera.View.ISO,
            R.string.view_front to Camera.View.FRONT,
            R.string.view_back to Camera.View.BACK,
            R.string.view_top to Camera.View.TOP,
            R.string.view_bottom to Camera.View.BOTTOM,
            R.string.view_left to Camera.View.LEFT,
            R.string.view_right to Camera.View.RIGHT,
        )
        views.forEachIndexed { i, (label, _) -> popup.menu.add(Menu.NONE, i, i, label) }
        popup.menu.add(1, MENU_ORTHO, 100, R.string.view_ortho).apply {
            isCheckable = true
            isChecked = orthographic
        }
        popup.menu.add(1, MENU_YUP, 101, R.string.view_y_up).apply {
            isCheckable = true
            isChecked = yUp
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_ORTHO -> {
                    orthographic = !orthographic
                    modelView.setOrthographic(orthographic)
                }
                MENU_YUP -> {
                    yUp = !yUp
                    modelView.setUpAxis(if (yUp) Camera.UpAxis.Y else Camera.UpAxis.Z)
                }
                else -> views.getOrNull(item.itemId)?.let { modelView.animateTo(it.second) }
            }
            true
        }
        popup.show()
    }

    private fun cycleDisplayMode() {
        displayMode = when (displayMode) {
            ModelRenderer.DisplayMode.SHADED_EDGES -> ModelRenderer.DisplayMode.SHADED
            ModelRenderer.DisplayMode.SHADED -> ModelRenderer.DisplayMode.WIREFRAME
            ModelRenderer.DisplayMode.WIREFRAME -> ModelRenderer.DisplayMode.SHADED_EDGES
        }
        toolDisplayLabel.setText(
            when (displayMode) {
                ModelRenderer.DisplayMode.SHADED_EDGES -> R.string.display_shaded_edges
                ModelRenderer.DisplayMode.SHADED -> R.string.display_shaded
                ModelRenderer.DisplayMode.WIREFRAME -> R.string.display_wireframe
            }
        )
        modelView.setDisplayMode(displayMode)
    }

    // -----------------------------------------------------------------------
    // Dosya açma
    // -----------------------------------------------------------------------

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
            intent.data?.let { loadModel(it, persist = true) }
        }
    }

    private fun pickFile() {
        if (loading) return
        // CAD MIME türleri cihazdan cihaza değiştiği için tüm dosyalar gösterilir.
        openDocument.launch(arrayOf("*/*"))
    }

    private fun loadModel(uri: Uri, persist: Boolean) {
        if (loading) {
            Toast.makeText(this, R.string.busy, Toast.LENGTH_SHORT).show()
            return
        }
        loading = true
        val name = displayName(uri)
        progressText.text = getString(R.string.loading_file, name)
        progressOverlay.visibility = View.VISIBLE

        if (persist) {
            // Son açılanlar listesinden tekrar açabilmek için kalıcı izin (mümkünse)
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }

        executor.execute {
            val started = SystemClock.elapsedRealtime()
            val result: Result<StepMesh> = try {
                val file = copyToCache(uri)
                runOnUiThread { progressText.text = getString(R.string.meshing_file, name) }
                Result.success(StepNative.loadModel(file.absolutePath, 0.0))
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: OutOfMemoryError) {
                Result.failure(IOException(getString(R.string.error_oom)))
            } catch (e: SecurityException) {
                recentFiles.remove(uri)
                Result.failure(IOException(getString(R.string.error_permission)))
            }
            val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0

            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                loading = false
                progressOverlay.visibility = View.GONE
                result.onSuccess { mesh ->
                    onModelLoaded(mesh, name, seconds)
                    if (hasPersistedPermission(uri)) recentFiles.add(uri, name)
                }.onFailure { e ->
                    showRecentFiles()
                    showError(e.message ?: getString(R.string.error_unknown))
                }
            }
        }
    }

    private fun onModelLoaded(mesh: StepMesh, name: String, seconds: Double) {
        currentMesh = mesh
        currentName = name
        partVisible = BooleanArray(mesh.partCount) { true }
        partHighlight = IntArray(0)
        measureOverlay = ModelRenderer.Overlay()

        modelView.setMesh(mesh)
        modelView.setDisplayMode(displayMode)
        measure.setMesh(mesh)
        setMeasureMode(false)
        setSection(false)

        titleText.text = name
        subtitleText.text = getString(R.string.model_info_line, mesh.formatName, mesh.partCount, mesh.triangleCount, seconds)
        subtitleText.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
        toolbarCard.visibility = View.VISIBLE
        sideButtons.visibility = View.VISIBLE
    }

    private fun hasPersistedPermission(uri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    private fun showRecentFiles() {
        val items = recentFiles.list()
        recentList.removeAllViews()
        recentTitle.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        val inflater = LayoutInflater.from(this)
        for (item in items) {
            val row = inflater.inflate(R.layout.item_recent, recentList, false)
            row.findViewById<TextView>(R.id.recent_name).text = item.name
            row.findViewById<TextView>(R.id.recent_date).text =
                DateFormat.getMediumDateFormat(this).format(item.time) + " " +
                    DateFormat.getTimeFormat(this).format(item.time)
            row.setOnClickListener { loadModel(item.uri, persist = false) }
            recentList.addView(row)
        }
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /** İçerik URI'sini OCCT'nin okuyabileceği gerçek bir dosyaya kopyalar. */
    @Throws(IOException::class)
    private fun copyToCache(uri: Uri): File {
        val target = File(cacheDir, "current_model.dat")
        val input = contentResolver.openInputStream(uri) ?: throw IOException(getString(R.string.error_open))
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

    // -----------------------------------------------------------------------
    // Ölçüm
    // -----------------------------------------------------------------------

    private fun setMeasureMode(enabled: Boolean) {
        measureMode = enabled
        toolMeasure.isActivated = enabled
        measureCard.visibility = if (enabled && currentMesh != null) View.VISIBLE else View.GONE
        measure.clear()
        if (!enabled) {
            measureOverlay = ModelRenderer.Overlay()
            pushOverlay()
        }
    }

    private fun onModelTap(x: Float, y: Float) {
        if (!measureMode || loading) return
        val mesh = currentMesh ?: return
        val mvp = modelView.currentMvp()
        val w = modelView.width
        val h = modelView.height
        val threshold = 18f * resources.displayMetrics.density
        val visible = if (partVisible.all { it }) null else partVisible.copyOf()
        val clip = if (sectionOn) clipPlane(mesh) else null
        executor.execute {
            val hit = Picker.pick(mesh, mvp, w, h, x, y, threshold, visible, clip)
            runOnUiThread { if (mesh === currentMesh && measureMode) measure.onPicked(hit) }
        }
    }

    private fun pushOverlay() {
        val o = measureOverlay
        modelView.setOverlay(
            ModelRenderer.Overlay(
                faceRanges = o.faceRanges,
                edgeSegments = o.edgeSegments,
                measureLine = o.measureLine,
                partRanges = partHighlight,
            )
        )
    }

    // -----------------------------------------------------------------------
    // Kesit
    // -----------------------------------------------------------------------

    private fun setupSection() {
        findViewById<ChipGroup>(R.id.section_axis_group).setOnCheckedStateChangeListener { _, ids ->
            sectionAxis = when (ids.firstOrNull()) {
                R.id.chip_y -> 1
                R.id.chip_z -> 2
                else -> 0
            }
            updateSection()
        }
        sectionSlider.addOnChangeListener { _, _, _ -> updateSection() }
        findViewById<View>(R.id.section_flip).setOnClickListener {
            sectionFlip = !sectionFlip
            updateSection()
        }
        findViewById<View>(R.id.section_close).setOnClickListener { setSection(false) }
    }

    private fun setSection(enabled: Boolean) {
        sectionOn = enabled && currentMesh != null
        toolSection.isActivated = sectionOn
        sectionCard.visibility = if (sectionOn) View.VISIBLE else View.GONE
        updateSection()
    }

    private fun updateSection() {
        val mesh = currentMesh
        if (!sectionOn || mesh == null) {
            modelView.setClipPlane(null)
            return
        }
        val plane = clipPlane(mesh)
        val axisName = arrayOf("X", "Y", "Z")[sectionAxis]
        sectionLabel.text = getString(R.string.section_position, axisName, fmt(sectionPosition(mesh), 2))
        modelView.setClipPlane(plane)
    }

    private fun sectionPosition(mesh: StepMesh): Float {
        val min = mesh.bbox[sectionAxis]
        val max = mesh.bbox[sectionAxis + 3]
        val margin = (max - min) * 0.01f
        return (min - margin) + (max - min + 2 * margin) * (sectionSlider.value / 1000f)
    }

    /** [nx, ny, nz, d]: n·p + d >= 0 olan kısım görünür. */
    private fun clipPlane(mesh: StepMesh): FloatArray {
        val pos = sectionPosition(mesh)
        val plane = FloatArray(4)
        val sign = if (sectionFlip) 1f else -1f
        plane[sectionAxis] = sign
        plane[3] = -sign * pos
        return plane
    }

    // -----------------------------------------------------------------------
    // Parçalar
    // -----------------------------------------------------------------------

    private fun showParts() {
        val mesh = currentMesh ?: return
        PartsSheet(
            context = this,
            mesh = mesh,
            partVisible = partVisible,
            onVisibilityChanged = { applyVisibility() },
            onHighlight = { start, end ->
                val ranges = ArrayList<Int>()
                for (p in start until end) {
                    val r = mesh.partTriangleRange(p)
                    if (!r.isEmpty()) {
                        ranges += r.first
                        ranges += r.last - r.first + 1
                    }
                }
                partHighlight = ranges.toIntArray()
                pushOverlay()
            },
        ).show()
    }

    /** Görünen parçaların üçgen ve kenar aralıklarını çiziciye verir. */
    private fun applyVisibility() {
        val mesh = currentMesh ?: return
        if (partVisible.all { it }) {
            modelView.setVisibility(null, null)
            return
        }
        val tri = ArrayList<Int>()
        val seg = ArrayList<Int>()
        var p = 0
        while (p < mesh.partCount) {
            if (!partVisible[p]) {
                p++
                continue
            }
            // Ardışık görünen parçaları tek çizim çağrısında birleştir
            var q = p
            while (q + 1 < mesh.partCount && partVisible[q + 1]) q++
            val t0 = mesh.faceOffsets[mesh.partFaceOffsets[p]]
            val t1 = mesh.faceOffsets[mesh.partFaceOffsets[q + 1]]
            if (t1 > t0) { tri += t0; tri += t1 - t0 }
            val s0 = mesh.edgeOffsets[mesh.partEdgeOffsets[p]]
            val s1 = mesh.edgeOffsets[mesh.partEdgeOffsets[q + 1]]
            if (s1 > s0) { seg += s0; seg += s1 - s0 }
            p = q + 1
        }
        modelView.setVisibility(tri.toIntArray(), seg.toIntArray())
    }

    // -----------------------------------------------------------------------
    // Model bilgisi, paylaşma, hakkında
    // -----------------------------------------------------------------------

    private fun showModelInfo() {
        val mesh = currentMesh ?: return
        ModelInfoDialog(
            context = this,
            mesh = mesh,
            partVisible = partVisible,
            runInBackground = { task -> executor.execute(task) },
            runOnUi = { task -> runOnUiThread(task) },
        ).show()
    }

    private fun shareScreenshot() {
        val overlayText = if (measureMode) measure.lastText else ""
        modelView.captureScreenshot { bmp ->
            if (bmp == null) {
                Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
                return@captureScreenshot
            }
            try {
                val annotated = annotate(bmp, currentName, overlayText)
                val dir = File(cacheDir, "shared").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "ekran_${System.currentTimeMillis()}.png")
                file.outputStream().use { annotated.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, currentName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, getString(R.string.share_title)))
            } catch (e: Exception) {
                Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Görüntünün üstüne dosya adını, altına ölçüm sonuçlarını yazar. */
    private fun annotate(src: Bitmap, title: String, text: String): Bitmap {
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bmp)
        val d = resources.displayMetrics.density
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 15f * d
        }
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 20, 23, 28) }

        // Başlık
        paint.textSize = 16f * d
        val titleW = paint.measureText(title)
        canvas.drawRoundRect(RectF(12 * d, 12 * d, 12 * d + titleW + 24 * d, 48 * d), 12 * d, 12 * d, bg)
        canvas.drawText(title, 24 * d, 36 * d, paint)

        // Ölçüm
        if (text.isNotBlank()) {
            paint.textSize = 14f * d
            val lines = text.split('\n')
            val lineH = 20f * d
            val boxH = lines.size * lineH + 20 * d
            val maxW = lines.maxOf { paint.measureText(it) }
            val top = bmp.height - boxH - 12 * d
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(255, 140, 0)
                style = Paint.Style.STROKE
                strokeWidth = 1.5f * d
            }
            val rect = RectF(12 * d, top, 12 * d + maxW + 28 * d, top + boxH)
            canvas.drawRoundRect(rect, 14 * d, 14 * d, bg)
            canvas.drawRoundRect(rect, 14 * d, 14 * d, border)
            lines.forEachIndexed { i, line ->
                canvas.drawText(line, 26 * d, top + 10 * d + (i + 1) * lineH - 5 * d, paint)
            }
        }
        return bmp
    }

    private fun showAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            text = getString(R.string.about_text)
            setPadding(pad, pad / 2, pad, 0)
            setTextColor(getColor(R.color.text))
            textSize = 14f
            Linkify.addLinks(this, Linkify.WEB_URLS)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.about_title, version))
            .setView(tv)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    companion object {
        private const val MENU_INFO = 1
        private const val MENU_SHARE = 2
        private const val MENU_ABOUT = 3
        private const val MENU_ORTHO = 50
        private const val MENU_YUP = 51
    }
}
