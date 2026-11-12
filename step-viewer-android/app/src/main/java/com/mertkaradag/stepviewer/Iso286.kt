package com.mertkaradag.stepviewer

import kotlin.math.abs

/**
 * ISO 286 (DIN 7182) geçme toleransları, 500 mm'ye kadar; ISO 2768 genel toleranslar.
 * Tüm sapmalar µm.
 */
object Iso286 {

    /** Ana boyut aralıklarının üst sınırları. */
    private val MAIN = doubleArrayOf(3.0, 6.0, 10.0, 18.0, 30.0, 50.0, 80.0, 120.0, 180.0, 250.0, 315.0, 400.0, 500.0)

    /** Ara aralıklar (c, r, s, u sapmaları bunlara göre değişir). */
    private val SUB = doubleArrayOf(
        3.0, 6.0, 10.0, 14.0, 18.0, 24.0, 30.0, 40.0, 50.0, 65.0, 80.0, 100.0, 120.0, 140.0, 160.0, 180.0,
        200.0, 225.0, 250.0, 280.0, 315.0, 355.0, 400.0, 450.0, 500.0,
    )

    /** IT1..IT16 temel toleranslar, ana aralıklara göre. */
    private val IT = arrayOf(
        doubleArrayOf(0.8, 1.0, 1.0, 1.2, 1.5, 1.5, 2.0, 2.5, 3.5, 4.5, 6.0, 7.0, 8.0),
        doubleArrayOf(1.2, 1.5, 1.5, 2.0, 2.5, 2.5, 3.0, 4.0, 5.0, 7.0, 8.0, 9.0, 10.0),
        doubleArrayOf(2.0, 2.5, 2.5, 3.0, 4.0, 4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 13.0, 15.0),
        doubleArrayOf(3.0, 4.0, 4.0, 5.0, 6.0, 7.0, 8.0, 10.0, 12.0, 14.0, 16.0, 18.0, 20.0),
        doubleArrayOf(4.0, 5.0, 6.0, 8.0, 9.0, 11.0, 13.0, 15.0, 18.0, 20.0, 23.0, 25.0, 27.0),
        doubleArrayOf(6.0, 8.0, 9.0, 11.0, 13.0, 16.0, 19.0, 22.0, 25.0, 29.0, 32.0, 36.0, 40.0),
        doubleArrayOf(10.0, 12.0, 15.0, 18.0, 21.0, 25.0, 30.0, 35.0, 40.0, 46.0, 52.0, 57.0, 63.0),
        doubleArrayOf(14.0, 18.0, 22.0, 27.0, 33.0, 39.0, 46.0, 54.0, 63.0, 72.0, 81.0, 89.0, 97.0),
        doubleArrayOf(25.0, 30.0, 36.0, 43.0, 52.0, 62.0, 74.0, 87.0, 100.0, 115.0, 130.0, 140.0, 155.0),
        doubleArrayOf(40.0, 48.0, 58.0, 70.0, 84.0, 100.0, 120.0, 140.0, 160.0, 185.0, 210.0, 230.0, 250.0),
        doubleArrayOf(60.0, 75.0, 90.0, 110.0, 130.0, 160.0, 190.0, 220.0, 250.0, 290.0, 320.0, 360.0, 400.0),
        doubleArrayOf(100.0, 120.0, 150.0, 180.0, 210.0, 250.0, 300.0, 350.0, 400.0, 460.0, 520.0, 570.0, 630.0),
        doubleArrayOf(140.0, 180.0, 220.0, 270.0, 330.0, 390.0, 460.0, 540.0, 630.0, 720.0, 810.0, 890.0, 970.0),
        doubleArrayOf(250.0, 300.0, 360.0, 430.0, 520.0, 620.0, 740.0, 870.0, 1000.0, 1150.0, 1300.0, 1400.0, 1550.0),
        doubleArrayOf(400.0, 480.0, 580.0, 700.0, 840.0, 1000.0, 1200.0, 1400.0, 1600.0, 1850.0, 2100.0, 2300.0, 2500.0),
        doubleArrayOf(600.0, 750.0, 900.0, 1100.0, 1300.0, 1600.0, 1900.0, 2200.0, 2500.0, 2900.0, 3200.0, 3600.0, 4000.0),
    )

    // Mil üst sapmaları es (a..h), ana aralıklar
    private val ES_MAIN = mapOf(
        "d" to doubleArrayOf(-20.0, -30.0, -40.0, -50.0, -65.0, -80.0, -100.0, -120.0, -145.0, -170.0, -190.0, -210.0, -230.0),
        "e" to doubleArrayOf(-14.0, -20.0, -25.0, -32.0, -40.0, -50.0, -60.0, -72.0, -85.0, -100.0, -110.0, -125.0, -135.0),
        "f" to doubleArrayOf(-6.0, -10.0, -13.0, -16.0, -20.0, -25.0, -30.0, -36.0, -43.0, -50.0, -56.0, -62.0, -68.0),
        "g" to doubleArrayOf(-2.0, -4.0, -5.0, -6.0, -7.0, -9.0, -10.0, -12.0, -14.0, -15.0, -17.0, -18.0, -20.0),
        "h" to DoubleArray(13),
    )
    private val ES_SUB = mapOf(
        "c" to doubleArrayOf(
            -60.0, -70.0, -80.0, -95.0, -95.0, -110.0, -110.0, -120.0, -130.0, -140.0, -150.0, -170.0, -180.0,
            -200.0, -210.0, -230.0, -240.0, -260.0, -280.0, -300.0, -330.0, -360.0, -400.0, -440.0, -480.0,
        ),
    )

    // Mil alt sapmaları ei (k..u)
    private val K_EI = doubleArrayOf(0.0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 3.0, 3.0, 4.0, 4.0, 4.0, 5.0)
    private val EI_MAIN = mapOf(
        "m" to doubleArrayOf(2.0, 4.0, 6.0, 7.0, 8.0, 9.0, 11.0, 13.0, 15.0, 17.0, 20.0, 21.0, 23.0),
        "n" to doubleArrayOf(4.0, 8.0, 10.0, 12.0, 15.0, 17.0, 20.0, 23.0, 27.0, 31.0, 34.0, 37.0, 40.0),
        "p" to doubleArrayOf(6.0, 12.0, 15.0, 18.0, 22.0, 26.0, 32.0, 37.0, 43.0, 50.0, 56.0, 62.0, 68.0),
    )
    private val EI_SUB = mapOf(
        "r" to doubleArrayOf(
            10.0, 15.0, 19.0, 23.0, 23.0, 28.0, 28.0, 34.0, 34.0, 41.0, 43.0, 51.0, 54.0, 63.0, 65.0, 68.0,
            77.0, 80.0, 84.0, 94.0, 98.0, 108.0, 114.0, 126.0, 132.0,
        ),
        "s" to doubleArrayOf(
            14.0, 19.0, 23.0, 28.0, 28.0, 35.0, 35.0, 43.0, 43.0, 53.0, 59.0, 71.0, 79.0, 92.0, 100.0, 108.0,
            122.0, 130.0, 140.0, 158.0, 170.0, 190.0, 208.0, 232.0, 252.0,
        ),
        "u" to doubleArrayOf(
            18.0, 23.0, 28.0, 33.0, 33.0, 41.0, 48.0, 60.0, 70.0, 87.0, 102.0, 124.0, 144.0, 170.0, 190.0, 210.0,
            236.0, 258.0, 284.0, 315.0, 350.0, 390.0, 435.0, 490.0, 540.0,
        ),
    )

    val SHAFT_LETTERS = listOf("c", "d", "e", "f", "g", "h", "js", "k", "m", "n", "p", "r", "s", "u")
    val HOLE_LETTERS = SHAFT_LETTERS.map { it.uppercase() }
    val GRADES = (1..16).toList()

    const val MAX_SIZE = 500.0

    /** Tolerans aralığı: alt ve üst sapma (µm). */
    data class Zone(val code: String, val lower: Double, val upper: Double, val isHole: Boolean)

    private fun index(size: Double, table: DoubleArray): Int {
        for (i in table.indices) if (size <= table[i] + 1e-9) return i
        return -1
    }

    fun itValue(grade: Int, size: Double): Double? {
        if (grade !in 1..16 || size <= 0 || size > MAX_SIZE) return null
        val i = index(size, MAIN)
        return if (i < 0) null else IT[grade - 1][i]
    }

    private fun shaftFundamental(letter: String, grade: Int, size: Double): Pair<Double, Boolean>? {
        // Dönüş: (sapma, üst sapma mı)
        val mi = index(size, MAIN)
        val si = index(size, SUB)
        if (mi < 0 || si < 0) return null
        ES_MAIN[letter]?.let { return it[mi] to true }
        ES_SUB[letter]?.let { return it[si] to true }
        if (letter == "k") {
            val v = if (grade in 4..7 && size > 3.0) K_EI[mi] else 0.0
            return v to false
        }
        EI_MAIN[letter]?.let { return it[mi] to false }
        EI_SUB[letter]?.let { return it[si] to false }
        return null
    }

    /** "H7", "g6", "JS7", "js6" gibi bir kodun aralığı; geçersizse null. */
    fun zone(code: String, size: Double): Zone? {
        val m = Regex("^([A-Za-z]{1,2})(\\d{1,2})$").find(code.trim()) ?: return null
        val letterRaw = m.groupValues[1]
        val grade = m.groupValues[2].toInt()
        val isHole = letterRaw[0].isUpperCase()
        val letter = letterRaw.lowercase()
        if (letter !in SHAFT_LETTERS) return null
        val tol = itValue(grade, size) ?: return null
        val canonical = (if (isHole) letter.uppercase() else letter) + grade
        if (letter == "js") return Zone(canonical, -tol / 2, tol / 2, isHole)
        if (!isHole) {
            val (dev, upper) = shaftFundamental(letter, grade, size) ?: return null
            return if (upper) Zone(canonical, dev - tol, dev, false) else Zone(canonical, dev, dev + tol, false)
        }
        // Delik: ISO 286-1 genel ve özel kurallar
        val mi = index(size, MAIN)
        val delta = if (size > 3.0 && grade >= 2) IT[grade - 1][mi] - IT[grade - 2][mi] else 0.0
        return when (letter) {
            "c", "d", "e", "f", "g", "h" -> {
                val es = shaftFundamental(letter, grade, size)?.first ?: return null
                Zone(canonical, -es, -es + tol, true)
            }
            "k" -> {
                val upper = if (grade <= 8) -(if (size > 3.0) K_EI[mi] else 0.0) + delta else 0.0
                Zone(canonical, upper - tol, upper, true)
            }
            "m", "n" -> {
                val ei = shaftFundamental(letter, grade, size)?.first ?: return null
                val upper = when {
                    grade <= 8 -> -ei + delta
                    letter == "n" -> 0.0
                    else -> -ei
                }
                Zone(canonical, upper - tol, upper, true)
            }
            else -> {
                val ei = shaftFundamental(letter, grade, size)?.first ?: return null
                val upper = if (grade <= 7) -ei + delta else -ei
                Zone(canonical, upper - tol, upper, true)
            }
        }
    }

    enum class FitKind { CLEARANCE, TRANSITION, INTERFERENCE }

    /** Boşluk değerleri (µm): pozitif = boşluk, negatif = sıkılık. */
    data class Fit(val hole: Zone, val shaft: Zone, val maxClearance: Double, val minClearance: Double, val kind: FitKind)

    fun fit(holeCode: String, shaftCode: String, size: Double): Fit? {
        val h = zone(holeCode, size) ?: return null
        val s = zone(shaftCode, size) ?: return null
        if (!h.isHole || s.isHole) return null
        val maxC = h.upper - s.lower
        val minC = h.lower - s.upper
        val kind = when {
            minC >= 0 -> FitKind.CLEARANCE
            maxC <= 0 -> FitKind.INTERFERENCE
            else -> FitKind.TRANSITION
        }
        return Fit(h, s, maxC, minC, kind)
    }

    /** Sık kullanılan geçmeler: (delik, mil, açıklama string id). */
    val COMMON = listOf(
        Triple("H11", "c11", R.string.fit_use_loose),
        Triple("H9", "d9", R.string.fit_use_free),
        Triple("H8", "f7", R.string.fit_use_running),
        Triple("H7", "g6", R.string.fit_use_sliding),
        Triple("H7", "h6", R.string.fit_use_location),
        Triple("H7", "k6", R.string.fit_use_transition),
        Triple("H7", "n6", R.string.fit_use_tight),
        Triple("H7", "p6", R.string.fit_use_press),
        Triple("H7", "s6", R.string.fit_use_shrink),
    )

    // ---------------------------------------------------------------------
    // ISO 2768-1 genel toleranslar (doğrusal ölçüler, açılar)
    // ---------------------------------------------------------------------

    val GENERAL_CLASSES = listOf("f", "m", "c", "v")
    private val LIN_LIMITS = doubleArrayOf(3.0, 6.0, 30.0, 120.0, 400.0, 1000.0, 2000.0, 4000.0)
    private val LIN = mapOf(
        "f" to doubleArrayOf(0.05, 0.05, 0.1, 0.15, 0.2, 0.3, 0.5, 0.5),
        "m" to doubleArrayOf(0.1, 0.1, 0.2, 0.3, 0.5, 0.8, 1.2, 2.0),
        "c" to doubleArrayOf(0.2, 0.3, 0.5, 0.8, 1.2, 2.0, 3.0, 4.0),
        "v" to doubleArrayOf(0.5, 0.5, 1.0, 1.5, 2.5, 4.0, 6.0, 8.0),
    )

    /** Doğrusal ölçü için ± genel tolerans (mm). */
    fun generalLinear(cls: String, size: Double): Double {
        val t = LIN[cls] ?: LIN.getValue("m")
        val s = abs(size)
        for (i in LIN_LIMITS.indices) if (s <= LIN_LIMITS[i]) return t[i]
        return t.last()
    }

    /** Açı için ± genel tolerans (derece), kısa kenar boyu bilinmediğinde 10-50 mm aralığı. */
    fun generalAngle(cls: String): Double = when (cls) {
        "f", "m" -> 0.5
        "c" -> 1.0
        else -> 2.0
    }
}
