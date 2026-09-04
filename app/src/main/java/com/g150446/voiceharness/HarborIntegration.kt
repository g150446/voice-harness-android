package com.g150446.voiceharness

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

enum class InteractionMode { AI, READER, HARBOR }

internal enum class CapturePurpose { AI_QUERY, MODE_SWITCH }

internal fun parseInteractionMode(text: String): InteractionMode? {
    val normalized = text.lowercase(Locale.ROOT)
        .replace(Regex("[\\s、。,.!！?？・:_-]+"), "")
    val matches = buildSet {
        if (listOf("ハーバー", "terminalharbor", "ターミナル").any(normalized::contains)) {
            add(InteractionMode.HARBOR)
        }
        if (listOf("ai対話", "aiモード", "対話", "チャット").any(normalized::contains) ||
            normalized.contains("ai")
        ) {
            add(InteractionMode.AI)
        }
        if (listOf("リーダー", "読書", "reader").any(normalized::contains)) {
            add(InteractionMode.READER)
        }
    }
    return matches.singleOrNull()
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

internal data class HarborWorkspace(val id: String, val name: String, val selected: Boolean)
internal data class HarborScreen(val text: String)

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

    fun clear() = prefs.edit().remove(KEY_VALUE).apply()

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
        const val KEY_ALIAS = "voice_harness_terminal_harbor_aes"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

internal class HarborApiClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build(),
) {
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
                    )
                )
            }
        }
    }

    fun fetchScreen(credentials: HarborCredentials, workspaceId: String): HarborScreen {
        val path = "/v1/workspaces/${Uri.encode(workspaceId)}/screen?lines=60"
        val response = authorized(credentials, "GET", path)
        checkOk(response)
        return HarborScreen(JSONObject(String(response.body)).optString("text"))
    }

    private fun authorized(
        credentials: HarborCredentials,
        method: String,
        path: String,
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
                    ByteArray(0),
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
        http.newCall(builder.build()).execute().use { response ->
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
    }
}

internal class HarborMirrorController(
    context: Context,
    private val scope: CoroutineScope,
    private val client: HarborApiClient = HarborApiClient(),
) {
    private val store = HarborCredentialsStore(context)
    private var credentials = store.load()
    private var job: Job? = null
    private var mode = InteractionMode.AI
    private val _state = MutableStateFlow(
        HarborConnectionState(paired = credentials != null, deviceName = credentials?.deviceName)
    )
    val state: StateFlow<HarborConnectionState> = _state.asStateFlow()

    fun setMode(value: InteractionMode) {
        mode = value
        reconcile()
    }

    fun setG2Active(active: Boolean) {
        if (active) reconcile() else stopPolling()
    }

    fun pair(rawUri: String) {
        scope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(error = null)
            runCatching { client.pair(HarborPairPayload.parse(rawUri)) }
                .onSuccess {
                    credentials = it
                    store.save(it)
                    _state.value = HarborConnectionState(
                        paired = true,
                        connected = true,
                        deviceName = it.deviceName ?: Uri.parse(it.baseUrl).host,
                    )
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
        store.clear()
        _state.value = HarborConnectionState()
        if (mode == InteractionMode.HARBOR) {
            EvenG2ReadingSession.publishHarbor(null, null, "Terminal Harborをペアリングしてください")
        }
    }

    private fun reconcile() {
        if (mode != InteractionMode.HARBOR || !EvenG2ReadingSession.isClientActive()) {
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
            while (mode == InteractionMode.HARBOR && EvenG2ReadingSession.isClientActive()) {
                try {
                    val workspace = client.listWorkspaces(creds).firstOrNull { it.selected }
                    if (workspace == null) {
                        _state.value = _state.value.copy(connected = true, workspaceName = null, error = null)
                        EvenG2ReadingSession.publishHarbor(null, null, "選択中のワークスペースがありません")
                    } else {
                        val screen = client.fetchScreen(creds, workspace.id)
                        _state.value = _state.value.copy(
                            connected = true,
                            workspaceName = workspace.name,
                            error = null,
                        )
                        EvenG2ReadingSession.publishHarbor(workspace.name, screen.text, null)
                    }
                    failures = 0
                    delay(1_000L)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failures++
                    _state.value = _state.value.copy(
                        connected = false,
                        error = error.message ?: "Terminal Harborに接続できません",
                    )
                    if (failures >= 3) {
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
