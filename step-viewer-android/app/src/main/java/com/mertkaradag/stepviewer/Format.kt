package com.mertkaradag.stepviewer

import java.util.Locale

/** Sayı biçimlendirme (cihaz diline göre ondalık ayırıcı). */
fun fmt(value: Double, digits: Int = 3): String =
    String.format(Locale.getDefault(), "%.${digits}f", value)

fun fmt(value: Float, digits: Int = 3): String = fmt(value.toDouble(), digits)
