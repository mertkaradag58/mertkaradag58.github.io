package com.mertkaradag.stepviewer

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Ayarlar ekranı. Görünüm, ölçü, dosya, dil ve genel bölümleri. Satın alma, gizlilik
 * seçenekleri, rehber ve hakkında işlemleri ana ekrana sonuç olarak döner (reklam ve satın alma
 * altyapısı orada çalışır).
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private val d by lazy { resources.displayMetrics.density }
    private var decimalsPreview: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            val light = !Prefs.isNight(this@SettingsActivity)
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.background))
        }
        // Başlık çubuğu
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(16), dp(8))
        }
        val back = MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle).apply {
            setIconResource(R.drawable.ic_arrow_back)
            iconTint = ColorStateList.valueOf(getColor(R.color.text))
            contentDescription = getString(R.string.back)
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(this).apply {
            text = getString(R.string.settings)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.text))
            setPadding(dp(8), 0, 0, 0)
        })
        root.addView(header)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(32))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            content.updatePadding(bottom = bars.bottom + dp(24))
            insets
        }

        build()
    }

    private fun build() {
        // --- Görünüm ---
        section(R.string.settings_appearance)
        chipRow(
            R.drawable.ic_palette, R.string.settings_theme, R.string.settings_theme_sub,
            listOf(
                Prefs.THEME_SYSTEM to R.string.theme_system,
                Prefs.THEME_LIGHT to R.string.theme_light,
                Prefs.THEME_DARK to R.string.theme_dark,
                Prefs.THEME_SKY to R.string.theme_sky,
            ),
            Prefs.theme,
            swatches = mapOf(
                Prefs.THEME_SYSTEM to intArrayOf(0xFFFFFFFF.toInt(), 0xFF2E3647.toInt()),
                Prefs.THEME_LIGHT to intArrayOf(0xFFFFFFFF.toInt(), 0xFFDCE2EA.toInt()),
                Prefs.THEME_DARK to intArrayOf(0xFF46526E.toInt(), 0xFF1E2433.toInt()),
                Prefs.THEME_SKY to intArrayOf(0xFF9EC3E8.toInt(), 0xFFFFFFFF.toInt()),
            ),
        ) { v ->
            if (Prefs.theme != v) {
                Prefs.theme = v
                // Arka plan temaya göre olsun (aksi halde tema değişikliği görünmeyebilir)
                Prefs.background = Prefs.BG_AUTO
                // Gece kipi değişirse AppCompat ekranları kendisi yeniden oluşturur
                Prefs.applyNightMode()
            }
        }
        chipRow(
            R.drawable.ic_image, R.string.settings_background, R.string.settings_background_sub,
            listOf(
                Prefs.BG_AUTO to R.string.bg_auto,
                Prefs.BG_WHITE to R.string.bg_white,
                Prefs.BG_GRAY to R.string.bg_gray,
                Prefs.BG_SKY to R.string.bg_sky,
                Prefs.BG_NAVY to R.string.bg_navy,
                Prefs.BG_BLACK to R.string.bg_black,
            ),
            Prefs.background,
            swatches = mapOf(
                Prefs.BG_WHITE to intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()),
                Prefs.BG_GRAY to intArrayOf(0xFFFFFFFF.toInt(), 0xFFDCE2EA.toInt()),
                Prefs.BG_SKY to intArrayOf(0xFF9EC3E8.toInt(), 0xFFFFFFFF.toInt()),
                Prefs.BG_NAVY to intArrayOf(0xFF46526E.toInt(), 0xFF1E2433.toInt()),
                Prefs.BG_BLACK to intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt()),
            ),
        ) { Prefs.background = it }
        switchRow(R.drawable.ic_sun, R.string.settings_sunlight, R.string.settings_sunlight_sub, Prefs.highContrast) {
            Prefs.highContrast = it
        }
        switchRow(R.drawable.ic_display, R.string.settings_edges, R.string.settings_edges_sub, Prefs.showEdges) {
            Prefs.showEdges = it
        }
        switchRow(R.drawable.ic_rotate, R.string.settings_spin, R.string.settings_spin_sub, Prefs.spinOnLoad) {
            Prefs.spinOnLoad = it
        }

        // --- Ölçü ---
        section(R.string.settings_measure)
        chipRow(
            R.drawable.ic_ruler, R.string.settings_unit, R.string.settings_unit_sub,
            listOf(Prefs.UNIT_MM to R.string.unit_mm, Prefs.UNIT_CM to R.string.unit_cm, Prefs.UNIT_IN to R.string.unit_in),
            Prefs.unit,
        ) {
            Prefs.unit = it
            Units.load()
            updateDecimalsPreview()
        }
        chipRow(
            R.drawable.ic_decimal, R.string.settings_decimals, 0,
            (0..4).map { it.toString() to 0 },
            Prefs.decimals.toString(),
            preview = true,
        ) {
            Prefs.decimals = it.toInt()
            Units.load()
            updateDecimalsPreview()
        }
        updateDecimalsPreview()
        switchRow(R.drawable.ic_loupe, R.string.settings_loupe, R.string.settings_loupe_sub, Prefs.loupe) {
            Prefs.loupe = it
        }
        switchRow(R.drawable.ic_snap, R.string.settings_snap, R.string.settings_snap_sub, Prefs.snap) {
            Prefs.snap = it
        }
        switchRow(R.drawable.ic_vibration, R.string.settings_haptics, R.string.settings_haptics_sub, Prefs.haptics) {
            Prefs.haptics = it
        }

        // --- Dosya ---
        section(R.string.settings_files)
        chipRow(
            R.drawable.ic_cube, R.string.menu_quality, R.string.settings_quality_sub,
            listOf("0" to R.string.quality_fast_short, "1" to R.string.quality_normal, "2" to R.string.quality_fine_short),
            Prefs.meshQuality.toString(),
        ) {
            Prefs.meshQuality = it.toInt()
            setResult(RESULT_OK, Intent().putExtra(EXTRA_QUALITY_CHANGED, true))
        }

        // --- Dil ---
        section(R.string.settings_language)
        val current = Prefs.language
        chipRow(
            R.drawable.ic_language, R.string.settings_language, R.string.settings_language_sub,
            listOf(
                "" to R.string.lang_system,
                "tr" to R.string.lang_tr,
                "en" to R.string.lang_en,
                "de" to R.string.lang_de,
                "es" to R.string.lang_es,
                "fr" to R.string.lang_fr,
                "pt" to R.string.lang_pt,
                "ru" to R.string.lang_ru,
            ),
            current,
        ) { v -> if (v != Prefs.language) Prefs.language = v }

        // --- Genel ---
        section(R.string.settings_general)
        val adFree = intent.getBooleanExtra(EXTRA_AD_FREE, false)
        if (!adFree) {
            val price = intent.getStringExtra(EXTRA_PRICE)
            actionRow(
                R.drawable.ic_star, R.string.menu_remove_ads,
                if (price != null) getString(R.string.settings_remove_ads_sub, price) else getString(R.string.settings_remove_ads_sub_noprice),
            ) { finishWith(ACTION_BUY) }
            actionRow(R.drawable.ic_restore, R.string.menu_restore_purchase, null) { finishWith(ACTION_RESTORE) }
            if (intent.getBooleanExtra(EXTRA_PRIVACY, false)) {
                actionRow(R.drawable.ic_shield, R.string.menu_privacy, null) { finishWith(ACTION_PRIVACY) }
            }
        } else {
            actionRow(R.drawable.ic_star, R.string.settings_ad_free, getString(R.string.settings_ad_free_sub), null)
        }
        actionRow(R.drawable.ic_school, R.string.settings_tutorial, getString(R.string.settings_tutorial_sub)) {
            finishWith(ACTION_TUTORIAL)
        }
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        actionRow(R.drawable.ic_info, R.string.menu_about, getString(R.string.app_name) + " " + version) {
            finishWith(ACTION_ABOUT)
        }
    }

    private fun finishWith(action: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, action))
        finish()
    }

    private fun updateDecimalsPreview() {
        decimalsPreview?.text = Units.localize(Units.fl(12.3456) + " mm")
    }

    // -----------------------------------------------------------------------
    // Satır oluşturucular
    // -----------------------------------------------------------------------

    private fun dp(v: Int) = (v * d).toInt()

    private fun section(title: Int) {
        content.addView(TextView(this).apply {
            text = getString(title).uppercase()
            textSize = 12f
            letterSpacing = 0.08f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(getColor(R.color.accent))
            setPadding(dp(22), dp(20), dp(22), dp(6))
        })
    }

    private fun iconView(icon: Int): View {
        val box = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = 11 * d
                setColor(getColor(R.color.accent_container))
            }
        }
        box.addView(ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(getColor(R.color.accent))
        }, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        box.layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(14) }
        return box
    }

    private fun texts(title: Int, sub: String?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@SettingsActivity).apply {
            text = getString(title)
            textSize = 15.5f
            setTextColor(getColor(R.color.text))
        })
        if (!sub.isNullOrBlank()) {
            addView(TextView(this@SettingsActivity).apply {
                text = sub
                textSize = 12.5f
                setTextColor(getColor(R.color.text_secondary))
            })
        }
    }

    private fun rowContainer(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(22), dp(10), dp(22), dp(10))
        minimumHeight = dp(60)
    }

    private fun switchRow(icon: Int, title: Int, sub: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
        val row = rowContainer()
        row.addView(iconView(icon))
        row.addView(texts(title, if (sub != 0) getString(sub) else null), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = MaterialSwitch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(sw)
        row.isClickable = true
        row.setBackgroundResource(selectableBg())
        row.setOnClickListener { sw.toggle() }
        content.addView(row)
    }

    private fun actionRow(icon: Int, title: Int, sub: String?, onClick: (() -> Unit)?) {
        val row = rowContainer()
        row.addView(iconView(icon))
        row.addView(texts(title, sub), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (onClick != null) {
            row.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = ColorStateList.valueOf(getColor(R.color.text_secondary))
            }, LinearLayout.LayoutParams(dp(20), dp(20)))
            row.isClickable = true
            row.setBackgroundResource(selectableBg())
            row.setOnClickListener { onClick() }
        }
        content.addView(row)
    }

    /**
     * Başlık + seçenek çipleri. [swatches] verilirse çipte iki renkli küçük bir yuvarlak gösterilir.
     * Etiket kaynağı 0 olan seçeneklerde değer metni yazılır.
     */
    private fun chipRow(
        icon: Int,
        title: Int,
        sub: Int,
        options: List<Pair<String, Int>>,
        selected: String,
        swatches: Map<String, IntArray> = emptyMap(),
        preview: Boolean = false,
        onSelect: (String) -> Unit,
    ) {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(6))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(iconView(icon))
        head.addView(texts(title, if (sub != 0) getString(sub) else null), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (preview) {
            decimalsPreview = TextView(this).apply {
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(getColor(R.color.accent))
            }
            head.addView(decimalsPreview)
        }
        block.addView(head)
        val group = ChipGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            chipSpacingHorizontal = dp(8)
            setPadding(dp(50), dp(6), 0, 0)
        }
        for ((value, label) in options) {
            val chip = Chip(this).apply {
                setChipDrawable(
                    com.google.android.material.chip.ChipDrawable.createFromAttributes(
                        this@SettingsActivity, null, 0, com.google.android.material.R.style.Widget_Material3_Chip_Filter,
                    )
                )
                text = if (label != 0) getString(label) else value
                isCheckable = true
                isChecked = value == selected
                swatches[value]?.let { colors ->
                    chipIcon = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
                        shape = GradientDrawable.OVAL
                        setStroke(dp(1), Color.argb(90, 128, 128, 128))
                    }
                    isChipIconVisible = true
                    chipIconSize = 18 * d
                }
                setOnCheckedChangeListener { _, isChecked -> if (isChecked) onSelect(value) }
            }
            group.addView(chip)
        }
        block.addView(group)
        content.addView(block)
    }

    private fun selectableBg(): Int {
        val out = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return out.resourceId
    }

    companion object {
        const val EXTRA_AD_FREE = "ad_free"
        const val EXTRA_PRICE = "price"
        const val EXTRA_PRIVACY = "privacy"
        const val EXTRA_ACTION = "action"
        const val EXTRA_QUALITY_CHANGED = "quality_changed"
        const val ACTION_BUY = "buy"
        const val ACTION_RESTORE = "restore"
        const val ACTION_PRIVACY = "privacy"
        const val ACTION_TUTORIAL = "tutorial"
        const val ACTION_ABOUT = "about"
    }
}
