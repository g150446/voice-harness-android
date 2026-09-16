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
        harborToolEnabled: Boolean = false,
        forceHarborCommand: Boolean = false,
        harborContext: HarborInterpretContext? = null,
    ): String {
        val currentTimeMillis = System.currentTimeMillis()
        val includeHarbor = supportsTools && (harborToolEnabled || forceHarborCommand)
        val harborOnly = supportsTools && forceHarborCommand
        val systemPrompt = GroqChatRequestBuilder.buildSystemPromptForOpenRouter(
            languageCode = languageCode,
            currentTimeMillis = currentTimeMillis,
            screenContext = screenContext,
            includeHarbor = includeHarbor,
            harborOnly = harborOnly,
            harborContext = harborContext,
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
                put(
                    "tools",
                    GroqChatRequestBuilder.buildToolsArray(
                        includeHarbor = includeHarbor,
                        harborOnly = harborOnly,
                    ),
                )
                if (forceHarborCommand) {
                    put(
                        "tool_choice",
                        JSONObject().apply {
                            put("type", "function")
                            put(
                                "function",
                                JSONObject().apply { put("name", HARBOR_COMMAND_TOOL_NAME) },
                            )
                        },
                    )
                } else {
                    put("tool_choice", "auto")
                }
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
