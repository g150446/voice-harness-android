package com.g150446.voiceharness

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

enum class ModelReadiness {
    MISSING,
    FOUND,
    LOADING,
    READY,
    ERROR
}

enum class ModelSlot {
    GEMMA,
    FAST_CHAT,
    QWEN_LLM,
    QWEN_ASR_DECODER,
    QWEN_ASR_PROJECTOR,
    LFM_CHAT
}

data class SlotStatus(
    val readiness: ModelReadiness = ModelReadiness.MISSING,
    val path: String? = null,
    val fileName: String? = null,
    val sizeBytes: Long = 0L,
    val message: String = ""
)

data class ModelStatus(
    val profile: OnDeviceProfile = OnDeviceProfile.GEMMA,
    val readiness: ModelReadiness = ModelReadiness.MISSING,
    val modelPath: String? = null,
    val modelFileName: String? = null,
    val modelSizeBytes: Long = 0L,
    val message: String = "",
    val lastLoadMs: Long = 0L,
    val lastAsrMs: Long = 0L,
    val lastChatMs: Long = 0L,
    val lastChatTtftMs: Long = 0L,
    val lastPrefillTokensPerSecond: Double = 0.0,
    val lastDecodeTokensPerSecond: Double = 0.0,
    val speculativeDecodingActive: Boolean = false,
    val debugPipelineTimingEnabled: Boolean = false,
    val gemma: SlotStatus = SlotStatus(),
    val fastChat: SlotStatus = SlotStatus(),
    val qwenLlm: SlotStatus = SlotStatus(),
    val qwenAsrDecoder: SlotStatus = SlotStatus(),
    val qwenAsrProjector: SlotStatus = SlotStatus(),
    val lfmChat: SlotStatus = SlotStatus()
)

/**
 * Multi-model manager for Qwen (default) and Gemma profiles.
 */
object ModelManager {
    private const val TAG = "ModelManager"
    private const val PREFS = "model_prefs"
    private const val KEY_PROFILE = "on_device_profile"
    private const val KEY_STT_BACKEND = "stt_backend_id"
    private const val KEY_LLM_BACKEND = "llm_backend_id"
    private const val KEY_BACKEND_SPLIT_MIGRATED = "backend_split_migrated_v1"
    private const val KEY_SPEECH_BASE_LANGUAGE = "speech_base_language"
    private const val KEY_SPECULATIVE_DECODING = "speculative_decoding"
    private const val KEY_DEBUG_PIPELINE_TIMING = "debug_pipeline_timing"
    private const val KEY_GEMMA_DEFAULT_MIGRATED = "gemma_default_migrated_v1"
    private const val KEY_GEMMA_PATH = "gemma_path"
    private const val KEY_FAST_CHAT_PATH = "fast_chat_path"
    private const val KEY_QWEN_LLM_PATH = "qwen_llm_path"
    private const val KEY_QWEN_ASR_DECODER_PATH = "qwen_asr_decoder_path"
    private const val KEY_QWEN_ASR_PROJECTOR_PATH = "qwen_asr_projector_path"
    private const val KEY_LFM_CHAT_PATH = "lfm_chat_path"
    private const val MIN_MODEL_BYTES = 50_000_000L

    const val GEMMA_FILE = "gemma-4-E2B-it.litertlm"
    const val FAST_CHAT_FILE = "qwen3_0_6b_mixed_int4.litertlm"
    const val QWEN_LLM_FILE = "qwen35_mm_q8_ekv2048.litertlm"
    const val QWEN_ASR_DECODER_FILE = "Qwen3-ASR-0.6B-Q8_0.gguf"
    const val QWEN_ASR_PROJECTOR_FILE = "mmproj-Qwen3-ASR-0.6B-Q8_0.gguf"
    const val LFM_CHAT_FILE = "LFM2.5-2.6B-Q4_K_M.gguf"

    private val _status = MutableStateFlow(ModelStatus())
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    fun preferredModelsDir(context: Context): File =
        File(context.filesDir, "models").also { it.mkdirs() }

    fun currentProfile(context: Context): OnDeviceProfile {
        migrateBackendSplitIfNeeded(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // One-shot: restore Gemma as default after Qwen-first rollout so BLE voice works
        // without requiring a reinstall when Qwen ASR assets are missing.
        if (!prefs.getBoolean(KEY_GEMMA_DEFAULT_MIGRATED, false)) {
            prefs.edit()
                .putString(KEY_PROFILE, OnDeviceProfile.GEMMA.name)
                .putString(KEY_STT_BACKEND, SttBackendId.GEMMA.name)
                .putString(KEY_LLM_BACKEND, LlmBackendId.GEMMA.name)
                .putBoolean(KEY_GEMMA_DEFAULT_MIGRATED, true)
                .putBoolean(KEY_BACKEND_SPLIT_MIGRATED, true)
                .apply()
            return OnDeviceProfile.GEMMA
        }
        val stt = currentSttBackend(context)
        val llm = currentLlmBackend(context)
        if (stt.name == llm.name) {
            return OnDeviceProfile.entries.firstOrNull { it.name == stt.name } ?: OnDeviceProfile.GEMMA
        }
        return OnDeviceProfile.fromStorage(prefs.getString(KEY_PROFILE, OnDeviceProfile.GEMMA.name))
    }

    fun setProfile(context: Context, profile: OnDeviceProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILE, profile.name)
            .putString(KEY_STT_BACKEND, SttBackendId.fromProfile(profile).name)
            .putString(KEY_LLM_BACKEND, LlmBackendId.fromProfile(profile).name)
            .putBoolean(KEY_BACKEND_SPLIT_MIGRATED, true)
            .apply()
        refresh(context)
    }

    /** Idempotent: copy legacy single profile into independent STT/LLM keys once. */
    fun migrateBackendSplitIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BACKEND_SPLIT_MIGRATED, false)) return
        val profile = OnDeviceProfile.fromStorage(
            prefs.getString(KEY_PROFILE, OnDeviceProfile.GEMMA.name)
        )
        prefs.edit()
            .putString(KEY_STT_BACKEND, SttBackendId.fromProfile(profile).name)
            .putString(KEY_LLM_BACKEND, LlmBackendId.fromProfile(profile).name)
            .putBoolean(KEY_BACKEND_SPLIT_MIGRATED, true)
            .apply()
    }

    fun currentSttBackend(context: Context): SttBackendId {
        migrateBackendSplitIfNeeded(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SttBackendId.fromStorage(prefs.getString(KEY_STT_BACKEND, SttBackendId.GEMMA.name))
    }

    fun currentLlmBackend(context: Context): LlmBackendId {
        migrateBackendSplitIfNeeded(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return LlmBackendId.fromStorage(prefs.getString(KEY_LLM_BACKEND, LlmBackendId.GEMMA.name))
    }

    fun setSttBackend(context: Context, backend: SttBackendId) {
        migrateBackendSplitIfNeeded(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val llm = LlmBackendId.fromStorage(prefs.getString(KEY_LLM_BACKEND, LlmBackendId.GEMMA.name))
        val editor = prefs.edit().putString(KEY_STT_BACKEND, backend.name)
        if (backend.name == llm.name) {
            OnDeviceProfile.entries.firstOrNull { it.name == backend.name }?.let {
                editor.putString(KEY_PROFILE, it.name)
            }
        }
        editor.apply()
        refresh(context)
    }

    fun setLlmBackend(context: Context, backend: LlmBackendId) {
        migrateBackendSplitIfNeeded(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stt = SttBackendId.fromStorage(prefs.getString(KEY_STT_BACKEND, SttBackendId.GEMMA.name))
        val editor = prefs.edit().putString(KEY_LLM_BACKEND, backend.name)
        if (backend != LlmBackendId.OPENROUTER && backend.name == stt.name) {
            OnDeviceProfile.entries.firstOrNull { it.name == backend.name }?.let {
                editor.putString(KEY_PROFILE, it.name)
            }
        }
        editor.apply()
        refresh(context)
    }

    fun currentSpeechBaseLanguage(context: Context): SpeechBaseLanguage {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SpeechBaseLanguage.fromStorage(
            prefs.getString(KEY_SPEECH_BASE_LANGUAGE, SpeechBaseLanguage.JAPANESE.name)
        )
    }

    fun setSpeechBaseLanguage(context: Context, language: SpeechBaseLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SPEECH_BASE_LANGUAGE, language.name)
            .apply()
    }

    /** MTP (speculative decoding) preference. Takes effect the next time an engine is created. */
    fun isSpeculativeDecodingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SPECULATIVE_DECODING, true)

    fun setSpeculativeDecodingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SPECULATIVE_DECODING, enabled)
            .apply()
    }

    fun isDebugPipelineTimingEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEBUG_PIPELINE_TIMING, false)

    fun setDebugPipelineTimingEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DEBUG_PIPELINE_TIMING, enabled)
            .apply()
        _status.value = _status.value.copy(debugPipelineTimingEnabled = enabled)
    }

    fun resolveGemmaModel(context: Context): File? =
        resolveNamed(
            context = context,
            prefKey = KEY_GEMMA_PATH,
            preferredNames = listOf(GEMMA_FILE),
            extensions = listOf(".litertlm"),
            nameHints = listOf("gemma"),
            allowUnhintedFallback = false,
            configuredNameMustMatchHints = true
        )

    fun resolveFastChatModel(context: Context): File? =
        resolveNamed(
            context = context,
            prefKey = KEY_FAST_CHAT_PATH,
            preferredNames = listOf(FAST_CHAT_FILE),
            extensions = listOf(".litertlm"),
            nameHints = listOf("qwen3_0_6b_mixed_int4", "qwen3-0.6b", "qwen3_0.6b"),
            allowUnhintedFallback = false,
            configuredNameMustMatchHints = true
        )

    fun resolveQwenLlmModel(context: Context): File? =
        resolveNamed(
            context = context,
            prefKey = KEY_QWEN_LLM_PATH,
            preferredNames = listOf(QWEN_LLM_FILE, "Qwen3.5-0.8B.litertlm"),
            extensions = listOf(".litertlm"),
            nameHints = listOf("qwen35", "qwen3.5", "qwen_3_5", "0.8b", "qwen3_5"),
            allowUnhintedFallback = false,
            configuredNameMustMatchHints = true
        )

    fun resolveQwenAsrDecoder(context: Context): File? = resolveNamed(
        context = context,
        prefKey = KEY_QWEN_ASR_DECODER_PATH,
        preferredNames = listOf(QWEN_ASR_DECODER_FILE),
        extensions = listOf(".gguf"),
        nameHints = listOf("qwen3-asr", "qwen3_asr"),
        allowUnhintedFallback = false,
        configuredNameMustMatchHints = true
    )

    fun resolveQwenAsrProjector(context: Context): File? = resolveNamed(
        context = context,
        prefKey = KEY_QWEN_ASR_PROJECTOR_PATH,
        preferredNames = listOf(QWEN_ASR_PROJECTOR_FILE),
        extensions = listOf(".gguf"),
        nameHints = listOf("mmproj", "projector"),
        allowUnhintedFallback = false,
        configuredNameMustMatchHints = true
    )

    fun resolveLfmChatModel(context: Context): File? = resolveNamed(
        context = context,
        prefKey = KEY_LFM_CHAT_PATH,
        preferredNames = listOf(LFM_CHAT_FILE),
        extensions = listOf(".gguf"),
        nameHints = listOf("lfm2.5-2.6b", "lfm2_5-2.6b", "lfm25-2.6b", "lfm2.5_2.6b"),
        allowUnhintedFallback = false,
        configuredNameMustMatchHints = true
    )

    /** Backward-compatible single-file resolve for active profile primary model. */
    fun resolveModelFile(context: Context): File? = when (currentProfile(context)) {
        OnDeviceProfile.GEMMA -> resolveGemmaModel(context)
        OnDeviceProfile.QWEN -> resolveLfmChatModel(context)
        OnDeviceProfile.GROQ -> null
    }

    private fun resolveNamed(
        context: Context,
        prefKey: String,
        preferredNames: List<String>,
        extensions: List<String>,
        nameHints: List<String>,
        allowUnhintedFallback: Boolean = true,
        configuredNameMustMatchHints: Boolean = false
    ): File? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val configured = prefs.getString(prefKey, null)
        if (!configured.isNullOrBlank()) {
            val file = File(configured)
            if (isModelFile(file, extensions) &&
                (!configuredNameMustMatchHints || matchesNameHints(file.name, preferredNames, nameHints))
            ) {
                return file
            }
        }

        val candidates = linkedSetOf<File>()
        collectModels(preferredModelsDir(context), candidates, extensions)
        context.getExternalFilesDir(null)?.let { root ->
            collectModels(File(root, "models").also { it.mkdirs() }, candidates, extensions)
            collectModels(root, candidates, extensions)
        }
        listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File("/sdcard/Download"),
            File("/storage/emulated/0/Download")
        ).forEach { collectModels(it, candidates, extensions) }

        preferredNames.forEach { name ->
            candidates.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return remember(context, prefKey, it) }
        }
        val hinted = candidates.filter { file -> matchesNameHints(file.name, emptyList(), nameHints) }
        val chosen = hinted.maxByOrNull { it.lastModified() }
            ?: if (allowUnhintedFallback) candidates.maxByOrNull { it.lastModified() } else null
        return chosen?.let { remember(context, prefKey, it) }
    }

    private fun matchesNameHints(
        fileName: String,
        preferredNames: List<String>,
        nameHints: List<String>
    ): Boolean {
        val lower = fileName.lowercase()
        if (preferredNames.any { lower == it.lowercase() }) return true
        return nameHints.any { hint -> lower.contains(hint.lowercase()) }
    }

    private fun remember(context: Context, prefKey: String, file: File): File {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(prefKey, file.absolutePath)
            .apply()
        return file
    }

    private fun collectModels(dir: File?, out: MutableSet<File>, extensions: List<String>) {
        if (dir == null || !dir.isDirectory || !dir.canRead()) return
        dir.listFiles()?.forEach { file ->
            if (isModelFile(file, extensions)) out += file
        }
    }

    private fun isModelFile(file: File, extensions: List<String>): Boolean {
        if (!file.isFile || !file.canRead() || file.length() < MIN_MODEL_BYTES) return false
        val name = file.name.lowercase()
        return extensions.any { name.endsWith(it.lowercase()) }
    }

    fun refresh(context: Context): ModelStatus {
        val profile = currentProfile(context)
        val gemmaFile = resolveGemmaModel(context)
        val fastChatFile = resolveFastChatModel(context)
        val qwenLlmFile = resolveQwenLlmModel(context)
        val qwenAsrDecoderFile = resolveQwenAsrDecoder(context)
        val qwenAsrProjectorFile = resolveQwenAsrProjector(context)
        val lfmChatFile = resolveLfmChatModel(context)

        val gemma = toSlot(gemmaFile, "Gemma")
        val fastChat = toSlot(fastChatFile, "Fast Chat")
        val qwenLlm = toSlot(qwenLlmFile, "Qwen LLM")
        val qwenAsrDecoder = toSlot(qwenAsrDecoderFile, "Qwen ASR decoder")
        val qwenAsrProjector = toSlot(qwenAsrProjectorFile, "Qwen ASR projector")
        val lfmChat = toSlot(lfmChatFile, "LFM Chat")

        val (readiness, path, fileName, size, message) = when (profile) {
            OnDeviceProfile.GEMMA -> {
                if (gemma.readiness == ModelReadiness.MISSING) {
                    StatusTuple(ModelReadiness.MISSING, null, null, 0L, "Gemma 未検出。「ファイルから取り込む」で配置してください。")
                } else {
                    StatusTuple(ModelReadiness.FOUND, gemma.path, gemma.fileName, gemma.sizeBytes, "Gemma 検出済み")
                }
            }
            OnDeviceProfile.QWEN -> {
                val missing = listOf(lfmChat, qwenAsrDecoder, qwenAsrProjector).count {
                    it.readiness == ModelReadiness.MISSING
                }
                if (missing > 0) {
                    StatusTuple(ModelReadiness.MISSING, null, null, 0L, "Qwen ASR + LFM 必須モデルが $missing 件未検出。")
                } else {
                    StatusTuple(
                        ModelReadiness.FOUND,
                        lfmChat.path,
                        lfmChat.fileName,
                        lfmChat.sizeBytes + qwenAsrDecoder.sizeBytes + qwenAsrProjector.sizeBytes,
                        "Qwen3-ASR + LFM 2.5 検出済み"
                    )
                }
            }
            OnDeviceProfile.GROQ -> {
                if (GroqPrefs.hasApiKey(context)) {
                    StatusTuple(
                        ModelReadiness.READY,
                        null,
                        "Groq Cloud",
                        0L,
                        "Groq API キー設定済み（Whisper + Chat）"
                    )
                } else {
                    StatusTuple(
                        ModelReadiness.MISSING,
                        null,
                        null,
                        0L,
                        "Groq API キー未設定。下の欄に入力して保存してください。"
                    )
                }
            }
        }

        val current = _status.value
        val debugPipelineTimingEnabled = isDebugPipelineTimingEnabled(context)
        // Preserve READY/LOADING if same active path still valid.
        val next = if (
            (current.readiness == ModelReadiness.READY || current.readiness == ModelReadiness.LOADING) &&
            current.profile == profile &&
            current.modelPath != null &&
            current.modelPath == path
        ) {
            current.copy(
                gemma = gemma,
                fastChat = fastChat,
                qwenLlm = qwenLlm,
                qwenAsrDecoder = qwenAsrDecoder,
                qwenAsrProjector = qwenAsrProjector,
                lfmChat = lfmChat,
                modelSizeBytes = size,
                modelFileName = fileName ?: current.modelFileName,
                message = if (current.readiness == ModelReadiness.READY) current.message else message,
                debugPipelineTimingEnabled = debugPipelineTimingEnabled
            )
        } else {
            ModelStatus(
                profile = profile,
                readiness = if (current.readiness == ModelReadiness.ERROR && current.profile == profile && current.modelPath == path) {
                    ModelReadiness.ERROR
                } else {
                    readiness
                },
                modelPath = path,
                modelFileName = fileName,
                modelSizeBytes = size,
                message = if (current.readiness == ModelReadiness.ERROR && current.profile == profile && current.modelPath == path) {
                    current.message
                } else {
                    message
                },
                lastLoadMs = current.lastLoadMs,
                lastAsrMs = current.lastAsrMs,
                lastChatMs = current.lastChatMs,
                lastChatTtftMs = current.lastChatTtftMs,
                lastPrefillTokensPerSecond = current.lastPrefillTokensPerSecond,
                lastDecodeTokensPerSecond = current.lastDecodeTokensPerSecond,
                debugPipelineTimingEnabled = debugPipelineTimingEnabled,
                gemma = gemma,
                fastChat = fastChat,
                qwenLlm = qwenLlm,
                qwenAsrDecoder = qwenAsrDecoder,
                qwenAsrProjector = qwenAsrProjector,
                lfmChat = lfmChat
            )
        }
        _status.value = next
        Log.d(TAG, "refresh -> $next")
        return next
    }

    private data class StatusTuple(
        val readiness: ModelReadiness,
        val path: String?,
        val fileName: String?,
        val size: Long,
        val message: String
    )

    private fun toSlot(file: File?, label: String): SlotStatus {
        return if (file == null) {
            SlotStatus(readiness = ModelReadiness.MISSING, message = "$label 未検出")
        } else {
            SlotStatus(
                readiness = ModelReadiness.FOUND,
                path = file.absolutePath,
                fileName = file.name,
                sizeBytes = file.length(),
                message = "$label 検出"
            )
        }
    }

    fun markSlotLoading(slot: ModelSlot, path: String) {
        updateSlot(slot, ModelReadiness.LOADING, path, "読み込み中...")
        if (isActiveSlot(slot)) {
            _status.value = _status.value.copy(
                readiness = ModelReadiness.LOADING,
                modelPath = path,
                message = "モデル読み込み中..."
            )
        }
    }

    fun markSlotReady(slot: ModelSlot, path: String, loadMs: Long) {
        val file = File(path)
        updateSlot(slot, ModelReadiness.READY, path, "準備完了")
        if (isActiveSlot(slot) || slot == ModelSlot.LFM_CHAT || slot == ModelSlot.GEMMA) {
            val profile = _status.value.profile
            val activeReady = when (profile) {
                OnDeviceProfile.GEMMA -> slot == ModelSlot.GEMMA
                OnDeviceProfile.QWEN -> slot == ModelSlot.LFM_CHAT
                OnDeviceProfile.GROQ -> false
            }
            if (activeReady) {
                _status.value = _status.value.copy(
                    readiness = ModelReadiness.READY,
                    modelPath = path,
                    modelFileName = file.name,
                    modelSizeBytes = if (profile == OnDeviceProfile.QWEN) {
                        _status.value.lfmChat.sizeBytes +
                            _status.value.qwenAsrDecoder.sizeBytes +
                            _status.value.qwenAsrProjector.sizeBytes
                    } else {
                        file.length()
                    },
                    message = "準備完了（${loadMs} ms）",
                    lastLoadMs = if (loadMs > 0) loadMs else _status.value.lastLoadMs
                )
            }
        }
    }

    fun markSlotError(slot: ModelSlot, message: String) {
        updateSlot(slot, ModelReadiness.ERROR, _status.value.let {
            when (slot) {
                ModelSlot.GEMMA -> it.gemma.path
                ModelSlot.FAST_CHAT -> it.fastChat.path
                ModelSlot.QWEN_LLM -> it.qwenLlm.path
                ModelSlot.QWEN_ASR_DECODER -> it.qwenAsrDecoder.path
                ModelSlot.QWEN_ASR_PROJECTOR -> it.qwenAsrProjector.path
                ModelSlot.LFM_CHAT -> it.lfmChat.path
            }
        }, message)
        if (isActiveSlot(slot) || slot == ModelSlot.LFM_CHAT || slot == ModelSlot.GEMMA) {
            _status.value = _status.value.copy(
                readiness = ModelReadiness.ERROR,
                message = message
            )
        }
    }

    fun markSlotMissing(slot: ModelSlot) {
        updateSlot(slot, ModelReadiness.MISSING, null, "未検出")
    }

    private fun isActiveSlot(slot: ModelSlot): Boolean = when (_status.value.profile) {
        OnDeviceProfile.GEMMA -> slot == ModelSlot.GEMMA
        OnDeviceProfile.QWEN -> slot == ModelSlot.LFM_CHAT
        OnDeviceProfile.GROQ -> false
    }

    fun markCloudReady(context: Context) {
        if (currentProfile(context) != OnDeviceProfile.GROQ) return
        _status.value = _status.value.copy(
            profile = OnDeviceProfile.GROQ,
            readiness = ModelReadiness.READY,
            modelPath = null,
            modelFileName = "Groq Cloud",
            modelSizeBytes = 0L,
            message = "Groq API 準備完了",
            lastLoadMs = 0L
        )
    }

    private fun updateSlot(slot: ModelSlot, readiness: ModelReadiness, path: String?, message: String) {
        val file = path?.let { File(it) }
        val slotStatus = SlotStatus(
            readiness = readiness,
            path = path,
            fileName = file?.name,
            sizeBytes = file?.length() ?: 0L,
            message = message
        )
        _status.value = when (slot) {
            ModelSlot.GEMMA -> _status.value.copy(gemma = slotStatus)
            ModelSlot.FAST_CHAT -> _status.value.copy(fastChat = slotStatus)
            ModelSlot.QWEN_LLM -> _status.value.copy(qwenLlm = slotStatus)
            ModelSlot.QWEN_ASR_DECODER -> _status.value.copy(qwenAsrDecoder = slotStatus)
            ModelSlot.QWEN_ASR_PROJECTOR -> _status.value.copy(qwenAsrProjector = slotStatus)
            ModelSlot.LFM_CHAT -> _status.value.copy(lfmChat = slotStatus)
        }
    }

    fun markLoading(path: String) {
        // compatibility
        when (currentProfileFromStatus()) {
            OnDeviceProfile.GROQ -> {
                _status.value = _status.value.copy(
                    readiness = ModelReadiness.LOADING,
                    message = "Groq 接続確認中..."
                )
            }
            OnDeviceProfile.GEMMA -> markSlotLoading(ModelSlot.GEMMA, path)
            OnDeviceProfile.QWEN -> markSlotLoading(ModelSlot.LFM_CHAT, path)
        }
    }

    fun markReady(path: String, loadMs: Long) {
        when (currentProfileFromStatus()) {
            OnDeviceProfile.GROQ -> {
                _status.value = _status.value.copy(
                    readiness = ModelReadiness.READY,
                    modelFileName = "Groq Cloud",
                    message = "Groq API 準備完了",
                    lastLoadMs = loadMs
                )
            }
            OnDeviceProfile.GEMMA -> markSlotReady(ModelSlot.GEMMA, path, loadMs)
            OnDeviceProfile.QWEN -> markSlotReady(ModelSlot.LFM_CHAT, path, loadMs)
        }
    }

    fun markError(message: String, path: String? = _status.value.modelPath) {
        when (currentProfileFromStatus()) {
            OnDeviceProfile.GROQ -> {
                _status.value = _status.value.copy(
                    readiness = ModelReadiness.ERROR,
                    message = message,
                    modelPath = path
                )
            }
            OnDeviceProfile.GEMMA -> {
                markSlotError(ModelSlot.GEMMA, message)
                if (path != null) {
                    _status.value = _status.value.copy(modelPath = path)
                }
            }
            OnDeviceProfile.QWEN -> {
                markSlotError(ModelSlot.LFM_CHAT, message)
                if (path != null) {
                    _status.value = _status.value.copy(modelPath = path)
                }
            }
        }
    }

    fun markReleased(context: Context) {
        refresh(context)
    }

    private fun currentProfileFromStatus(): OnDeviceProfile = _status.value.profile

    fun recordAsrMs(ms: Long) {
        _status.value = _status.value.copy(lastAsrMs = ms)
    }

    fun recordChatMs(ms: Long) {
        _status.value = _status.value.copy(lastChatMs = ms)
    }

    fun recordChatMetrics(
        latencyMs: Long,
        timeToFirstTokenMs: Long,
        prefillTokensPerSecond: Double,
        decodeTokensPerSecond: Double
    ) {
        _status.value = _status.value.copy(
            lastChatMs = latencyMs,
            lastChatTtftMs = timeToFirstTokenMs,
            lastPrefillTokensPerSecond = prefillTokensPerSecond,
            lastDecodeTokensPerSecond = decodeTokensPerSecond
        )
    }

    /** Whether the loaded engine actually got MTP, which can differ from the preference. */
    fun recordSpeculativeDecoding(active: Boolean) {
        _status.value = _status.value.copy(speculativeDecodingActive = active)
    }

    fun readinessLabel(readiness: ModelReadiness): String = when (readiness) {
        ModelReadiness.MISSING -> "未検出"
        ModelReadiness.FOUND -> "検出済み"
        ModelReadiness.LOADING -> "読み込み中"
        ModelReadiness.READY -> "準備完了"
        ModelReadiness.ERROR -> "エラー"
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return "-"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) String.format("%.2f GB", mb / 1024.0) else String.format("%.0f MB", mb)
    }

    suspend fun importFromUri(context: Context, uri: Uri, slotHint: ModelSlot? = null): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val displayName = queryDisplayName(context, uri) ?: "model.bin"
                val lower = displayName.lowercase()
                val slot = slotHint ?: when {
                    lower.contains("lfm") -> ModelSlot.LFM_CHAT
                    lower.contains("qwen3_0_6b_mixed_int4") ||
                        lower.contains("qwen3-0.6b") -> ModelSlot.FAST_CHAT
                    lower.contains("gemma") -> ModelSlot.GEMMA
                    lower.contains("mmproj") || lower.contains("projector") -> ModelSlot.QWEN_ASR_PROJECTOR
                    lower.endsWith(".gguf") -> ModelSlot.QWEN_ASR_DECODER
                    lower.contains("qwen") -> ModelSlot.QWEN_LLM
                    lower.endsWith(".litertlm") && currentProfile(context) == OnDeviceProfile.GEMMA -> ModelSlot.GEMMA
                    lower.endsWith(".litertlm") -> ModelSlot.QWEN_LLM
                    else -> ModelSlot.QWEN_LLM
                }
                val expectedExtension = when (slot) {
                    ModelSlot.QWEN_ASR_DECODER,
                    ModelSlot.QWEN_ASR_PROJECTOR,
                    ModelSlot.LFM_CHAT -> ".gguf"
                    else -> ".litertlm"
                }
                require(lower.endsWith(expectedExtension)) {
                    "$expectedExtension モデルを選択してください。"
                }
                val destName = when (slot) {
                    ModelSlot.GEMMA -> if (lower.endsWith(".litertlm")) displayName.substringAfterLast('/') else GEMMA_FILE
                    ModelSlot.FAST_CHAT -> FAST_CHAT_FILE
                    ModelSlot.QWEN_LLM -> if (lower.endsWith(".litertlm")) displayName.substringAfterLast('/') else QWEN_LLM_FILE
                    ModelSlot.QWEN_ASR_DECODER -> QWEN_ASR_DECODER_FILE
                    ModelSlot.QWEN_ASR_PROJECTOR -> QWEN_ASR_PROJECTOR_FILE
                    ModelSlot.LFM_CHAT -> LFM_CHAT_FILE
                }
                val dest = File(preferredModelsDir(context), destName)
                val tmp = File(preferredModelsDir(context), "$destName.partial")
                Log.d(TAG, "import slot=$slot uri=$uri dest=${dest.absolutePath}")
                resolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            total += read
                        }
                        output.fd.sync()
                        if (total < MIN_MODEL_BYTES) {
                            error("ファイルが小さすぎます (${formatSize(total)})")
                        }
                    }
                } ?: error("ファイルを開けませんでした")
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    FileInputStream(tmp).use { input ->
                        FileOutputStream(dest).use { output -> input.copyTo(output) }
                    }
                    tmp.delete()
                }
                val prefKey = when (slot) {
                    ModelSlot.GEMMA -> KEY_GEMMA_PATH
                    ModelSlot.FAST_CHAT -> KEY_FAST_CHAT_PATH
                    ModelSlot.QWEN_LLM -> KEY_QWEN_LLM_PATH
                    ModelSlot.QWEN_ASR_DECODER -> KEY_QWEN_ASR_DECODER_PATH
                    ModelSlot.QWEN_ASR_PROJECTOR -> KEY_QWEN_ASR_PROJECTOR_PATH
                    ModelSlot.LFM_CHAT -> KEY_LFM_CHAT_PATH
                }
                remember(context, prefKey, dest)
                refresh(context)
                dest
            }.onFailure { e ->
                Log.e(TAG, "importFromUri failed", e)
                markError("取り込み失敗: ${e.message}")
            }
        }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                }
        } catch (e: Exception) {
            null
        }
    }
}
