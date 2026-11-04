package com.mertkaradag.stepviewer

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Kullanıcı ayarları. Tüm uygulama tek bir SharedPreferences ("settings") kullanır;
 * eski sürümlerden kalan anahtarlar (mesh_quality, ar_intro_seen) aynen korunur.
 */
object Prefs {

    // Tema: arayüz (açık/koyu) ve 3B görünüm arka planı birlikte değişir
    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"   // Aydınlık Atölye
    const val THEME_DARK = "dark"     // Yumuşak Gece
    const val THEME_SKY = "sky"       // Gökyüzü CAD

    // 3B arka plan (tema yerine)
    const val BG_AUTO = "auto"
    const val BG_WHITE = "white"
    const val BG_GRAY = "gray"
    const val BG_SKY = "sky"
    const val BG_NAVY = "navy"
    const val BG_BLACK = "black"

    const val UNIT_MM = "mm"
    const val UNIT_CM = "cm"
    const val UNIT_IN = "in"

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (!::sp.isInitialized) {
            sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        }
    }

    val prefs: SharedPreferences get() = sp

    var theme: String
        get() = sp.getString("theme", THEME_SYSTEM) ?: THEME_SYSTEM
        set(v) = sp.edit().putString("theme", v).apply()

    var background: String
        get() = sp.getString("background", BG_AUTO) ?: BG_AUTO
        set(v) = sp.edit().putString("background", v).apply()

    /** Güneş ışığı modu: beyaz zemin, siyah ve kalın kenarlar. */
    var highContrast: Boolean
        get() = sp.getBoolean("high_contrast", false)
        set(v) = sp.edit().putBoolean("high_contrast", v).apply()

    var unit: String
        get() = sp.getString("unit", UNIT_MM) ?: UNIT_MM
        set(v) = sp.edit().putString("unit", v).apply()

    /** Uzunluklarda ondalık hane (0-4). */
    var decimals: Int
        get() = sp.getInt("decimals", 2).coerceIn(0, 4)
        set(v) = sp.edit().putInt("decimals", v.coerceIn(0, 4)).apply()

    /** Açılışta kenar çizgileri görünsün mü (gölgeli + kenar ya da yalnız gölgeli). */
    var showEdges: Boolean
        get() = sp.getBoolean("show_edges", true)
        set(v) = sp.edit().putBoolean("show_edges", v).apply()

    var spinOnLoad: Boolean
        get() = sp.getBoolean("spin_on_load", false)
        set(v) = sp.edit().putBoolean("spin_on_load", v).apply()

    /** Ölçümde uzun basınca büyüteçli hassas imleç. */
    var loupe: Boolean
        get() = sp.getBoolean("loupe", true)
        set(v) = sp.edit().putBoolean("loupe", v).apply()

    /** Nokta ölçümünde köşe / orta nokta / merkez yakalama. */
    var snap: Boolean
        get() = sp.getBoolean("snap", true)
        set(v) = sp.edit().putBoolean("snap", v).apply()

    /** Dokunuşlarda titreşim. */
    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()

    /** 0 = hızlı, 1 = normal, 2 = hassas. */
    var meshQuality: Int
        get() = sp.getInt("mesh_quality", 1)
        set(v) = sp.edit().putInt("mesh_quality", v).apply()

    var onboardingSeen: Boolean
        get() = sp.getBoolean("onboarding_seen", false)
        set(v) = sp.edit().putBoolean("onboarding_seen", v).apply()

    /** "" = sistem dili; aksi halde dil etiketi (tr, en, de...). */
    var language: String
        get() {
            val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            return tags.substringBefore(',').substringBefore('-')
        }
        set(v) {
            val list = if (v.isBlank()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(v)
            AppCompatDelegate.setApplicationLocales(list)
        }

    /** Tema ayarını arayüze uygular (Activity'ler yeniden oluşturulur). */
    fun applyNightMode() {
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                THEME_LIGHT, THEME_SKY -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    /** Geçerli arayüz koyu mu (sistem temasında cihaz ayarına bakılır). */
    fun isNight(context: Context): Boolean {
        val mask = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mask == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }
}
