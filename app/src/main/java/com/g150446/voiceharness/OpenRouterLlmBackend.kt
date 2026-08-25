package com.g150446.voiceharness

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OpenRouterLlmBackend(
    private val appContext: Context,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val httpClient: OkHttpClient = defaultClient(),
) : LlmBackend {

    override val name: String = "OpenRouter"

    private val activeCall = AtomicReference<Call?>(null)

    override suspend fun ensureReady(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!OpenRouterPrefs.hasApiKey(appContext)) {
                error("OpenRouter API キーが未設定です。モデル設定で入力してください。")
            }
            val modelId = OpenRouterPrefs.getModelId(appContext)
            if (modelId.isBlank()) {
                error("OpenRouter モデルが未選択です。モデル設定で選択してください。")
            }
        }
    }

    override suspend fun chat(request: ChatRequest): Result<ChatResult> = withContext(Dispatchers.IO) {
        runCatching {
            val apiKey = OpenRouterPrefs.getApiKey(appContext)
            if (apiKey.isBlank()) error("OpenRouter API キーが未設定です。")
            val modelId = OpenRouterPrefs.getModelId(appContext)
            if (modelId.isBlank()) error("OpenRouter モデルが未選択です。")

            val models = loadModelsForCapabilities()
            val selected = models.firstOrNull { it.id == modelId }
            if (models.isNotEmpty() && selected == null) {
                error("選択中のモデルがカタログにありません。モデル設定で再選択してください。")
            }
            val supportsTools = selected?.supportsTools ?: false
            val supportsImage = selected?.supportsImage ?: false

            val started = System.currentTimeMillis()
            val body = OpenRouterChatRequestBuilder.buildRequestBody(
                modelId = modelId,
                conversationHistory = request.conversationHistory,
                languageCode = request.languageCode,
                screenContext = request.screenContext,
                supportsTools = supportsTools,
                supportsImage = supportsImage,
            )
            val call = httpClient.newCall(
                Request.Builder()
                    .url("$baseUrl/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("HTTP-Referer", "https://github.com/g150446/voice-harness-android")
                    .addHeader("X-Title", "Voice Harness")
                    .post(body.toRequestBody(JSON_MEDIA))
                    .build()
            )
            activeCall.set(call)
            try {
                call.execute().use { response ->
                    val responseText = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        error(OpenRouterChatRequestBuilder.safeHttpError(response.code, responseText))
                    }
                    val parsed = OpenRouterChatRequestBuilder.parseChatResponse(responseText)
                    val latencyMs = System.currentTimeMillis() - started
                    ModelManager.recordChatMs(latencyMs)
                    Log.d(
                        TAG,
                        "Chat ok latency=${latencyMs}ms tools=${parsed.toolCalls.size} text='${parsed.text.take(80)}'",
                    )
                    parsed.copy(latencyMs = latencyMs)
                }
            } finally {
                activeCall.compareAndSet(call, null)
            }
        }
    }

    suspend fun fetchModels(forceRefresh: Boolean = false): Result<List<OpenRouterModel>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cachedAt = OpenRouterPrefs.getModelsCacheAt(appContext)
                val cachedJson = OpenRouterPrefs.getModelsCacheJson(appContext)
                if (!forceRefresh &&
                    cachedJson != null &&
                    OpenRouterModelCatalog.isCacheFresh(cachedAt)
                ) {
                    return@runCatching OpenRouterModelCatalog.parseModelsJson(cachedJson)
                }
                val apiKey = OpenRouterPrefs.getApiKey(appContext)
                if (apiKey.isBlank()) error("OpenRouter API キーが未設定です。")
                val call = httpClient.newCall(
                    Request.Builder()
                        .url("$baseUrl/models")
                        .addHeader("Authorization", "Bearer $apiKey")
                        .get()
                        .build()
                )
                call.execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        if (cachedJson != null) {
                            Log.w(TAG, "Models refresh failed; keeping cache")
                            return@runCatching OpenRouterModelCatalog.parseModelsJson(cachedJson)
                        }
                        error(OpenRouterChatRequestBuilder.safeHttpError(response.code, body))
                    }
                    // Validate parse before overwriting cache.
                    val models = OpenRouterModelCatalog.parseModelsJson(body)
                    OpenRouterPrefs.setModelsCache(appContext, body)
                    models
                }
            }
        }

    private fun loadModelsForCapabilities(): List<OpenRouterModel> {
        val cached = OpenRouterPrefs.getModelsCacheJson(appContext) ?: return emptyList()
        return try {
            OpenRouterModelCatalog.parseModelsJson(cached)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun cancel() {
        activeCall.getAndSet(null)?.cancel()
    }

    override fun release() {
        cancel()
    }

    companion object {
        private const val TAG = "OpenRouterLlmBackend"
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }
}
