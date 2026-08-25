package com.g150446.voiceharness

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

object OpenRouterChatRequestBuilder {
    fun buildRequestBody(
        modelId: String,
        conversationHistory: List<ConversationTurn>,
        languageCode: String?,
        screenContext: ScreenContext?,
        supportsTools: Boolean,
        supportsImage: Boolean,
    ): String {
        val currentTimeMillis = System.currentTimeMillis()
        val systemPrompt = GroqChatRequestBuilder.buildSystemPromptForOpenRouter(
            languageCode = languageCode,
            currentTimeMillis = currentTimeMillis,
            screenContext = screenContext,
        )
        return JSONObject().apply {
            put("model", modelId)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                conversationHistory.forEachIndexed { index, turn ->
                    val isLastUser = index == conversationHistory.lastIndex && turn.role == "user"
                    put(JSONObject().apply {
                        put("role", turn.role)
                        if (isLastUser && supportsImage && screenContext?.hasImage == true) {
                            put(
                                "content",
                                buildMultimodalUserContent(turn.content, screenContext.jpegBytes!!),
                            )
                        } else {
                            put("content", turn.content)
                        }
                    })
                }
            })
            if (supportsTools) {
                put("tools", reminderToolsArray())
                put("tool_choice", "auto")
            }
        }.toString()
    }

    private fun buildMultimodalUserContent(text: String, jpegBytes: ByteArray): JSONArray {
        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        return JSONArray().apply {
            // Text before image per plan.
            put(JSONObject().apply {
                put("type", "text")
                put("text", text)
            })
            put(JSONObject().apply {
                put("type", "image_url")
                put(
                    "image_url",
                    JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$b64")
                    },
                )
            })
        }
    }

    private fun reminderToolsArray(): JSONArray = JSONArray().apply {
        put(JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", "set_reminder")
                put(
                    "description",
                    "Set a reminder for the user at a specific date and time. " +
                        "Use this when the user wants to be reminded of something in the future.",
                )
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("title", JSONObject().apply {
                            put("type", "string")
                            put("description", "A concise description of what to remind the user about")
                        })
                        put("datetime", JSONObject().apply {
                            put("type", "string")
                            put(
                                "description",
                                "The target date and time in ISO 8601 format with Asia/Tokyo timezone (+09:00).",
                            )
                        })
                        put("tts_enabled", JSONObject().apply {
                            put("type", "boolean")
                            put("description", "Whether to read the reminder aloud via TTS. Default false.")
                        })
                    })
                    put("required", JSONArray().apply {
                        put("title")
                        put("datetime")
                    })
                })
            })
        })
    }

    fun parseChatResponse(responseBody: String): ChatResult {
        val root = JSONObject(responseBody)
        val choices = root.optJSONArray("choices")
        val messageObj = if (choices != null && choices.length() > 0) {
            choices.getJSONObject(0).optJSONObject("message")
        } else {
            null
        }
        if (messageObj == null) {
            return ChatResult(text = "")
        }
        val toolCalls = mutableListOf<ChatToolCall>()
        val toolCallsJson = messageObj.optJSONArray("tool_calls")
        if (toolCallsJson != null) {
            for (i in 0 until toolCallsJson.length()) {
                val call = toolCallsJson.optJSONObject(i) ?: continue
                val function = call.optJSONObject("function") ?: continue
                val name = function.optString("name", "").trim()
                if (name.isEmpty()) continue
                toolCalls += ChatToolCall(
                    name = name,
                    argumentsJson = function.optString("arguments", "{}"),
                )
            }
        }
        val content = messageObj.optString("content", "").trim()
        return ChatResult(text = content, toolCalls = toolCalls)
    }

    fun safeHttpError(code: Int, body: String?): String {
        val snippet = body
            ?.lineSequence()
            ?.firstOrNull()
            ?.take(120)
            ?.replace(Regex("sk-[A-Za-z0-9_-]+"), "[redacted]")
            .orEmpty()
        return "OpenRouter error $code${if (snippet.isNotEmpty()) ": $snippet" else ""}"
    }
}
