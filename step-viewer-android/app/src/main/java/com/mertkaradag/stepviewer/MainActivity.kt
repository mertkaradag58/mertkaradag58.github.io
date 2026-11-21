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
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.print.PrintHelper
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.ChipGroup
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.HapticFeedbackConstants
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
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
    private lateinit var toolSelect: View
    private lateinit var selectCard: MaterialCardView
    private lateinit var selectName: TextView
    private lateinit var selectPath: TextView
    private lateinit var selectActions: View
    private lateinit var hiddenCard: View
    private lateinit var hiddenText: TextView

    private val executor = Executors.newSingleThreadExecutor()
    /** Klasör tarama gibi model dışı dosya işleri (model yüklemesini bekletmesin). */
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private lateinit var loupeView: LoupeView
    private lateinit var home: HomeScreen
    private lateinit var savedViews: SavedViews
    private val notes = NotesController(this) { pushOverlay() }
    private var sectionFace: FloatArray? = null   // [nx, ny, nz, px, py, pz]: yüzeyden kesit
    private var pickTarget = PICK_NONE
    private var pickSnackbar: Snackbar? = null
    private lateinit var measureModeGroup: ChipGroup
    private lateinit var measureUndo: View
    /** Parça rengi (0 = dosyadaki renk) ve şeffaflığı. */
    private var partColor = IntArray(0)
    private var partTransparent = BooleanArray(0)
    /** Hassas imleç: önizleme yakalaması sürüyor mu. */
    private var cursorPreviewBusy = false
    private var cursorX = -1f
    private var cursorY = -1f
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

    private var displayMode =
        if (Prefs.showEdges) ModelRenderer.DisplayMode.SHADED_EDGES else ModelRenderer.DisplayMode.SHADED
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
    private val exportHolesCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) runHolesExport(uri)
    }

    private val exportDxf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/dxf")) { uri ->
        val data = pendingDxf
        pendingDxf = null
        if (uri != null && data != null) runDxfSave(uri, data)
    }

    private val exportDrawingPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val job = pendingDrawing
        pendingDrawing = null
        if (uri != null && job != null) runDrawingSave(uri, job, pdf = true)
    }

    private val exportDrawingDxf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/dxf")) { uri ->
        val job = pendingDrawing
        pendingDrawing = null
        if (uri != null && job != null) runDrawingSave(uri, job, pdf = false)
    }

    private val exportQcPdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) runQcPdf(uri)
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

    private val openFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                CadFiles.addFolder(this, uri)
                home.refresh()
            }
        }

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data ?: return@registerForActivityResult
            when (data.getStringExtra(SettingsActivity.EXTRA_ACTION)) {
                SettingsActivity.ACTION_BUY -> billing.buyAdFree(this)
                SettingsActivity.ACTION_RESTORE -> billing.restore()
                SettingsActivity.ACTION_PRIVACY -> ads.showPrivacyOptions()
                SettingsActivity.ACTION_TUTORIAL -> Onboarding(this) {}.show()
                SettingsActivity.ACTION_ABOUT -> showAbout()
            }
            if (data.getBooleanExtra(SettingsActivity.EXTRA_QUALITY_CHANGED, false)) {
                val uri = currentUri
                if (uri != null && currentMesh != null) {
                    Toast.makeText(this, R.string.quality_reload, Toast.LENGTH_SHORT).show()
                    loadModel(uri, persist = false)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        Prefs.init(this)
        Units.load()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            val light = !Prefs.isNight(this@MainActivity)
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
        setContentView(R.layout.activity_main)
        savedViews = SavedViews(this)

        recentFiles = RecentFiles(this)
        measure = MeasureController(
            context = this,
            runInBackground = { task -> executor.execute(task) },
            runOnUi = { task -> runOnUiThread(task) },
            onResult = { text, overlay ->
                measureText.text = text
                // Yeni sonuç geldiğinde balon başa dönsün
                findViewById<View>(R.id.measure_scroll).scrollTo(0, 0)
                measureOverlay = overlay
                pushOverlay()
                findViewById<View>(R.id.measure_actions).visibility =
                    if (measure.candidates.isEmpty()) View.GONE else View.VISIBLE
            },
        )

        bindViews()
        applyInsets()
        setupToolbar()
        setupMeasureModes()
        setupSection()
        setupSideButtons()
        setupPanelTransitions()
        setupBackHandling()
        modelView.setPalette(Palette.current(this))
        home = HomeScreen(
            context = this,
            container = findViewById(R.id.empty_content),
            recent = recentFiles,
            runInBackground = { task -> ioExecutor.execute(task) },
            runOnUi = { task -> runOnUiThread { if (!isDestroyed) task() } },
            onOpenFile = { pickFile() },
            onPhotoCad = { openPhotoCad() },
            onAddFolder = { addFolder() },
            onOpenUri = { uri, _ -> loadModel(uri, persist = true) },
            onRemoveFolder = { f ->
                MaterialAlertDialogBuilder(this)
                    .setTitle(f.name)
                    .setMessage(R.string.home_folder_remove_confirm)
                    .setPositiveButton(R.string.home_folder_remove) { _, _ ->
                        CadFiles.removeFolder(this, f.uri)
                        home.refresh()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            },
        )
        home.build()
        updateDisplayLabel()

        ads = Ads(this, findViewById(R.id.ad_container))
        billing = Billing(
            context = this,
            onAdFreeChanged = { adFree -> if (adFree) ads.disable() else ads.enable() },
            onMessage = { res -> if (!isDestroyed) Toast.makeText(this, res, Toast.LENGTH_LONG).show() },
        )
        // Reklamsız alınmışsa reklam altyapısı (ve onay formu) hiç başlatılmaz
        if (billing.adFree) ads.disable() else ads.start()
        billing.start()

        if (savedInstanceState == null) {
            Session.clear()
            val opened = handleIntent(intent)
            CrashLog.showPendingReport(this)
            if (!opened && !Prefs.onboardingSeen) {
                Onboarding(this) { Prefs.onboardingSeen = true }.show()
            }
        } else {
            restoreSession()
        }
    }

    /** Tema / dil değişince ekran yeniden oluşturulur: açık model geri yüklenir. */
    private fun restoreSession() {
        val mesh = Session.mesh ?: return
        val uri = Session.uri
        displayMode = Session.displayMode ?: displayMode
        yUp = Session.yUp
        if (yUp) modelView.camera.upAxis = Camera.UpAxis.Y
        currentUri = uri
        onModelLoaded(mesh, Session.name, -1.0, restoring = true)
        Session.partVisible?.takeIf { it.size == mesh.partCount }?.copyInto(partVisible)
        Session.partColor?.takeIf { it.size == mesh.partCount }?.copyInto(partColor)
        Session.partTransparent?.takeIf { it.size == mesh.partCount }?.copyInto(partTransparent)
        lastVisible = partVisible.copyOf()
        applyVisibility(recordUndo = false)
        Session.camera?.let { st -> modelView.post { modelView.animateToState(st) } }
        updateDisplayLabel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (currentMesh != null) {
            Session.camera = modelView.saveCameraState()
            Session.displayMode = displayMode
            Session.yUp = yUp
        }
    }

    private fun bindViews() {
        modelView = findViewById(R.id.model_view)
        titleText = findViewById(R.id.title_text)
        subtitleText = findViewById(R.id.subtitle_text)
        emptyState = findViewById(R.id.empty_state)
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

        findViewById<View>(R.id.measure_to_report).setOnClickListener { addMeasureToReport() }
        findViewById<View>(R.id.measure_to_fit).setOnClickListener { openFitFromMeasure() }

        loupeView = findViewById(R.id.loupe_view)
        measureModeGroup = findViewById(R.id.measure_mode_group)
        measureUndo = findViewById(R.id.measure_undo)
        modelView.onCursorMove = { x, y -> onCursorMove(x, y) }
        modelView.onCursorUp = { x, y -> onCursorUp(x, y) }
        modelView.onTap = { x, y -> onModelTap(x, y) }
        modelView.onDoubleTap = { x, y -> onModelDoubleTap(x, y) }
        modelView.onLongPress = { x, y -> onModelLongPress(x, y) }
        modelView.setFreeOrbit(freeOrbit)
        modelView.camera.rotateSpeed = ROTATE_SPEEDS[rotateSpeedLevel]
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
        findViewById<View>(R.id.open_button).setOnClickListener { pickFile() }
        findViewById<View>(R.id.tool_tools).setOnClickListener { showToolsSheet() }
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
        findViewById<View>(R.id.measure_close).setOnClickListener { setMeasureMode(false) }
        findViewById<View>(R.id.settings_button).setOnClickListener { openSettings() }
        findViewById<View>(R.id.select_color).setOnClickListener { showColorDialog() }
        findViewById<View>(R.id.select_transparent).setOnClickListener { toggleTransparency() }
    }

    private fun openSettings() {
        val i = Intent(this, SettingsActivity::class.java)
            .putExtra(SettingsActivity.EXTRA_AD_FREE, billing.adFree)
            .putExtra(SettingsActivity.EXTRA_PRICE, billing.formattedPrice)
            .putExtra(SettingsActivity.EXTRA_PRIVACY, ads.privacyOptionsRequired)
        if (currentMesh != null) Session.camera = modelView.saveCameraState()
        settingsLauncher.launch(i)
    }

    private fun addFolder() {
        try {
            openFolder.launch(null)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.error_unknown, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupMeasureModes() {
        measureModeGroup.setOnCheckedStateChangeListener { _, ids ->
            val mode = when (ids.firstOrNull()) {
                R.id.mode_point -> MeasureController.Mode.POINT
                R.id.mode_chain -> MeasureController.Mode.CHAIN
                else -> MeasureController.Mode.SMART
            }
            measure.setMode(mode)
            measureUndo.visibility = if (mode == MeasureController.Mode.CHAIN) View.VISIBLE else View.GONE
        }
        measureUndo.setOnClickListener { measure.undoLast() }
    }

    private fun haptic() {
        if (Prefs.haptics) modelView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun setupSideButtons() {
        findViewById<View>(R.id.fit_button).setOnClickListener { modelView.fit() }
        findViewById<View>(R.id.spin_button).setOnClickListener { modelView.spin360() }
        findViewById<View>(R.id.views_button).setOnClickListener { showViewsSheet() }
        findViewById<View>(R.id.explode_button).setOnClickListener { toggleExplodeCard() }
        pmiButton.setOnClickListener { showPmiDialog() }
        findViewById<View>(R.id.ar_button).setOnClickListener { openAr() }
        findViewById<View>(R.id.explode_close).setOnClickListener { closeExplode() }
        // Uzun basınca düğmenin ne işe yaradığı yazsın
        for (id in intArrayOf(R.id.views_button, R.id.spin_button, R.id.fit_button, R.id.ar_button, R.id.pmi_button, R.id.explode_button)) {
            val b = findViewById<View>(id)
            TooltipCompat.setTooltipText(b, b.contentDescription)
        }
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

    /** Araçlar paneli: eski üç nokta menüsünün yerine kategorili, aramalı panel. */
    private fun showToolsSheet() {
        val hasModel = currentMesh != null
        val cats = listOf(
            ToolsSheet.Category("model", getString(R.string.tab_model), R.drawable.ic_cube, 0xFF2F7BD8.toInt()),
            ToolsSheet.Category("workshop", getString(R.string.tab_workshop), R.drawable.ic_shaft, getColor(R.color.accent)),
            ToolsSheet.Category("share", getString(R.string.tab_share), R.drawable.ic_share, 0xFF17A673.toInt()),
            ToolsSheet.Category("app", getString(R.string.tab_app), R.drawable.ic_settings, 0xFF7C5CE0.toInt()),
        )
        val t = ArrayList<ToolsSheet.Tool>()
        fun add(
            id: String, cat: String, icon: Int, title: Int, caption: String?, enabled: Boolean = true,
            badge: String? = null, keywords: String = "", action: () -> Unit,
        ) {
            t += ToolsSheet.Tool(id, cat, icon, getString(title), caption, enabled, badge, keywords, action)
        }
        // Model
        add("info", "model", R.drawable.ic_info, R.string.t_info, getString(R.string.c_info), hasModel) { showModelInfo() }
        add("cost", "model", R.drawable.ic_calc, R.string.t_cost, getString(R.string.c_cost), hasModel) { showCost() }
        add("holes", "model", R.drawable.ic_holes, R.string.t_holes, getString(R.string.c_holes), hasModel) { showHoleTable() }
        add("bom", "model", R.drawable.ic_list, R.string.t_bom, getString(R.string.c_bom), hasModel) { showBomDialog() }
        add("note_add", "model", R.drawable.ic_bookmark, R.string.t_note_add, getString(R.string.c_note_add), hasModel) {
            startPick(PICK_NOTE, R.string.note_pick_hint)
        }
        add("notes", "model", R.drawable.ic_note, R.string.t_notes, getString(R.string.c_notes, notes.notes.size), hasModel) { notes.showList() }
        add("explode", "model", R.drawable.ic_explode, R.string.t_explode, getString(R.string.c_explode), hasModel) { toggleExplodeCard() }
        add("spin", "model", R.drawable.ic_rotate, R.string.t_spin, getString(R.string.c_spin), hasModel) { modelView.spin360() }
        if (pmiButton.visibility == View.VISIBLE) {
            add("pmi", "model", R.drawable.ic_pmi, R.string.t_pmi, getString(R.string.c_pmi)) { showPmiDialog() }
        }
        // Atölye
        add("drawing", "workshop", R.drawable.ic_drawing, R.string.t_drawing, getString(R.string.c_drawing), hasModel) { showDrawing() }
        add("dxf", "workshop", R.drawable.ic_dxf, R.string.t_dxf, getString(R.string.c_dxf), hasModel, keywords = getString(R.string.kw_dxf)) { startDxfPick() }
        add("sheet", "workshop", R.drawable.ic_sheet, R.string.t_sheet, getString(R.string.c_sheet), hasModel) { startSheetPick() }
        add("qc", "workshop", R.drawable.ic_qc, R.string.t_qc, getString(R.string.c_qc), hasModel) { showInspection() }
        add("fit", "workshop", R.drawable.ic_shaft, R.string.t_fit, getString(R.string.c_fit)) { FitDialog(this).show() }
        add("photo", "workshop", R.drawable.ic_camera_alt, R.string.t_photo, getString(R.string.c_photo), badge = getString(R.string.badge_beta)) { openPhotoCad() }
        // Paylaş
        add("share", "share", R.drawable.ic_share, R.string.t_share, getString(R.string.c_share), hasModel) { shareScreenshot() }
        add("export", "share", R.drawable.ic_download, R.string.t_export, getString(R.string.c_export), hasModel) { showExportDialog() }
        add("markup", "share", R.drawable.ic_pen, R.string.t_markup, getString(R.string.c_markup), hasModel) { startMarkup() }
        add("print", "share", R.drawable.ic_print, R.string.t_print, getString(R.string.c_print), hasModel) { printScreenshot() }
        add("ar", "share", R.drawable.ic_ar, R.string.t_ar, getString(R.string.c_ar), hasModel) { openAr() }
        // Uygulama
        add("settings", "app", R.drawable.ic_settings, R.string.t_settings, getString(R.string.c_settings)) { openSettings() }
        add("quality", "app", R.drawable.ic_display, R.string.t_quality, getString(R.string.c_quality)) { showQualityDialog() }
        if (!billing.adFree) {
            add("noads", "app", R.drawable.ic_star, R.string.t_noads, billing.formattedPrice ?: getString(R.string.c_noads)) {
                billing.buyAdFree(this)
            }
        }
        add("about", "app", R.drawable.ic_info, R.string.t_about, getString(R.string.c_about)) { showAbout() }

        haptic()
        ToolsSheet(this, getString(R.string.tools_title), currentName.ifEmpty { null }, cats, t).show()
    }

    private fun showViewsSheet() {
        haptic()
        ViewsSheet(this, object : ViewsSheet.Host {
            override fun goToView(view: Camera.View) = modelView.animateTo(view)
            override fun fitView() = modelView.fit()
            override var displayMode: ModelRenderer.DisplayMode
                get() = this@MainActivity.displayMode
                set(v) {
                    this@MainActivity.displayMode = v
                    modelView.setDisplayMode(v)
                    Session.displayMode = v
                }
            override var orthographic: Boolean
                get() = this@MainActivity.orthographic
                set(v) {
                    this@MainActivity.orthographic = v
                    modelView.setOrthographic(v)
                }
            override var yUp: Boolean
                get() = this@MainActivity.yUp
                set(v) {
                    this@MainActivity.yUp = v
                    modelView.setUpAxis(if (v) Camera.UpAxis.Y else Camera.UpAxis.Z)
                }
            override var freeOrbit: Boolean
                get() = this@MainActivity.freeOrbit
                set(v) {
                    this@MainActivity.freeOrbit = v
                    modelView.setFreeOrbit(v)
                }
            override var rotateSpeedLevel: Int
                get() = this@MainActivity.rotateSpeedLevel
                set(v) {
                    this@MainActivity.rotateSpeedLevel = v
                    modelView.camera.rotateSpeed = ROTATE_SPEEDS[v]
                }
            override fun savedViewNames(): List<String> = savedViews.list(currentName).map { it.name }
            override fun goToSaved(index: Int) {
                savedViews.list(currentName).getOrNull(index)?.let { modelView.animateToState(it.state) }
            }
            override fun saveView() = saveCurrentView()
            override fun manageViews() = this@MainActivity.manageViews()
        }).show()
    }

    /** Alt çubuk düğmesi: seçili arka plan yumuşakça gelir, simge hafifçe zıplar. */
    private fun setToolActive(tool: View, on: Boolean) {
        if (tool.isActivated == on) return
        tool.isActivated = on
        val icon = (tool as? ViewGroup)?.getChildAt(0) ?: return
        icon.animate().cancel()
        if (on) {
            icon.scaleX = 0.75f
            icon.scaleY = 0.75f
            icon.animate().scaleX(1f).scaleY(1f).setDuration(320)
                .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
        }
    }

    /** Paneller (ölçüm, kesit, seçim...) aşağıdan kayarak ve belirerek açılır. */
    private fun setupPanelTransitions() {
        val d = resources.displayMetrics.density
        val lt = android.animation.LayoutTransition().apply {
            setAnimator(
                android.animation.LayoutTransition.APPEARING,
                android.animation.ObjectAnimator.ofPropertyValuesHolder(
                    null as Any?,
                    android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f),
                    android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 36 * d, 0f),
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 0.94f, 1f),
                    android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.94f, 1f),
                ),
            )
            setAnimator(
                android.animation.LayoutTransition.DISAPPEARING,
                android.animation.ObjectAnimator.ofPropertyValuesHolder(
                    null as Any?,
                    android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f),
                    android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0f, 24 * d),
                ),
            )
            setDuration(android.animation.LayoutTransition.APPEARING, 280)
            setDuration(android.animation.LayoutTransition.DISAPPEARING, 160)
            setDuration(android.animation.LayoutTransition.CHANGE_APPEARING, 240)
            setDuration(android.animation.LayoutTransition.CHANGE_DISAPPEARING, 240)
            setStartDelay(android.animation.LayoutTransition.APPEARING, 0)
            setStartDelay(android.animation.LayoutTransition.CHANGE_DISAPPEARING, 80)
            setInterpolator(android.animation.LayoutTransition.APPEARING, android.view.animation.DecelerateInterpolator(2.2f))
            setInterpolator(android.animation.LayoutTransition.CHANGE_APPEARING, android.view.animation.DecelerateInterpolator(2f))
            setInterpolator(android.animation.LayoutTransition.CHANGE_DISAPPEARING, android.view.animation.DecelerateInterpolator(2f))
        }
        findViewById<ViewGroup>(R.id.bottom_area).layoutTransition = lt
    }

    /** Model ilk açıldığında üst çubuk, yan düğmeler ve alt çubuk sırayla yerine kayar. */
    private fun animateViewerChrome() {
        val d = resources.displayMetrics.density
        val bars = (sideButtons as ViewGroup)
        var k = 0
        for (i in 0 until bars.childCount) {
            val b = bars.getChildAt(i)
            if (b.visibility != View.VISIBLE) continue
            b.alpha = 0f
            b.translationX = 40 * d
            b.animate().alpha(1f).translationX(0f).setStartDelay(120L + k * 45L).setDuration(320)
                .setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start()
            k++
        }
        val top = findViewById<View>(R.id.top_bar)
        top.alpha = 0f
        top.translationY = -20 * d
        top.animate().alpha(1f).translationY(0f).setStartDelay(60).setDuration(300).start()
        val fab = findViewById<View>(R.id.tool_tools_circle)
        fab.scaleX = 0f
        fab.scaleY = 0f
        fab.animate().scaleX(1f).scaleY(1f).setStartDelay(260).setDuration(380)
            .setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
    }

    private fun showRotateSpeedDialog() {
        val labels = arrayOf(
            getString(R.string.rotate_speed_slow),
            getString(R.string.rotate_speed_normal),
            getString(R.string.rotate_speed_fast),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rotate_speed)
            .setSingleChoiceItems(labels, rotateSpeedLevel) { d, which ->
                rotateSpeedLevel = which
                modelView.camera.rotateSpeed = ROTATE_SPEEDS[which]
                d.dismiss()
            }
            .show()
    }

    private fun cycleDisplayMode() {
        displayMode = when (displayMode) {
            ModelRenderer.DisplayMode.SHADED_EDGES -> ModelRenderer.DisplayMode.SHADED
            ModelRenderer.DisplayMode.SHADED -> ModelRenderer.DisplayMode.XRAY
            ModelRenderer.DisplayMode.XRAY -> ModelRenderer.DisplayMode.WIREFRAME
            ModelRenderer.DisplayMode.WIREFRAME -> ModelRenderer.DisplayMode.HIDDEN_LINE
            ModelRenderer.DisplayMode.HIDDEN_LINE -> ModelRenderer.DisplayMode.SHADED_EDGES
        }
        updateDisplayLabel()
        modelView.setDisplayMode(displayMode)
        Session.displayMode = displayMode
        Toast.makeText(this, displayModeName(), Toast.LENGTH_SHORT).show()
    }

    private fun updateDisplayLabel() {}

    private fun displayModeName(): String = getString(
            when (displayMode) {
                ModelRenderer.DisplayMode.SHADED_EDGES -> R.string.display_shaded_edges
                ModelRenderer.DisplayMode.SHADED -> R.string.display_shaded
                ModelRenderer.DisplayMode.WIREFRAME -> R.string.display_wireframe
                ModelRenderer.DisplayMode.XRAY -> R.string.display_xray
                ModelRenderer.DisplayMode.HIDDEN_LINE -> R.string.display_hidden_line
            }
        )

    // -----------------------------------------------------------------------
    // Kayıtlı görünümler
    // -----------------------------------------------------------------------

    private fun saveCurrentView() {
        if (currentMesh == null) return
        val state = modelView.saveCameraState()
        val input = android.widget.EditText(this).apply {
            setText(getString(R.string.views_default_name, savedViews.list(currentName).size + 1))
            selectAll()
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.views_save)
            .setView(box)
            .setPositiveButton(R.string.markup_save) { _, _ ->
                val name = input.text.toString().trim().ifBlank { getString(R.string.views_default_name, 1) }
                savedViews.add(currentName, name, state)
                Toast.makeText(this, getString(R.string.views_saved, name), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun manageViews() {
        val views = savedViews.list(currentName)
        if (views.isEmpty()) return
        val names = views.map { it.name }.toTypedArray()
        val checked = BooleanArray(names.size)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.views_manage)
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.views_delete_selected) { _, _ ->
                for (i in names.indices) if (checked[i]) savedViews.remove(currentName, names[i])
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // -----------------------------------------------------------------------
    /** Fotoğraftan kaba katı model (STEP) ekranı. */
    private fun openPhotoCad() {
        startActivity(Intent(this, com.mertkaradag.stepviewer.photocad.PhotoCadActivity::class.java))
    }

    // Dosya açma
    // -----------------------------------------------------------------------

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Ayarlar ekranından dönüldüyse yeni ayarları uygula
        Units.load()
        modelView.setPalette(Palette.current(this))
        if (measureMode) measure.redraw()
        if (sectionOn) updateSection()
        if (emptyState.visibility == View.VISIBLE) home.redrawLists()
        modelView.onResume()
        ads.onResume()
        if (adAfterShare) {
            adAfterShare = false
            ads.showInterstitial()
        }
    }

    override fun onPause() {
        if (currentMesh != null) Session.camera = modelView.saveCameraState()
        ads.onPause()
        modelView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        tickHandler.removeCallbacks(tick)
        ioExecutor.shutdownNow()
        ads.onDestroy()
        billing.destroy()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?): Boolean {
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let {
                loadModel(it, persist = true)
                return true
            }
        }
        return false
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

    /** Serbest döndürme (iz topu) açık mı; kapalıyken döner tabla. */
    private var freeOrbit: Boolean
        get() = settings.getBoolean("free_orbit", false)
        set(v) = settings.edit().putBoolean("free_orbit", v).apply()

    /** 0 = yavaş, 1 = normal, 2 = hızlı. */
    private var rotateSpeedLevel: Int
        get() = settings.getInt("rotate_speed", 1).coerceIn(0, 2)
        set(v) = settings.edit().putInt("rotate_speed", v).apply()

    /** 0 = hızlı, 1 = normal, 2 = hassas. */
    private var meshQuality: Int
        get() = Prefs.meshQuality
        set(v) {
            Prefs.meshQuality = v
        }

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
        startProgress(getString(R.string.loading_file, name))

        if (persist) {
            // Son açılanlar listesinden tekrar açabilmek için kalıcı izin (mümkünse)
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }

        executor.execute {
            val file = try {
                copyToCache(uri, name)
            } catch (e: Exception) {
                if (e is SecurityException) recentFiles.forget(uri)
                val msg = if (e is SecurityException) getString(R.string.error_permission) else e.message
                runOnUiThread { failLoad(token, msg) }
                return@execute
            }
            if (!CadFiles.isZip(name)) {
                meshFile(token, uri, name, name, file)
                return@execute
            }
            // ZIP: içindeki model dosyalarını listele
            val entries = try {
                CadFiles.zipModels(file)
            } catch (e: Exception) {
                runOnUiThread { failLoad(token, getString(R.string.zip_failed)) }
                return@execute
            }
            when {
                entries.isEmpty() -> runOnUiThread { failLoad(token, getString(R.string.zip_empty)) }
                entries.size == 1 -> extractAndMesh(token, uri, name, file, entries[0].name)
                else -> runOnUiThread {
                    if (token != loadToken || isDestroyed) return@runOnUiThread
                    stopProgress()
                    val labels = entries.map {
                        it.name + if (it.size >= 0) "  (" + android.text.format.Formatter.formatShortFileSize(this, it.size) + ")" else ""
                    }.toTypedArray()
                    var chosen = false
                    MaterialAlertDialogBuilder(this)
                        .setTitle(getString(R.string.zip_choose, name))
                        .setItems(labels) { _, which ->
                            chosen = true
                            startProgress(getString(R.string.loading_file, entries[which].name.substringAfterLast('/')))
                            executor.execute { extractAndMesh(token, uri, name, file, entries[which].name) }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .setOnDismissListener {
                            if (!chosen && token == loadToken) {
                                loading = false
                                showRecentFiles()
                            }
                        }
                        .show()
                }
            }
        }
    }

    private fun extractAndMesh(token: Int, uri: Uri, zipName: String, zip: File, entry: String) {
        val inner = try {
            CadFiles.extract(zip, entry, File(cacheDir, "zip_model"))
        } catch (e: Exception) {
            runOnUiThread { failLoad(token, getString(R.string.zip_failed)) }
            return
        }
        val innerName = entry.substringAfterLast('/')
        meshFile(token, uri, "$innerName ($zipName)", zipName, inner)
    }

    private fun startProgress(text: String) {
        progressText.text = text
        progressOverlay.visibility = View.VISIBLE
        progressCancel.visibility = View.VISIBLE
        tickInfo = ""
        tickStarted = SystemClock.elapsedRealtime()
        progressElapsed.text = ""
        progressElapsed.visibility = View.VISIBLE
        tickHandler.removeCallbacks(tick)
        tickHandler.postDelayed(tick, 1000)
    }

    private fun failLoad(token: Int, message: String?) {
        if (isDestroyed || token != loadToken) return
        loading = false
        stopProgress()
        showRecentFiles()
        showError(localizedError(message))
    }

    /**
     * Önbellekteki dosyayı ağa çevirir (arka plan iş parçacığında çağrılır).
     * [title] ekranda görünen ad, [recentName] son açılanlar listesindeki ad.
     */
    private fun meshFile(token: Int, uri: Uri, title: String, recentName: String, file: File) {
        val quality = meshQuality
        val started = SystemClock.elapsedRealtime()
        var fellBack = false
        val mb = file.length() / (1024.0 * 1024.0)
        runOnUiThread {
            if (token != loadToken) return@runOnUiThread
            tickInfo = String.format(Locale.getDefault(), "%.1f MB", mb)
            progressText.text = getString(R.string.meshing_file, title)
        }
        val result: Result<StepMesh> = try {
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
        }
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0

        runOnUiThread {
            if (isDestroyed || token != loadToken) return@runOnUiThread // iptal edildi
            loading = false
            stopProgress()
            result.onSuccess { mesh ->
                currentUri = uri
                onModelLoaded(mesh, title, seconds)
                if (hasPersistedPermission(uri)) {
                    recentFiles.add(uri, recentName)
                    pendingThumbUri = uri.toString()
                    pendingShortcut = uri to recentName
                }
                if (fellBack) Toast.makeText(this, R.string.oom_retried, Toast.LENGTH_LONG).show()
                ads.showInterstitial()
            }.onFailure { e ->
                showRecentFiles()
                showError(localizedError(e.message))
            }
        }
    }

    /** Yükleme bitince küçük resim alınacak dosya (kalıcı izni olan). */
    private var pendingThumbUri: String? = null
    private var pendingShortcut: Pair<Uri, String>? = null

    /** İlk kare çizildikten sonra küçük resim ve ana ekran kısayolu. */
    private fun afterFirstFrame(mesh: StepMesh) {
        modelView.postDelayed({
            if (currentMesh !== mesh || isDestroyed) return@postDelayed
            val thumbUri = pendingThumbUri
            val shortcut = pendingShortcut
            pendingThumbUri = null
            pendingShortcut = null
            if (thumbUri != null) {
                modelView.captureScreenshot { bmp ->
                    if (bmp != null) {
                        ioExecutor.execute {
                            Thumbs.save(this, thumbUri, bmp)
                            if (shortcut != null) runOnUiThread { pushShortcut(shortcut.first, shortcut.second) }
                        }
                    }
                    if (Prefs.spinOnLoad && currentMesh === mesh) modelView.spin360()
                }
            } else if (Prefs.spinOnLoad) {
                modelView.spin360()
            }
        }, 700)
    }

    /** Uygulama simgesine uzun basınca son dosyalar. */
    private fun pushShortcut(uri: Uri, name: String) {
        try {
            val thumb = Thumbs.load(this, uri.toString())
            val icon = if (thumb != null) IconCompat.createWithAdaptiveBitmap(padForAdaptive(thumb))
            else IconCompat.createWithResource(this, R.mipmap.ic_launcher)
            val intent = Intent(Intent.ACTION_VIEW, uri, this, MainActivity::class.java)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val label = name.substringBeforeLast('.').ifBlank { name }
            val info = ShortcutInfoCompat.Builder(this, "file_" + Integer.toHexString(uri.toString().hashCode()))
                .setShortLabel(label.take(24))
                .setLongLabel(name.take(40))
                .setIcon(icon)
                .setIntent(intent)
                .build()
            ShortcutManagerCompat.pushDynamicShortcut(this, info)
        } catch (_: Throwable) {
        }
    }

    /** Uyarlanabilir simge için resmi ortadaki güvenli alana yerleştirir. */
    private fun padForAdaptive(src: Bitmap): Bitmap {
        val size = 216
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val inner = 144
        val off = (size - inner) / 2f
        c.drawBitmap(src, null, RectF(off, off, off + inner, off + inner), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
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
        Session.clear()
        notes.clear()
        stopPick()
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

    private fun onModelLoaded(mesh: StepMesh, name: String, seconds: Double, restoring: Boolean = false) {
        currentMesh = mesh
        currentName = name
        notes.load(name)
        sectionFace = null
        stopPick()
        partVisible = BooleanArray(mesh.partCount) { true }
        partColor = IntArray(mesh.partCount)
        partTransparent = BooleanArray(mesh.partCount)
        lastVisible = partVisible.copyOf()
        visibilityUndo.clear()
        partHighlight = IntArray(0)
        measureOverlay = ModelRenderer.Overlay()
        selStart = -1
        selEnd = -1
        clearHoleSelection()
        holeCache = null
        holeCacheMesh = null
        stopDxfPick()

        modelView.setMesh(mesh)
        modelView.setDisplayMode(displayMode)
        pushOverlay()
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
        if (!restoring) {
            Session.subtitle = getString(R.string.model_info_line, mesh.formatName, mesh.partCount, mesh.triangleCount, seconds)
        }
        subtitleText.text = Session.subtitle
        subtitleText.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
        val firstShow = toolbarCard.visibility != View.VISIBLE
        toolbarCard.visibility = View.VISIBLE
        sideButtons.visibility = View.VISIBLE
        if (firstShow) animateViewerChrome()

        Session.mesh = mesh
        Session.name = name
        Session.uri = currentUri
        Session.partVisible = partVisible
        Session.partColor = partColor
        Session.partTransparent = partTransparent
        Session.displayMode = displayMode
        Session.yUp = yUp
        if (!restoring) {
            Session.camera = null
            afterFirstFrame(mesh)
        }
    }

    private fun hasPersistedPermission(uri: Uri): Boolean {
        val s = uri.toString()
        return contentResolver.persistedUriPermissions.any {
            it.isReadPermission && (it.uri == uri || s.startsWith(it.uri.toString() + "/document/"))
        }
    }

    private fun showRecentFiles() {
        if (::home.isInitialized) home.redrawLists()
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
        if (enabled) {
            stopDxfPick()
            stopSheetPick()
        }
        measureMode = enabled
        setToolActive(toolMeasure, enabled)
        measureCard.visibility = if (enabled && currentMesh != null) View.VISIBLE else View.GONE
        modelView.precisionCursor = enabled && Prefs.loupe
        if (!enabled) hideCursor()
        measure.clear()
        if (!enabled) {
            measureOverlay = ModelRenderer.Overlay()
            pushOverlay()
        }
    }

    private fun onModelTap(x: Float, y: Float) {
        if (loading) return
        if (pickTarget != PICK_NONE) {
            handlePick(x, y)
            return
        }
        if (dxfPickMode) {
            pickDxfFace(x, y)
            return
        }
        if (sheetPickMode) {
            pickSheetFace(x, y)
            return
        }
        if (selectMode) {
            pickPartAt(x, y)
            return
        }
        if (!measureMode) {
            openNoteAt(x, y)
            return
        }
        val mesh = currentMesh ?: return
        val mvp = modelView.currentMvp()
        val w = modelView.width
        val h = modelView.height
        val density = resources.displayMetrics.density
        val threshold = 18f * density
        val visible = if (partVisible.all { it }) null else partVisible.copyOf()
        val clip = if (sectionOn) clipPlane(mesh) else null
        val mode = measure.mode
        val snapOn = Prefs.snap
        executor.execute {
            val sel: MeasureController.Sel? = when (mode) {
                MeasureController.Mode.POINT ->
                    Picker.snapPoint(mesh, mvp, w, h, x, y, threshold, visible, clip, snapOn) { circleCenter(it) }
                        ?.let { MeasureController.Sel.of(it) }
                MeasureController.Mode.CHAIN ->
                    Picker.pick(mesh, mvp, w, h, x, y, threshold, visible, clip)?.let { MeasureController.Sel.of(it) }
                MeasureController.Mode.SMART -> {
                    // Köşeye çok yakın dokunulduysa köşe (nokta) seçilir; değilse kenar / yüzey
                    val v = if (snapOn) {
                        Picker.snapPoint(mesh, mvp, w, h, x, y, 11f * density, visible, clip, true) { null }
                            ?.takeIf { it.kind == Picker.SnapKind.VERTEX }
                    } else {
                        null
                    }
                    if (v != null) MeasureController.Sel.of(v)
                    else Picker.pick(mesh, mvp, w, h, x, y, threshold, visible, clip)?.let { MeasureController.Sel.of(it) }
                }
            }
            runOnUiThread {
                if (mesh === currentMesh && measureMode) {
                    if (sel != null) haptic()
                    measure.onPicked(sel)
                }
            }
        }
    }

    /** Kenar çember veya yay ise merkezi (arka plan iş parçacığında). */
    private fun circleCenter(edge: Int): FloatArray? {
        val info = StepNative.shapeInfo(StepNative.KIND_EDGE, edge) ?: return null
        if (info[0].toInt() != GeomKind.CIRCLE) return null
        return floatArrayOf(info[6].toFloat(), info[7].toFloat(), info[8].toFloat())
    }

    // -----------------------------------------------------------------------
    // Hassas imleç ve büyüteç (ölçümde uzun bas)
    // -----------------------------------------------------------------------

    private fun onCursorMove(fx: Float, fy: Float) {
        val mesh = currentMesh ?: return
        if (!measureMode) return
        val first = !loupeView.active
        cursorX = fx.coerceIn(0f, modelView.width - 1f)
        cursorY = (fy - loupeView.fingerOffset).coerceIn(0f, modelView.height - 1f)
        if (first) haptic()
        loupeView.setCursor(cursorX, cursorY)
        modelView.setLoupe(cursorX, cursorY, loupeView.sampleSize) { bmp -> if (loupeView.active) loupeView.setImage(bmp) }
        previewSnap(mesh)
    }

    /** İmlecin altında ne yakalanacağını gösterir (bir önceki hesap bitmeden yenisi başlamaz). */
    private fun previewSnap(mesh: StepMesh) {
        if (cursorPreviewBusy) return
        cursorPreviewBusy = true
        val x = cursorX
        val y = cursorY
        val mvp = modelView.currentMvp()
        val w = modelView.width
        val h = modelView.height
        val density = resources.displayMetrics.density
        val visible = if (partVisible.all { it }) null else partVisible.copyOf()
        val clip = if (sectionOn) clipPlane(mesh) else null
        val mode = measure.mode
        val snapOn = Prefs.snap
        executor.execute {
            val label: Pair<String, Boolean> = when (mode) {
                MeasureController.Mode.POINT -> {
                    val snap = Picker.snapPoint(mesh, mvp, w, h, x, y, 18f * density, visible, clip, snapOn) { circleCenter(it) }
                    if (snap == null) "" to false
                    else measure.snapName(snap.kind) to (snap.kind == Picker.SnapKind.VERTEX ||
                        snap.kind == Picker.SnapKind.MIDPOINT || snap.kind == Picker.SnapKind.CENTER)
                }
                else -> {
                    val v = if (snapOn && mode == MeasureController.Mode.SMART) {
                        Picker.snapPoint(mesh, mvp, w, h, x, y, 11f * density, visible, clip, true) { null }
                            ?.takeIf { it.kind == Picker.SnapKind.VERTEX }
                    } else {
                        null
                    }
                    if (v != null) {
                        measure.snapName(v.kind) to true
                    } else {
                        when (Picker.pick(mesh, mvp, w, h, x, y, 18f * density, visible, clip)?.kind) {
                            StepNative.KIND_EDGE -> getString(R.string.cursor_edge) to false
                            StepNative.KIND_FACE -> getString(R.string.cursor_face) to false
                            else -> "" to false
                        }
                    }
                }
            }
            runOnUiThread {
                cursorPreviewBusy = false
                if (!loupeView.active || mesh !== currentMesh) return@runOnUiThread
                loupeView.setSnap(label.first, label.second)
                // İmleç bu arada hareket ettiyse yeni konum için tekrar hesapla
                if (cursorX != x || cursorY != y) previewSnap(mesh)
            }
        }
    }

    private fun onCursorUp(fx: Float, fy: Float) {
        val x = cursorX
        val y = cursorY
        hideCursor()
        if (fx.isNaN() || x < 0f) return
        onModelTap(x, y)
    }

    private fun hideCursor() {
        cursorX = -1f
        cursorY = -1f
        loupeView.setCursor(-1f, -1f)
        modelView.setLoupe(0f, 0f, 0, null)
    }

    // -----------------------------------------------------------------------
    // Parça seçimi
    // -----------------------------------------------------------------------

    private fun setSelectMode(enabled: Boolean) {
        val on = enabled && currentMesh != null
        if (on && measureMode) setMeasureMode(false)
        if (on) stopDxfPick()
        selectMode = on
        setToolActive(toolSelect, on)
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
                faceRanges = if (holeHighlight.isEmpty()) o.faceRanges else o.faceRanges + holeHighlight,
                edgeSegments = o.edgeSegments,
                measureLine = o.measureLine,
                partRanges = partHighlight,
                points = mergePoints(o.points, notes.points()),
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
            sectionFace = null
            updateSection()
        }
        sectionSlider.addOnChangeListener { _, _, _ -> updateSection() }
        findViewById<View>(R.id.section_flip).setOnClickListener {
            sectionFlip = !sectionFlip
            updateSection()
        }
        findViewById<View>(R.id.section_close).setOnClickListener { setSection(false) }
        findViewById<View>(R.id.section_face).setOnClickListener {
            startPick(PICK_SECTION_FACE, R.string.section_pick_face)
        }
    }

    private fun setSection(enabled: Boolean) {
        sectionOn = enabled && currentMesh != null
        setToolActive(toolSection, sectionOn)
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
        if (sectionFace != null) {
            sectionLabel.text = Units.localize(getString(R.string.section_face_depth, Units.fl(sectionFaceDepth(mesh).toDouble())))
        } else {
            val axisName = arrayOf("X", "Y", "Z")[sectionAxis]
            sectionLabel.text = Units.localize(getString(R.string.section_position, axisName, Units.fl(sectionPosition(mesh).toDouble())))
        }
        modelView.setClipPlane(plane)
    }

    /** Yüzeyden kesitte derinlik (mm): kaydırıcı sağda 0, solda modelin köşegeni kadar. */
    private fun sectionFaceDepth(mesh: StepMesh): Float {
        val b = mesh.bbox
        val dx = b[3] - b[0]
        val dy = b[4] - b[1]
        val dz = b[5] - b[2]
        val diag = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        return (1f - sectionSlider.value / 1000f) * diag
    }

    private fun sectionPosition(mesh: StepMesh): Float {
        val min = mesh.bbox[sectionAxis]
        val max = mesh.bbox[sectionAxis + 3]
        val margin = (max - min) * 0.01f
        return (min - margin) + (max - min + 2 * margin) * (sectionSlider.value / 1000f)
    }

    /** [nx, ny, nz, d]: n·p + d >= 0 olan kısım görünür. */
    private fun clipPlane(mesh: StepMesh): FloatArray {
        sectionFace?.let { f ->
            // Yüzey normali dışarı bakar: düzlem yüzeyden [derinlik] kadar içeri kaydırılır ve
            // yüzey tarafı kesilip atılır (çevirince tersi).
            val depth = sectionFaceDepth(mesh)
            val px = f[3] - f[0] * depth
            val py = f[4] - f[1] * depth
            val pz = f[5] - f[2] * depth
            val sign = if (sectionFlip) 1f else -1f
            return floatArrayOf(
                sign * f[0], sign * f[1], sign * f[2],
                -sign * (f[0] * px + f[1] * py + f[2] * pz),
            )
        }
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

    /**
     * Görünen parçaların üçgen ve kenar aralıklarını çiziciye verir. Renk verilmiş veya şeffaf
     * parçalar ayrı gruplarda çizilir.
     */
    private fun applyVisibility(recordUndo: Boolean = true) {
        val mesh = currentMesh ?: return
        if (recordUndo && !lastVisible.contentEquals(partVisible)) {
            visibilityUndo.addLast(lastVisible)
            while (visibilityUndo.size > 30) visibilityUndo.removeFirst()
        }
        lastVisible = partVisible.copyOf()
        updateHiddenCard()
        val styled = partColor.any { it != 0 } || partTransparent.any { it }
        if (partVisible.all { it } && !styled) {
            modelView.setVisibility(null, null)
            return
        }
        // Normal (renksiz, opak) görünen parçalar
        val normal = BooleanArray(mesh.partCount) { partVisible[it] && partColor[it] == 0 && !partTransparent[it] }
        val tri = triangleRanges(mesh, normal)
        val seg = ArrayList<Int>()
        var p = 0
        while (p < mesh.partCount) {
            if (!partVisible[p]) {
                p++
                continue
            }
            var q = p
            while (q + 1 < mesh.partCount && partVisible[q + 1]) q++
            val s0 = mesh.edgeOffsets[mesh.partEdgeOffsets[p]]
            val s1 = mesh.edgeOffsets[mesh.partEdgeOffsets[q + 1]]
            if (s1 > s0) { seg += s0; seg += s1 - s0 }
            p = q + 1
        }
        // Gruplar: (renk, şeffaf) çiftine göre
        val groups = ArrayList<ModelRenderer.StyleGroup>()
        if (styled) {
            val keys = LinkedHashSet<Pair<Int, Boolean>>()
            for (i in 0 until mesh.partCount) {
                if (partVisible[i] && (partColor[i] != 0 || partTransparent[i])) keys += partColor[i] to partTransparent[i]
            }
            for ((color, transparent) in keys) {
                val mask = BooleanArray(mesh.partCount) {
                    partVisible[it] && partColor[it] == color && partTransparent[it] == transparent
                }
                val rgb = if (color == 0) null else floatArrayOf(
                    Color.red(color) / 255f, Color.green(color) / 255f, Color.blue(color) / 255f,
                )
                groups += ModelRenderer.StyleGroup(triangleRanges(mesh, mask), rgb, if (transparent) 0.28f else 1f)
            }
        }
        modelView.setVisibility(tri, seg.toIntArray(), groups)
    }

    /** Maskedeki parçaların üçgen aralıkları (ardışık parçalar birleştirilir). */
    private fun triangleRanges(mesh: StepMesh, mask: BooleanArray): IntArray {
        val tri = ArrayList<Int>()
        var p = 0
        while (p < mesh.partCount) {
            if (!mask[p]) {
                p++
                continue
            }
            var q = p
            while (q + 1 < mesh.partCount && mask[q + 1]) q++
            val t0 = mesh.faceOffsets[mesh.partFaceOffsets[p]]
            val t1 = mesh.faceOffsets[mesh.partFaceOffsets[q + 1]]
            if (t1 > t0) { tri += t0; tri += t1 - t0 }
            p = q + 1
        }
        return tri.toIntArray()
    }

    // -----------------------------------------------------------------------
    // Parça rengi ve şeffaflık
    // -----------------------------------------------------------------------

    private fun showColorDialog() {
        if (currentMesh == null || selStart < 0) return
        val colors = intArrayOf(
            0, 0xFFE8650E.toInt(), 0xFFD93B3B.toInt(), 0xFFF2B705.toInt(), 0xFF3FA34D.toInt(),
            0xFF2F7BD8.toInt(), 0xFF6A4FC9.toInt(), 0xFF8C9AAE.toInt(), 0xFF2B2F36.toInt(), 0xFFF5F5F5.toInt(),
            0xFFB87333.toInt(), 0xFF00A6A6.toInt(),
        )
        val d = resources.displayMetrics.density
        val grid = android.widget.GridLayout(this).apply {
            columnCount = 6
            setPadding((20 * d).toInt(), (12 * d).toInt(), (20 * d).toInt(), 0)
        }
        var dialog: androidx.appcompat.app.AlertDialog? = null
        for (c in colors) {
            val sw = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (c == 0) {
                        setColor(getColor(R.color.surface_variant))
                        setStroke((2 * d).toInt(), getColor(R.color.text_secondary))
                    } else {
                        setColor(c)
                        setStroke((1 * d).toInt(), Color.argb(80, 0, 0, 0))
                    }
                }
                contentDescription = if (c == 0) getString(R.string.color_default) else String.format("#%06X", c and 0xFFFFFF)
                setOnClickListener {
                    for (p in selStart until selEnd) partColor[p] = c
                    applyVisibility(recordUndo = false)
                    dialog?.dismiss()
                }
            }
            val size = (40 * d).toInt()
            grid.addView(sw, android.widget.GridLayout.LayoutParams().apply {
                width = size
                height = size
                setMargins((5 * d).toInt(), (5 * d).toInt(), (5 * d).toInt(), (5 * d).toInt())
            })
        }
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.color_title, selName))
            .setView(grid)
            .setNeutralButton(R.string.color_reset_all) { _, _ ->
                partColor.fill(0)
                applyVisibility(recordUndo = false)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toggleTransparency() {
        if (selStart < 0 || currentMesh == null) return
        val makeTransparent = !(selStart until selEnd).all { partTransparent[it] }
        for (p in selStart until selEnd) partTransparent[p] = makeTransparent
        applyVisibility(recordUndo = false)
        Toast.makeText(
            this,
            if (makeTransparent) R.string.transparent_on else R.string.transparent_off,
            Toast.LENGTH_SHORT,
        ).show()
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
    // AR
    // -----------------------------------------------------------------------

    private fun openAr() {
        val mesh = currentMesh ?: return
        if (!settings.getBoolean("ar_intro_seen", false)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ar)
                .setMessage(R.string.ar_intro)
                .setPositiveButton(R.string.ar_start) { _, _ ->
                    settings.edit().putBoolean("ar_intro_seen", true).apply()
                    openAr()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        ArActivity.pendingMesh = mesh
        ArActivity.pendingRanges = visibleTriangleRanges(mesh)
        startActivity(Intent(this, ArActivity::class.java))
    }

    /** Görünen parçaların üçgen aralıkları (başlangıç, adet); hepsi görünüyorsa null. */
    private fun visibleTriangleRanges(mesh: StepMesh): IntArray? {
        if (partVisible.all { it }) return null
        val out = ArrayList<Int>()
        for (p in 0 until mesh.partCount) {
            if (!partVisible[p]) continue
            val r = mesh.partTriangleRange(p)
            if (!r.isEmpty()) {
                out += r.first
                out += r.last - r.first + 1
            }
        }
        return out.toIntArray()
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

    // -----------------------------------------------------------------------
    // Delik tablosu
    // -----------------------------------------------------------------------

    private var holeCache: List<Holes.Hole>? = null
    private var holeCacheMesh: StepMesh? = null
    private var holeGroups: List<Holes.Group> = emptyList()
    private var holeHighlight = IntArray(0)
    private var holeSnackbar: Snackbar? = null

    private fun showHoleTable() {
        val mesh = currentMesh ?: return
        if (mesh.isMeshFormat) {
            Toast.makeText(this, R.string.holes_cad_only, Toast.LENGTH_LONG).show()
            return
        }
        val cached = holeCache
        if (cached != null && holeCacheMesh === mesh) {
            openHoleSheet(mesh, cached)
            return
        }
        showBusy(getString(R.string.holes_busy))
        executor.execute {
            val holes = try {
                Holes.load()
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                hideBusy()
                if (currentMesh !== mesh) return@runOnUiThread
                if (holes == null) {
                    Toast.makeText(this, R.string.holes_cad_only, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                holeCache = holes
                holeCacheMesh = mesh
                openHoleSheet(mesh, holes)
            }
        }
    }

    private fun openHoleSheet(mesh: StepMesh, holes: List<Holes.Hole>) {
        clearHoleSelection()
        val visible = partVisible
        val groups = Holes.group(holes) { p -> p < visible.size && visible[p] }
        if (groups.isEmpty()) {
            Toast.makeText(this, R.string.holes_none, Toast.LENGTH_LONG).show()
            return
        }
        holeGroups = groups
        HoleTableSheet(
            context = this,
            groups = groups,
            totalHoles = groups.sumOf { it.holes.size },
            onSelect = { selectHoleGroup(mesh, it) },
            onSaveCsv = { exportHolesCsv.launch(baseFileName() + "_delik_tablosu.csv") },
            onAddToReport = { addHolesToReport(groups) },
        ).show()
    }

    private fun selectHoleGroup(mesh: StepMesh, g: Holes.Group) {
        val ranges = ArrayList<Int>()
        for (h in g.holes) {
            for (f in h.faces) {
                if (f < 0 || f + 1 >= mesh.faceOffsets.size) continue
                val a = mesh.faceOffsets[f]
                val b = mesh.faceOffsets[f + 1]
                if (b > a) {
                    ranges += a
                    ranges += b - a
                }
            }
        }
        holeHighlight = ranges.toIntArray()
        pushOverlay()
        if (g.holes.size == 1) modelView.focusOn(g.first.entry)
        holeSnackbar?.dismiss()
        val text = getString(R.string.holes_selected, g.label, g.holes.size) + "  " + Holes.title(this, g.first)
        val bar = Snackbar.make(findViewById(R.id.model_view), text, Snackbar.LENGTH_INDEFINITE)
        bar.setAction(R.string.holes_back) {
            val holes = holeCache
            if (holes != null && currentMesh === mesh) openHoleSheet(mesh, holes)
        }
        bar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                if (holeSnackbar === transientBottomBar) {
                    holeSnackbar = null
                    if (event != BaseTransientBottomBar.BaseCallback.DISMISS_EVENT_CONSECUTIVE) {
                        holeHighlight = IntArray(0)
                        pushOverlay()
                    }
                }
            }
        })
        holeSnackbar = bar
        bar.show()
    }

    private fun clearHoleSelection() {
        val bar = holeSnackbar
        holeSnackbar = null
        bar?.dismiss()
        if (holeHighlight.isNotEmpty()) {
            holeHighlight = IntArray(0)
            pushOverlay()
        }
    }

    private fun runHolesExport(uri: Uri) {
        val mesh = currentMesh ?: return
        val groups = holeGroups
        if (groups.isEmpty()) return
        val name = displayName(uri)
        val headers = Holes.CsvHeaders(
            hole = getString(R.string.holes_h_hole),
            type = getString(R.string.holes_h_type),
            diameter = getString(R.string.holes_h_dia),
            kind = getString(R.string.holes_h_kind),
            depth = getString(R.string.holes_h_depth),
            cbore = getString(R.string.holes_h_cbore),
            csink = getString(R.string.holes_h_csink),
            note = getString(R.string.holes_h_note),
            part = getString(R.string.bom_h_name),
            through = getString(R.string.holes_through),
            blind = getString(R.string.holes_kind_blind),
        )
        showBusy(getString(R.string.export_busy))
        executor.execute {
            val result = runCatching {
                val csv = Holes.csv(this, groups, { exportPartName(mesh, it) }, headers)
                contentResolver.openOutputStream(uri, "w")?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                    ?: throw IOException("open")
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                hideBusy()
                result.onSuccess {
                    Toast.makeText(this, getString(R.string.holes_done, name), Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Yüzeyi DXF'e aktar (lazer / plazma kesim)
    // -----------------------------------------------------------------------

    private var dxfPickMode = false
    private var dxfSnackbar: Snackbar? = null
    private var pendingDxf: ByteArray? = null

    private fun startDxfPick() {
        val mesh = currentMesh ?: return
        if (mesh.isMeshFormat) {
            Toast.makeText(this, R.string.dxf_cad_only, Toast.LENGTH_LONG).show()
            return
        }
        if (measureMode) setMeasureMode(false)
        if (selectMode) setSelectMode(false)
        if (explodeFactor > 0f) closeExplode()
        clearHoleSelection()
        dxfPickMode = true
        dxfSnackbar?.dismiss()
        val bar = Snackbar.make(findViewById(R.id.model_view), R.string.dxf_pick_hint, Snackbar.LENGTH_INDEFINITE)
        bar.setAction(R.string.cancel) { }
        bar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                if (dxfSnackbar === transientBottomBar) {
                    dxfSnackbar = null
                    dxfPickMode = false
                }
            }
        })
        dxfSnackbar = bar
        bar.show()
    }

    private fun stopDxfPick() {
        dxfPickMode = false
        val bar = dxfSnackbar
        dxfSnackbar = null
        bar?.dismiss()
    }

    private fun pickDxfFace(x: Float, y: Float) {
        val mesh = currentMesh ?: return
        val mvp = modelView.currentMvp()
        val w = modelView.width
        val h = modelView.height
        val visible = if (partVisible.all { it }) null else partVisible.copyOf()
        val clip = if (sectionOn) clipPlane(mesh) else null
        executor.execute {
            val face = Picker.pickFace(mesh, mvp, w, h, x, y, visible, clip)
            val data = if (face >= 0) {
                try {
                    StepNative.faceDxf(face)
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
            runOnUiThread {
                if (mesh !== currentMesh || !dxfPickMode || face < 0) return@runOnUiThread
                if (data == null) {
                    Toast.makeText(this, R.string.dxf_not_planar, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                stopDxfPick()
                val a = mesh.faceOffsets[face]
                val b = mesh.faceOffsets[face + 1]
                holeHighlight = if (b > a) intArrayOf(a, b - a) else IntArray(0)
                pushOverlay()
                val info = data[1] as DoubleArray
                val text = data[0] as ByteArray
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dxf_title)
                    .setMessage(getString(R.string.dxf_info, Holes.mm(info[0]), Holes.mm(info[1]), info[2].toInt()))
                    .setPositiveButton(R.string.markup_save) { _, _ ->
                        pendingDxf = text
                        exportDxf.launch(baseFileName() + "_kesim.dxf")
                    }
                    .setNeutralButton(R.string.menu_share) { _, _ -> shareDxf(text) }
                    .setNegativeButton(R.string.cancel, null)
                    .setOnDismissListener {
                        holeHighlight = IntArray(0)
                        pushOverlay()
                    }
                    .show()
            }
        }
    }

    private fun runDxfSave(uri: Uri, data: ByteArray) {
        val name = displayName(uri)
        try {
            contentResolver.openOutputStream(uri, "w")?.use { it.write(data) } ?: throw IOException("open")
            Toast.makeText(this, getString(R.string.dxf_done, name), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun shareDxf(data: ByteArray, suffix: String = "_kesim.dxf") {
        try {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, baseFileName() + suffix)
            file.writeBytes(data)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/dxf"
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

    // -----------------------------------------------------------------------
    // Teknik resim
    // -----------------------------------------------------------------------

    private class DrawingJob(val views: TechDrawing.Views, val layout: TechDrawing.Layout, val texts: TechDrawing.Texts)

    private var pendingDrawing: DrawingJob? = null

    private fun showDrawing() {
        val mesh = currentMesh ?: return
        if (mesh.isMeshFormat) {
            Toast.makeText(this, R.string.drawing_cad_only, Toast.LENGTH_LONG).show()
            return
        }
        val visible = partVisible.copyOf()
        val ids = if (visible.all { it }) null else visible.indices.filter { visible[it] }.toIntArray()
        if (ids != null && ids.isEmpty()) return
        val zUp = !yUp
        showBusy(getString(R.string.drawing_busy))
        executor.execute {
            val views = try {
                TechDrawing.load(ids, zUp)
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                hideBusy()
                if (mesh !== currentMesh) return@runOnUiThread
                if (views == null) {
                    Toast.makeText(this, R.string.drawing_failed, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val texts = TechDrawing.Texts(
                    name = baseFileName(),
                    nameLabel = getString(R.string.drawing_t_name),
                    scaleLabel = getString(R.string.drawing_t_scale),
                    dateLabel = getString(R.string.drawing_t_date),
                    date = DateFormat.getDateFormat(this).format(java.util.Date()),
                    unitLabel = getString(R.string.drawing_t_unit),
                    projectionLabel = getString(R.string.drawing_t_projection),
                    sheetLabel = getString(R.string.drawing_t_sheet),
                    app = getString(R.string.app_name),
                )
                DrawingDialog(
                    context = this,
                    views = views,
                    texts = texts,
                    onSavePdf = { l ->
                        pendingDrawing = DrawingJob(views, l, texts)
                        exportDrawingPdf.launch(baseFileName() + "_teknik_resim.pdf")
                    },
                    onSaveDxf = { l ->
                        pendingDrawing = DrawingJob(views, l, texts)
                        exportDrawingDxf.launch(baseFileName() + "_teknik_resim.dxf")
                    },
                    onSharePdf = { l -> shareDrawingPdf(DrawingJob(views, l, texts)) },
                ).show()
            }
        }
    }

    private fun runDrawingSave(uri: Uri, job: DrawingJob, pdf: Boolean) {
        val name = displayName(uri)
        showBusy(getString(R.string.export_busy))
        executor.execute {
            val result = runCatching {
                contentResolver.openOutputStream(uri, "w")?.use { out ->
                    if (pdf) {
                        TechDrawing.pdf(job.views, job.layout, job.texts, out)
                    } else {
                        out.write(TechDrawing.dxf(job.views, job.layout, job.texts).toByteArray(Charsets.US_ASCII))
                    }
                } ?: throw IOException("open")
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                hideBusy()
                result.onSuccess {
                    Toast.makeText(this, getString(R.string.drawing_done, name), Toast.LENGTH_LONG).show()
                }.onFailure {
                    Toast.makeText(this, R.string.export_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun shareDrawingPdf(job: DrawingJob) {
        try {
            val dir = File(cacheDir, "shared").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, baseFileName() + "_teknik_resim.pdf")
            file.outputStream().use { TechDrawing.pdf(job.views, job.layout, job.texts, it) }
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
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

    /** Maliyet hesabı: seçim varsa seçili parçalar, yoksa görünen parçalar. */
    private fun showCost() {
        val mesh = currentMesh ?: return
        val parts: IntArray
        val title: String
        if (selStart >= 0) {
            parts = (selStart until selEnd).toList().toIntArray()
            title = selName
        } else {
            parts = partVisible.indices.filter { partVisible[it] }.toIntArray()
            title = if (parts.size == mesh.partCount) getString(R.string.info_scope_all, mesh.partCount)
            else getString(R.string.info_scope_visible, parts.size, mesh.partCount)
        }
        if (parts.isEmpty()) return
        CostDialog(
            context = this,
            mesh = mesh,
            parts = parts,
            scopeTitle = title,
            runInBackground = { task -> executor.execute(task) },
            runOnUi = { task -> runOnUiThread { if (!isDestroyed) task() } },
            loadHoles = {
                val cached = holeCache
                if (cached != null && holeCacheMesh === mesh) {
                    cached
                } else {
                    val loaded = Holes.load()
                    if (loaded != null) runOnUiThread {
                        if (currentMesh === mesh) {
                            holeCache = loaded
                            holeCacheMesh = mesh
                        }
                    }
                    loaded
                }
            },
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

    // -----------------------------------------------------------------------
    // Atölye: kalite kontrol raporu, geçme hesabı, sac açınımı
    // -----------------------------------------------------------------------

    private var inspection: Inspection? = null

    private fun currentInspection(): Inspection {
        val key = currentName.ifEmpty { "-" }
        val cur = inspection
        if (cur != null && cur.fileKey == key) return cur
        return Inspection(this, key).also { inspection = it }
    }

    private fun addMeasureToReport() {
        val cands = measure.candidates
        if (cands.isEmpty()) return
        if (cands.size == 1) {
            addCandidate(cands[0])
            return
        }
        val labels = cands.map { c ->
            c.label + ": " + if (c.unit == Inspection.Dim.DEG) fmt(c.value, 2) + "°" else fmt(c.value)
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.qc_pick_value)
            .setItems(labels) { _, which -> addCandidate(cands[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addCandidate(c: MeasureController.Candidate) {
        val report = currentInspection()
        val item = report.newItem(c.label, roundNominal(c.value, c.unit), c.unit, c.anchor)
        InspectionSheet.editItem(this, report, item, isNew = true) {
            report.items += item
            report.save()
            Snackbar.make(findViewById(R.id.model_view), getString(R.string.qc_added, report.items.size), Snackbar.LENGTH_LONG)
                .setAction(R.string.qc_open) { showInspection() }
                .show()
        }
    }

    /** CAD ölçüsünü makul basamağa yuvarlar (kayan nokta gürültüsü olmasın). */
    private fun roundNominal(v: Double, unit: Inspection.Dim): Double {
        val f = if (unit == Inspection.Dim.DEG) 100.0 else 1000.0
        return kotlin.math.round(v * f) / f
    }

    private fun openFitFromMeasure() {
        val c = measure.candidates.firstOrNull { it.isDiameter }
            ?: measure.candidates.firstOrNull { it.unit == Inspection.Dim.MM }
        FitDialog(this, c?.let { roundNominal(it.value, it.unit) }).show()
    }

    private fun addHolesToReport(groups: List<Holes.Group>) {
        val report = currentInspection()
        for (g in groups) {
            val h = g.first
            val label = getString(R.string.qc_hole_label, g.label, g.holes.size)
            report.items += report.newItem(label, roundNominal(h.diameter, Inspection.Dim.MM), Inspection.Dim.MM, h.entry)
        }
        report.save()
        showInspection()
    }

    private fun showInspection() {
        if (currentMesh == null) return
        InspectionSheet(
            context = this,
            report = currentInspection(),
            onSavePdf = { exportQcPdf.launch(baseFileName() + "_kalite_kontrol.pdf") },
            onSharePdf = { shareQcPdf() },
            onSelect = { item -> item.anchor?.let { modelView.focusOn(it) } },
        ).show()
    }

    private fun qcTexts(report: Inspection): Inspection.PdfTexts {
        val (ok, nok, open) = report.counts()
        val now = java.util.Date()
        return Inspection.PdfTexts(
            title = getString(R.string.qc_title),
            fileLabel = getString(R.string.qc_pdf_file),
            fileName = currentName,
            dateLabel = getString(R.string.drawing_t_date),
            date = DateFormat.getDateFormat(this).format(now) + " " + DateFormat.getTimeFormat(this).format(now),
            inspectorLabel = getString(R.string.qc_inspector),
            orderLabel = getString(R.string.qc_order),
            generalLabel = getString(R.string.qc_pdf_general, "ISO 2768-" + report.generalClass),
            headers = listOf(
                "#", getString(R.string.qc_h_feature), getString(R.string.qc_h_nominal), getString(R.string.qc_h_tol),
                "Min", "Max", getString(R.string.qc_h_actual), getString(R.string.qc_h_result),
            ),
            okText = getString(R.string.qc_ok),
            nokText = getString(R.string.qc_nok),
            summary = getString(R.string.qc_summary, report.items.size, ok, nok, open),
            signature = getString(R.string.qc_signature),
            app = getString(R.string.app_name),
        )
    }

    /** Balonlu model görüntüsüyle rapor PDF'ini yazar; yazma arka planda yapılır. */
    private fun writeQcPdf(open: () -> java.io.OutputStream, done: (Boolean) -> Unit) {
        val report = currentInspection()
        val texts = qcTexts(report)
        val mvp = modelView.currentMvp()
        val items = ArrayList(report.items)
        modelView.captureScreenshot { bmp ->
            if (isDestroyed) return@captureScreenshot
            val image = bmp?.let { Inspection.drawBalloons(it, items, mvp, resources.displayMetrics.density) }
            showBusy(getString(R.string.export_busy))
            executor.execute {
                val ok = runCatching { open().use { report.writePdf(it, image, texts) } }.isSuccess
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    hideBusy()
                    done(ok)
                }
            }
        }
    }

    private fun runQcPdf(uri: Uri) {
        val name = displayName(uri)
        writeQcPdf({ contentResolver.openOutputStream(uri, "w") ?: throw IOException("open") }) { ok ->
            Toast.makeText(
                this,
                if (ok) getString(R.string.qc_saved, name) else getString(R.string.export_failed),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun shareQcPdf() {
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, baseFileName() + "_kalite_kontrol.pdf")
        writeQcPdf({ file.outputStream() }) { ok ->
            if (!ok) {
                Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
                return@writeQcPdf
            }
            try {
                val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
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

    private var sheetPickMode = false
    private var sheetSnackbar: Snackbar? = null

    private fun startSheetPick() {
        val mesh = currentMesh ?: return
        if (mesh.isMeshFormat) {
            Toast.makeText(this, R.string.sheet_cad_only, Toast.LENGTH_LONG).show()
            return
        }
        val visibleParts = partVisible.indices.filter { partVisible[it] }
        if (visibleParts.size == 1) {
            runUnfold(mesh, visibleParts[0], -1)
            return
        }
        if (measureMode) setMeasureMode(false)
        if (selectMode) setSelectMode(false)
        if (explodeFactor > 0f) closeExplode()
        stopDxfPick()
        sheetPickMode = true
        sheetSnackbar?.dismiss()
        val bar = Snackbar.make(findViewById(R.id.model_view), R.string.sheet_pick_hint, Snackbar.LENGTH_INDEFINITE)
        bar.setAction(R.string.cancel) { }
        bar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                if (sheetSnackbar === transientBottomBar) {
                    sheetSnackbar = null
                    sheetPickMode = false
                }
            }
        })
        sheetSnackbar = bar
        bar.show()
    }

    private fun stopSheetPick() {
        sheetPickMode = false
        val bar = sheetSnackbar
        sheetSnackbar = null
        bar?.dismiss()
    }

    private fun pickSheetFace(x: Float, y: Float) {
        val mesh = currentMesh ?: return
        val mvp = modelView.currentMvp()
        val w = modelView.width
        val h = modelView.height
        val visible = if (partVisible.all { it }) null else partVisible.copyOf()
        val clip = if (sectionOn) clipPlane(mesh) else null
        executor.execute {
            val face = Picker.pickFace(mesh, mvp, w, h, x, y, visible, clip)
            runOnUiThread {
                if (mesh !== currentMesh || !sheetPickMode || face < 0) return@runOnUiThread
                var part = 0
                while (part + 1 < mesh.partFaceOffsets.size - 1 && mesh.partFaceOffsets[part + 1] <= face) part++
                stopSheetPick()
                runUnfold(mesh, part, face)
            }
        }
    }

    private fun runUnfold(mesh: StepMesh, part: Int, face: Int) {
        val prefs = getSharedPreferences("workshop", MODE_PRIVATE)
        val k = prefs.getFloat("k_factor", 0.44f).toDouble()
        showBusy(getString(R.string.sheet_busy))
        executor.execute {
            val r = try {
                SheetMetal.unfold(part, face, k)
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                hideBusy()
                if (mesh !== currentMesh) return@runOnUiThread
                val msg = when {
                    r == null -> R.string.sheet_failed
                    r.status == SheetMetal.STATUS_NOT_PLANAR -> R.string.sheet_not_planar
                    r.status == SheetMetal.STATUS_NO_THICKNESS -> R.string.sheet_not_sheet
                    r.status != SheetMetal.STATUS_OK -> R.string.sheet_not_sheet
                    else -> 0
                }
                if (msg != 0 || r == null) {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                SheetMetal.show(
                    context = this,
                    r = r,
                    onKFactor = { k2 ->
                        prefs.edit().putFloat("k_factor", k2.toFloat()).apply()
                        runUnfold(mesh, part, face)
                    },
                    onSave = {
                        pendingDxf = r.dxf
                        exportDxf.launch(baseFileName() + "_acinim.dxf")
                    },
                    onShare = { shareDxf(r.dxf, "_acinim.dxf") },
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Dokunarak seçim (not konumu, kesit yüzeyi) ve notlar
    // -----------------------------------------------------------------------

    private fun startPick(target: Int, hint: Int) {
        if (currentMesh == null) return
        if (measureMode) setMeasureMode(false)
        if (selectMode) setSelectMode(false)
        stopDxfPick()
        pickSnackbar?.dismiss()
        pickTarget = target
        val bar = Snackbar.make(findViewById(R.id.model_view), hint, Snackbar.LENGTH_INDEFINITE)
        bar.setAction(R.string.cancel) { }
        bar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                if (pickSnackbar === transientBottomBar) {
                    pickSnackbar = null
                    pickTarget = PICK_NONE
                }
            }
        })
        pickSnackbar = bar
        bar.show()
    }

    private fun stopPick() {
        pickTarget = PICK_NONE
        val bar = pickSnackbar
        pickSnackbar = null
        bar?.dismiss()
    }

    private fun handlePick(x: Float, y: Float) {
        val mesh = currentMesh ?: return
        val mvp = modelView.currentMvp()
        val w = modelView.width
        val h = modelView.height
        val visible = if (partVisible.all { it }) null else partVisible.copyOf()
        val clip = if (sectionOn) clipPlane(mesh) else null
        val threshold = 18f * resources.displayMetrics.density
        val target = pickTarget
        executor.execute {
            when (target) {
                PICK_NOTE -> {
                    val s = Picker.snapPoint(mesh, mvp, w, h, x, y, threshold, visible, clip, false) { null }
                    runOnUiThread {
                        if (mesh !== currentMesh || pickTarget != PICK_NOTE || s == null) return@runOnUiThread
                        stopPick()
                        notes.addAt(s.point)
                    }
                }
                PICK_SECTION_FACE -> {
                    val face = Picker.pickFace(mesh, mvp, w, h, x, y, visible, clip)
                    val info = if (face >= 0) {
                        try {
                            StepNative.shapeInfo(StepNative.KIND_FACE, face)
                        } catch (_: Throwable) {
                            null
                        }
                    } else {
                        null
                    }
                    runOnUiThread {
                        if (mesh !== currentMesh || pickTarget != PICK_SECTION_FACE || face < 0) return@runOnUiThread
                        if (info == null || info.size < 12 || info[0].toInt() != GeomKind.PLANE) {
                            Toast.makeText(this, R.string.section_face_not_planar, Toast.LENGTH_SHORT).show()
                            return@runOnUiThread
                        }
                        stopPick()
                        sectionFace = floatArrayOf(
                            info[9].toFloat(), info[10].toFloat(), info[11].toFloat(),
                            info[6].toFloat(), info[7].toFloat(), info[8].toFloat(),
                        )
                        sectionFlip = false
                        sectionSlider.value = 950f
                        if (!sectionOn) setSection(true) else updateSection()
                        haptic()
                    }
                }
            }
        }
    }

    private fun openNoteAt(x: Float, y: Float) {
        if (notes.notes.isEmpty()) return
        val note = notes.findNear(
            modelView.currentMvp(), modelView.width, modelView.height, x, y,
            24f * resources.displayMetrics.density,
        ) ?: return
        notes.open(note)
    }

    private fun mergePoints(a: FloatArray?, b: FloatArray): FloatArray? = when {
        a == null || a.isEmpty() -> b.takeIf { it.isNotEmpty() }
        b.isEmpty() -> a
        else -> a + b
    }

    private fun printScreenshot() {
        val overlayText = if (measureMode) measure.lastText else ""
        modelView.captureScreenshot { bmp ->
            if (bmp == null || isDestroyed) {
                Toast.makeText(this, R.string.print_failed, Toast.LENGTH_SHORT).show()
                return@captureScreenshot
            }
            try {
                val helper = PrintHelper(this)
                helper.scaleMode = PrintHelper.SCALE_MODE_FIT
                helper.orientation = if (bmp.width > bmp.height) PrintHelper.ORIENTATION_LANDSCAPE
                else PrintHelper.ORIENTATION_PORTRAIT
                helper.printBitmap(baseFileName(), annotate(bmp, currentName, overlayText))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.print_failed, Toast.LENGTH_SHORT).show()
            }
        }
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
        private const val MENU_HOLES = 11
        private const val MENU_DXF = 12
        private const val MENU_DRAWING = 13
        private const val MENU_COST = 14
        private const val MENU_PHOTO_CAD = 15
        private const val MENU_SHEET = 20
        private const val MENU_QC = 21
        private const val MENU_FIT = 22
        private const val MENU_SETTINGS = 23
        private const val MENU_NOTE_ADD = 24
        private const val MENU_NOTES = 25
        private const val MENU_PRINT = 26
        private const val PICK_NONE = 0
        private const val PICK_NOTE = 1
        private const val PICK_SECTION_FACE = 2
        private const val MENU_SAVE_VIEW = 60
        private const val MENU_MANAGE_VIEWS = 61
        private const val MENU_SAVED_BASE = 1000
        private const val MENU_ORTHO = 50
        private const val MENU_YUP = 51
        private const val MENU_FREE_ORBIT = 52
        private const val MENU_ROTATE_SPEED = 53
        private val ROTATE_SPEEDS = floatArrayOf(0.55f, 1f, 1.7f)
    }
}
