package com.g150446.voiceharness

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

object OpenClawChatRequestBuilder {
    const val MODEL = "openclaw/default"

    fun buildRequestBody(request: ChatRequest): String {
        val includeHarbor = request.harborToolEnabled || request.forceHarborCommand
        val systemPrompt = GroqChatRequestBuilder.buildSystemPromptForOpenRouter(
            languageCode = request.languageCode,
            currentTimeMillis = System.currentTimeMillis(),
            screenContext = request.screenContext,
            includeHarbor = includeHarbor,
            harborOnly = request.forceHarborCommand,
            harborContext = request.harborContext,
        )
        val latestUser = request.conversationHistory.lastOrNull { it.role == "user" }
            ?: error("OpenClaw に送るユーザーメッセージがありません。")
        return JSONObject().apply {
            put("model", MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().apply {
                    put("role", "user")
                    if (request.screenContext?.hasImage == true) {
                        put("content", multimodalContent(latestUser.content, request.screenContext.jpegBytes!!))
                    } else {
                        put("content", latestUser.content)
                    }
                })
            })
            if (includeHarbor) {
                put(
                    "tools",
                    GroqChatRequestBuilder.buildToolsArray(
                        includeHarbor = true,
                        harborOnly = request.forceHarborCommand,
                    ),
                )
                put(
                    "tool_choice",
                    if (request.forceHarborCommand) {
                        JSONObject()
                            .put("type", "function")
                            .put("function", JSONObject().put("name", HARBOR_COMMAND_TOOL_NAME))
                    } else {
                        "auto"
                    },
                )
            }
        }.toString()
    }

    private fun multimodalContent(text: String, jpeg: ByteArray): JSONArray = JSONArray()
        .put(JSONObject().put("type", "text").put("text", text))
        .put(
            JSONObject().put("type", "image_url").put(
                "image_url",
                JSONObject().put(
                    "url",
                    "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(jpeg)}",
                ),
            ),
        )

    fun parseChatResponse(body: String): ChatResult {
        val message = JSONObject(body)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?: error("OpenClaw の応答にメッセージがありません。")
        val toolCalls = buildList {
            val calls = message.optJSONArray("tool_calls") ?: JSONArray()
            for (index in 0 until calls.length()) {
                val function = calls.optJSONObject(index)?.optJSONObject("function") ?: continue
                val name = function.optString("name").trim()
                if (name.isNotEmpty()) {
                    add(ChatToolCall(name, function.optString("arguments", "{}")))
                }
            }
        }
        return ChatResult(
            text = message.optString("content", "").trim(),
            toolCalls = toolCalls,
        )
    }

    fun safeHttpError(code: Int, body: String?): String {
        val label = when (code) {
            401 -> "認証に失敗しました。Gateway token を確認してください。"
            403 -> "Gateway にこの操作の権限がありません。"
            404 -> "Chat Completions が見つかりません。Gateway 側で有効化してください。"
            429 -> "Gateway の要求上限に達しました。しばらく待ってください。"
            in 500..599 -> "Gateway でエラーが発生しました。"
            else -> "Gateway への要求に失敗しました。"
        }
        // Gateway error bodies are deliberately not echoed: auth servers sometimes reflect credentials.
        return "OpenClaw error $code: $label"
    }
}
