package com.mertkaradag.stepviewer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Dosya adına göre kayıtlı kamera görünümleri. */
class SavedViews(context: Context) {

    class Entry(val name: String, val state: FloatArray)

    private val prefs = context.getSharedPreferences("saved_views", Context.MODE_PRIVATE)

    fun list(file: String): List<Entry> {
        val json = prefs.getString(file, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val s = o.optJSONArray("s") ?: return@mapNotNull null
                Entry(o.optString("n"), FloatArray(s.length()) { s.optDouble(it).toFloat() })
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(file: String, name: String, state: FloatArray) {
        val items = list(file).toMutableList()
        items.removeAll { it.name == name }
        items.add(Entry(name, state))
        save(file, items.takeLast(MAX))
    }

    fun remove(file: String, name: String) = save(file, list(file).filter { it.name != name })

    private fun save(file: String, items: List<Entry>) {
        val arr = JSONArray()
        for (v in items) {
            val s = JSONArray()
            for (f in v.state) s.put(f.toDouble())
            arr.put(JSONObject().put("n", v.name).put("s", s))
        }
        prefs.edit().putString(file, arr.toString()).apply()
    }

    companion object {
        private const val MAX = 20
    }
}
