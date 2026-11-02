package com.qoder.mdreader.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ReadingAnchor(val line: Int, val offset: Int = 0, val label: String = "", val segment: Int = 0)
class ReadingState(ctx: Context) {
    private val sp = ctx.getSharedPreferences("md_reading_state", Context.MODE_PRIVATE)
    fun position(key: String): ReadingAnchor? = if (sp.contains("line|$key")) ReadingAnchor(sp.getInt("line|$key", 0), sp.getInt("offset|$key", 0), segment = sp.getInt("segment|$key", 0)) else null
    fun setPosition(key: String, anchor: ReadingAnchor) { sp.edit().putInt("line|$key", anchor.line).putInt("offset|$key", anchor.offset).putInt("segment|$key", anchor.segment).apply() }
    fun bookmarks(key: String): List<ReadingAnchor> = runCatching {
        val array = JSONArray(sp.getString("marks|$key", "[]"))
        (0 until array.length()).map { i -> array.getJSONObject(i).let { ReadingAnchor(it.getInt("line"), it.getInt("offset"), it.getString("label"), it.optInt("segment", 0)) } }
    }.getOrDefault(emptyList())
    fun add(key: String, anchor: ReadingAnchor) {
        val entries = (listOf(anchor) + bookmarks(key).filter { it.line != anchor.line }).take(100)
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("line", it.line).put("offset", it.offset).put("label", it.label).put("segment", it.segment)) }
        sp.edit().putString("marks|$key", array.toString()).apply()
    }
    fun remove(key: String, line: Int) {
        val array = JSONArray()
        bookmarks(key).filter { it.line != line }.forEach { array.put(JSONObject().put("line", it.line).put("offset", it.offset).put("label", it.label).put("segment", it.segment)) }
        sp.edit().putString("marks|$key", array.toString()).apply()
    }
}
