package com.mertkaradag.stepviewer

import android.content.Context

/** 3B görünümün renkleri: tema, arka plan ayarı ve güneş ışığı moduna göre. */
class Palette(
    /** Arka plan: üst ve alt renk (r, g, b 0..1). */
    val bgTop: FloatArray,
    val bgBottom: FloatArray,
    /** true ise ortadan kenara yuvarlak degrade (bgTop ortada). */
    val radial: Boolean,
    /** Açık zemin: kenar ve ölçü çizgileri koyu çizilir. */
    val light: Boolean,
    val edge: FloatArray,
    val wire: FloatArray,
    val measure: FloatArray,
    val edgeWidth: Float,
    /** Gölgelendirme kontrastı (güneş ışığında daha sert). */
    val contrast: Float,
) {
    companion object {
        private fun rgb(hex: Int) = floatArrayOf(
            ((hex shr 16) and 0xFF) / 255f, ((hex shr 8) and 0xFF) / 255f, (hex and 0xFF) / 255f,
        )

        fun current(context: Context): Palette {
            val night = Prefs.isNight(context)
            val theme = Prefs.theme
            if (Prefs.highContrast) {
                return Palette(
                    bgTop = rgb(0xFFFFFF), bgBottom = rgb(0xFFFFFF), radial = false, light = true,
                    edge = rgb(0x000000), wire = rgb(0x000000), measure = rgb(0xD84300),
                    edgeWidth = 2.5f, contrast = 1.25f,
                )
            }
            val bg = when (Prefs.background) {
                Prefs.BG_WHITE -> Prefs.BG_WHITE
                Prefs.BG_GRAY -> Prefs.BG_GRAY
                Prefs.BG_SKY -> Prefs.BG_SKY
                Prefs.BG_NAVY -> Prefs.BG_NAVY
                Prefs.BG_BLACK -> Prefs.BG_BLACK
                else -> when {
                    theme == Prefs.THEME_SKY -> Prefs.BG_SKY
                    night -> Prefs.BG_NAVY
                    else -> Prefs.BG_GRAY
                }
            }
            return when (bg) {
                Prefs.BG_WHITE -> lightPalette(rgb(0xFFFFFF), rgb(0xFFFFFF), false)
                Prefs.BG_SKY -> lightPalette(rgb(0x9EC3E8), rgb(0xFFFFFF), false)
                Prefs.BG_NAVY -> darkPalette(rgb(0x46526E), rgb(0x1E2433), true)
                Prefs.BG_BLACK -> darkPalette(rgb(0x000000), rgb(0x000000), false)
                else -> lightPalette(rgb(0xFFFFFF), rgb(0xDCE2EA), false)
            }
        }

        private fun lightPalette(top: FloatArray, bottom: FloatArray, radial: Boolean) = Palette(
            bgTop = top, bgBottom = bottom, radial = radial, light = true,
            edge = floatArrayOf(0.12f, 0.15f, 0.21f),
            wire = floatArrayOf(0.16f, 0.22f, 0.34f),
            measure = floatArrayOf(0.91f, 0.36f, 0.0f),
            edgeWidth = 1.5f, contrast = 1.0f,
        )

        private fun darkPalette(top: FloatArray, bottom: FloatArray, radial: Boolean) = Palette(
            bgTop = top, bgBottom = bottom, radial = radial, light = false,
            edge = floatArrayOf(0.07f, 0.08f, 0.10f),
            wire = floatArrayOf(0.80f, 0.86f, 0.95f),
            measure = floatArrayOf(1.0f, 0.86f, 0.1f),
            edgeWidth = 1.5f, contrast = 1.0f,
        )
    }
}
