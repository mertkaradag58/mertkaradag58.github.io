package com.mertkaradag.stepviewer

import java.util.Locale

/** Sayı biçimlendirme (cihaz diline göre ondalık ayırıcı). */
fun fmt(value: Double, digits: Int = Units.decimals): String =
    String.format(Locale.getDefault(), "%.${digits.coerceIn(0, 6)}f", value)

fun fmt(value: Float, digits: Int = Units.decimals): String = fmt(value.toDouble(), digits)

/**
 * Ekranda gösterilen uzunluk birimi. Model içi hesaplar her zaman mm'dir; yalnızca ölçüm
 * paneli, kesit etiketi ve model bilgisi seçilen birimde gösterilir. Dosyaya yazılan çıktılar
 * (DXF, teknik resim, delik tablosu, parça listesi) üretim için mm kalır.
 *
 * Metin kaynakları "mm", "mm²", "mm³" yazar; [localize] bu birimleri seçilen birimle değiştirir.
 */
object Units {

    @Volatile var unit: String = Prefs.UNIT_MM
    @Volatile var decimals: Int = 2

    fun load() {
        unit = Prefs.unit
        decimals = Prefs.decimals
    }

    val isMetricMm: Boolean get() = unit == Prefs.UNIT_MM

    /** mm cinsinden uzunluğu seçilen birime çevirir. */
    fun len(mm: Double): Double = when (unit) {
        Prefs.UNIT_CM -> mm / 10.0
        Prefs.UNIT_IN -> mm / 25.4
        else -> mm
    }

    fun area(mm2: Double): Double = when (unit) {
        Prefs.UNIT_CM -> mm2 / 100.0
        Prefs.UNIT_IN -> mm2 / (25.4 * 25.4)
        else -> mm2
    }

    /** Uzunluk: seçilen birime çevrilmiş ve ayarlanan ondalıkla. İnçte en az 3 hane. */
    fun fl(mm: Double): String = fmt(len(mm), lengthDigits())

    fun fl(mm: Float): String = fl(mm.toDouble())

    fun fa(mm2: Double): String = fmt(area(mm2), lengthDigits())

    fun lengthDigits(): Int = when (unit) {
        Prefs.UNIT_IN -> maxOf(decimals, 3)
        Prefs.UNIT_CM -> maxOf(decimals, 1) + 1
        else -> decimals
    }

    val label: String
        get() = when (unit) {
            Prefs.UNIT_CM -> "cm"
            Prefs.UNIT_IN -> "in"
            else -> "mm"
        }

    private val mmRegex = Regex("(?<![\\p{L}])mm(?=[²³]|(?![\\p{L}]))")
    private val mmRuRegex = Regex("(?<![\\p{L}])мм(?=[²³]|(?![\\p{L}]))")

    private val labelRu: String
        get() = when (unit) {
            Prefs.UNIT_CM -> "см"
            Prefs.UNIT_IN -> "дюйм"
            else -> "мм"
        }

    /** Metindeki "mm" (ve Rusça "мм") birimlerini seçilen birimle değiştirir (sayılar önceden çevrilmiş olmalı). */
    fun localize(text: String): String =
        if (isMetricMm) text else text.replace(mmRegex, label).replace(mmRuRegex, labelRu)
}
