package com.g150446.voiceharness

import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Interprets Harbor voice commands with an OpenClaw session scoped to one workspace.
 *
 * This is a separate entry point from the OpenClaw interaction mode: Harbor mode keeps its own
 * LLM selection, and the Gateway is reached here even though [isOpenClawRoute] is false.
 *
 * OpenClaw only *decides*. Execution stays on the phone behind the tap confirmation, so the
 * Gateway's own `harbor_send_*` tools are not involved and an agent that decided to act on its
 * own could still not reach the terminal through this path.
 *
 * It also holds its own HTTP client. The chat client waits up to 210 s, which is fine for a
 * conversation but not for a glass that is showing 「解析中…」; past that the user is better
 * served by the old cloud interpreter than by a longer wait.
 */
internal class OpenClawHarborInterpreter(
    private val appContext: Context,
    private val httpClient: OkHttpClient = defaultClient(),
) {
    private val activeClient = AtomicReference<OpenClawApiClient?>(null)

    fun isConfigured(): Boolean {
        val baseUrl = OpenClawPrefs.getBaseUrl(appContext)
        return OpenClawPrefs.getToken(appContext).isNotBlank() &&
            (baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))
    }

    suspend fun interpret(request: ChatRequest): Result<ChatResult> = withContext(Dispatchers.IO) {
        runCatching {
            val started = System.currentTimeMillis()
            val result = client().also(activeClient::set).chat(request)
            Log.i(TAG, "Harbor interpret latency=${System.currentTimeMillis() - started}ms")
            result
        }
    }

    fun cancel() {
        activeClient.getAndSet(null)?.cancel()
    }

    private fun client(): OpenClawApiClient = OpenClawApiClient(
        baseUrl = OpenClawPrefs.getBaseUrl(appContext),
        token = OpenClawPrefs.getToken(appContext),
        // Irrelevant here — every request from this class is Harbor-scoped — but a sane value
        // keeps the client from ever addressing someone's chat session by accident.
        sessionKey = OpenClawPrefs.getOrCreateSessionKey(appContext),
        httpClient = httpClient,
        harborSessionBase = OpenClawPrefs.getOrCreateSessionKey(appContext),
        model = OpenClawPrefs.getAgent(appContext),
        harborModel = OpenClawPrefs.getHarborAgent(appContext),
    )

    companion object {
        private const val TAG = "OpenClawHarbor"
        const val TIMEOUT_MS = 15_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }
}
