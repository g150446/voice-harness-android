package com.g150446.voiceharness

import org.json.JSONArray
import org.json.JSONObject

data class OpenClawHistoryMessage(val role: String, val text: String)

data class OpenClawSessionInfo(
    val key: String,
    val title: String,
    val preview: String,
    val updatedAt: Long,
)

/**
 * Parses `POST /tools/invoke` responses for `sessions_history` / `sessions_list`.
 * The tool result is `{ok, result:{content:[{type:"text",text}], details:{...}}}`; `details`
 * carries the structured payload, with the JSON text in `content` as a fallback.
 */
object OpenClawHistoryParser {
    private val RESERVED_KEY = Regex("(^|:)(subagent|cron|acp):", RegexOption.IGNORE_CASE)
    private val THINKING = Regex("<(think|thinking|thought|reasoning)>.*?(</\\1>|$)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val MEMORIES = Regex("<relevant[-_]memories>.*?(</relevant[-_]memories>|$)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val TOOL_XML = Regex("<(tool_calls?|function_calls?)>.*?(</\\1>|$)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val CONTROL_TOKEN = Regex("<[|｜][^<>|｜]{1,64}[|｜]>")
    private val DIRECTIVE = Regex("\\[\\[[^\\]\\n]{1,64}]]")

    fun parseHistory(body: String): List<OpenClawHistoryMessage> {
        val messages = payload(body).optJSONArray("messages") ?: return emptyList()
        return buildList {
            for (index in 0 until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                val role = message.optString("role")
                if (role != "user" && role != "assistant") continue
                val text = sanitize(extractText(message.opt("content")))
                if (text.isNotEmpty()) add(OpenClawHistoryMessage(role, text))
            }
        }
    }

    fun parseSessions(body: String): List<OpenClawSessionInfo> {
        val rows = payload(body).optJSONArray("sessions") ?: return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val key = row.optString("key").trim()
                // `:harbor` and `:harbor:<workspace>` are interpretation sessions, not chats.
                if (key.isEmpty() || RESERVED_KEY.containsMatchIn(key) || isHarborSessionKey(key)) continue
                val title = listOf("displayName", "derivedTitle", "label", "title")
                    .map { row.optString(it).trim() }
                    .firstOrNull(String::isNotEmpty)
                    ?: key
                add(
                    OpenClawSessionInfo(
                        key = key,
                        title = title,
                        preview = row.optString("lastMessagePreview").trim(),
                        updatedAt = row.optLong("updatedAt", 0L),
                    ),
                )
            }
        }.sortedByDescending { it.updatedAt }
    }

    fun sanitize(raw: String): String = raw
        .replace(THINKING, "")
        .replace(MEMORIES, "")
        .replace(TOOL_XML, "")
        .replace(CONTROL_TOKEN, "")
        .replace(DIRECTIVE, "")
        .trim()
        .takeUnless { it.equals("NO_REPLY", ignoreCase = true) }
        .orEmpty()

    private fun extractText(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> buildString {
            for (index in 0 until content.length()) {
                val block = content.optJSONObject(index) ?: continue
                if (block.optString("type") == "text") {
                    if (isNotEmpty()) append('\n')
                    append(block.optString("text"))
                }
            }
        }
        else -> ""
    }

    private fun payload(body: String): JSONObject {
        val result = JSONObject(body).optJSONObject("result") ?: return JSONObject()
        result.optJSONObject("details")?.let { return it }
        val text = result.optJSONArray("content")?.optJSONObject(0)?.optString("text").orEmpty()
        return runCatching { JSONObject(text) }.getOrDefault(JSONObject())
    }
}
