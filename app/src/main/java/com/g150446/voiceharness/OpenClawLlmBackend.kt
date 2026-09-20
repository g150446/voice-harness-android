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

internal class OpenClawApiClient(
    private val baseUrl: String,
    private val token: String,
    private val sessionKey: String,
    private val httpClient: OkHttpClient,
) {
    private val activeCall = AtomicReference<Call?>(null)

    fun chat(request: ChatRequest): ChatResult {
        val key = if (request.harborToolEnabled || request.forceHarborCommand) {
            "$sessionKey:harbor"
        } else {
            sessionKey
        }
        val body = OpenClawChatRequestBuilder.buildRequestBody(request)
        val call = httpClient.newCall(
            authorizedRequest("${normalizedBaseUrl()}/v1/chat/completions")
                .addHeader("x-openclaw-session-key", key)
                .post(body.toRequestBody(JSON_MEDIA))
                .build(),
        )
        return execute(call) { responseBody -> OpenClawChatRequestBuilder.parseChatResponse(responseBody) }
    }

    fun testConnection(): String {
        val call = httpClient.newCall(
            authorizedRequest("${normalizedBaseUrl()}/v1/models").get().build(),
        )
        return execute(call) { body ->
            val data = JSONObjectCompat.modelIds(body)
            if (data.none { it == "openclaw" || it == OpenClawChatRequestBuilder.MODEL }) {
                error("Gateway は応答しましたが OpenClaw agent target がありません。")
            }
            "OpenClaw Gateway に接続しました。"
        }
    }

    fun cancel() {
        activeCall.getAndSet(null)?.cancel()
    }

    private fun authorizedRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .addHeader("Authorization", "Bearer $token")
        .addHeader("Content-Type", "application/json")

    private fun normalizedBaseUrl(): String {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.startsWith("http://") || normalized.startsWith("https://")) {
            "Gateway URL は http:// または https:// で入力してください。"
        }
        return normalized
    }

    private fun <T> execute(call: Call, parse: (String) -> T): T {
        activeCall.set(call)
        try {
            call.execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    error(OpenClawChatRequestBuilder.safeHttpError(response.code, body))
                }
                return parse(body)
            }
        } finally {
            activeCall.compareAndSet(call, null)
        }
    }

    private object JSONObjectCompat {
        fun modelIds(body: String): List<String> {
            val data = org.json.JSONObject(body).optJSONArray("data") ?: org.json.JSONArray()
            return buildList {
                for (index in 0 until data.length()) {
                    data.optJSONObject(index)?.optString("id")?.takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}

class OpenClawLlmBackend(
    private val appContext: Context,
    private val httpClient: OkHttpClient = defaultClient(),
) : LlmBackend {
    override val name: String = "OpenClaw"
    private val activeClient = AtomicReference<OpenClawApiClient?>(null)

    override suspend fun ensureReady(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            configuredClient()
            Unit
        }
    }

    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        runCatching { configuredClient().also(activeClient::set).testConnection() }
    }

    override suspend fun chat(request: ChatRequest): Result<ChatResult> = withContext(Dispatchers.IO) {
        runCatching {
            val started = System.currentTimeMillis()
            val result = configuredClient().also(activeClient::set).chat(request)
            val latencyMs = System.currentTimeMillis() - started
            ModelManager.recordChatMs(latencyMs)
            Log.d(TAG, "Chat ok latency=${latencyMs}ms textLength=${result.text.length}")
            result.copy(latencyMs = latencyMs)
        }
    }

    override fun cancel() {
        activeClient.getAndSet(null)?.cancel()
    }

    override fun release() = cancel()

    private fun configuredClient(): OpenClawApiClient {
        val token = OpenClawPrefs.getToken(appContext)
        if (token.isBlank()) error("OpenClaw Gateway token が未設定です。")
        val baseUrl = OpenClawPrefs.getBaseUrl(appContext)
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            error("Gateway URL は http:// または https:// で入力してください。")
        }
        return OpenClawApiClient(
            baseUrl = baseUrl,
            token = token,
            sessionKey = OpenClawPrefs.getOrCreateSessionKey(appContext),
            httpClient = httpClient,
        )
    }

    companion object {
        private const val TAG = "OpenClawLlmBackend"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(210, TimeUnit.SECONDS)
            .build()
    }
}
