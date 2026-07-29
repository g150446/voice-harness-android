package com.g150446.voiceharness

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import com.google.ai.edge.litertlm.tool
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

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
    const val CHAT_TIMEOUT_MS = 60_000L
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

    fun buildSystemPrompt(languageCode: String?): String {
        val base = GroqChatRequestBuilder.buildMessageSpecs(
            userText = "",
            languageCode = languageCode
        ).firstOrNull { it.role == "system" }?.content

        val currentTimeStr = java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            Locale.US
        ).apply {
            timeZone = java.util.TimeZone.getTimeZone("Asia/Tokyo")
        }.format(java.util.Date())

        val reminderInstructions =
            "You can set reminders for the user by calling the set_reminder function. " +
                "When the user asks to set a reminder, always call the function rather than just saying you will remember. " +
                "The current date and time is $currentTimeStr (Asia/Tokyo, UTC+09:00). Use this as the reference for all relative time calculations. " +
                "If the user asks for the current time, respond with only the hours and minutes, without the date or seconds. " +
                "If the user asks for today's date, respond with only the year, month, and day, without the time. " +
                "If the user mentions a time without a date, assume today based on the current time. " +
                "If the user says something like '読み上げして', 'speak it aloud', or similar, set tts_enabled to true."

        return listOfNotNull(base, reminderInstructions).joinToString(" ")
    }

    fun createEngine(
        context: Context,
        modelPath: String,
        enableAudio: Boolean,
        preferGpu: Boolean
    ): Engine {
        val mainBackend = if (preferGpu) {
            try {
                Backend.GPU()
            } catch (_: Exception) {
                Backend.CPU()
            }
        } else {
            Backend.CPU()
        }
        val config = EngineConfig(
            modelPath = modelPath,
            backend = mainBackend,
            audioBackend = if (enableAudio) Backend.CPU() else null,
            cacheDir = context.cacheDir.absolutePath
        )
        val engine = Engine(config)
        engine.initialize()
        return engine
    }

    fun runChat(
        engine: Engine,
        conversationHistory: List<ConversationTurn>,
        languageCode: String?,
        pendingReminder: AtomicReference<ReminderToolArgs?>,
        temperature: Double,
        tag: String
    ): ChatResult {
        pendingReminder.set(null)
        val systemPrompt = buildSystemPrompt(languageCode)
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
            ModelManager.recordChatMs(latency)

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
            Log.d(tag, "Chat done in ${latency} ms tools=${toolCalls.size} text='${text.take(120)}'")
            return ChatResult(text = text, toolCalls = toolCalls, latencyMs = latency)
        }
    }
}
