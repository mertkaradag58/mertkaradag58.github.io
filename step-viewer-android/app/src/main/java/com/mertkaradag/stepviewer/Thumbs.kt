package com.mertkaradag.stepviewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.security.MessageDigest

/** Son açılan dosyaların küçük resimleri (uygulama klasöründe, URI özetiyle adlandırılır). */
object Thumbs {

    private const val SIZE = 256

    private fun dir(context: Context) = File(context.filesDir, "thumbs").apply { mkdirs() }

    private fun key(uri: String): String {
        val md = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
        return md.joinToString("") { "%02x".format(it) }
    }

    fun file(context: Context, uri: String) = File(dir(context), key(uri) + ".png")

    /** Ekran görüntüsünün ortasından kare bir parça alıp küçültür ve kaydeder. */
    fun save(context: Context, uri: String, shot: Bitmap) {
        try {
            val w = shot.width
            val h = shot.height
            // Üst çubuk ve alt araç çubuğu dışında kalan orta bölge
            val side = minOf(w, (h * 0.62f).toInt())
            val x = (w - side) / 2
            val y = ((h - side) / 2).coerceAtLeast(0)
            val crop = Bitmap.createBitmap(shot, x, y, side, minOf(side, h - y))
            val small = Bitmap.createScaledBitmap(crop, SIZE, SIZE * crop.height / crop.width, true)
            file(context, uri).outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 90, it) }
            if (crop !== shot) crop.recycle()
            small.recycle()
        } catch (_: Throwable) {
        }
    }

    fun load(context: Context, uri: String): Bitmap? = try {
        val f = file(context, uri)
        if (f.exists()) BitmapFactory.decodeFile(f.absolutePath) else null
    } catch (_: Throwable) {
        null
    }

    fun delete(context: Context, uri: String) {
        file(context, uri).delete()
    }
}
