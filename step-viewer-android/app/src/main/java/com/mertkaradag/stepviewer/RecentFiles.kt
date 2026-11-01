package com.mertkaradag.stepviewer

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Son açılan dosyalar (kalıcı okuma izni alınabilen URI'ler). */
class RecentFiles(context: Context) {

    data class Entry(val uri: Uri, val name: String, val time: Long)

    private val prefs = context.getSharedPreferences("recent_files", Context.MODE_PRIVATE)

    fun list(): List<Entry> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
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

    fun add(uri: Uri, name: String) {
        val items = list().filter { it.uri != uri }.toMutableList()
        items.add(0, Entry(uri, name, System.currentTimeMillis()))
        save(items.take(MAX))
    }

    fun remove(uri: Uri) = save(list().filter { it.uri != uri })

    private fun save(items: List<Entry>) {
        val arr = JSONArray()
        for (e in items) {
            arr.put(JSONObject().put("uri", e.uri.toString()).put("name", e.name).put("time", e.time))
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val KEY = "items"
        private const val MAX = 8
    }
}
