package com.mertkaradag.stepviewer

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Son açılan dosyalar ve favoriler (kalıcı okuma izni alınabilen URI'ler). */
class RecentFiles(context: Context) {

    data class Entry(val uri: Uri, val name: String, val time: Long)

    private val prefs = context.getSharedPreferences("recent_files", Context.MODE_PRIVATE)

    fun list(): List<Entry> = read(KEY)

    fun favorites(): List<Entry> = read(KEY_FAV)

    fun isFavorite(uri: Uri): Boolean = favorites().any { it.uri == uri }

    fun add(uri: Uri, name: String) {
        val items = list().filter { it.uri != uri }.toMutableList()
        items.add(0, Entry(uri, name, System.currentTimeMillis()))
        write(KEY, items.take(MAX))
        // Favorideki adı ve tarihi de güncelle
        val favs = favorites()
        if (favs.any { it.uri == uri }) {
            write(KEY_FAV, favs.map { if (it.uri == uri) Entry(uri, name, System.currentTimeMillis()) else it })
        }
    }

    /** Favoriye ekler veya çıkarır; yeni durumu döndürür. */
    fun toggleFavorite(uri: Uri, name: String): Boolean {
        val favs = favorites().toMutableList()
        val i = favs.indexOfFirst { it.uri == uri }
        return if (i >= 0) {
            favs.removeAt(i)
            write(KEY_FAV, favs)
            false
        } else {
            val time = list().firstOrNull { it.uri == uri }?.time ?: System.currentTimeMillis()
            favs.add(0, Entry(uri, name, time))
            write(KEY_FAV, favs.take(MAX_FAV))
            true
        }
    }

    fun remove(uri: Uri) {
        write(KEY, list().filter { it.uri != uri })
    }

    /** Dosya artık açılamıyorsa her iki listeden de siler. */
    fun forget(uri: Uri) {
        remove(uri)
        write(KEY_FAV, favorites().filter { it.uri != uri })
    }

    private fun read(key: String): List<Entry> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Entry(Uri.parse(o.getString("uri")), o.optString("name"), o.optLong("time"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun write(key: String, items: List<Entry>) {
        val arr = JSONArray()
        for (e in items) {
            arr.put(JSONObject().put("uri", e.uri.toString()).put("name", e.name).put("time", e.time))
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "items"
        private const val KEY_FAV = "favorites"
        private const val MAX = 12
        private const val MAX_FAV = 30
    }
}
