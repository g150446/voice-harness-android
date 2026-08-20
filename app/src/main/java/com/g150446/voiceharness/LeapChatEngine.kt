package com.g150446.voiceharness

import android.content.Context
import android.util.Log
import ai.liquid.leap.Conversation
import ai.liquid.leap.GenerationOptions
import ai.liquid.leap.ModelRunner
import ai.liquid.leap.function.LeapFunction
import ai.liquid.leap.function.LeapFunctionCall
import ai.liquid.leap.function.LeapFunctionParameter
import ai.liquid.leap.function.LeapFunctionParameterType
import ai.liquid.leap.manifest.LeapDownloader
import ai.liquid.leap.manifest.LeapDownloaderConfig
import ai.liquid.leap.manifest.ModelSource
import ai.liquid.leap.message.ChatMessage
import ai.liquid.leap.message.ChatMessageContent
import ai.liquid.leap.message.MessageResponse
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * LFM 2.5 chat via Liquid LEAP. Independent from Qwen3-ASR llama.cpp natives.
 */
internal class LeapChatEngine(
    private val appContext: Context,
    private val tag: String = TAG
) {
    private var runner: ModelRunner? = null
    private var loadedModelPath: String? = null
    private val pendingReminder = AtomicReference<ReminderToolArgs?>(null)

    val isReady: Boolean
        get() = runner != null

    fun isLoaded(modelFile: File): Boolean =
        runner != null && loadedModelPath == modelFile.absolutePath

    fun ensureReady(modelFile: File, slot: ModelSlot): Result<Unit> = runCatching {
        require(modelFile.isFile) { "LFM GGUF missing: ${modelFile.absolutePath}" }
        val path = modelFile.absolutePath
        if (runner != null && loadedModelPath == path) {
            ModelManager.markSlotReady(slot, path, ModelManager.status.value.lastLoadMs)
            return@runCatching
        }

        release()
        ModelManager.markSlotLoading(slot, path)
        val started = System.currentTimeMillis()
        Log.d(tag, "Loading LFM Chat: $path (${ModelManager.formatSize(modelFile.length())})")
        val downloader = LeapDownloader(
            config = LeapDownloaderConfig(saveDir = File(appContext.cacheDir, "leap").absolutePath)
        )
        val loaded = runBlocking {
            downloader.loadSimpleModel(
                model = ModelSource(
                    modelPath = path,
                    modelName = MODEL_NAME,
                    quantizationId = QUANTIZATION_ID
                )
            )
        }
        runner = loaded
        loadedModelPath = path
        val loadMs = System.currentTimeMillis() - started
        ModelManager.markSlotReady(slot, path, loadMs)
        Log.d(tag, "LFM Chat loaded in $loadMs ms")
    }.onFailure { error ->
        Log.e(tag, "LFM Chat initialization failed", error)
        release()
    }

    fun chat(
        conversationHistory: List<ConversationTurn>,
        languageCode: String?
    ): ChatResult {
        val currentRunner = runner ?: error("LFM Chat engine is not ready")
        pendingReminder.set(null)
        val history = conversationHistory.toList()
        if (history.isEmpty()) error("conversation history is empty")
        val last = history.last()
        require(last.role == "user") { "last turn must be user" }

        val systemPrompt = LitertLlmSupport.buildSystemPrompt(languageCode)
        val conversation = currentRunner.createConversation(systemPrompt)
        try {
            conversation.registerFunction(setReminderFunction())
        } catch (e: Exception) {
            Log.w(tag, "set_reminder LEAP function registration failed; text-only chat. ${e.message}")
        }
        history.dropLast(1).forEach { turn ->
            conversation.appendToHistory(turn.toChatMessage())
        }

        val options = GenerationOptions.build {
            temperature = 0.2f
            enableThinking = false
            maxTokens = MAX_TOKENS
        }
        val text = StringBuilder()
        val toolCalls = mutableListOf<ChatToolCall>()
        val started = System.currentTimeMillis()
        var tokenPerSecond = 0.0
        var totalTokens = 0

        try {
            runBlocking {
                withTimeout(CHAT_TIMEOUT_MS) {
                    conversation.generateResponse(last.toChatMessage(), options).collect { response ->
                        when (response) {
                            is MessageResponse.Chunk -> text.append(response.text)
                            is MessageResponse.ReasoningChunk ->
                                Log.d(tag, "LFM reasoning: ${response.reasoning.take(120)}")
                            is MessageResponse.FunctionCalls -> {
                                response.functionCalls.forEach { call ->
                                    toolCalls += dispatchFunction(call)
                                }
                            }
                            is MessageResponse.Complete -> {
                                totalTokens = (response.stats?.totalTokens ?: 0L).toInt()
                                tokenPerSecond = response.stats?.tokenPerSecond?.toDouble() ?: 0.0
                            }
                            else -> {}
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw GenerationTimedOutException(CHAT_TIMEOUT_MS)
        }

        val latency = System.currentTimeMillis() - started
        val reminderTools = pendingReminder.getAndSet(null)?.let { reminder ->
            listOf(
                ChatToolCall(
                    name = "set_reminder",
                    argumentsJson = JSONObject()
                        .put("title", reminder.title)
                        .put("datetime", reminder.datetime)
                        .put("tts_enabled", reminder.ttsEnabled)
                        .toString()
                )
            )
        }.orEmpty()
        val allTools = if (reminderTools.isNotEmpty()) reminderTools else toolCalls
        ModelManager.recordChatMetrics(
            latencyMs = latency,
            timeToFirstTokenMs = 0L,
            prefillTokensPerSecond = 0.0,
            decodeTokensPerSecond = tokenPerSecond
        )
        val reply = text.toString().trim()
        Log.d(
            tag,
            "Chat done in $latency ms decode=${"%.1f".format(tokenPerSecond)}tok/s " +
                "tokens=$totalTokens tools=${allTools.size} text='${reply.take(120)}'"
        )
        return ChatResult(
            text = reply,
            toolCalls = allTools,
            latencyMs = latency,
            performance = ChatPerformance(
                decodeTokenCount = totalTokens,
                decodeTokensPerSecond = tokenPerSecond
            )
        )
    }

    fun release() {
        val current = runner
        runner = null
        loadedModelPath = null
        pendingReminder.set(null)
        if (current == null) return
        try {
            runBlocking { current.unload() }
        } catch (e: Exception) {
            Log.w(tag, "LFM Chat unload failed: ${e.message}")
        }
    }

    private fun dispatchFunction(call: LeapFunctionCall): ChatToolCall {
        val name = call.name
        val args = JSONObject()
        call.arguments.forEach { (key, value) ->
            args.put(key, value?.toString().orEmpty())
        }
        if (name == "set_reminder") {
            pendingReminder.set(
                ReminderToolArgs(
                    title = args.optString("title").trim(),
                    datetime = args.optString("datetime").trim(),
                    ttsEnabled = args.optBoolean("tts_enabled") ||
                        args.optString("tts_enabled").equals("true", ignoreCase = true)
                )
            )
            Log.d(tag, "set_reminder from LEAP title=${pendingReminder.get()?.title}")
        } else {
            Log.w(tag, "Unhandled LEAP function $name args=$args")
        }
        return ChatToolCall(name = name, argumentsJson = args.toString())
    }

    private fun ConversationTurn.toChatMessage(): ChatMessage {
        val role = when (role) {
            "assistant" -> ChatMessage.Role.ASSISTANT
            "tool" -> ChatMessage.Role.TOOL
            else -> ChatMessage.Role.USER
        }
        return ChatMessage(
            role = role,
            content = listOf(ChatMessageContent.Text(content))
        )
    }

    private fun setReminderFunction() = LeapFunction(
        name = "set_reminder",
        description = "Set a reminder for the user at a specific date and time. Use this when the user wants to be reminded of something in the future.",
        parameters = listOf(
            LeapFunctionParameter(
                name = "title",
                type = LeapFunctionParameterType.LeapStr(),
                description = "A concise description of what to remind the user about",
                optional = false
            ),
            LeapFunctionParameter(
                name = "datetime",
                type = LeapFunctionParameterType.LeapStr(),
                description = "The target date and time in ISO 8601 format with Asia/Tokyo timezone (+09:00). If the user only mentions a time, assume today's date.",
                optional = false
            ),
            LeapFunctionParameter(
                name = "tts_enabled",
                type = LeapFunctionParameterType.LeapBool(),
                description = "Whether to read the reminder aloud via TTS when the time comes. Default is false.",
                optional = true
            )
        )
    )

    private companion object {
        private const val TAG = "LeapChatEngine"
        private const val MODEL_NAME = "LFM2.5-2.6B"
        private const val QUANTIZATION_ID = "Q4_K_M"
        private const val MAX_TOKENS = 256
        private const val CHAT_TIMEOUT_MS = 60_000L
    }
}
