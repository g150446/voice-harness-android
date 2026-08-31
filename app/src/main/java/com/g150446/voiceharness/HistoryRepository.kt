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

    /** Applies [label] to [id]; returns false when the entry has aged out. */
    fun setGestureLabel(id: String, label: GestureLabel?): Boolean {
        val list = loadRaw()
        if (list.none { it.id == id }) return false
        val updated = list.map { if (it.id == id) it.copy(gestureLabel = label) else it }
        prefs.edit().putString(KEY_HISTORY, serialize(updated)).apply()
        return true
    }

    /**
     * Labels every entry whose timestamp falls in [fromMs]..[toMs]. Bulk labelling
     * is how a clinic session actually gets classified — the user knows the whole
     * block was accidental without opening each entry.
     */
    fun setGestureLabelInRange(fromMs: Long, toMs: Long, label: GestureLabel?): Int {
        val list = loadRaw()
        var count = 0
        val updated = list.map { entry ->
            if (entry.timestamp in fromMs..toMs) {
                count++
                entry.copy(gestureLabel = label)
            } else {
                entry
            }
        }
        if (count > 0) prefs.edit().putString(KEY_HISTORY, serialize(updated)).apply()
        return count
    }

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
                put("gestureDiags", GestureDiagEntry.listToJson(entry.gestureDiags))
                entry.trajectoryFile?.let { put("trajectoryFile", it) }
                if (entry.diagsFromNodeBatch) put("diagsFromNodeBatch", true)
                entry.gestureLabel?.let { put("gestureLabel", it.name) }
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
        errorMessage = obj.optString("errorMessage", ""),
        gestureDiags = GestureDiagEntry.listFromJson(obj.optJSONArray("gestureDiags")),
        trajectoryFile = obj.optString("trajectoryFile", "").ifBlank { null },
        diagsFromNodeBatch = obj.optBoolean("diagsFromNodeBatch", false),
        gestureLabel = GestureLabel.fromStorage(obj.optString("gestureLabel", "")),
    )
}
