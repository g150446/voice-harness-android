package com.g150446.voiceharness

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Cloud backend: Groq Whisper STT + Chat Completions (with set_reminder tools).
 */
class GroqVoiceAiBackend(
    private val appContext: Context
) : VoiceAiBackend {

    override val name: String = "Groq"
    override val profile: OnDeviceProfile = OnDeviceProfile.GROQ

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    override suspend fun ensureReady(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!GroqPrefs.hasApiKey(appContext)) {
                error("Groq API キーが未設定です。モデル設定で入力してください。")
            }
            ModelManager.markCloudReady(appContext)
        }
    }

    override suspend fun transcribe(
        audioFile: File,
        vocabulary: List<AsrVocabularyTerm>
    ): Result<TranscriptionResult> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = requireApiKey()
            val started = System.currentTimeMillis()
            val mimeType = when (audioFile.extension.lowercase()) {
                "wav" -> "audio/wav"
                "m4a", "mp4" -> "audio/mp4"
                "ogg" -> "audio/ogg"
                else -> "audio/wav"
            }
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody(mimeType.toMediaType())
                )
                .addFormDataPart("model", WHISPER_MODEL)
                .addFormDataPart("response_format", "json")
                .build()

            val response = httpClient.newCall(
                Request.Builder()
                    .url(TRANSCRIPTIONS_URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(body)
                    .build()
            ).execute()

            val responseText = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                error("Whisper error ${response.code}: ${responseText.take(200)}")
            }

            val payload = parseTranscriptionPayload(responseText)
            val latencyMs = System.currentTimeMillis() - started
            ModelManager.recordAsrMs(latencyMs)
            Log.d(TAG, "Whisper ok latency=${latencyMs}ms lang=${payload.languageCode}")
            TranscriptionResult(
                text = payload.text,
                languageCode = payload.languageCode,
                latencyMs = latencyMs
            )
        }
    }

    override suspend fun chat(
        conversationHistory: List<ConversationTurn>,
        languageCode: String?
    ): Result<ChatResult> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = requireApiKey()
            val started = System.currentTimeMillis()
            val chatJson = GroqChatRequestBuilder.buildRequestBodyWithFunctionCalling(
                conversationHistory = conversationHistory,
                languageCode = languageCode
            )
            val response = httpClient.newCall(
                Request.Builder()
                    .url(CHAT_COMPLETIONS_URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(chatJson.toRequestBody(JSON_MEDIA))
                    .build()
            ).execute()

            val responseText = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                error("Chat error ${response.code}: ${responseText.take(200)}")
            }

            val parsed = parseChatResponse(responseText)
            val latencyMs = System.currentTimeMillis() - started
            ModelManager.recordChatMs(latencyMs)
            Log.d(
                TAG,
                "Chat ok latency=${latencyMs}ms tools=${parsed.toolCalls.size} text='${parsed.text.take(80)}'"
            )
            ChatResult(
                text = parsed.text,
                toolCalls = parsed.toolCalls,
                latencyMs = latencyMs
            )
        }
    }

    override fun release() {
        // Stateless HTTP client; nothing to unload.
    }

    private fun requireApiKey(): String {
        val key = GroqPrefs.getApiKey(appContext)
        if (key.isBlank()) {
            error("Groq API キーが未設定です。モデル設定で入力してください。")
        }
        return key
    }

    private fun parseTranscriptionPayload(responseBody: String): TranscriptionPayload {
        val trimmedBody = responseBody.trim()
        if (trimmedBody.startsWith("{")) {
            val json = JSONObject(trimmedBody)
            val language = json.optString("language").trim().ifBlank { null }
            return TranscriptionPayload(
                text = json.optString("text").trim(),
                languageCode = language
            )
        }
        return TranscriptionPayload(text = trimmedBody, languageCode = null)
    }

    private fun parseChatResponse(responseBody: String): ParsedChat {
        val root = JSONObject(responseBody)
        val choices = root.optJSONArray("choices")
        val messageObj = if (choices != null && choices.length() > 0) {
            choices.getJSONObject(0).optJSONObject("message")
        } else {
            null
        }
        if (messageObj == null) {
            return ParsedChat(text = "", toolCalls = emptyList())
        }

        val toolCalls = mutableListOf<ChatToolCall>()
        val toolCallsJson = messageObj.optJSONArray("tool_calls")
        if (toolCallsJson != null) {
            for (i in 0 until toolCallsJson.length()) {
                val call = toolCallsJson.optJSONObject(i) ?: continue
                val function = call.optJSONObject("function") ?: continue
                val name = function.optString("name", "").trim()
                if (name.isEmpty()) continue
                val arguments = function.optString("arguments", "{}")
                toolCalls += ChatToolCall(name = name, argumentsJson = arguments)
            }
        }

        val content = messageObj.optString("content", "").trim()
        return ParsedChat(text = content, toolCalls = toolCalls)
    }

    private data class TranscriptionPayload(
        val text: String,
        val languageCode: String?
    )

    private data class ParsedChat(
        val text: String,
        val toolCalls: List<ChatToolCall>
    )

    private companion object {
        private const val TAG = "GroqVoiceAiBackend"
        private const val WHISPER_MODEL = "whisper-large-v3-turbo"
        private const val TRANSCRIPTIONS_URL =
            "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val CHAT_COMPLETIONS_URL =
            "https://api.groq.com/openai/v1/chat/completions"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
