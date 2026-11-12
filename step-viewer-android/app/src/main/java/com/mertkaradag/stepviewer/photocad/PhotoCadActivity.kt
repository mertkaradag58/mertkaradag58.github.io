package com.mertkaradag.stepviewer.photocad

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.mertkaradag.stepviewer.MainActivity
import com.mertkaradag.stepviewer.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Fotoğraftan (veya video karesinden) kaba katı model: kontur çıkar, ölçü ver, STEP üret.
 */
class PhotoCadActivity : AppCompatActivity(), SketchView.Listener {

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var project: PhotoCadProject
    private var current = 0
    private val bitmaps = arrayOfNulls<Bitmap>(2)
    /** Çözümleme için küçültülmüş resim ve asıl resme oranı. */
    private val analysis = arrayOfNulls<Bitmap>(2)
    private val analysisScale = floatArrayOf(1f, 1f)
    private val lastSeed = arrayOf(floatArrayOf(-1f, -1f), floatArrayOf(-1f, -1f))
    private var sensitivity = 0.5f
    private var detail = 0.5f
    private val undo = arrayOf(ArrayDeque<List<Sketch.Loop>>(), ArrayDeque<List<Sketch.Loop>>())
    private var busy = false

    private lateinit var sketchView: SketchView
    private lateinit var hint: TextView
    private lateinit var viewToggle: MaterialButtonToggleGroup
    private lateinit var actions: LinearLayout
    private lateinit var tunePanel: View
    private lateinit var buildButton: MaterialButton
    private lateinit var progress: View
    private lateinit var progressText: TextView

    private val sketch: Sketch get() = project.sketches[current]
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    // -- Kaynaklar -----------------------------------------------------------------

    private val captureFile by lazy { File(File(cacheDir, "shared").apply { mkdirs() }, "pc_capture.jpg") }
    private val videoFile by lazy { File(File(cacheDir, "shared").apply { mkdirs() }, "pc_video.mp4") }
    private fun shareUri(f: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", f)

    private var pendingCamera: (() -> Unit)? = null
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val run = pendingCamera
        pendingCamera = null
        if (ok) run?.invoke() else toast(R.string.pc_camera_denied)
    }
    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) loadImage(Uri.fromFile(captureFile))
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadImage(it) }
    }
    private val captureVideo = registerForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        if (ok) showFramePicker(Uri.fromFile(videoFile))
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { showFramePicker(it) }
    }
    private var pendingSave: File? = null
    private val saveStep = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val src = pendingSave
        if (uri != null && src != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                toast(R.string.pc_saved)
            } catch (e: Exception) {
                toast(R.string.pc_save_failed)
            }
        }
    }

    private fun withCamera(run: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) run()
        else { pendingCamera = run; cameraPermission.launch(Manifest.permission.CAMERA) }
    }

    // -- Yaşam döngüsü -------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val id = savedInstanceState?.getString(KEY_PROJECT) ?: intent.getStringExtra(EXTRA_PROJECT)
        val dir = id?.let { File(PhotoCadProject.root(this), it) }
        val loaded = dir?.let { PhotoCadProject.load(it) }
        if (loaded != null) {
            openProject(loaded, savedInstanceState?.getInt(KEY_VIEW) ?: 0)
        } else {
            project = PhotoCadProject.create(this)
            showStartDialog()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (sketchView.mode == SketchView.Mode.DRAW) { cancelDrawing(); return }
                saveProject()
                finish()
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::project.isInitialized) {
            saveProject()
            outState.putString(KEY_PROJECT, project.id)
            outState.putInt(KEY_VIEW, current)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::project.isInitialized) saveProject()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun saveProject() {
        if (project.sketches.any { !it.isEmpty || it.image != null }) {
            try { project.save() } catch (_: Exception) {}
        }
    }

    // -- Arayüz ---------------------------------------------------------------------

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@PhotoCadActivity, R.color.background))
        }
        val toolbar = com.google.android.material.appbar.MaterialToolbar(this).apply {
            setTitle(R.string.pc_title)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            menu.add(0, 1, 0, R.string.pc_menu_method)
            menu.add(0, 2, 1, R.string.pc_menu_projects)
            menu.add(0, 3, 2, R.string.pc_menu_help)
            menu.add(0, 4, 3, R.string.pc_menu_delete)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> showMethodDialog(false)
                    2 -> showProjectsDialog(false)
                    3 -> showHelp()
                    4 -> confirmDelete()
                }
                true
            }
        }
        root.addView(toolbar)

        viewToggle = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            addView(toggleButton(R.string.pc_view_top, 101))
            addView(toggleButton(R.string.pc_view_front, 102))
            check(101)
            addOnButtonCheckedListener { _, id, checked -> if (checked) switchView(if (id == 102) 1 else 0) }
            visibility = View.GONE
        }
        root.addView(viewToggle, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(4), dp(12), dp(4)) })

        val frame = FrameLayout(this)
        sketchView = SketchView(this).also { it.listener = this }
        frame.addView(sketchView, FrameLayout.LayoutParams(-1, -1))
        hint = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xC0141820.toInt())
            gravity = Gravity.CENTER
        }
        frame.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        progress = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt())
            addView(com.google.android.material.progressindicator.CircularProgressIndicator(this@PhotoCadActivity).apply {
                isIndeterminate = true
            })
            progressText = TextView(this@PhotoCadActivity).apply {
                setTextColor(0xFFFFFFFF.toInt()); setPadding(dp(12), 0, 0, 0)
            }
            addView(progressText)
            visibility = View.GONE
            isClickable = true
        }
        frame.addView(progress, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        tunePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), 0)
            addView(label(R.string.pc_sensitivity))
            addView(Slider(this@PhotoCadActivity).apply {
                valueFrom = 0f; valueTo = 1f; value = sensitivity
                addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) {}
                    override fun onStopTrackingTouch(slider: Slider) { sensitivity = slider.value; redetect() }
                })
            })
            addView(label(R.string.pc_detail))
            addView(Slider(this@PhotoCadActivity).apply {
                valueFrom = 0f; valueTo = 1f; value = detail
                addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: Slider) {}
                    override fun onStopTrackingTouch(slider: Slider) { detail = slider.value; redetect() }
                })
            })
            visibility = View.GONE
        }
        root.addView(tunePanel)

        actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(2))
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(actions)
        })

        buildButton = MaterialButton(this).apply {
            setText(R.string.pc_build)
            setOnClickListener { onBuildClicked() }
        }
        root.addView(buildButton, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(12), dp(4), dp(12), dp(8)) })

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }
        setContentView(root)
    }

    private fun toggleButton(textRes: Int, viewId: Int) = MaterialButton(
        this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        id = viewId
        setText(textRes)
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
    }

    private fun label(textRes: Int) = TextView(this).apply {
        setText(textRes); textSize = 12f
        setTextColor(ContextCompat.getColor(this@PhotoCadActivity, R.color.text))
    }

    private fun chip(textRes: Int, onClick: () -> Unit) = MaterialButton(
        this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        setText(textRes)
        isAllCaps = false
        minWidth = 0
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
    }

    private fun refreshActions() {
        actions.removeAllViews()
        if (sketchView.mode == SketchView.Mode.DRAW) {
            actions.addView(chip(R.string.pc_draw_done) {
                if (!sketchView.finishDrawing()) toast(R.string.pc_draw_need3)
            })
            actions.addView(chip(R.string.pc_draw_undo_point) { sketchView.undoDrawPoint() })
            actions.addView(chip(R.string.pc_cancel) { cancelDrawing() })
            buildButton.isEnabled = false
            updateHint()
            return
        }
        buildButton.isEnabled = true
        actions.addView(chip(R.string.pc_camera) { withCamera { takePicture.launch(shareUri(captureFile)) } })
        actions.addView(chip(R.string.pc_gallery) { pickImage.launch("image/*") })
        actions.addView(chip(R.string.pc_video) { showVideoSource() })
        actions.addView(chip(R.string.pc_draw) { startDrawing() })
        if (!sketch.isEmpty) {
            actions.addView(chip(R.string.pc_scale) { showScaleDialog(null) })
            actions.addView(chip(R.string.pc_straighten) {
                pushUndo(); sketch.straighten(); sketchView.invalidate(); saveProject()
                toast(R.string.pc_straightened)
            })
            actions.addView(chip(R.string.pc_add_hole) { addHole() })
        }
        if (bitmaps[current] != null) actions.addView(chip(R.string.pc_tune) {
            tunePanel.visibility = if (tunePanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        if (project.mode == PhotoCadNative.MODE_REVOLVE) {
            actions.addView(chip(if (project.axis == 0) R.string.pc_axis_h else R.string.pc_axis_v) {
                project.axis = 1 - project.axis
                sketchView.axis = project.axis
                refreshActions()
            })
        }
        if (undo[current].isNotEmpty()) actions.addView(chip(R.string.pc_undo) { doUndo() })
        updateHint()
    }

    private fun updateHint() {
        val s = sketch
        val text = when {
            sketchView.mode == SketchView.Mode.DRAW -> getString(R.string.pc_hint_draw)
            sketchView.mode == SketchView.Mode.SEED -> getString(R.string.pc_hint_seed)
            s.image == null && s.isEmpty -> getString(
                when {
                    project.mode == PhotoCadNative.MODE_TWO_VIEW && current == 1 -> R.string.pc_hint_start_front
                    project.mode == PhotoCadNative.MODE_TWO_VIEW -> R.string.pc_hint_start_top
                    project.mode == PhotoCadNative.MODE_REVOLVE -> R.string.pc_hint_start_side
                    else -> R.string.pc_hint_start_top
                })
            s.isEmpty -> getString(R.string.pc_hint_seed)
            !s.scaled -> getString(R.string.pc_hint_scale)
            else -> {
                val sz = s.sizeMm()
                getString(R.string.pc_hint_edit, fmt(sz[0]), fmt(sz[1]))
            }
        }
        hint.text = text
    }

    private fun fmt(v: Double) = String.format(Locale.getDefault(), "%.1f", v)

    // -- Proje / yöntem ---------------------------------------------------------------

    private fun methodLabels() = arrayOf(
        getString(R.string.pc_method_extrude), getString(R.string.pc_method_two_view), getString(R.string.pc_method_revolve),
    )

    private fun showStartDialog() {
        val projects = PhotoCadProject.list(this)
        val items = methodLabels().toMutableList()
        if (projects.isNotEmpty()) items.add(getString(R.string.pc_open_saved, projects.size))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pc_start_title)
            .setItems(items.toTypedArray()) { _, which ->
                if (which < 3) {
                    project.mode = which
                    openProject(project, 0)
                    if (which == PhotoCadNative.MODE_REVOLVE) toast(R.string.pc_revolve_tip, long = true)
                } else showProjectsDialog(true)
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun showMethodDialog(finishOnCancel: Boolean) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pc_menu_method)
            .setSingleChoiceItems(methodLabels(), project.mode) { d, which ->
                project.mode = which
                if (which != PhotoCadNative.MODE_TWO_VIEW && current == 1) current = 0
                openProject(project, current)
                d.dismiss()
            }
            .setOnCancelListener { if (finishOnCancel) finish() }
            .show()
    }

    private fun showProjectsDialog(fromStart: Boolean) {
        val projects = PhotoCadProject.list(this)
        if (projects.isEmpty()) { toast(R.string.pc_no_projects); return }
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val labels = projects.map { p ->
            "${p.name}  ·  ${methodLabels().getOrElse(p.mode) { "" }.substringBefore(" (")}\n${df.format(Date(p.updated))}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pc_menu_projects)
            .setItems(labels) { _, which ->
                saveProject()
                openProject(projects[which], 0)
            }
            .setOnCancelListener { if (fromStart) showStartDialog() }
            .show()
    }

    private fun openProject(p: PhotoCadProject, view: Int) {
        project = p
        for (i in 0..1) {
            bitmaps[i] = null; analysis[i] = null; undo[i].clear()
            lastSeed[i][0] = -1f; lastSeed[i][1] = -1f
            p.sketches[i].image?.let { name ->
                val f = p.imageFile(name)
                if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.let { setBitmap(i, it) }
            }
        }
        viewToggle.visibility = if (p.mode == PhotoCadNative.MODE_TWO_VIEW) View.VISIBLE else View.GONE
        current = if (p.mode == PhotoCadNative.MODE_TWO_VIEW) view.coerceIn(0, 1) else 0
        viewToggle.check(if (current == 1) 102 else 101)
        switchView(current)
    }

    private fun switchView(i: Int) {
        current = i
        sketchView.mode = SketchView.Mode.EDIT
        sketchView.sketch = sketch
        sketchView.bitmap = bitmaps[i]
        sketchView.axis = if (project.mode == PhotoCadNative.MODE_REVOLVE) project.axis else -1
        sketchView.fit()
        if (sketch.isEmpty && bitmaps[i] != null) sketchView.mode = SketchView.Mode.SEED
        tunePanel.visibility = View.GONE
        refreshActions()
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.pc_delete_confirm)
            .setPositiveButton(R.string.pc_delete) { _, _ ->
                project.dir.deleteRecursively()
                project = PhotoCadProject.create(this)
                openProject(project, 0)
                showStartDialog()
            }
            .setNegativeButton(R.string.pc_cancel, null)
            .show()
    }

    private fun showHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pc_menu_help)
            .setMessage(R.string.pc_help)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // -- Resim yükleme --------------------------------------------------------------

    private fun showVideoSource() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pc_video)
            .setItems(arrayOf(getString(R.string.pc_video_record), getString(R.string.pc_video_pick))) { _, w ->
                if (w == 0) withCamera { captureVideo.launch(shareUri(videoFile)) } else pickVideo.launch("video/*")
            }
            .show()
    }

    private fun loadImage(uri: Uri) {
        val target = current
        showProgress(getString(R.string.pc_loading_image))
        executor.execute {
            val bmp = try { decodeImage(uri) } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
            runOnUiThread {
                hideProgress()
                if (bmp == null) { toast(R.string.pc_image_failed); return@runOnUiThread }
                useBitmap(target, bmp)
            }
        }
    }

    private fun useBitmap(target: Int, bmp: Bitmap) {
        val name = if (target == 0) "a.jpg" else "b.jpg"
        try {
            project.dir.mkdirs()
            FileOutputStream(project.imageFile(name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        } catch (_: IOException) {}
        val s = project.sketches[target]
        if (!s.isEmpty) { undo[target].addLast(s.snapshot()) }
        s.loops.clear()
        s.image = name
        s.imageW = bmp.width; s.imageH = bmp.height
        lastSeed[target][0] = -1f
        setBitmap(target, bmp)
        if (target == current) {
            switchView(target)
            sketchView.mode = SketchView.Mode.SEED
            refreshActions()
        }
        saveProject()
    }

    private fun setBitmap(i: Int, bmp: Bitmap) {
        bitmaps[i] = bmp
        val f = 900f / max(bmp.width, bmp.height)
        if (f < 1f) {
            analysis[i] = Bitmap.createScaledBitmap(bmp, (bmp.width * f).roundToInt(), (bmp.height * f).roundToInt(), true)
            analysisScale[i] = 1f / f
        } else {
            analysis[i] = bmp
            analysisScale[i] = 1f
        }
    }

    /** En uzun kenarı 1600 px olacak şekilde, EXIF yönü uygulanmış resim. */
    private fun decodeImage(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        val raw = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = try {
            contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (_: Exception) { 0 }
        return fitBitmap(raw, rotation)
    }

    private fun fitBitmap(raw: Bitmap, rotation: Int): Bitmap {
        val f = minOf(1f, 1600f / max(raw.width, raw.height))
        if (rotation == 0 && f >= 1f) return raw
        val mat = Matrix().apply { postScale(f, f); postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, mat, true)
    }

    private fun showFramePicker(uri: Uri) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
        } catch (e: Exception) {
            toast(R.string.pc_video_failed); retriever.release(); return
        }
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            minimumHeight = dp(200)
        }
        val time = TextView(this).apply { gravity = Gravity.CENTER }
        var frameBmp: Bitmap? = null
        var token = 0
        fun load(ms: Long, exact: Boolean) {
            val my = ++token
            executor.execute {
                val frame = try {
                    retriever.getFrameAtTime(ms * 1000,
                        if (exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (_: Exception) { null }
                runOnUiThread {
                    if (my != token || frame == null) return@runOnUiThread
                    frameBmp = frame
                    image.setImageBitmap(frame)
                    time.text = String.format(Locale.getDefault(), "%.1f s", ms / 1000.0)
                }
            }
        }
        val slider = Slider(this).apply {
            valueFrom = 0f; valueTo = max(1f, durationMs.toFloat()); value = 0f
            addOnChangeListener { _, v, fromUser -> if (fromUser) load(v.toLong(), false) }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) { load(slider.value.toLong(), true) }
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(TextView(this@PhotoCadActivity).apply { setText(R.string.pc_frame_hint) })
            addView(image, LinearLayout.LayoutParams(-1, dp(260)))
            addView(time)
            addView(slider)
        }
        load(0, true)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pc_frame_title)
            .setView(box)
            .setPositiveButton(R.string.pc_frame_use) { _, _ ->
                val f = frameBmp
                if (f != null) {
                    // getFrameAtTime yönü uygulamış döner (API 28+); eski sürümlerde elle çevir
                    val rot = if (android.os.Build.VERSION.SDK_INT >= 28) 0 else rotation
                    useBitmap(current, fitBitmap(f, rot))
                }
            }
            .setNegativeButton(R.string.pc_cancel, null)
            .setOnDismissListener { token++; executor.execute { try { retriever.release() } catch (_: Exception) {} } }
            .show()
    }

    // -- Kontur bulma -----------------------------------------------------------------

    override fun onSeed(x: Float, y: Float) {
        lastSeed[current][0] = x; lastSeed[current][1] = y
        detect(x, y)
    }

    private fun redetect() {
        val s = lastSeed[current]
        if (analysis[current] == null) return
        detect(s[0], s[1])
    }

    private fun detect(x: Float, y: Float) {
        val bmp = analysis[current] ?: return
        val k = analysisScale[current]
        val target = current
        val sens = sensitivity; val det = detail
        showProgress(getString(R.string.pc_detecting))
        executor.execute {
            val r = try {
                ContourExtractor.extract(bmp, if (x >= 0) x / k else -1f, if (y >= 0) y / k else -1f, sens, det, k)
            } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
            runOnUiThread {
                hideProgress()
                if (r == null || r.loops.isEmpty()) {
                    toast(R.string.pc_detect_failed, long = true)
                    return@runOnUiThread
                }
                val s = project.sketches[target]
                if (!s.isEmpty) undo[target].addLast(s.snapshot())
                s.loops.clear()
                s.loops.addAll(r.loops)
                autoScale(target)
                if (target == current) {
                    sketchView.mode = SketchView.Mode.EDIT
                    sketchView.invalidate()
                    refreshActions()
                    if (!s.scaled) showScaleDialog(null)
                }
                saveProject()
            }
        }
    }

    /**
     * Ölçeksiz çizime başlangıç ölçeği verir: iki görünüşte ön görünüş, üst görünüşün
     * genişliğine eşitlenir; diğer durumda genişlik 100 mm varsayılır.
     */
    private fun autoScale(i: Int) {
        val s = project.sketches[i]
        if (s.scaled || s.isEmpty) return
        val other = project.sketches[1 - i]
        if (project.mode == PhotoCadNative.MODE_TWO_VIEW && other.scaled && !other.isEmpty) {
            s.sx = 1.0; s.sy = 1.0
            s.setSize(other.sizeMm()[0], 0.0)
            toast(R.string.pc_auto_scaled, long = true)
        } else {
            s.sx = 1.0; s.sy = 1.0
            val w = s.sizeMm()[0]
            if (w > 0) { s.sx = 100.0 / w; s.sy = s.sx }
        }
    }

    // -- Elle çizim ----------------------------------------------------------------------

    private fun startDrawing() {
        if (!sketch.isEmpty) {
            // Varsa yeni döngü delik olur
            toast(R.string.pc_draw_as_hole)
        } else if (sketch.imageW == 0) {
            sketch.imageW = 1000; sketch.imageH = 1000
            sketchView.fit()
        }
        sketchView.mode = SketchView.Mode.DRAW
        tunePanel.visibility = View.GONE
        refreshActions()
    }

    private fun cancelDrawing() {
        sketchView.mode = if (sketch.isEmpty && bitmaps[current] != null) SketchView.Mode.SEED else SketchView.Mode.EDIT
        refreshActions()
    }

    private fun addHole() {
        pushUndo()
        val c = sketchView.centerInImage()
        val l = Sketch.Loop(circle = true, cx = c[0], cy = c[1], r = sketchView.imagePx(30f))
        sketch.loops.add(l)
        sketchView.invalidate()
        refreshActions()
        onCircleTap(l)
    }

    // -- Ölçü verme --------------------------------------------------------------------

    private fun numberField(hintRes: Int, value: Double?): Pair<TextInputLayout, TextInputEditText> {
        val edit = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            value?.let { setText(fmt(it)) }
            setSelectAllOnFocus(true)
        }
        val layout = TextInputLayout(this).apply {
            hint = getString(hintRes)
            suffixText = "mm"
            addView(edit)
        }
        return layout to edit
    }

    private fun parse(e: EditText): Double? =
        e.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 }

    private fun dialogBox(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
        views.forEach { addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }) }
    }

    private fun showScaleDialog(after: (() -> Unit)?) {
        val s = sketch
        if (s.isEmpty) return
        val size = s.sizeMm()
        val (wl, we) = numberField(R.string.pc_width, if (s.scaled) size[0] else null)
        val (hl, he) = numberField(R.string.pc_height, if (s.scaled) size[1] else null)
        val note = TextView(this).apply { setText(R.string.pc_scale_note); textSize = 12f }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pc_scale_title)
            .setView(dialogBox(note, wl, hl))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val w = parse(we); val h = parse(he)
                val oldW = if (s.scaled) size[0] else -1.0
                val oldH = if (s.scaled) size[1] else -1.0
                val wChanged = w != null && kotlin.math.abs(w - oldW) > 1e-3
                val hChanged = h != null && kotlin.math.abs(h - oldH) > 1e-3
                when {
                    wChanged && hChanged -> s.setSize(w!!, h!!)
                    wChanged -> s.setSize(w!!, 0.0)
                    hChanged -> s.setSize(0.0, h!!)
                    else -> return@setPositiveButton
                }
                sketchView.invalidate(); refreshActions(); saveProject()
                after?.invoke()
            }
            .setNegativeButton(R.string.pc_cancel, null)
            .show()
        we.requestFocus()
    }

    override fun onSegmentTap(loop: Sketch.Loop, index: Int) {
        val s = sketch
        if (!s.scaled) { showScaleDialog(null); return }
        val (l, e) = numberField(R.string.pc_edge_length, s.segmentLengthMm(loop, index))
        val note = TextView(this).apply { setText(R.string.pc_edge_note); textSize = 12f }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pc_edge_title)
            .setView(dialogBox(note, l))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val v = parse(e) ?: return@setPositiveButton
                pushUndo()
                s.setSegmentLength(loop, index, v)
                sketchView.invalidate(); refreshActions(); saveProject()
            }
            .setNegativeButton(R.string.pc_cancel, null)
            .show()
        e.requestFocus()
    }

    override fun onCircleTap(loop: Sketch.Loop) {
        val s = sketch
        if (!s.scaled) { showScaleDialog(null); return }
        val (l, e) = numberField(R.string.pc_diameter, s.diameterMm(loop))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pc_hole_title)
            .setView(dialogBox(l))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val v = parse(e) ?: return@setPositiveButton
                pushUndo()
                s.setDiameter(loop, v)
                sketchView.invalidate(); saveProject()
            }
            .setNeutralButton(R.string.pc_delete) { _, _ ->
                pushUndo()
                if (s.loops.indexOf(loop) > 0) s.loops.remove(loop)
                sketchView.invalidate(); refreshActions(); saveProject()
            }
            .setNegativeButton(R.string.pc_cancel, null)
            .show()
        e.requestFocus()
    }

    override fun onBeforeEdit() = pushUndo()

    override fun onEdited() {
        refreshActions()
        saveProject()
    }

    private fun pushUndo() {
        val u = undo[current]
        u.addLast(sketch.snapshot())
        while (u.size > 40) u.removeFirst()
    }

    private fun doUndo() {
        val u = undo[current]
        if (u.isEmpty()) return
        sketch.restore(u.removeLast())
        sketchView.invalidate()
        refreshActions()
        saveProject()
    }

    // -- STEP üretme ---------------------------------------------------------------------

    private fun onBuildClicked() {
        val twoView = project.mode == PhotoCadNative.MODE_TWO_VIEW
        for (i in 0 until if (twoView) 2 else 1) {
            val s = project.sketches[i]
            if (s.isEmpty) {
                if (twoView && i != current) { viewToggle.check(if (i == 1) 102 else 101) }
                toast(if (twoView && i == 1) R.string.pc_need_front else R.string.pc_need_contour, long = true)
                return
            }
            if (!s.scaled) {
                if (i != current) viewToggle.check(if (i == 1) 102 else 101)
                showScaleDialog { onBuildClicked() }
                return
            }
        }
        val (nl, ne) = run {
            val e = TextInputEditText(this).apply { setText(project.name); setSingleLine() }
            TextInputLayout(this).apply { hint = getString(R.string.pc_name); addView(e) } to e
        }
        val views = mutableListOf<View>(nl)
        var thick: EditText? = null
        if (project.mode == PhotoCadNative.MODE_EXTRUDE) {
            val (tl, te) = numberField(R.string.pc_thickness, project.thickness)
            views.add(tl); thick = te
        }
        val info = TextView(this).apply {
            textSize = 12f
            setText(when (project.mode) {
                PhotoCadNative.MODE_TWO_VIEW -> R.string.pc_build_note_two_view
                PhotoCadNative.MODE_REVOLVE -> R.string.pc_build_note_revolve
                else -> R.string.pc_build_note_extrude
            })
        }
        views.add(info)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pc_build)
            .setView(dialogBox(*views.toTypedArray()))
            .setPositiveButton(R.string.pc_build_go) { _, _ ->
                project.name = ne.text?.toString()?.trim().orEmpty().ifEmpty { "parca" }
                thick?.let { t -> parse(t)?.let { project.thickness = it } }
                runBuild()
            }
            .setNegativeButton(R.string.pc_cancel, null)
            .show()
    }

    private fun runBuild() {
        if (busy) return
        val a = project.sketches[0].encode() ?: return
        val b = if (project.mode == PhotoCadNative.MODE_TWO_VIEW) project.sketches[1].encode() else null
        val out = project.stepFile()
        val mode = project.mode; val param = project.thickness; val axis = project.axis
        val name = project.name
        project.dir.mkdirs()
        saveProject()
        showProgress(getString(R.string.pc_building))
        executor.execute {
            val r: Result<DoubleArray> = try {
                Result.success(PhotoCadNative.buildSolid(mode, a, b, param, axis, out.absolutePath, name))
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: Throwable) {
                Result.failure(IOException("build_failed"))
            }
            runOnUiThread {
                hideProgress()
                r.onSuccess { showResult(out, it) }
                    .onFailure { showBuildError(it.message) }
            }
        }
    }

    private fun showBuildError(code: String?) {
        val msg = when (code) {
            "open_profile" -> R.string.pc_err_open
            "bad_profile" -> R.string.pc_err_bad
            "empty_result" -> R.string.pc_err_empty
            "write_failed" -> R.string.pc_err_write
            "bad_param" -> R.string.pc_err_param
            else -> R.string.pc_err_build
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pc_err_title)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showResult(file: File, info: DoubleArray) {
        val msg = getString(R.string.pc_result, fmt(info[1]), fmt(info[2]), fmt(info[3]),
            String.format(Locale.getDefault(), "%,.0f", info[0]), file.name)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pc_result_title)
            .setMessage(msg)
            .setPositiveButton(R.string.pc_view_model) { _, _ -> openInViewer(file) }
            .setNeutralButton(R.string.pc_save) { _, _ -> pendingSave = file; saveStep.launch(file.name) }
            .setNegativeButton(R.string.pc_share) { _, _ -> share(file) }
            .show()
    }

    private fun openInViewer(file: File) {
        val uri = shareUri(file)
        startActivity(Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun share(file: File) {
        val uri = shareUri(file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.pc_share)))
    }

    // -- Yardımcılar -----------------------------------------------------------------------

    private fun showProgress(text: String) {
        busy = true
        progressText.text = text
        progress.visibility = View.VISIBLE
    }

    private fun hideProgress() {
        busy = false
        progress.visibility = View.GONE
    }

    private fun toast(res: Int, long: Boolean = false) =
        Toast.makeText(this, res, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_PROJECT = "project"
        private const val KEY_PROJECT = "project"
        private const val KEY_VIEW = "view"
    }
}
