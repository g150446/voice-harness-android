package com.g150446.voiceharness.assistant

import com.g150446.voiceharness.ScreenContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local store for screen snapshots. Tokens are single-use or expire after 30s.
 * JPEG bytes never travel through Intents.
 */
object ScreenContextStore {
    private const val TTL_MS = 30_000L

    private data class Entry(
        val context: ScreenContext,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun put(context: ScreenContext): String {
        prune()
        val token = UUID.randomUUID().toString()
        entries[token] = Entry(context)
        return token
    }

    /** Consume once. Returns null if missing or expired. */
    fun take(token: String?): ScreenContext? {
        if (token.isNullOrBlank()) return null
        prune()
        val entry = entries.remove(token) ?: return null
        if (System.currentTimeMillis() - entry.createdAt > TTL_MS) return null
        return entry.context
    }

    fun peek(token: String?): ScreenContext? {
        if (token.isNullOrBlank()) return null
        prune()
        val entry = entries[token] ?: return null
        if (System.currentTimeMillis() - entry.createdAt > TTL_MS) {
            entries.remove(token)
            return null
        }
        return entry.context
    }

    fun remove(token: String?) {
        if (token.isNullOrBlank()) return
        entries.remove(token)
    }

    fun clear() {
        entries.clear()
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        entries.entries.removeIf { now - it.value.createdAt > TTL_MS }
    }
}
