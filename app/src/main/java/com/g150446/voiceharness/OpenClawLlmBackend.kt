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
import org.json.JSONObject

internal class OpenClawApiClient(
    private val baseUrl: String,
    private val token: String,
    private val sessionKey: String,
    private val httpClient: OkHttpClient,
    /** Base for Harbor-scoped sessions; the app-owned key even when chat uses another one. */
    private val harborSessionBase: String = sessionKey,
    /** Agent target for ordinary chat. */
    private val model: String = OpenClawChatRequestBuilder.MODEL,
    /** Agent target for Harbor interpretation, which may differ from the chat agent. */
    private val harborModel: String = OpenClawChatRequestBuilder.MODEL,
) {
    private val activeCall = AtomicReference<Call?>(null)

    fun chat(request: ChatRequest): ChatResult {
        val harborScoped = request.harborToolEnabled || request.forceHarborCommand
        val target = if (harborScoped) harborModel else model
        val key = agentScopedSessionKey(
            target,
            if (harborScoped) {
                harborSessionKey(harborSessionBase, request.harborContext?.workspaceId)
            } else {
                sessionKey
            },
        )
        val body = OpenClawChatRequestBuilder.buildRequestBody(request, model = target)
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
            // Also check the configured targets: on a multi-agent Gateway the generic
            // `openclaw/default` exists but names no agent, so "it is listed" is not enough.
            if (data.none { it == "openclaw" || it == OpenClawChatRequestBuilder.MODEL }) {
                error("Gateway は応答しましたが OpenClaw agent target がありません。")
            }
            val missing = listOf(model, harborModel)
                .filter { openClawAgentId(it) != null }
                .distinct()
                .filterNot(data::contains)
            if (missing.isNotEmpty()) {
                error("Gateway に agent ${missing.joinToString(", ")} がありません。")
            }
            "OpenClaw Gateway に接続しました。"
        }
    }

    /** Newest tail (up to [limit] messages) of [sessionKey] via `POST /tools/invoke` `sessions_history`. */
    fun fetchHistory(sessionKey: String, limit: Int = HISTORY_LIMIT): List<OpenClawHistoryMessage> =
        invokeTool("sessions_history", JSONObject().put("sessionKey", sessionKey).put("limit", limit))
            .let(OpenClawHistoryParser::parseHistory)

    /** Sessions visible to the Gateway operator, most recently updated first. */
    fun listSessions(limit: Int = SESSION_LIST_LIMIT): List<OpenClawSessionInfo> =
        invokeTool(
            "sessions_list",
            JSONObject()
                .put("limit", limit)
                .put("includeDerivedTitles", true)
                .put("includeLastMessage", true),
        ).let(OpenClawHistoryParser::parseSessions)

    fun cancel() {
        activeCall.getAndSet(null)?.cancel()
    }

    // Deliberately not tracked in activeCall: cancel() is for the in-flight chat turn only.
    private fun invokeTool(tool: String, args: JSONObject): String {
        // A Gateway running several agents refuses a tool call it cannot attribute to one,
        // so the calling session travels with the request. Harmless on a single-agent one.
        val body = JSONObject()
            .put("tool", tool)
            .put("args", args)
            .put("sessionKey", agentScopedSessionKey(model, sessionKey))
            .toString()
        val call = httpClient.newBuilder()
            .callTimeout(TOOL_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
            .newCall(
                authorizedRequest("${normalizedBaseUrl()}/tools/invoke")
                    .post(body.toRequestBody(JSON_MEDIA))
                    .build(),
            )
        call.execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                error(
                    OpenClawChatRequestBuilder.safeHttpError(
                        response.code,
                        text,
                        notFound = "$tool が許可されていません。Gateway の tools.allow を確認してください。",
                    ),
                )
            }
            return text
        }
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
        const val HISTORY_LIMIT = 100
        const val SESSION_LIST_LIMIT = 30
        const val TOOL_CALL_TIMEOUT_SECONDS = 20L
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

    suspend fun loadHistory(): Result<List<OpenClawHistoryMessage>> = withContext(Dispatchers.IO) {
        runCatching {
            val client = configuredClient()
            // The same key the chat turns go to, agent scope included, or the history read
            // would look up a session that was never written.
            client.fetchHistory(
                agentScopedSessionKey(
                    OpenClawPrefs.getAgent(appContext),
                    OpenClawPrefs.getChatSessionKey(appContext),
                ),
            )
        }
    }

    suspend fun listSessions(): Result<List<OpenClawSessionInfo>> = withContext(Dispatchers.IO) {
        runCatching { configuredClient().listSessions() }
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
            sessionKey = OpenClawPrefs.getChatSessionKey(appContext),
            httpClient = httpClient,
            harborSessionBase = OpenClawPrefs.getOrCreateSessionKey(appContext),
            model = OpenClawPrefs.getAgent(appContext),
            harborModel = OpenClawPrefs.getHarborAgent(appContext),
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
