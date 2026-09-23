package com.g150446.voiceharness

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class InteractionMode { AI, READER, HARBOR, OPENCLAW, EPUB }

internal enum class CapturePurpose { AI_QUERY, COMMAND }

internal fun parseInteractionMode(text: String): InteractionMode? {
    val normalized = text.lowercase(Locale.ROOT)
        .replace(Regex("[\\s、。,.!！?？・:_-]+"), "")
    val openClaw = listOf("openclaw", "オープンクロー", "オープンクロウ", "オープンクロ")
        .any(normalized::contains)
    val epub = listOf("epub", "イーパブ", "イーパッブ", "イーバブ").any(normalized::contains)
    val matches = buildSet {
        if (epub) add(InteractionMode.EPUB)
        if (openClaw) add(InteractionMode.OPENCLAW)
        if (listOf("ハーバー", "terminalharbor", "ターミナル").any(normalized::contains)) {
            add(InteractionMode.HARBOR)
        }
        // "OpenClawチャット" names the OpenClaw chat, not the on-device AI mode.
        val aiWords = if (openClaw) listOf("ai対話", "aiモード") else
            listOf("ai対話", "aiモード", "対話", "チャット")
        if (aiWords.any(normalized::contains) || (!openClaw && normalized.contains("ai"))) {
            add(InteractionMode.AI)
        }
        // "EPUBリーダー" names the EPUB reader, not the Kindle reader.
        if (!epub && listOf("リーダー", "読書", "reader").any(normalized::contains)) {
            add(InteractionMode.READER)
        }
    }
    return matches.singleOrNull()
}

internal fun canEnableInteractionMode(
    mode: InteractionMode,
    g2Active: Boolean,
    harborPaired: Boolean,
    openClawConfigured: Boolean = false,
    epubReady: Boolean = false,
): Boolean = when (mode) {
    InteractionMode.AI -> true
    InteractionMode.READER -> g2Active
    InteractionMode.HARBOR -> harborPaired
    InteractionMode.OPENCLAW -> openClawConfigured
    InteractionMode.EPUB -> epubReady
}

internal data class HarborEndpoint(val kind: String, val url: String)

internal data class HarborPairPayload(
    val token: String,
    val serverId: String,
    val endpoints: List<HarborEndpoint>,
) {
    companion object {
        fun parse(raw: String): HarborPairPayload {
            val uri = Uri.parse(raw.trim())
            require(uri.scheme == "harbor" && uri.host == "pair") {
                "Terminal HarborのペアリングURIではありません"
            }
            require(uri.getQueryParameter("v") == "1") { "未対応のペアリングURIです" }
            require(uri.getQueryParameter("auth") == "hmac-sha256-v1") {
                "HMAC対応のTerminal Harborが必要です"
            }
            val token = uri.getQueryParameter("token")?.takeIf(String::isNotBlank)
                ?: error("ペアリングトークンがありません")
            val serverId = uri.getQueryParameter("sid")?.takeIf(String::isNotBlank)
                ?: error("Terminal Harborのserver IDがありません")
            val endpoints = uri.getQueryParameters("endpoint").mapNotNull { encoded ->
                val separator = encoded.indexOf(',')
                if (separator <= 0) null else HarborEndpoint(
                    kind = encoded.substring(0, separator),
                    url = encoded.substring(separator + 1).trimEnd('/'),
                ).takeIf { it.url.startsWith("http://") || it.url.startsWith("https://") }
            }.toMutableList()
            val host = uri.getQueryParameter("host")
            val port = uri.getQueryParameter("port")?.toIntOrNull()
            if (host != null && port != null && port in 1..65535) {
                val scheme = if (uri.getQueryParameter("tls") in setOf("1", "true")) "https" else "http"
                val fallback = HarborEndpoint("legacy", "$scheme://$host:$port")
                if (endpoints.none { it.url == fallback.url }) endpoints += fallback
            }
            require(endpoints.isNotEmpty()) { "接続先がありません" }
            val rank = mapOf("tailscale_https" to 0, "tailscale_direct" to 1, "lan" to 2)
            return HarborPairPayload(token, serverId, endpoints.sortedBy { rank[it.kind] ?: 3 })
        }
    }
}

internal data class HarborCredentials(
    val baseUrl: String,
    val serverId: String,
    val clientId: String,
    val key: ByteArray,
    val endpoints: List<HarborEndpoint> = emptyList(),
    val deviceName: String? = null,
)

data class HarborConnectionState(
    val paired: Boolean = false,
    val connected: Boolean = false,
    val deviceName: String? = null,
    val workspaceName: String? = null,
    val error: String? = null,
)

data class HarborWorkspace(
    val id: String,
    val name: String,
    val selected: Boolean,
    val root: String? = null,
    val agent: String? = null,
    val process: String? = null,
    val summary: String? = null,
)

/**
 * Resolves a confirmed switch target against the workspace list.
 *
 * The target comes from the interpreter's `workspace` field, so it is already a name
 * rather than a whole utterance; matching it by name is safe here. An ambiguous partial
 * match resolves to null so the caller can say so instead of guessing.
 */
internal fun resolveHarborWorkspace(
    target: String,
    workspaces: List<HarborWorkspace>,
): HarborWorkspace? {
    val needle = harborWorkspaceKey(target)
    if (needle.isEmpty()) return null
    workspaces.firstOrNull { harborWorkspaceKey(it.name) == needle }?.let { return it }
    workspaces.firstOrNull { harborWorkspaceKey(it.id) == needle }?.let { return it }
    return workspaces.filter {
        val key = harborWorkspaceKey(it.name)
        key.isNotEmpty() && (key.contains(needle) || needle.contains(key))
    }.singleOrNull()
}

private fun harborWorkspaceKey(value: String): String =
    value.lowercase(Locale.ROOT).filter(Char::isLetterOrDigit)

internal data class HarborScreen(val text: String)
data class HarborTab(
    val id: String,
    val title: String,
    val selected: Boolean,
    val paneCount: Int,
)

data class HarborDevice(
    val id: String,
    val name: String,
    val endpoint: String,
    val active: Boolean,
)

/**
 * The plan file an agent wrote, from `GET /v1/workspaces/{id}/plan`.
 *
 * This is never derived from the terminal screen: the screen only holds the rows that are
 * still on it, so a plan read from there is cut off at whatever has scrolled past. [available]
 * false is a normal answer with a [reason] — the caller shows that reason and must not put
 * screen text in its place.
 */
data class HarborPlan(
    val available: Boolean,
    val capability: String,
    val agent: String? = null,
    val text: String = "",
    val updatedAt: String? = null,
    val reason: String? = null,
)

/** One turn of the agent conversation, from `GET /v1/workspaces/{id}/transcript`. */
data class HarborTranscriptMessage(
    val role: String,
    val text: String,
    val at: String? = null,
    /** Opaque paging position; hand it back as `before` to read older messages. */
    val cursor: String = "",
    val truncated: Boolean = false,
) {
    val isUser: Boolean get() = role == "user"
}

/**
 * A page of the conversation between the user and the agent in a workspace.
 *
 * An agent's TUI repaints in place, so the terminal keeps roughly one screenful of it and
 * `/screen` cannot reach the instruction behind an earlier reply. This comes from the agent's
 * own session log instead. As with [HarborPlan], [available] false is a normal answer with a
 * [reason], and terminal text is never shown in its place.
 */
data class HarborTranscript(
    val available: Boolean,
    val capability: String,
    val agent: String? = null,
    val messages: List<HarborTranscriptMessage> = emptyList(),
    val hasMore: Boolean = false,
    val nextBefore: String? = null,
    val reason: String? = null,
)

data class HarborUiState(
    val devices: List<HarborDevice> = emptyList(),
    val workspaces: List<HarborWorkspace> = emptyList(),
    val tabs: List<HarborTab> = emptyList(),
    val selectedWorkspaceId: String? = null,
    val screenText: String = "",
    val speechHints: List<String> = emptyList(),
    val plan: HarborPlan? = null,
    val planError: String? = null,
    val planLoading: Boolean = false,
    /** Oldest first, grown at the front as older pages are loaded. */
    val transcript: List<HarborTranscriptMessage> = emptyList(),
    val transcriptAgent: String? = null,
    val transcriptHasMore: Boolean = false,
    val transcriptCursor: String? = null,
    val transcriptUnavailable: HarborTranscript? = null,
    val transcriptError: String? = null,
    val transcriptLoading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)
internal data class HarborG2View(
    val summary: Boolean,
    val text: String = "",
    val summaryText: String = "",
    val question: String = "",
    val options: List<String> = emptyList(),
)

internal fun filterHarborDisplayText(text: String): String {
    val separators = "-_.=~‐‑‒–—―·•⋅⋯…─━│┃┄┅┆┇┈┉┊┋╌╍╎╏┌┐└┘├┤┬┴┼╭╮╰╯"
    return text.lineSequence()
        .map(String::trimEnd)
        .filter { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.all { it.isWhitespace() || separators.contains(it) }
        }
        .joinToString("\n")
}

internal fun shouldPublishHarborPoll(paused: Boolean, coroutineActive: Boolean): Boolean =
    !paused && coroutineActive

/** One signed call against Terminal Harbor, already bound to the workspace it targets. */
internal sealed interface HarborOperation {
    val workspaceId: String
    val waitMs: Int

    data class Activate(override val workspaceId: String, override val waitMs: Int = 0) :
        HarborOperation

    data class Instruction(
        override val workspaceId: String,
        val text: String,
        val submit: Boolean,
        override val waitMs: Int = HARBOR_STEP_DELAY_MS,
    ) : HarborOperation

    data class Key(
        override val workspaceId: String,
        val key: String,
        override val waitMs: Int = HARBOR_STEP_DELAY_MS,
    ) : HarborOperation

    /**
     * Leave Claude Code in [mode]. How many ⇧Tab presses that takes is not decided here, and
     * deliberately not decided by whoever interpreted the request either: the sender presses
     * one key at a time and reads the mode back off the screen between presses.
     */
    data class SetMode(
        override val workspaceId: String,
        val mode: ClaudeCodeMode,
        override val waitMs: Int = HARBOR_MODE_SETTLE_MS,
    ) : HarborOperation
}

internal data class HarborSubmitPlan(
    val operations: List<HarborOperation>,
    val message: String,
)

/**
 * Turns a confirmed command into the exact calls to make, and refuses anything Harbor cannot
 * carry out — before the first one is sent, so a bad key in the middle of a sequence cannot
 * leave the terminal half-way through something the user approved as a whole.
 *
 * The target workspace is the one the interpretation was made against. Resolving it at send
 * time instead would let a switch between the confirmation and the tap redirect approved text
 * into another terminal.
 */
internal fun planHarborSubmit(
    args: HarborCommandArgs,
    workspaces: List<HarborWorkspace>,
): HarborSubmitPlan {
    if (args.action == HarborCommandAction.SWITCH_WORKSPACE) {
        val target = args.workspace?.trim().orEmpty()
        if (target.isEmpty()) error("切り替え先のワークスペースが空です")
        val match = resolveHarborWorkspace(target, workspaces)
            ?: error("「$target」というワークスペースが見つかりません")
        return HarborSubmitPlan(
            operations = listOf(HarborOperation.Activate(match.id)),
            message = "${match.name} に切り替えました",
        )
    }

    val targetId = args.workspaceId?.takeIf { id -> workspaces.any { it.id == id } }
        ?: workspaces.firstOrNull { it.selected }?.id
        ?: error("選択中のワークスペースがありません")

    val steps = args.effectiveSteps
    if (steps.isEmpty()) error("送信する指示が空です")
    val operations = steps.map { step ->
        when (step.action) {
            HarborStepAction.KEY -> {
                val key = HarborCommandTool.normalizeKeyName(step.key)
                    ?: error("「${step.key}」は送信できないキーです")
                HarborOperation.Key(targetId, key, step.waitMs)
            }

            HarborStepAction.MODE -> {
                val mode = step.mode ?: error("切り替えるモードが指定されていません")
                HarborOperation.SetMode(targetId, mode, step.waitMs)
            }

            HarborStepAction.INSTRUCTION -> {
                val text = step.command.trim()
                if (text.isEmpty()) error("送信する指示が空です")
                HarborOperation.Instruction(targetId, text, step.submit, step.waitMs)
            }
        }
    }
    return HarborSubmitPlan(operations, describeHarborSteps(steps))
}

/**
 * What was just sent, in one line the glass can hold next to the terminal mirror.
 *
 * A mode step has no line here: what it did is only known once the screen has been read back,
 * so [HarborMirrorController.submitCommand] supplies that line itself.
 */
internal fun describeHarborSteps(steps: List<HarborCommandStep>): String {
    if (steps.size == 1) {
        val step = steps.first()
        return when (step.action) {
            HarborStepAction.KEY -> "${HarborCommandTool.keyLabel(step.key ?: "enter")}キーを送りました"
            HarborStepAction.MODE -> ""
            HarborStepAction.INSTRUCTION ->
                if (step.submit) "指示を送りました" else "指示を貼り付けました"
        }
    }
    val sequence = steps.joinToString(" → ") { step ->
        when (step.action) {
            HarborStepAction.KEY -> HarborCommandTool.keyLabel(step.key ?: "enter")
            HarborStepAction.MODE -> HarborCommandTool.modeLabel(step.mode)
            HarborStepAction.INSTRUCTION -> step.command.take(20)
        }
    }
    return "$sequence を送りました"
}

/**
 * Parses a `/plan` response.
 *
 * A success is always whole: the bridge sends `complete: true` and a SHA-256 of the bytes it
 * read. Both are checked here, because showing a partial plan as if it were the plan is worse
 * than showing nothing — the user would act on half a document without knowing it.
 */
internal fun parseHarborPlan(code: Int, json: JSONObject): HarborPlan {
    val capability = json.optString("capability").ifBlank { "unknown" }
    val agent = json.optString("agent").trim().takeIf(String::isNotEmpty)
    if (code == 413) {
        return HarborPlan(
            available = false,
            capability = capability,
            agent = agent,
            reason = json.optString("reason").ifBlank { "plan_too_large" },
        )
    }
    if (!json.optBoolean("available", false)) {
        return HarborPlan(
            available = false,
            capability = capability,
            agent = agent,
            reason = json.optString("reason").ifBlank { "unknown" },
        )
    }
    val text = json.optString("text")
    if (!json.optBoolean("complete", false)) {
        error("Terminal Harborが不完全なプランを返しました")
    }
    val expected = json.optString("content_sha256").lowercase(Locale.ROOT)
    if (expected.isNotEmpty() && expected != sha256Hex(text.toByteArray(Charsets.UTF_8))) {
        error("プランの内容が壊れています（ハッシュ不一致）")
    }
    return HarborPlan(
        available = true,
        capability = capability,
        agent = agent,
        text = text,
        updatedAt = json.optString("updated_at").trim().takeIf(String::isNotEmpty),
    )
}

/**
 * Parses a `/transcript` page.
 *
 * Messages arrive oldest first within a page; [HarborTranscript.nextBefore] walks to the page
 * before it. A message the bridge had to cut carries its own marker, so nothing here has to
 * guess whether text is complete.
 */
internal fun parseHarborTranscript(code: Int, json: JSONObject): HarborTranscript {
    val capability = json.optString("capability").ifBlank { "unknown" }
    val agent = json.optString("agent").trim().takeIf(String::isNotEmpty)
    if (code !in 200..299 || !json.optBoolean("available", false)) {
        return HarborTranscript(
            available = false,
            capability = capability,
            agent = agent,
            reason = json.optString("reason").ifBlank { "unknown" },
        )
    }
    val rows = json.optJSONArray("messages") ?: JSONArray()
    val messages = buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val role = row.optString("role").trim()
            val text = row.optString("text")
            if (role.isEmpty() || text.isBlank()) continue
            add(
                HarborTranscriptMessage(
                    role = role,
                    text = text,
                    at = row.optString("at").trim().takeIf(String::isNotEmpty),
                    cursor = row.optString("cursor").trim(),
                    truncated = row.optBoolean("truncated", false),
                ),
            )
        }
    }
    return HarborTranscript(
        available = true,
        capability = capability,
        agent = agent,
        messages = messages,
        hasMore = json.optBoolean("has_more", false),
        nextBefore = json.optString("next_before").trim().takeIf(String::isNotEmpty),
    )
}

/**
 * Folds a freshly fetched page into what is already on screen.
 *
 * An older page goes in front, minus anything already shown: the log can grow between
 * requests, and repeating a message reads as the agent saying it twice.
 *
 * The newest page is a refresh. What it has in common with the screen says the two overlap,
 * so only its genuinely newer messages are appended and the history the reader has already
 * pulled in stays put. With nothing in common the conversation has moved on further than one
 * page, and there is no honest way to bridge that gap, so the newest page replaces what is
 * shown rather than leaving a seam that looks continuous.
 */
internal fun mergeHarborTranscript(
    current: List<HarborTranscriptMessage>,
    page: List<HarborTranscriptMessage>,
    before: String?,
): List<HarborTranscriptMessage> {
    val seen = current.mapTo(mutableSetOf()) { it.cursor }
    if (before != null) return page.filterNot { it.cursor in seen } + current
    if (current.isEmpty() || page.none { it.cursor in seen }) return page
    return current + page.filterNot { it.cursor in seen }
}

/** Why there is no conversation to show. Never implies the terminal view has it. */
internal fun harborTranscriptReasonText(transcript: HarborTranscript): String =
    when (transcript.reason) {
        "no_agent" -> "このworkspaceではエージェントが動いていません。"
        "unsupported_agent" -> "このエージェントは会話の取得に対応していません。"
        "session_unidentified" ->
            "このpaneのセッションを特定できません。Claude Codeなら Terminal Harbor で " +
                "`wezterm agent-session install-hooks --agent claude --apply` を実行して" +
                "起動し直してください。"
        "stale_session" -> "セッション登録が古くなっています。エージェントを起動し直してください。"
        "transcript_missing" -> "セッションの記録が見つかりません。"
        "ambiguous_session" ->
            "同じ条件のセッションが複数あり、どれがこのpaneのものか特定できません。"
        "stale_cursor" -> "会話の位置が変わりました。もう一度開き直してください。"
        "unsupported_api" -> "このTerminal Harborは会話の取得に未対応です（API 1.12.0以降が必要）。"
        else -> "会話を取得できませんでした。"
    }

/** Why there is no plan to show, in the user's language. Never implies the screen has one. */
internal fun harborPlanReasonText(plan: HarborPlan): String = when (plan.reason) {
    "plan_not_created" -> "このセッションはまだプランを作成していません。"
    "agent_has_no_plan_file" -> "このエージェント（${plan.agent ?: "Codex"}）はプランファイルを持ちません。"
    "unsupported_agent" -> "このエージェントはプラン取得に対応していません。"
    "no_agent" -> "このworkspaceではエージェントが動いていません。"
    "session_unidentified" ->
        "Claude Codeのhookが未登録です。Terminal Harborで " +
            "`wezterm agent-session install-hooks --agent claude --apply` を実行し、" +
            "Claude Codeを起動し直してください。"
    "stale_session" -> "セッション登録が古くなっています。Claude Codeを起動し直してください。"
    "plan_file_missing" -> "プランファイルが見つかりません。"
    "plan_too_large" -> "プランが大きすぎて取得できません（1MiB超）。"
    "unsupported_api" -> "このTerminal Harborはプラン取得に未対応です（API 1.11.0以降が必要）。"
    else -> "プランを取得できませんでした。"
}

internal fun parseHarborG2View(json: JSONObject): HarborG2View {
    if (json.optString("view") != "summary") {
        return HarborG2View(
            summary = false,
            text = filterHarborDisplayText(json.optString("text")),
        )
    }
    val optionsJson = json.optJSONArray("options") ?: JSONArray()
    val options = buildList {
        for (index in 0 until optionsJson.length()) {
            optionsJson.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
        }
    }
    return HarborG2View(
        summary = true,
        summaryText = json.optString("summary").trim(),
        question = json.optString("question").trim(),
        options = options,
    )
}

internal class HarborCredentialsStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): HarborCredentials? {
        val stored = prefs.getString(KEY_VALUE, null) ?: return null
        val plain = decrypt(stored) ?: return null
        return runCatching {
            val json = JSONObject(plain)
            HarborCredentials(
                baseUrl = json.getString("base_url"),
                serverId = json.getString("server_id"),
                clientId = json.getString("client_id"),
                key = b64Decode(json.getString("key")),
                endpoints = json.optJSONArray("endpoints")?.let { items ->
                    buildList {
                        for (index in 0 until items.length()) {
                            val item = items.getJSONObject(index)
                            add(HarborEndpoint(item.getString("kind"), item.getString("url")))
                        }
                    }
                }.orEmpty(),
                deviceName = json.optString("device_name").takeIf(String::isNotBlank),
            )
        }.getOrNull()
    }

    fun save(value: HarborCredentials) {
        val json = JSONObject().apply {
            put("base_url", value.baseUrl)
            put("server_id", value.serverId)
            put("client_id", value.clientId)
            put("key", b64(value.key))
            put("endpoints", JSONArray().apply {
                value.endpoints.forEach { endpoint ->
                    put(JSONObject().put("kind", endpoint.kind).put("url", endpoint.url))
                }
            })
            put("device_name", value.deviceName ?: "")
        }
        prefs.edit().putString(KEY_VALUE, encrypt(json.toString())).apply()
    }

    fun loadAll(): List<HarborCredentials> {
        val stored = prefs.getString(KEY_DEVICES, null)
        if (stored == null) {
            val legacy = load() ?: return emptyList()
            saveAll(listOf(legacy))
            return listOf(legacy)
        }
        val plain = decrypt(stored) ?: return emptyList()
        return runCatching {
            val array = JSONArray(plain)
            buildList {
                for (index in 0 until array.length()) {
                    parseCredentials(array.getJSONObject(index))?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveAll(values: List<HarborCredentials>) {
        val array = JSONArray()
        values.forEach { value ->
            array.put(JSONObject().apply {
                put("base_url", value.baseUrl)
                put("server_id", value.serverId)
                put("client_id", value.clientId)
                put("key", b64(value.key))
                put("endpoints", JSONArray().apply {
                    value.endpoints.forEach { endpoint ->
                        put(JSONObject().put("kind", endpoint.kind).put("url", endpoint.url))
                    }
                })
                put("device_name", value.deviceName ?: "")
            })
        }
        prefs.edit().putString(KEY_DEVICES, encrypt(array.toString())).apply()
    }

    fun activeServerId(): String? = prefs.getString(KEY_ACTIVE_SERVER, null)

    fun setActiveServerId(serverId: String?) {
        prefs.edit().apply {
            if (serverId == null) remove(KEY_ACTIVE_SERVER) else putString(KEY_ACTIVE_SERVER, serverId)
        }.apply()
    }

    private fun parseCredentials(json: JSONObject): HarborCredentials? = runCatching {
        HarborCredentials(
            baseUrl = json.getString("base_url"),
            serverId = json.getString("server_id"),
            clientId = json.getString("client_id"),
            key = b64Decode(json.getString("key")),
            endpoints = json.optJSONArray("endpoints")?.let { items ->
                buildList {
                    for (index in 0 until items.length()) {
                        val item = items.getJSONObject(index)
                        add(HarborEndpoint(item.getString("kind"), item.getString("url")))
                    }
                }
            }.orEmpty(),
            deviceName = json.optString("device_name").takeIf(String::isNotBlank),
        )
    }.getOrNull()

    fun clear() = prefs.edit().remove(KEY_VALUE).remove(KEY_DEVICES).remove(KEY_ACTIVE_SERVER).apply()

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
            .put(cipher.iv.size.toByte()).put(cipher.iv).put(encrypted).array()
        return "v1:" + Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String? = runCatching {
        require(value.startsWith("v1:"))
        val buffer = ByteBuffer.wrap(Base64.decode(value.removePrefix("v1:"), Base64.NO_WRAP))
        val iv = ByteArray(buffer.get().toInt() and 0xff).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFS = "terminal_harbor_credentials"
        const val KEY_VALUE = "credentials"
        const val KEY_DEVICES = "devices_v2"
        const val KEY_ACTIVE_SERVER = "active_server_id"
        const val KEY_ALIAS = "voice_harness_terminal_harbor_aes"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

internal fun hasStoredHarborCredentials(context: Context): Boolean =
    HarborCredentialsStore(context).loadAll().isNotEmpty()

private const val TAG = "HarborApiClient"

/** Enough of the terminal tail to hold Claude Code's footer, and no more. */
private const val MODE_SCREEN_LINES = 40

internal class HarborApiClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build(),
) {
    private val g2Http = http.newBuilder()
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    fun pair(payload: HarborPairPayload): HarborCredentials {
        val clientId = UUID.randomUUID().toString()
        val nonceBytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val key = hkdfDeviceKey(payload.token, payload.serverId, clientId, nonceBytes)
        val body = JSONObject().apply {
            put("auth_version", "hmac-sha256-v1")
            put("client_id", clientId)
            put("client_nonce", b64(nonceBytes))
            put("device_name", "Voice Harness Android")
        }.toString().toByteArray()
        var lastError: Throwable? = null
        for (endpoint in payload.endpoints) {
            try {
                val response = request(
                    endpoint.url,
                    "POST",
                    "/v1/pair",
                    body,
                    payload.token.toByteArray(),
                    responseKey = key,
                )
                if (response.code !in 200..299) error("Pairing failed: HTTP ${response.code}")
                val json = JSONObject(String(response.body))
                require(json.getString("server_id") == payload.serverId) {
                    "別のTerminal Harborが応答しました"
                }
                return HarborCredentials(
                    baseUrl = endpoint.url,
                    serverId = payload.serverId,
                    clientId = json.optString("client_id", clientId),
                    key = key,
                    endpoints = parseEndpoints(json.optJSONArray("endpoints")).ifEmpty {
                        payload.endpoints
                    },
                    deviceName = json.optString("device_name").takeIf(String::isNotBlank),
                )
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException("Terminal Harborに接続できません", lastError)
    }

    fun listWorkspaces(credentials: HarborCredentials): List<HarborWorkspace> {
        val response = authorized(credentials, "GET", "/v1/workspaces")
        checkOk(response)
        val items = JSONObject(String(response.body)).optJSONArray("workspaces") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                add(
                    HarborWorkspace(
                        id = item.getString("id"),
                        name = item.optString("directory").ifBlank {
                            item.optString("name", "Workspace")
                        },
                        selected = item.optBoolean("selected"),
                        root = item.optString("root").takeIf(String::isNotBlank),
                        agent = item.optString("agent").trim().takeIf { it.isNotEmpty() },
                        process = item.optString("process").trim().takeIf { it.isNotEmpty() },
                        summary = item.optString("summary").trim().takeIf { it.isNotEmpty() },
                    )
                )
            }
        }
    }

    fun createWorkspace(credentials: HarborCredentials, root: String?): HarborWorkspace {
        val body = JSONObject().apply {
            root?.trim()?.takeIf(String::isNotEmpty)?.let { put("root", it) }
        }.toString().toByteArray()
        val response = authorized(credentials, "POST", "/v1/workspaces", body)
        checkOk(response)
        val item = JSONObject(String(response.body))
        return HarborWorkspace(
            id = item.getString("id"),
            name = item.optString("directory").ifBlank { item.optString("name", "Workspace") },
            selected = item.optBoolean("selected", true),
            root = item.optString("root").takeIf(String::isNotBlank),
            agent = item.optString("agent").takeIf(String::isNotBlank),
            process = item.optString("process").takeIf(String::isNotBlank),
            summary = item.optString("summary").takeIf(String::isNotBlank),
        )
    }

    fun closeWorkspace(credentials: HarborCredentials, workspaceId: String) {
        val path = "/v1/workspaces/${Uri.encode(workspaceId)}"
        checkOk(authorized(credentials, "DELETE", path, "{\"confirm\":true}".toByteArray()))
    }

    fun listTabs(credentials: HarborCredentials, workspaceId: String): List<HarborTab> {
        val path = "/v1/workspaces/${Uri.encode(workspaceId)}/tabs"
        val response = authorized(credentials, "GET", path)
        checkOk(response)
        val items = JSONObject(String(response.body)).optJSONArray("tabs") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                add(HarborTab(
                    id = item.getString("id"),
                    title = item.optString("title", "Tab"),
                    selected = item.optBoolean("selected"),
                    paneCount = item.optInt("pane_count", 1),
                ))
            }
        }
    }

    fun createTab(credentials: HarborCredentials, workspaceId: String) {
        val path = "/v1/workspaces/${Uri.encode(workspaceId)}/tabs"
        checkOk(authorized(credentials, "POST", path, "{}".toByteArray()))
    }

    fun activateTab(credentials: HarborCredentials, workspaceId: String, tabId: String) {
        val path = "/v1/workspaces/${Uri.encode(workspaceId)}/tabs/${Uri.encode(tabId)}/activate"
        checkOk(authorized(credentials, "POST", path, "{}".toByteArray()))
    }

    fun closeTab(credentials: HarborCredentials, workspaceId: String, tabId: String) {
        val path = "/v1/workspaces/${Uri.encode(workspaceId)}/tabs/${Uri.encode(tabId)}"
        checkOk(authorized(credentials, "DELETE", path, "{\"confirm\":true}".toByteArray()))
    }

    fun fetchScreen(
        credentials: HarborCredentials,
        workspaceId: String,
        lines: Int = 60,
    ): HarborScreen {
        val clamped = lines.coerceIn(1, 20_000)
        val path = "/v1/workspaces/${Uri.encode(workspaceId)}/screen?lines=$clamped"
        val response = authorized(credentials, "GET", path)
        checkOk(response)
        return HarborScreen(JSONObject(String(response.body)).optString("text"))
    }

    /**
     * The agent's own plan file. Deliberately has no `/screen` fallback: a screen snapshot is
     * a different thing and [parseHarborPlan] would have no way to label it as one.
     */
    fun fetchPlan(credentials: HarborCredentials, workspaceId: String): HarborPlan {
        val path = "/v1/workspaces/${Uri.encode(workspaceId)}/plan"
        val response = authorized(credentials, "GET", path)
        if (response.code == 404) {
            return HarborPlan(available = false, capability = "unknown", reason = "unsupported_api")
        }
        if (response.code != 413) checkOk(response)
        return parseHarborPlan(response.code, JSONObject(String(response.body)))
    }

    /**
     * One page of the agent conversation. Like [fetchPlan] it has no `/screen` fallback: the
     * terminal holds one repaint of the agent's UI, not the exchange being asked for.
     */
    fun fetchTranscript(
        credentials: HarborCredentials,
        workspaceId: String,
        limit: Int = 20,
        before: String? = null,
    ): HarborTranscript {
        val query = buildString {
            append("?limit=").append(limit.coerceIn(1, 200))
            before?.takeIf(String::isNotBlank)?.let { append("&before=").append(Uri.encode(it)) }
        }
        val path = "/v1/workspaces/${Uri.encode(workspaceId)}/transcript$query"
        val response = authorized(credentials, "GET", path)
        if (response.code == 404) {
            return HarborTranscript(
                available = false,
                capability = "unknown",
                reason = "unsupported_api",
            )
        }
        checkOk(response)
        return parseHarborTranscript(response.code, JSONObject(String(response.body)))
    }

    fun fetchInterpretContext(credentials: HarborCredentials): HarborInterpretContext? {
        val workspaces = listWorkspaces(credentials)
        val workspace = workspaces.firstOrNull { it.selected } ?: return null
        val screen = runCatching {
            fetchScreen(credentials, workspace.id, lines = INTERPRET_CONTEXT_LINES)
        }.getOrElse {
            fetchScreen(credentials, workspace.id, lines = 60)
        }
        val conversation = filterHarborDisplayText(screen.text)
            .takeLast(INTERPRET_CONTEXT_MAX_CHARS)
        return HarborInterpretContext(
            workspaceId = workspace.id,
            workspaceName = workspace.name,
            agent = workspace.agent,
            process = workspace.process,
            workspaceSummary = workspace.summary,
            conversation = conversation,
            availableWorkspaces = workspaces.map { it.name },
        )
    }

    fun fetchG2View(credentials: HarborCredentials, workspaceId: String): HarborG2View {
        val path = "/v1/workspaces/${Uri.encode(workspaceId)}/g2-view"
        val response = authorized(credentials, "POST", path, "{}".toByteArray())
        if (response.code == 404) {
            return HarborG2View(
                summary = false,
                text = filterHarborDisplayText(fetchScreen(credentials, workspaceId).text),
            )
        }
        checkOk(response)
        return parseHarborG2View(JSONObject(String(response.body)))
    }

    fun fetchSpeechHints(credentials: HarborCredentials, workspaceId: String): List<String> {
        val path = "/v1/workspaces/${Uri.encode(workspaceId)}/speech/hints"
        val response = authorized(credentials, "GET", path)
        checkOk(response)
        val items = JSONObject(String(response.body)).optJSONArray("hints") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                items.optString(index).trim().takeIf(String::isNotEmpty)?.let(::add)
            }
        }
    }

    fun postInstruction(
        credentials: HarborCredentials,
        workspaceId: String,
        text: String,
        submit: Boolean = true,
    ) {
        val path = "/v1/workspaces/${Uri.encode(workspaceId)}/instruction"
        val body = JSONObject().put("text", text).put("submit", submit).toString().toByteArray()
        val response = authorized(credentials, "POST", path, body)
        Log.i(TAG, "instruction http=${response.code} chars=${text.length}")
        checkOk(response)
    }

    fun postActivate(credentials: HarborCredentials, workspaceId: String) {
        val path = "/v1/workspaces/${Uri.encode(workspaceId)}/activate"
        val response = authorized(credentials, "POST", path, "{}".toByteArray())
        Log.i(TAG, "activate http=${response.code} workspace=$workspaceId")
        checkOk(response)
    }

    fun postKey(credentials: HarborCredentials, workspaceId: String, key: String) {
        val path = "/v1/workspaces/${Uri.encode(workspaceId)}/key"
        val body = JSONObject().put("key", key).toString().toByteArray()
        val response = authorized(credentials, "POST", path, body)
        Log.i(TAG, "key http=${response.code} key=$key")
        checkOk(response)
    }

    private fun authorized(
        credentials: HarborCredentials,
        method: String,
        path: String,
        body: ByteArray = ByteArray(0),
    ): HarborResponse {
        val candidates = buildList {
            add(credentials.baseUrl)
            credentials.endpoints.mapTo(this) { it.url }
        }.distinct()
        var lastError: Throwable? = null
        for (baseUrl in candidates) {
            try {
                return request(
                    baseUrl,
                    method,
                    path,
                    body,
                    credentials.key,
                    clientId = credentials.clientId,
                )
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException("Terminal Harborに接続できません", lastError)
    }

    private fun parseEndpoints(items: JSONArray?): List<HarborEndpoint> {
        if (items == null) return emptyList()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val kind = item.optString("kind")
                val url = item.optString("url").trimEnd('/')
                if (kind.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
                    add(HarborEndpoint(kind, url))
                }
            }
        }
    }

    private data class HarborResponse(val code: Int, val body: ByteArray)

    private fun request(
        baseUrl: String,
        method: String,
        path: String,
        body: ByteArray,
        signingKey: ByteArray,
        responseKey: ByteArray = signingKey,
        clientId: String? = null,
    ): HarborResponse {
        val timestamp = System.currentTimeMillis() / 1_000L
        val nonce = b64(ByteArray(18).also(SecureRandom()::nextBytes))
        val canonical = "TH-HMAC-V1\n${method.uppercase()}\n$path\n$timestamp\n$nonce\n${sha256Hex(body)}"
        val signature = b64(hmac(signingKey, canonical.toByteArray()))
        val builder = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .header("Accept", "application/json")
            .header("X-Harbor-Timestamp", timestamp.toString())
            .header("X-Harbor-Nonce", nonce)
            .header("X-Harbor-Signature", signature)
        if (clientId != null) builder.header("X-Harbor-Client-Id", clientId)
        if (body.isNotEmpty()) builder.header("Content-Type", "application/json")
        builder.method(method, if (method == "GET") null else body.toRequestBody(JSON))
        val requestClient =
            if (path.endsWith("/g2-view") || path.endsWith("/voice/intent")) g2Http else http
        requestClient.newCall(builder.build()).execute().use { response ->
            val bytes = response.body.bytes()
            val actual = response.header("X-Harbor-Response-Signature")
                ?: error("認証されていない応答です")
            val responseCanonical =
                "TH-HMAC-V1-RESPONSE\n$nonce\n${response.code}\n${sha256Hex(bytes)}"
            val expected = hmac(responseKey, responseCanonical.toByteArray())
            require(MessageDigest.isEqual(expected, b64Decode(actual))) {
                "Terminal Harbor応答の署名が不正です"
            }
            return HarborResponse(response.code, bytes)
        }
    }

    private fun checkOk(response: HarborResponse) {
        if (response.code !in 200..299) error("Terminal Harbor HTTP ${response.code}")
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val INTERPRET_CONTEXT_LINES = 2_000
        const val INTERPRET_CONTEXT_MAX_CHARS = 64 * 1_024
    }
}

internal class HarborMirrorController(
    context: Context,
    private val scope: CoroutineScope,
    private val client: HarborApiClient = HarborApiClient(),
) {
    private val store = HarborCredentialsStore(context)
    private var allCredentials = store.loadAll().toMutableList()
    private var credentials = allCredentials.firstOrNull {
        it.serverId == store.activeServerId()
    } ?: allCredentials.firstOrNull()
    private var job: Job? = null
    @Volatile private var paused = false
    private var mode = InteractionMode.AI
    private val _state = MutableStateFlow(
        HarborConnectionState(paired = credentials != null, deviceName = credentials?.deviceName)
    )
    val state: StateFlow<HarborConnectionState> = _state.asStateFlow()
    private val _uiState = MutableStateFlow(
        HarborUiState(devices = deviceSummaries())
    )
    val uiState: StateFlow<HarborUiState> = _uiState.asStateFlow()

    fun isPaired(): Boolean = credentials != null

    private fun deviceSummaries(): List<HarborDevice> = allCredentials.map { item ->
        HarborDevice(
            id = item.serverId,
            name = item.deviceName ?: Uri.parse(item.baseUrl).host ?: "Terminal Harbor",
            endpoint = item.baseUrl,
            active = item.serverId == credentials?.serverId,
        )
    }

    private fun updateDevices() {
        _uiState.value = _uiState.value.copy(devices = deviceSummaries())
    }

    fun refreshWorkspaces(onComplete: (() -> Unit)? = null) {
        val creds = credentials ?: run {
            _uiState.value = _uiState.value.copy(error = "Terminal Harborをペアリングしてください")
            onComplete?.invoke()
            return
        }
        scope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(busy = true, error = null)
            runCatching { client.listWorkspaces(creds) }
                .onSuccess { workspaces ->
                    _uiState.value = _uiState.value.copy(
                        workspaces = workspaces,
                        selectedWorkspaceId = workspaces.firstOrNull { it.selected }?.id,
                        busy = false,
                    )
                    _state.value = _state.value.copy(
                        connected = true,
                        workspaceName = workspaces.firstOrNull { it.selected }?.name,
                        error = null,
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(busy = false, error = error.message)
                }
            onComplete?.invoke()
        }
    }

    fun selectDevice(serverId: String) {
        val selected = allCredentials.firstOrNull { it.serverId == serverId } ?: return
        stopPolling()
        credentials = selected
        store.setActiveServerId(serverId)
        _uiState.value = HarborUiState(devices = deviceSummaries())
        _state.value = HarborConnectionState(
            paired = true,
            deviceName = selected.deviceName ?: Uri.parse(selected.baseUrl).host,
        )
        refreshWorkspaces { reconcile() }
    }

    fun removeDevice(serverId: String) {
        allCredentials.removeAll { it.serverId == serverId }
        if (credentials?.serverId == serverId) {
            stopPolling()
            credentials = allCredentials.firstOrNull()
            store.setActiveServerId(credentials?.serverId)
        }
        store.saveAll(allCredentials)
        updateDevices()
        val active = credentials
        _state.value = HarborConnectionState(
            paired = active != null,
            deviceName = active?.deviceName ?: active?.baseUrl?.let { Uri.parse(it).host },
        )
        if (active == null) _uiState.value = HarborUiState()
        else refreshWorkspaces { reconcile() }
    }

    fun activateWorkspace(workspaceId: String) = mutateAndRefresh {
        client.postActivate(requireCredentials(), workspaceId)
    }

    fun createWorkspace(root: String?) = mutateAndRefresh {
        client.createWorkspace(requireCredentials(), root)
    }

    fun closeWorkspace(workspaceId: String) = mutateAndRefresh {
        client.closeWorkspace(requireCredentials(), workspaceId)
    }

    fun loadWorkspace(workspaceId: String, lines: Int = 500) {
        val creds = credentials ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                val tabs = client.listTabs(creds, workspaceId)
                val screen = client.fetchScreen(creds, workspaceId, lines)
                val cachedHints = _uiState.value.takeIf {
                    it.selectedWorkspaceId == workspaceId && it.speechHints.isNotEmpty()
                }?.speechHints
                val hints = cachedHints ?: runCatching {
                    client.fetchSpeechHints(creds, workspaceId)
                }.getOrDefault(emptyList())
                Triple(tabs, screen, hints)
            }.onSuccess { (tabs, screen, hints) ->
                _uiState.value = _uiState.value.copy(
                    selectedWorkspaceId = workspaceId,
                    tabs = tabs,
                    screenText = screen.text,
                    speechHints = hints,
                    error = null,
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(error = error.message)
            }
        }
    }

    /**
     * Fetched on demand rather than on the one-second screen poll: a plan changes when the
     * agent writes one, not every tick, and each call is another signed request.
     */
    fun loadWorkspacePlan(workspaceId: String) {
        val creds = credentials ?: return
        _uiState.value = _uiState.value.copy(planLoading = true, planError = null)
        scope.launch(Dispatchers.IO) {
            runCatching { client.fetchPlan(creds, workspaceId) }
                .onSuccess { plan ->
                    _uiState.value = _uiState.value.copy(
                        plan = plan,
                        planError = null,
                        planLoading = false,
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        plan = null,
                        planError = error.message ?: "プランを取得できませんでした。",
                        planLoading = false,
                    )
                }
        }
    }

    /**
     * One page of the agent conversation. `before` null loads the newest page and replaces
     * what is shown; a cursor prepends the page before it, which is how 「さらに遡る」 walks
     * back to the instruction that produced a reply.
     */
    fun loadWorkspaceTranscript(workspaceId: String, before: String? = null) {
        val creds = credentials ?: return
        _uiState.value = _uiState.value.copy(transcriptLoading = true, transcriptError = null)
        scope.launch(Dispatchers.IO) {
            runCatching { client.fetchTranscript(creds, workspaceId, before = before) }
                .onSuccess { page ->
                    val current = _uiState.value
                    if (!page.available) {
                        _uiState.value = current.copy(
                            transcript = if (before == null) emptyList() else current.transcript,
                            transcriptUnavailable = page,
                            transcriptError = null,
                            transcriptLoading = false,
                        )
                        return@onSuccess
                    }
                    val merged = mergeHarborTranscript(current.transcript, page.messages, before)
                    _uiState.value = current.copy(
                        transcript = merged,
                        transcriptAgent = page.agent ?: current.transcriptAgent,
                        // A refresh only speaks for the newest end. Keeping the older
                        // cursor lets the reader carry on walking back from where they
                        // were instead of starting the history again.
                        transcriptHasMore = if (before == null && merged.size > page.messages.size) {
                            current.transcriptHasMore
                        } else {
                            page.hasMore
                        },
                        transcriptCursor = if (before == null && merged.size > page.messages.size) {
                            current.transcriptCursor
                        } else {
                            page.nextBefore
                        },
                        transcriptUnavailable = null,
                        transcriptError = null,
                        transcriptLoading = false,
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        transcriptError = error.message ?: "会話を取得できませんでした。",
                        transcriptLoading = false,
                    )
                }
        }
    }

    fun createTab(workspaceId: String) = mutateWorkspace(workspaceId) {
        client.createTab(requireCredentials(), workspaceId)
    }

    fun activateTab(workspaceId: String, tabId: String) = mutateWorkspace(workspaceId) {
        client.activateTab(requireCredentials(), workspaceId, tabId)
    }

    fun closeTab(workspaceId: String, tabId: String) = mutateWorkspace(workspaceId) {
        client.closeTab(requireCredentials(), workspaceId, tabId)
    }

    fun sendInstruction(workspaceId: String, text: String, submit: Boolean = true) =
        mutateWorkspace(workspaceId) {
            client.postInstruction(requireCredentials(), workspaceId, text, submit)
        }

    fun sendKey(workspaceId: String, key: String) = mutateWorkspace(workspaceId) {
        client.postKey(requireCredentials(), workspaceId, key)
    }

    private fun requireCredentials(): HarborCredentials = credentials
        ?: error("Terminal Harborをペアリングしてください")

    private fun mutateAndRefresh(action: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(busy = true, error = null)
            runCatching(action)
                .onSuccess { refreshWorkspaces() }
                .onFailure { _uiState.value = _uiState.value.copy(busy = false, error = it.message) }
        }
    }

    private fun mutateWorkspace(workspaceId: String, action: () -> Unit) {
        scope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(busy = true, error = null)
            runCatching(action)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(busy = false)
                    loadWorkspace(workspaceId)
                    refreshWorkspaces()
                }
                .onFailure { _uiState.value = _uiState.value.copy(busy = false, error = it.message) }
        }
    }

    fun setMode(value: InteractionMode) {
        mode = value
        reconcile()
    }

    fun setG2Active(active: Boolean) {
        if (active) reconcile() else stopPolling()
    }

    fun setPaused(value: Boolean) {
        paused = value
        if (value) stopPolling() else reconcile()
    }

    fun pair(rawUri: String) {
        scope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(error = null)
            runCatching { client.pair(HarborPairPayload.parse(rawUri)) }
                .onSuccess {
                    credentials = it
                    allCredentials.removeAll { saved -> saved.serverId == it.serverId }
                    allCredentials.add(it)
                    store.saveAll(allCredentials)
                    store.setActiveServerId(it.serverId)
                    updateDevices()
                    _state.value = HarborConnectionState(
                        paired = true,
                        connected = true,
                        deviceName = it.deviceName ?: Uri.parse(it.baseUrl).host,
                    )
                    refreshWorkspaces()
                    reconcile()
                }
                .onFailure {
                    _state.value = _state.value.copy(error = it.message ?: "ペアリングに失敗しました")
                }
        }
    }

    fun clear() {
        stopPolling()
        credentials = null
        allCredentials.clear()
        store.clear()
        _state.value = HarborConnectionState()
        _uiState.value = HarborUiState()
        if (mode == InteractionMode.HARBOR) {
            EvenG2ReadingSession.publishHarbor(null, null, "Terminal Harborをペアリングしてください")
        }
    }

    fun speechHints(): List<String> {
        val creds = credentials ?: return emptyList()
        return runCatching {
            val workspace = client.listWorkspaces(creds).firstOrNull { it.selected } ?: return emptyList()
            client.fetchSpeechHints(creds, workspace.id)
        }.getOrDefault(emptyList())
    }

    fun interpretContext(): HarborInterpretContext? {
        val creds = credentials ?: return null
        return runCatching { client.fetchInterpretContext(creds) }.getOrNull()
    }

    /**
     * Carries out the action the user already confirmed on the glass.
     *
     * The interpretation happened once, on the phone, with the workspace list and the
     * agent conversation in context. Routing it through Harbor's own voice classifier
     * afterwards could only re-decide what the user had already approved — which is how
     * a confirmed instruction ended up switching workspaces instead of being sent.
     */
    fun submitCommand(args: HarborCommandArgs): String {
        val creds = credentials ?: error("Terminal Harborをペアリングしてください")
        val plan = planHarborSubmit(args, client.listWorkspaces(creds))
        // A mode change only knows what it did after the fact, so it reports its own line.
        val notes = mutableListOf<String>()
        plan.operations.forEachIndexed { index, operation ->
            when (operation) {
                is HarborOperation.Activate -> client.postActivate(creds, operation.workspaceId)
                is HarborOperation.Instruction -> client.postInstruction(
                    creds,
                    operation.workspaceId,
                    operation.text,
                    operation.submit,
                )

                is HarborOperation.Key ->
                    client.postKey(creds, operation.workspaceId, operation.key)

                is HarborOperation.SetMode -> notes += applyClaudeMode(creds, operation)
            }
            // The agent redraws between keys; sending the next one into a stale screen is how
            // a menu selection lands on the wrong row.
            if (index < plan.operations.lastIndex) Thread.sleep(operation.waitMs.toLong())
        }
        return when {
            notes.isEmpty() -> plan.message
            plan.message.isBlank() -> notes.joinToString("\n")
            else -> (listOf(plan.message) + notes).joinToString("\n")
        }
    }

    /**
     * Cycles ⇧Tab to [operation]'s mode, checking the screen after each press.
     *
     * The count is never assumed: Claude Code's cycle skips modes the session was not started
     * with, and the mode can have moved since whoever interpreted the request last saw it.
     * [cycleToClaudeMode] throws rather than press on when the screen stops saying where it is,
     * and the message names the mode actually reached — the user is left standing in it.
     */
    private fun applyClaudeMode(
        creds: HarborCredentials,
        operation: HarborOperation.SetMode,
    ): String {
        val cycler = object : ClaudeModeCycler {
            override fun readMode(): ClaudeCodeMode? = readClaudeCodeMode(
                client.fetchScreen(creds, operation.workspaceId, lines = MODE_SCREEN_LINES).text,
            )

            override fun pressCycleKey() =
                client.postKey(creds, operation.workspaceId, "shift-tab")

            override fun settle() = Thread.sleep(operation.waitMs.toLong())
        }
        val result = cycleToClaudeMode(operation.mode, cycler)
        Log.i(
            TAG,
            "mode target=${operation.mode.wire} reached=${result.mode.wire} " +
                "presses=${result.presses}",
        )
        return if (result.presses == 0) {
            "すでに${result.mode.label}モードです"
        } else {
            "${result.mode.label}モードにしました（⇧Tab ×${result.presses}）"
        }
    }

    private fun reconcile() {
        if (paused || mode != InteractionMode.HARBOR || !EvenG2ReadingSession.isClientActive()) {
            stopPolling()
            return
        }
        val creds = credentials
        if (creds == null) {
            EvenG2ReadingSession.publishHarbor(null, null, "Terminal Harborをペアリングしてください")
            return
        }
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { poll(creds) }
    }

    private suspend fun poll(creds: HarborCredentials) {
        var failures = 0
        try {
            while (
                !paused &&
                mode == InteractionMode.HARBOR &&
                EvenG2ReadingSession.isClientActive()
            ) {
                try {
                    val workspace = client.listWorkspaces(creds).firstOrNull { it.selected }
                    if (!shouldPublishHarborPoll(paused, coroutineContext.isActive)) return
                    if (workspace == null) {
                        _state.value = _state.value.copy(connected = true, workspaceName = null, error = null)
                        EvenG2ReadingSession.publishHarbor(null, null, "選択中のワークスペースがありません")
                    } else {
                        val view = client.fetchG2View(creds, workspace.id)
                        if (!shouldPublishHarborPoll(paused, coroutineContext.isActive)) return
                        _state.value = _state.value.copy(
                            connected = true,
                            workspaceName = workspace.name,
                            error = null,
                        )
                        if (view.summary) {
                            val action = buildList {
                                view.question.takeIf(String::isNotBlank)?.let(::add)
                                addAll(view.options)
                            }.joinToString("\n")
                            EvenG2ReadingSession.publishHarbor(
                                workspace.name,
                                null,
                                null,
                                summaryText = view.summaryText,
                                actionText = action,
                            )
                        } else {
                            EvenG2ReadingSession.publishHarbor(workspace.name, view.text, null)
                        }
                    }
                    failures = 0
                    delay(HARBOR_MIRROR_POLL_MS)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failures++
                    _state.value = _state.value.copy(
                        connected = false,
                        error = error.message ?: "Terminal Harborに接続できません",
                    )
                    if (failures >= 3 && shouldPublishHarborPoll(paused, coroutineContext.isActive)) {
                        EvenG2ReadingSession.publishHarbor(
                            _state.value.workspaceName,
                            null,
                            "Terminal Harborに接続できません",
                        )
                    }
                    delay((1_000L shl (failures - 1).coerceAtMost(3)).coerceAtMost(10_000L))
                }
            }
        } finally {
            job = null
        }
    }

    private fun stopPolling() {
        job?.cancel()
        job = null
    }
}

internal fun hkdfDeviceKey(
    pairToken: String,
    serverId: String,
    clientId: String,
    clientNonce: ByteArray,
): ByteArray {
    val prk = hmac(serverId.toByteArray(), pairToken.toByteArray())
    val info = "terminal-harbor/device/v2\u0000".toByteArray() +
        clientId.toByteArray() + byteArrayOf(0) + clientNonce
    return hmac(prk, info + byteArrayOf(1)).copyOf(32)
}

private fun hmac(key: ByteArray, value: ByteArray): ByteArray =
    Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(value)
    }

private fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(value).joinToString("") { "%02x".format(it) }

private fun b64(value: ByteArray): String =
    Base64.encodeToString(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

private fun b64Decode(value: String): ByteArray =
    Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
