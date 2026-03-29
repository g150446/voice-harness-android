package com.g150446.voiceharness

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object GroqChatRequestBuilder {

    private const val CHAT_MODEL = "openai/gpt-oss-120b"

    data class ChatMessageSpec(
        val role: String,
        val content: String
    )

    fun buildRequestBody(userText: String, languageCode: String?): String =
        JSONObject().apply {
            put("model", CHAT_MODEL)
            put("messages", JSONArray().apply {
                buildMessageSpecs(userText, languageCode).forEach { message ->
                    put(JSONObject().apply {
                        put("role", message.role)
                        put("content", message.content)
                    })
                }
            })
        }.toString()

    fun buildMessageSpecs(userText: String, languageCode: String?): List<ChatMessageSpec> =
        buildList {
            buildSystemPrompt(languageCode)?.let { prompt ->
                add(ChatMessageSpec(role = "system", content = prompt))
            }
            add(ChatMessageSpec(role = "user", content = userText))
        }

    private fun buildSystemPrompt(languageCode: String?): String? {
        val normalizedCode = languageCode
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.lowercase(Locale.ROOT)
            ?: return null
        val locale = Locale.forLanguageTag(normalizedCode)
        val languageTag = locale.toLanguageTag().takeIf { it.isNotBlank() && it != "und" } ?: normalizedCode
        val languageName = locale.getDisplayLanguage(Locale.ENGLISH).ifBlank { languageTag }

        return "Respond in the same language as the user's transcribed request. " +
            "The detected input language is $languageName ($languageTag). " +
            "Do not translate unless the user explicitly asks for translation."
    }
}
