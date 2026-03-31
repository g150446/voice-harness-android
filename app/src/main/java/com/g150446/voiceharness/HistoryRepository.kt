package com.g150446.voiceharness

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS_NAME = "voice_history_prefs"
private const val KEY_HISTORY = "history_json"
private const val MAX_ENTRIES = 100

class HistoryRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun addEntry(entry: HistoryEntry) {
        val list = loadRaw().toMutableList()
        list.add(0, entry)
        if (list.size > MAX_ENTRIES) {
            list.subList(MAX_ENTRIES, list.size).clear()
        }
        prefs.edit().putString(KEY_HISTORY, serialize(list)).apply()
    }

    fun getAll(): List<HistoryEntry> = loadRaw()

    private fun loadRaw(): List<HistoryEntry> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i -> deserialize(array.getJSONObject(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serialize(list: List<HistoryEntry>): String {
        val array = JSONArray()
        list.forEach { entry ->
            array.put(JSONObject().apply {
                put("id", entry.id)
                put("timestamp", entry.timestamp)
                put("transcription", entry.transcription)
                put("response", entry.response)
                put("isSilent", entry.isSilent)
                put("errorMessage", entry.errorMessage)
            })
        }
        return array.toString()
    }

    private fun deserialize(obj: JSONObject) = HistoryEntry(
        id = obj.getString("id"),
        timestamp = obj.getLong("timestamp"),
        transcription = obj.optString("transcription", ""),
        response = obj.optString("response", ""),
        isSilent = obj.optBoolean("isSilent", false),
        errorMessage = obj.optString("errorMessage", "")
    )
}
