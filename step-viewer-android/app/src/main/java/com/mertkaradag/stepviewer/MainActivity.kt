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
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var modelView: ModelView
    private lateinit var ads: Ads
    private lateinit var billing: Billing
    /** Paylaşım ekranından dönünce tam ekran reklam gösterilsin. */
    private var adAfterShare = false
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
    private lateinit var progressElapsed: TextView
    private lateinit var progressCancel: View
    private lateinit var toolMeasure: View
    private lateinit var toolSection: View
    private lateinit var toolDisplayLabel: TextView
    private lateinit var toolSelect: View
    private lateinit var selectCard: MaterialCardView
    private lateinit var selectName: TextView
    private lateinit var selectPath: TextView
    private lateinit var selectActions: View
    private lateinit var hiddenCard: View
    private lateinit var hiddenText: TextView

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

    // Parça seçimi: [selStart, selEnd) parça aralığı (tek parça veya alt montaj)
    private var selectMode = false
    private var selStart = -1
    private var selEnd = -1
    private var selName = ""

    // Görünürlük geçmişi (Geri al)
    private val visibilityUndo = ArrayDeque<BooleanArray>()
    private var lastVisible = BooleanArray(0)

    private var sectionOn = false
    private var sectionAxis = 0
    private var sectionFlip = false

    // Patlatılmış görünüm
    private lateinit var explodeCard: MaterialCardView
    private lateinit var explodeSlider: Slider
    private var explode: Explode? = null
    private var explodeFactor = 0f

    // PMI (STEP AP242 ölçü ve toleransları)
    private lateinit var pmiButton: View
    private var pmi: Pmi? = null
    private var pmiOn = false
    private var pmiSelected = -1

    // Dışa aktarma: dosya seçildikten sonra yazılacak içerik
    private var pendingExport: Exporter.Format? = null
    private var pendingBomDensity = 0.0

    private var displayMode = ModelRenderer.DisplayMode.SHADED_EDGES
    private var orthographic = false
    private var yUp = false

    private val exportStl = registerForActivityResult(ActivityResultContracts.CreateDocument("model/stl")) { uri ->
        if (uri != null) runExport(uri)
    }
    private val exportObj = registerForActivityResult(ActivityResultContracts.CreateDocument("model/obj")) { uri ->
        if (uri != null) runExport(uri)
    }
    private val exportGlb = registerForActivityResult(ActivityResultContracts.CreateDocument("model/gltf-binary")) { uri ->
        if (uri != null) runExport(uri)
    }
    private val exportCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) runBomExport(uri)
    }

    private var pendingImage: Bitmap? = null
    private val saveImage = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bmp = pendingImage
        pendingImage = null
        if (uri != null && bmp != null) {
            val name = displayName(uri)
            executor.execute {
                val ok = runCatching {
                    contentResolver.openOutputStream(uri, "w")?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        ?: throw IOException("open")
                }.isSuccess
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (ok) getString(R.string.markup_saved, name) else getString(R.string.export_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

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

        ads = Ads(this, findViewById(R.id.ad_container))
        billing = Billing(
            context = this,
            onAdFreeChanged = { adFree -> if (adFree) ads.disable() else ads.enable() },
            onMessage = { res -> if (!isDestroyed) Toast.makeText(this, res, Toast.LENGTH_LONG).show() },
        )
        // Reklamsız alınmışsa reklam altyapısı (ve onay formu) hiç başlatılmaz
        if (billing.adFree) ads.disable() else ads.start()
        billing.start()

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
        progressElapsed = findViewById(R.id.progress_elapsed)
        progressCancel = findViewById(R.id.progress_cancel)
        progressCancel.setOnClickListener { cancelLoad() }
        toolMeasure = findViewById(R.id.tool_measure)
        toolSection = findViewById(R.id.tool_section)
        toolDisplayLabel = findViewById(R.id.tool_display_label)
        toolSelect = findViewById(R.id.tool_select)
        selectCard = findViewById(R.id.select_card)
        selectName = findViewById(R.id.select_name)
        selectPath = findViewById(R.id.select_path)
        selectActions = findViewById(R.id.select_actions)
        hiddenCard = findViewById(R.id.hidden_card)
        hiddenText = findViewById(R.id.hidden_text)

        pmiButton = findViewById(R.id.pmi_button)
        explodeCard = findViewById(R.id.explode_card)
        explodeSlider = findViewById(R.id.explode_slider)

        modelView.onTap = { x, y -> onModelTap(x, y) }
        modelView.onDoubleTap = { x, y -> onModelDoubleTap(x, y) }
        modelView.onLongPress = { x, y -> onModelLongPress(x, y) }
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
            // Altta reklam alanı olduğu için boş ekran içeriği yukarıda biter
            emptyContent.updatePadding(top = bars.top + (96 * density).toInt(), bottom = bars.bottom + (130 * density).toInt())
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
        toolSelect.setOnClickListener { setSelectMode(!selectMode) }
        findViewById<View>(R.id.select_close).setOnClickListener { setSelectMode(false) }
        findViewById<View>(R.id.select_hide).setOnClickListener { hideSelection() }
        findViewById<View>(R.id.select_isolate).setOnClickListener { isolateSelection() }
        findViewById<View>(R.id.select_info).setOnClickListener { showSelectionInfo() }
        findViewById<View>(R.id.hidden_undo).setOnClickListener { undoVisibility() }
        findViewById<View>(R.id.hidden_show_all).setOnClickListener {
            partVisible.fill(true)
            applyVisibility()
        }
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
        findViewById<View>(R.id.explode_button).setOnClickListener { toggleExplodeCard() }
        pmiButton.setOnClickListener { showPmiDialog() }
        findViewById<View>(R.id.explode_close).setOnClickListener { closeExplode() }
        explodeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) setExplodeFactor(value / 100f * Explode.MAX_FACTOR)
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this) {
            when {
                measureMode -> setMeasureMode(false)
                selectMode -> setSelectMode(false)
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
        popup.menu.add(Menu.NONE, MENU_MARKUP, 1, R.string.menu_markup).isEnabled = hasModel
        popup.menu.add(Menu.NONE, MENU_EXPORT, 1, R.string.menu_export).isEnabled = hasModel
        popup.menu.add(Menu.NONE, MENU_BOM, 1, R.string.menu_bom).isEnabled = hasModel
        popup.menu.add(Menu.NONE, MENU_QUALITY, 2, R.string.menu_quality)
        popup.menu.add(Menu.NONE, MENU_ABOUT, 2, R.string.menu_about)
        if (!billing.adFree) {
            val price = billing.formattedPrice
            val title = if (price != null) getString(R.string.menu_remove_ads_price, price)
            else getString(R.string.menu_remove_ads)
            popup.menu.add(Menu.NONE, MENU_REMOVE_ADS, 3, title)
            popup.menu.add(Menu.NONE, MENU_RESTORE, 4, R.string.menu_restore_purchase)
            if (ads.privacyOptionsRequired) popup.menu.add(Menu.NONE, MENU_PRIVACY, 5, R.string.menu_privacy)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_INFO -> showModelInfo()
                MENU_SHARE -> shareScreenshot()
                MENU_MARKUP -> startMarkup()
                MENU_EXPORT -> showExportDialog()
                MENU_BOM -> showBomDialog()
                MENU_ABOUT -> showAbout()
                MENU_QUALITY -> showQualityDialog()
                MENU_PRIVACY -> ads.showPrivacyOptions()
                MENU_REMOVE_ADS -> billing.buyAdFree(this)
                MENU_RESTORE -> billing.restore()
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
        ads.onResume()
        if (adAfterShare) {
            adAfterShare = false
            ads.showInterstitial()
        }
    }

    override fun onPause() {
        ads.onPause()
        modelView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        tickHandler.removeCallbacks(tick)
        ads.onDestroy()
        billing.destroy()
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

    // Yükleme durumu: iptal edilen işin sonucu jetonla ayırt edilir
    private var loadToken = 0
    private var currentUri: Uri? = null
    private val tickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var tickStarted = 0L
    private var tickInfo = ""
    private val tick = object : Runnable {
        override fun run() {
            val sec = ((SystemClock.elapsedRealtime() - tickStarted) / 1000).toInt()
            progressElapsed.text = getString(R.string.elapsed, sec, tickInfo)
            tickHandler.postDelayed(this, 1000)
        }
    }

    private val settings by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    /** 0 = hızlı, 1 = normal, 2 = hassas. */
    private var meshQuality: Int
        get() = settings.getInt("mesh_quality", 1)
        set(v) = settings.edit().putInt("mesh_quality", v).apply()

    private fun deflectionFor(quality: Int): Double = when (quality) {
        0 -> 0.004
        2 -> 0.0003
        else -> 0.001
    }

    private fun loadModel(uri: Uri, persist: Boolean) {
        if (loading) {
            Toast.makeText(this, R.string.busy, Toast.LENGTH_SHORT).show()
            return
        }
        loading = true
        val token = ++loadToken
        val name = displayName(uri)
        progressText.text = getString(R.string.loading_file, name)
        progressOverlay.visibility = View.VISIBLE
        progressCancel.visibility = View.VISIBLE
        tickInfo = ""
        tickStarted = SystemClock.elapsedRealtime()
        progressElapsed.text = ""
        progressElapsed.visibility = View.VISIBLE
        tickHandler.removeCallbacks(tick)
        tickHandler.postDelayed(tick, 1000)

        if (persist) {
            // Son açılanlar listesinden tekrar açabilmek için kalıcı izin (mümkünse)
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }

        val quality = meshQuality
        executor.execute {
            val started = SystemClock.elapsedRealtime()
            var fellBack = false
            val result: Result<StepMesh> = try {
                val file = copyToCache(uri, name)
                val mb = file.length() / (1024.0 * 1024.0)
                runOnUiThread {
                    if (token != loadToken) return@runOnUiThread
                    tickInfo = String.format(Locale.getDefault(), "%.1f MB", mb)
                    progressText.text = getString(R.string.meshing_file, name)
                }
                try {
                    Result.success(StepNative.loadModel(file.absolutePath, deflectionFor(quality)))
                } catch (e: Throwable) {
                    val oom = e is OutOfMemoryError || (e is IOException && e.message?.startsWith("out_of_memory") == true)
                    if (!oom || quality == 0 || token != loadToken) throw e
                    // Bellek yetmedi: daha kaba ağla bir kez daha dene
                    System.gc()
                    runOnUiThread { if (token == loadToken) progressText.text = getString(R.string.oom_retry) }
                    fellBack = true
                    Result.success(StepNative.loadModel(file.absolutePath, deflectionFor(0)))
                }
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
                if (isDestroyed || token != loadToken) return@runOnUiThread // iptal edildi
                loading = false
                stopProgress()
                result.onSuccess { mesh ->
                    currentUri = uri
                    onModelLoaded(mesh, name, seconds)
                    if (hasPersistedPermission(uri)) recentFiles.add(uri, name)
                    if (fellBack) Toast.makeText(this, R.string.oom_retried, Toast.LENGTH_LONG).show()
                    ads.showInterstitial()
                }.onFailure { e ->
                    showRecentFiles()
                    showError(localizedError(e.message))
                }
            }
        }
    }

    /**
     * Yüklemeyi iptal eder. Yerel okuma yarıda kesilemez; biter bitmez sonucu atılır.
     * Yerel taraf artık başka bir modeli tuttuğu için ekran boş duruma döner.
     */
    private fun cancelLoad() {
        if (!loading) return
        loadToken++
        loading = false
        stopProgress()
        currentMesh = null
        currentUri = null
        modelView.visibility = View.VISIBLE
        setMeasureMode(false)
        setSelectMode(false)
        setSection(false)
        explodeCard.visibility = View.GONE
        toolbarCard.visibility = View.GONE
        sideButtons.visibility = View.GONE
        subtitleText.visibility = View.GONE
        titleText.setText(R.string.app_name)
        hiddenCard.visibility = View.GONE
        emptyState.visibility = View.VISIBLE
        showRecentFiles()
        Toast.makeText(this, R.string.load_cancelled, Toast.LENGTH_SHORT).show()
    }

    private fun stopProgress() {
        tickHandler.removeCallbacks(tick)
        progressOverlay.visibility = View.GONE
        progressCancel.visibility = View.GONE
        progressElapsed.visibility = View.GONE
    }

    private fun showQualityDialog() {
        val labels = arrayOf(
            getString(R.string.quality_fast),
            getString(R.string.quality_normal),
            getString(R.string.quality_fine),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_quality)
            .setSingleChoiceItems(labels, meshQuality) { d, which ->
                d.dismiss()
                if (which == meshQuality) return@setSingleChoiceItems
                meshQuality = which
                val uri = currentUri
                if (uri != null && currentMesh != null) {
                    Toast.makeText(this, R.string.quality_reload, Toast.LENGTH_SHORT).show()
                    loadModel(uri, persist = false)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onModelLoaded(mesh: StepMesh, name: String, seconds: Double) {
        currentMesh = mesh
        currentName = name
        partVisible = BooleanArray(mesh.partCount) { true }
        lastVisible = partVisible.copyOf()
        visibilityUndo.clear()
        partHighlight = IntArray(0)
        measureOverlay = ModelRenderer.Overlay()
        selStart = -1
        selEnd = -1

        modelView.setMesh(mesh)
        modelView.setDisplayMode(displayMode)
        explode = null
        explodeFactor = 0f
        explodeSlider.value = 0f
        explodeCard.visibility = View.GONE
        modelView.camera.extentScale = 1f
        pmi = null
        pmiOn = false
        pmiSelected = -1
        pmiButton.visibility = View.GONE
        if (mesh.format == StepMesh.FORMAT_STEP) loadPmi(mesh)
        measure.setMesh(mesh)
        setMeasureMode(false)
        setSelectMode(false)
        setSection(false)
        updateHiddenCard()

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

    /** Yerel çekirdeğin hata kodlarını (ör. "read_failed:STEP") cihaz diline çevirir. */
    private fun localizedError(message: String?): String {
        if (message.isNullOrBlank()) return getString(R.string.error_unknown)
        val code = message.substringBefore(':')
        val arg = message.substringAfter(':', "")
        return when (code) {
            "read_failed" -> getString(R.string.error_read_format, arg)
            "transfer_failed" -> getString(R.string.error_transfer_format, arg)
            "unsupported_format" -> getString(R.string.error_unsupported)
            "no_geometry" -> getString(R.string.error_no_geometry)
            "out_of_memory" -> getString(R.string.error_oom)
            "unknown_error" -> getString(R.string.error_unknown)
            else -> message
        }
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    /**
     * İçerik URI'sini okuyucuların açabileceği gerçek bir dosyaya kopyalar. Uzantı korunur:
     * mesh dosyalarının (OBJ, STL, FBX...) biçimi uzantısından anlaşılır.
     */
    @Throws(IOException::class)
    private fun copyToCache(uri: Uri, name: String): File {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            .takeIf { it.length in 1..5 && it.all { c -> c.isLetterOrDigit() } } ?: "dat"
        cacheDir.listFiles { f -> f.name.startsWith("current_model.") }?.forEach { it.delete() }
        val target = File(cacheDir, "current_model.$ext")
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
        if (enabled && explodeFactor > 0f) {
            // Ölçüm gerçek geometri üzerinde yapılır; kaydırılmış parçalarla karışmasın
            closeExplode()
            Toast.makeText(this, R.string.explode_measure_off, Toast.LENGTH_SHORT).show()
        }
        if (enabled && selectMode) setSelectMode(false)
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
        if (loading) return
        if (selectMode) {
            pickPartAt(x, y)
            return
        }
        if (!measureMode) return
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

    // -----------------------------------------------------------------------
    // Parça seçimi
    // -----------------------------------------------------------------------

    private fun setSelectMode(enabled: Boolean) {
        val on = enabled && currentMesh != null
        if (on && measureMode) setMeasureMode(false)
        selectMode = on
        toolSelect.isActivated = on
        if (!on) setSelection(-1, -1) else updateSelectCard()
    }

    /** Uzun basma: hangi modda olursa olsun parçayı seçer. */
    private fun onModelLongPress(x: Float, y: Float) {
        if (loading || currentMesh == null) return
        modelView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        if (!selectMode) setSelectMode(true)
        pickPartAt(x, y)
    }

    private fun pickPartAt(x: Float, y: Float) {
        val mesh = currentMesh ?: return
        val mvp = modelView.currentMvp()
        val w = modelView.width
        val h = modelView.height
        val threshold = 18f * resources.displayMetrics.density
        val visible = if (partVisible.all { it }) null else partVisible.copyOf()
        val clip = if (sectionOn) clipPlane(mesh) else null
        executor.execute {
            val offsets = currentExplodeOffsets()
            val part = Picker.pickPart(mesh, mvp, w, h, x, y, threshold, visible, clip, offsets)
            runOnUiThread {
                if (mesh !== currentMesh || !selectMode) return@runOnUiThread
                if (part < 0) setSelection(-1, -1) else setSelection(part, part + 1)
            }
        }
    }

    private fun setSelection(start: Int, end: Int) {
        val mesh = currentMesh
        if (mesh == null || start < 0 || end <= start) {
            selStart = -1
            selEnd = -1
        } else {
            selStart = start
            selEnd = end
        }
        highlightParts(selStart, selEnd)
        updateSelectCard()
    }

    private fun highlightParts(start: Int, end: Int) {
        val mesh = currentMesh
        val ranges = ArrayList<Int>()
        if (mesh != null && start >= 0) {
            for (p in start until end) {
                val r = mesh.partTriangleRange(p)
                if (!r.isEmpty()) {
                    ranges += r.first
                    ranges += r.last - r.first + 1
                }
            }
        }
        partHighlight = ranges.toIntArray()
        pushOverlay()
    }

    private fun updateSelectCard() {
        val mesh = currentMesh
        if (!selectMode || mesh == null) {
            selectCard.visibility = View.GONE
            return
        }
        selectCard.visibility = View.VISIBLE
        if (selStart < 0) {
            selectName.text = getString(R.string.select_hint)
            selectName.setTypeface(null, android.graphics.Typeface.NORMAL)
            selectName.textSize = 14f
            selectPath.visibility = View.GONE
            selectActions.visibility = View.GONE
            return
        }
        selectName.setTypeface(null, android.graphics.Typeface.BOLD)
        selectName.textSize = 16f
        selectActions.visibility = View.VISIBLE
        if (selEnd - selStart == 1) {
            selName = mesh.partName(selStart, getString(R.string.part_fallback, selStart + 1))
            val path = mesh.partPath(selStart)
            val index = getString(R.string.select_part_index, selStart + 1, mesh.partCount)
            selectPath.text = if (path.isEmpty()) index else path.joinToString(" › ") + "  ·  " + index
        } else {
            // Listeden seçilen alt montaj
            val node = mesh.tree.firstOrNull { it.isAssembly && it.leafStart == selStart && it.leafEnd == selEnd }
            selName = node?.name?.takeIf { it.isNotBlank() } ?: getString(R.string.assembly_fallback)
            selectPath.text = getString(R.string.parts_title, selEnd - selStart)
        }
        selectName.text = selName
        selectPath.visibility = View.VISIBLE
    }

    private fun hideSelection() {
        if (selStart < 0) return
        for (p in selStart until selEnd) partVisible[p] = false
        setSelection(-1, -1)
        applyVisibility()
    }

    private fun isolateSelection() {
        if (selStart < 0) return
        for (p in partVisible.indices) partVisible[p] = p >= selStart && p < selEnd
        applyVisibility()
    }

    private fun showSelectionInfo() {
        val mesh = currentMesh ?: return
        if (selStart < 0) return
        val only = BooleanArray(mesh.partCount) { it >= selStart && it < selEnd }
        ModelInfoDialog(
            context = this,
            mesh = mesh,
            partVisible = only,
            runInBackground = { task -> executor.execute(task) },
            runOnUi = { task -> runOnUiThread(task) },
            partTitle = selName,
            part = if (selEnd - selStart == 1) selStart else -1,
        ).show()
    }

    private fun undoVisibility() {
        val prev = visibilityUndo.removeLastOrNull() ?: return
        prev.copyInto(partVisible)
        applyVisibility(recordUndo = false)
    }

    private fun updateHiddenCard() {
        val hidden = partVisible.count { !it }
        if (currentMesh == null || hidden == 0) {
            hiddenCard.visibility = View.GONE
            return
        }
        hiddenCard.visibility = View.VISIBLE
        hiddenText.text = getString(R.string.hidden_count, hidden)
        findViewById<View>(R.id.hidden_undo).visibility = if (visibilityUndo.isEmpty()) View.GONE else View.VISIBLE
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
            selectedPart = if (selEnd - selStart == 1) selStart else -1,
            onVisibilityChanged = { applyVisibility() },
            onSelect = { start, end ->
                if (!selectMode) setSelectMode(true)
                setSelection(start, end)
            },
            onDismiss = { highlightParts(selStart, selEnd) },
        ).show()
    }

    /** Görünen parçaların üçgen ve kenar aralıklarını çiziciye verir. */
    private fun applyVisibility(recordUndo: Boolean = true) {
        val mesh = currentMesh ?: return
        if (recordUndo && !lastVisible.contentEquals(partVisible)) {
            visibilityUndo.addLast(lastVisible)
            while (visibilityUndo.size > 30) visibilityUndo.removeFirst()
        }
        lastVisible = partVisible.copyOf()
        updateHiddenCard()
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
    // Dönme merkezi (çift dokunma)
    // -----------------------------------------------------------------------

    /** Modele çift dokunma: o nokta dönme merkezi olur; boşluğa çift dokunma: sığdır. */
    private fun onModelDoubleTap(x: Float, y: Float) {
        if (loading) return
        val mesh = currentMesh ?: return
        val mvp = modelView.currentMvp()
        val w = modelView.width
        val h = modelView.height
        val visible = if (partVisible.all { it }) null else partVisible.copyOf()
        val clip = if (sectionOn) clipPlane(mesh) else null
        val offsets = currentExplodeOffsets()
        executor.execute {
            val point = Picker.pickPoint(mesh, mvp, w, h, x, y, visible, clip, offsets)
            runOnUiThread {
                if (mesh !== currentMesh) return@runOnUiThread
                if (point != null) {
                    modelView.focusOn(point)
                    if (!focusHintShown) {
                        focusHintShown = true
                        Toast.makeText(this, R.string.focus_hint, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    modelView.fit()
                }
            }
        }
    }

    private var focusHintShown = false

    // -----------------------------------------------------------------------
    // Patlatılmış görünüm
    // -----------------------------------------------------------------------

    private fun toggleExplodeCard() {
        val mesh = currentMesh ?: return
        if (explodeCard.visibility == View.VISIBLE) {
            closeExplode()
            return
        }
        if (mesh.partCount < 2) {
            Toast.makeText(this, R.string.explode_single_part, Toast.LENGTH_SHORT).show()
            return
        }
        if (measureMode) setMeasureMode(false)
        explodeCard.visibility = View.VISIBLE
        if (explode == null) {
            executor.execute {
                val data = Explode.compute(mesh)
                runOnUiThread {
                    if (mesh !== currentMesh) return@runOnUiThread
                    explode = data
                    modelView.setExplodeDirections(data.vertexDirs, data.edgeDirs)
                    setExplodeFactor(explodeSlider.value / 100f * Explode.MAX_FACTOR)
                }
            }
        }
    }

    private fun closeExplode() {
        explodeCard.visibility = View.GONE
        explodeSlider.value = 0f
        setExplodeFactor(0f)
    }

    private fun setExplodeFactor(f: Float) {
        explodeFactor = if (explode == null) 0f else f
        modelView.camera.extentScale = 1f + explodeFactor
        modelView.setExplodeFactor(explodeFactor)
    }

    /** Seçim için parça kaymaları; patlatma kapalıysa null. */
    private fun currentExplodeOffsets(): FloatArray? {
        val e = explode ?: return null
        return if (explodeFactor > 0f) e.partOffsets(explodeFactor) else null
    }

    // -----------------------------------------------------------------------
    // PMI
    // -----------------------------------------------------------------------

    private fun loadPmi(mesh: StepMesh) {
        executor.execute {
            val data = try {
                Pmi.load()
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                if (mesh !== currentMesh || data == null) return@runOnUiThread
                pmi = data
                modelView.setPmi(data.segments)
                pmiButton.visibility = View.VISIBLE
                // Çizimi olan PMI hemen gösterilir
                pmiOn = data.segments.isNotEmpty()
                updatePmiDisplay()
                Toast.makeText(this, getString(R.string.pmi_found, data.items.size), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updatePmiDisplay() {
        val data = pmi
        val item = data?.items?.getOrNull(pmiSelected)
        val highlight = if (item != null && item.segCount > 0) intArrayOf(item.segStart, item.segCount) else IntArray(0)
        modelView.setPmiVisible(pmiOn && data != null, highlight)
        pmiButton.isActivated = pmiOn
    }

    private fun showPmiDialog() {
        val data = pmi ?: return
        val labels = data.items.map { item ->
            val kind = when (item.kind) {
                Pmi.KIND_TOLERANCE -> getString(R.string.pmi_tolerance)
                Pmi.KIND_DATUM -> getString(R.string.pmi_datum)
                else -> getString(R.string.pmi_dimension)
            }
            "$kind: " + item.text.ifBlank { getString(R.string.pmi_unnamed) }
        }.toTypedArray()
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.pmi_title, data.items.size))
            .setItems(labels) { _, which ->
                pmiOn = true
                pmiSelected = which
                updatePmiDisplay()
                val item = data.items[which]
                item.anchor?.let { modelView.focusOn(it) }
                Toast.makeText(this, labels[which], Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.ok, null)
        if (pmiOn) {
            builder.setNeutralButton(R.string.pmi_hide) { _, _ ->
                pmiOn = false
                pmiSelected = -1
                updatePmiDisplay()
            }
        }
        builder.show()
        if (!pmiOn) {
            pmiOn = true
            updatePmiDisplay()
        }
    }

    // -----------------------------------------------------------------------
    // Dışa aktarma ve parça listesi
    // -----------------------------------------------------------------------

    private fun exportPartName(mesh: StepMesh, p: Int): String =
        mesh.partName(p, getString(R.string.part_fallback, p + 1))

    private fun baseFileName(): String =
        currentName.substringBeforeLast('.').ifBlank { "model" }

    private fun showExportDialog() {
        val mesh = currentMesh ?: return
        val visible = partVisible.count { it }
        if (visible == 0) {
            Toast.makeText(this, R.string.export_nothing, Toast.LENGTH_SHORT).show()
            return
        }
        val formats = arrayOf(Exporter.Format.STL, Exporter.Format.OBJ, Exporter.Format.GLB)
        val labels = arrayOf(getString(R.string.export_stl), getString(R.string.export_obj), getString(R.string.export_glb))
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.export_title, visible))
            .setItems(labels) { _, which ->
                val f = formats[which]
                pendingExport = f
                val name = baseFileName() + "." + f.extension
                when (f) {
                    Exporter.Format.STL -> exportStl.launch(name)
                    Exporter.Format.OBJ -> exportObj.launch(name)
                    Exporter.Format.GLB -> exportGlb.launch(name)
                }
            }
            .setNegativeButton(R.string.cancel, null)
        dialog.show()
        if (mesh.partCount > 1 && visible == mesh.partCount) {
            Toast.makeText(this, R.string.export_hidden_note, Toast.LENGTH_LONG).show()
        }
    }

    private fun runExport(uri: Uri) {
        val mesh = currentMesh ?: return
        val format = pendingExport ?: return
        pendingExport = null
        val visible = partVisible.copyOf()
        val name = displayName(uri)
        showBusy(getString(R.string.export_busy))
        executor.execute {
            val result = runCatching {
                contentResolver.openOutputStream(uri, "w")?.use { out ->
                    Exporter.write(mesh, visible, { exportPartName(mesh, it) }, format, out)
                } ?: throw IOException("open")
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                hideBusy()
                result.onSuccess { tris ->
                    Toast.makeText(this, getString(R.string.export_done, name, tris), Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showBomDialog() {
        currentMesh ?: return
        val names = resources.getStringArray(R.array.material_names)
        val densities = resources.getStringArray(R.array.material_densities).map { it.toDouble() }
        // "Özel…" yerine "Kütle olmadan"
        val labels = names.indices.map { i ->
            if (densities[i] <= 0.0) getString(R.string.bom_no_mass) else names[i]
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bom_title)
            .setItems(labels) { _, which ->
                pendingBomDensity = densities[which]
                exportCsv.launch(baseFileName() + "_parca_listesi.csv")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runBomExport(uri: Uri) {
        val mesh = currentMesh ?: return
        val visible = partVisible.copyOf()
        val density = pendingBomDensity
        val name = displayName(uri)
        val headers = Bom.Headers(
            no = getString(R.string.bom_h_no),
            name = getString(R.string.bom_h_name),
            path = getString(R.string.bom_h_path),
            qty = getString(R.string.bom_h_qty),
            sizeX = getString(R.string.bom_h_x),
            sizeY = getString(R.string.bom_h_y),
            sizeZ = getString(R.string.bom_h_z),
            volume = getString(R.string.bom_h_volume),
            unitMass = getString(R.string.bom_h_unit_mass),
            totalMass = getString(R.string.bom_h_total_mass),
            total = getString(R.string.bom_h_total),
        )
        showBusy(getString(R.string.export_busy))
        executor.execute {
            val result = runCatching {
                val csv = Bom.build(
                    mesh = mesh,
                    partVisible = visible,
                    partName = { exportPartName(mesh, it) },
                    massProps = { p -> StepNative.massProps(intArrayOf(p)) },
                    density = density,
                    headers = headers,
                )
                contentResolver.openOutputStream(uri, "w")?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                    ?: throw IOException("open")
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                hideBusy()
                result.onSuccess {
                    Toast.makeText(this, getString(R.string.bom_done, name), Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showBusy(text: String) {
        progressText.text = text
        progressOverlay.visibility = View.VISIBLE
    }

    private fun hideBusy() {
        if (!loading) progressOverlay.visibility = View.GONE
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

    /** Ekran görüntüsünü alıp işaretleme ekranını açar. */
    private fun startMarkup() {
        val overlayText = if (measureMode) measure.lastText else ""
        modelView.captureScreenshot { bmp ->
            if (bmp == null || isDestroyed) {
                Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
                return@captureScreenshot
            }
            val annotated = annotate(bmp, currentName, overlayText)
            MarkupDialog(
                context = this,
                image = annotated,
                onShare = { shareBitmap(it) },
                onSave = { marked ->
                    pendingImage = marked
                    saveImage.launch(baseFileName() + "_not.png")
                },
            ).show()
        }
    }

    private fun shareScreenshot() {
        val overlayText = if (measureMode) measure.lastText else ""
        modelView.captureScreenshot { bmp ->
            if (bmp == null) {
                Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
                return@captureScreenshot
            }
            shareBitmap(annotate(bmp, currentName, overlayText))
        }
    }

    private fun shareBitmap(annotated: Bitmap) {
        run {
            try {
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
                adAfterShare = true
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
        private const val MENU_PRIVACY = 4
        private const val MENU_REMOVE_ADS = 5
        private const val MENU_RESTORE = 6
        private const val MENU_EXPORT = 7
        private const val MENU_BOM = 8
        private const val MENU_MARKUP = 9
        private const val MENU_QUALITY = 10
        private const val MENU_ORTHO = 50
        private const val MENU_YUP = 51
    }
}
