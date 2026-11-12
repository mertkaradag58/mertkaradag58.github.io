package com.mertkaradag.stepviewer.photocad

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Kaydedilen çizim projesi: filesDir/photocad/<id>/project.json + fotoğraflar + STEP çıktısı.
 * Böylece kullanıcı sonradan açıp ölçüleri değiştirerek STEP'i yeniden üretebilir.
 */
class PhotoCadProject(val dir: File) {
    var name = "parca"
    var mode = PhotoCadNative.MODE_EXTRUDE
    var thickness = 10.0
    var axis = 0
    var updated = 0L
    /** 0: üst/yan görünüş, 1: ön görünüş (iki görünüş yönteminde). */
    val sketches = arrayOf(Sketch(), Sketch())

    val id: String get() = dir.name

    fun save() {
        dir.mkdirs()
        updated = System.currentTimeMillis()
        val o = JSONObject().apply {
            put("name", name); put("mode", mode); put("thickness", thickness); put("axis", axis)
            put("updated", updated)
            put("sketches", JSONArray().apply { sketches.forEach { put(it.toJson()) } })
        }
        val tmp = File(dir, "project.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(File(dir, "project.json"))
    }

    fun imageFile(name: String) = File(dir, name)

    fun stepFile(): File = File(dir, safeName(name) + ".step")

    companion object {
        fun root(ctx: Context) = File(ctx.filesDir, "photocad")

        fun create(ctx: Context): PhotoCadProject {
            val dir = File(root(ctx), "p" + System.currentTimeMillis())
            dir.mkdirs()
            return PhotoCadProject(dir)
        }

        fun load(dir: File): PhotoCadProject? = try {
            val o = JSONObject(File(dir, "project.json").readText())
            PhotoCadProject(dir).apply {
                name = o.optString("name", "parca")
                mode = o.optInt("mode", 0)
                thickness = o.optDouble("thickness", 10.0)
                axis = o.optInt("axis", 0)
                updated = o.optLong("updated")
                val arr = o.optJSONArray("sketches")
                if (arr != null) for (i in 0 until minOf(2, arr.length())) {
                    sketches[i] = Sketch.fromJson(arr.getJSONObject(i))
                }
            }
        } catch (_: Exception) {
            null
        }

        fun list(ctx: Context): List<PhotoCadProject> =
            (root(ctx).listFiles() ?: emptyArray())
                .filter { File(it, "project.json").exists() }
                .mapNotNull { load(it) }
                .sortedByDescending { it.updated }

        fun safeName(s: String): String {
            val t = s.trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            return t.ifEmpty { "parca" }.take(60)
        }
    }
}
