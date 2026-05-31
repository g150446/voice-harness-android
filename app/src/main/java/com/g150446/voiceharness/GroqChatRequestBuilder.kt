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

    // --- Function Calling (Reminder) ---

    fun buildRequestBodyWithFunctionCalling(
        conversationHistory: List<ConversationTurn>,
        languageCode: String?
    ): String {
        val currentTimeMillis = System.currentTimeMillis()
        return JSONObject().apply {
            put("model", CHAT_MODEL)
            put("messages", JSONArray().apply {
                buildSystemPromptWithReminders(languageCode, currentTimeMillis)?.let { prompt ->
                    put(JSONObject().apply {
                        put("role", "system")
                        put("content", prompt)
                    })
                }
                conversationHistory.forEach { turn ->
                    put(JSONObject().apply {
                        put("role", turn.role)
                        put("content", turn.content)
                    })
                }
            })
            put("tools", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "function")
                    put("function", JSONObject().apply {
                        put("name", "set_reminder")
                        put("description", "Set a reminder for the user at a specific date and time. Use this when the user wants to be reminded of something in the future.")
                        put("parameters", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("title", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "A concise description of what to remind the user about")
                                })
                                put("datetime", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "The target date and time in ISO 8601 format with Asia/Tokyo timezone (+09:00). If the user only mentions a time (e.g., '3時'), assume today's date. If the user mentions a relative time (e.g., '30分後'), calculate the absolute time from now.")
                                })
                                put("tts_enabled", JSONObject().apply {
                                    put("type", "boolean")
                                    put("description", "Whether to read the reminder aloud via TTS when the time comes. Set to true if the user says something like '読み上げして', 'speak it aloud', 'notify with voice', etc. Default is false.")
                                })
                            })
                            put("required", JSONArray().apply {
                                put("title")
                                put("datetime")
                            })
                        })
                    })
                })
            })
            put("tool_choice", "auto")
        }.toString()
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
            "Do not translate unless the user explicitly asks for translation. " +
            "This response will be read aloud as speech, so write in natural spoken language. " +
            "Do not use markdown, bullet points, numbered lists, headers, or special characters. " +
            "Use short, clear sentences. Avoid parenthetical asides. " +
            "Keep responses brief unless the user explicitly asks for a detailed explanation."
    }

    private fun buildSystemPromptWithReminders(languageCode: String?, currentTimeMillis: Long): String? {
        val basePrompt = buildSystemPrompt(languageCode)
        val currentTimeStr = formatCurrentTimeJst(currentTimeMillis)
        val reminderInstructions = "You can set reminders for the user by calling the set_reminder function. " +
            "When the user asks to set a reminder, always call the function rather than just saying you will remember. " +
            "The current date and time is $currentTimeStr (Asia/Tokyo, UTC+09:00). Use this as the reference for all relative time calculations. " +
            "If the user asks for the current time, respond with only the hours and minutes (e.g., '午後3時15分' or '15時15分'), without the date or seconds. " +
            "If the user asks for today's date, respond with only the year, month, and day (e.g., '2026年5月31日'), without the time. " +
            "If the user mentions a time without a date, assume today based on the current time. " +
            "If the user says something like '3時に会議', set the reminder for 3 PM today. " +
            "If the time is ambiguous (e.g., just '3時' without AM/PM context), use your best judgment based on common usage. " +
            "If the user says something like '読み上げして', 'speak it aloud', 'notify with voice', or similar, set tts_enabled to true. " +
            "If critical information like the title or exact time is missing, ask the user for clarification in a natural way."
        return if (basePrompt != null) {
            "$basePrompt $reminderInstructions"
        } else {
            reminderInstructions
        }
    }

    private fun formatCurrentTimeJst(millis: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("Asia/Tokyo")
        return sdf.format(java.util.Date(millis))
    }
}
