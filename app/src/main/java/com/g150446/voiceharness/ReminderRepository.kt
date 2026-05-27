package com.g150446.voiceharness

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

private const val PREFS_NAME = "reminder_prefs"
private const val KEY_REMINDERS = "reminders_json"
private const val MAX_ENTRIES = 100

class ReminderRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun addEntry(entry: ReminderEntry) {
        val list = loadRaw().toMutableList()
        list.add(0, entry)
        if (list.size > MAX_ENTRIES) {
            list.subList(MAX_ENTRIES, list.size).clear()
        }
        prefs.edit().putString(KEY_REMINDERS, serialize(list)).apply()
    }

    fun getAll(): List<ReminderEntry> = loadRaw()

    fun getPending(): List<ReminderEntry> {
        val now = System.currentTimeMillis()
        return loadRaw().filter { !it.isCompleted && it.scheduledAtMillis > now }
    }

    fun getOverdue(): List<ReminderEntry> {
        val now = System.currentTimeMillis()
        return loadRaw().filter { !it.isCompleted && it.scheduledAtMillis <= now }
    }

    fun markCompleted(id: String) {
        val list = loadRaw().map {
            if (it.id == id) it.copy(isCompleted = true) else it
        }
        prefs.edit().putString(KEY_REMINDERS, serialize(list)).apply()
    }

    fun deleteEntry(id: String) {
        val list = loadRaw().filter { it.id != id }
        prefs.edit().putString(KEY_REMINDERS, serialize(list)).apply()
    }

    fun getEntry(id: String): ReminderEntry? {
        return loadRaw().find { it.id == id }
    }

    private fun loadRaw(): List<ReminderEntry> {
        val json = prefs.getString(KEY_REMINDERS, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i -> deserialize(array.getJSONObject(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serialize(list: List<ReminderEntry>): String {
        val array = JSONArray()
        list.forEach { entry ->
            array.put(JSONObject().apply {
                put("id", entry.id)
                put("title", entry.title)
                put("scheduledAtMillis", entry.scheduledAtMillis)
                put("createdAtMillis", entry.createdAtMillis)
                put("isCompleted", entry.isCompleted)
                put("isTtsEnabled", entry.isTtsEnabled)
            })
        }
        return array.toString()
    }

    private fun deserialize(obj: JSONObject) = ReminderEntry(
        id = obj.getString("id"),
        title = obj.getString("title"),
        scheduledAtMillis = obj.getLong("scheduledAtMillis"),
        createdAtMillis = obj.getLong("createdAtMillis"),
        isCompleted = obj.optBoolean("isCompleted", false),
        isTtsEnabled = obj.optBoolean("isTtsEnabled", false)
    )
}
