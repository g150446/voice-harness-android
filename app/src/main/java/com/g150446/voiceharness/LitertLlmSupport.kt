package com.g150446.voiceharness

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Whether an engine may use MTP (multi-token prediction / speculative decoding).
 * [AUTO] enables it only when the model file actually ships a drafter; [DISABLED] never does.
 */
internal enum class SpeculativeDecodingMode { AUTO, DISABLED }

internal data class ReminderToolArgs(
    val title: String,
    val datetime: String,
    val ttsEnabled: Boolean
)

internal class ReminderToolSet(
    private val pendingReminder: AtomicReference<ReminderToolArgs?>
) : ToolSet {
    @Tool(description = "Set a reminder for the user at a specific date and time. Use this when the user wants to be reminded of something in the future.")
    fun set_reminder(
        @ToolParam(description = "A concise description of what to remind the user about")
        title: String,
        @ToolParam(description = "The target date and time in ISO 8601 format with Asia/Tokyo timezone (+09:00). If the user only mentions a time, assume today's date.")
        datetime: String,
        @ToolParam(description = "Whether to read the reminder aloud via TTS when the time comes. Default is false.")
        tts_enabled: Boolean = false
    ): Map<String, Any> {
        pendingReminder.set(
            ReminderToolArgs(
                title = title.trim(),
                datetime = datetime.trim(),
                ttsEnabled = tts_enabled
            )
        )
        Log.d("ReminderToolSet", "set_reminder title=$title datetime=$datetime tts=$tts_enabled")
        return mapOf(
            "status" to "ok",
            "title" to title,
            "datetime" to datetime,
            "tts_enabled" to tts_enabled
        )
    }
}

class GenerationTimedOutException(
    timeoutMs: Long
) : RuntimeException("On-device generation timed out after ${timeoutMs}ms")

internal object LitertLlmSupport {
    private const val TAG = "LitertLlmSupport"
    const val CHAT_TIMEOUT_MS = 20_000L
    const val ASR_TIMEOUT_MS = 120_000L
    private const val CANCEL_GRACE_MS = 5_000L

    /**
     * Some on-device models don't reliably emit a stop token (seen in the field: llama.cpp
     * logging "control-looking token ... was not control-type" for this same model family),
     * which can make generation run away forever. Run the blocking call on a worker thread and
     * force-cancel via [Conversation.cancelProcess] if it overruns, so a runaway generation fails
     * loudly instead of hanging forever and starving every later request queued on the engine's
     * mutex.
     */
    fun runGeneration(
        conversation: Conversation,
        tag: String,
        timeoutMs: Long = CHAT_TIMEOUT_MS,
        block: () -> Message
    ): Message {
        val resultRef = AtomicReference<Message?>(null)
        val errorRef = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                resultRef.set(block())
            } catch (e: Throwable) {
                errorRef.set(e)
            }
        }.apply {
            name = "litertlm-generation"
            start()
        }

        worker.join(timeoutMs)
        if (worker.isAlive) {
            Log.w(tag, "Generation exceeded ${timeoutMs}ms — cancelling")
            try {
                conversation.cancelProcess()
            } catch (e: Exception) {
                Log.w(tag, "cancelProcess failed: ${e.message}")
            }
            worker.join(CANCEL_GRACE_MS)
            throw GenerationTimedOutException(timeoutMs)
        }
        errorRef.get()?.let { throw it }
        return resultRef.get() ?: error("On-device generation produced no response")
    }

    fun buildSystemPrompt(
        languageCode: String?,
        screenContext: ScreenContext? = null,
    ): String {
        val currentTimeStr = java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            Locale.US
        ).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Tokyo")
        }.format(java.util.Date())

        val detectedLanguage = languageCode
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { "Detected language: $it. " }
            .orEmpty()
        return "Reply in the user's language. $detectedLanguage" +
            "Use natural speech without markdown. ${GroqChatRequestBuilder.CONCISE_RESPONSE_INSTRUCTION} " +
            "For reminder requests, call set_reminder. Current time: $currentTimeStr Asia/Tokyo. " +
            "Resolve relative times from it; a time without a date means today. " +
            "Set tts_enabled only when spoken notification is requested." +
            ScreenContextPrompt.systemAppendix(screenContext?.withoutImage())
    }

    @OptIn(ExperimentalApi::class)
    fun createEngine(
        context: Context,
        modelPath: String,
        enableAudio: Boolean,
        preferGpu: Boolean,
        maxNumTokens: Int? = null,
        speculativeDecoding: SpeculativeDecodingMode = SpeculativeDecodingMode.DISABLED
    ): Engine {
        val mtpRequested = speculativeDecoding == SpeculativeDecodingMode.AUTO &&
            supportsSpeculativeDecoding(modelPath)

        fun initialize(backend: Backend, useMtp: Boolean): Engine {
            // enableSpeculativeDecoding is a global experimental flag, so assign it on every
            // attempt instead of only when enabling. That keeps one model's MTP setting from
            // leaking into the next engine loaded in this process.
            ExperimentalFlags.enableSpeculativeDecoding = useMtp
            val config = EngineConfig(
                modelPath = modelPath,
                backend = backend,
                audioBackend = if (enableAudio) Backend.CPU() else null,
                maxNumTokens = maxNumTokens,
                cacheDir = context.cacheDir.absolutePath
            )
            val engine = Engine(config)
            return try {
                engine.initialize()
                ModelManager.recordSpeculativeDecoding(useMtp)
                Log.d(TAG, "Engine initialized backend=${backend.name} mtp=$useMtp $modelPath")
                engine
            } catch (error: Throwable) {
                try {
                    engine.close()
                } catch (_: Exception) {
                    // Preserve the initialization error.
                }
                throw error
            }
        }

        fun initializeWithBackendFallback(useMtp: Boolean): Engine {
            if (!preferGpu) return initialize(Backend.CPU(), useMtp)
            return try {
                initialize(Backend.GPU(), useMtp)
            } catch (gpuError: Throwable) {
                Log.w(TAG, "GPU initialization failed for $modelPath; retrying on CPU", gpuError)
                initialize(Backend.CPU(), useMtp)
            }
        }

        return try {
            initializeWithBackendFallback(mtpRequested)
        } catch (error: Throwable) {
            if (!mtpRequested) throw error
            // A usable engine matters more than the speedup, so give up MTP rather than the load.
            Log.w(TAG, "MTP initialization failed for $modelPath; retrying without MTP", error)
            initializeWithBackendFallback(false)
        }
    }

    /**
     * Forcing the flag on for a model without a drafter fails, so gate on the model file itself.
     * Opens the model natively, so call this once per load rather than per request.
     */
    private fun supportsSpeculativeDecoding(modelPath: String): Boolean =
        try {
            Capabilities(modelPath).use { it.hasSpeculativeDecodingSupport() }
        } catch (e: Throwable) {
            Log.w(TAG, "MTP capability check failed for $modelPath: ${e.message}")
            false
        }

    fun runChat(
        engine: Engine,
        conversationHistory: List<ConversationTurn>,
        languageCode: String?,
        pendingReminder: AtomicReference<ReminderToolArgs?>,
        temperature: Double,
        tag: String,
        screenContext: ScreenContext? = null,
    ): ChatResult {
        pendingReminder.set(null)
        val systemPrompt = buildSystemPrompt(languageCode, screenContext)
        val started = System.currentTimeMillis()

        val history = conversationHistory.toList()
        if (history.isEmpty()) error("conversation history is empty")
        val last = history.last()
        require(last.role == "user") { "last turn must be user" }

        val initialMessages = history.dropLast(1).mapNotNull { turn ->
            when (turn.role) {
                "user" -> Message.user(turn.content)
                "assistant" -> Message.model(turn.content)
                else -> null
            }
        }

        engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                initialMessages = initialMessages,
                tools = listOf(tool(ReminderToolSet(pendingReminder))),
                samplerConfig = SamplerConfig(
                    topK = 20,
                    topP = 0.9,
                    temperature = temperature
                ),
                extraContext = mapOf("enable_thinking" to false)
            )
        ).use { conversation ->
            val response = runGeneration(conversation, tag, CHAT_TIMEOUT_MS) {
                conversation.sendMessage(last.content)
            }
            val latency = System.currentTimeMillis() - started
            val performance = readBenchmarkInfo(conversation, tag)
            ModelManager.recordChatMetrics(
                latencyMs = latency,
                timeToFirstTokenMs = performance.timeToFirstTokenMs,
                prefillTokensPerSecond = performance.prefillTokensPerSecond,
                decodeTokensPerSecond = performance.decodeTokensPerSecond
            )

            val reminder = pendingReminder.getAndSet(null)
            val toolCalls = if (reminder != null) {
                listOf(
                    ChatToolCall(
                        name = "set_reminder",
                        argumentsJson = org.json.JSONObject()
                            .put("title", reminder.title)
                            .put("datetime", reminder.datetime)
                            .put("tts_enabled", reminder.ttsEnabled)
                            .toString()
                    )
                )
            } else {
                emptyList()
            }

            val text = response.toString().trim()
            Log.d(
                tag,
                "Chat done in ${latency} ms ttft=${performance.timeToFirstTokenMs}ms " +
                    "prefill=${performance.prefillTokenCount}@${"%.1f".format(performance.prefillTokensPerSecond)}tok/s " +
                    "decode=${performance.decodeTokenCount}@${"%.1f".format(performance.decodeTokensPerSecond)}tok/s " +
                    "tools=${toolCalls.size} text='${text.take(120)}'"
            )
            return ChatResult(
                text = text,
                toolCalls = toolCalls,
                latencyMs = latency,
                performance = performance
            )
        }
    }

    /**
     * LiteRT-LM 0.14 exposes benchmark getters in bytecode but not in its Kotlin metadata.
     * Reflection keeps the metrics optional and avoids tying inference to that metadata issue.
     */
    private fun readBenchmarkInfo(conversation: Conversation, tag: String): ChatPerformance =
        try {
            val benchmark = conversation.javaClass
                .getMethod("getBenchmarkInfo")
                .invoke(conversation)
            val type = benchmark.javaClass
            fun doubleValue(method: String): Double =
                (type.getMethod(method).invoke(benchmark) as Number).toDouble()
            fun intValue(method: String): Int =
                (type.getMethod(method).invoke(benchmark) as Number).toInt()

            ChatPerformance(
                timeToFirstTokenMs = (doubleValue("getTimeToFirstTokenInSecond") * 1000).toLong(),
                prefillTokenCount = intValue("getLastPrefillTokenCount"),
                decodeTokenCount = intValue("getLastDecodeTokenCount"),
                prefillTokensPerSecond = doubleValue("getLastPrefillTokensPerSecond"),
                decodeTokensPerSecond = doubleValue("getLastDecodeTokensPerSecond")
            )
        } catch (e: Exception) {
            Log.w(tag, "Unable to read LiteRT-LM benchmark info: ${e.message}")
            ChatPerformance()
        }
}
