package com.g150446.voiceharness

import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

internal fun evenG2DoubleTapJson(status: DoubleTapStatus): String =
    "{\"count\":${status.count},\"lastDetectedAtMillis\":" +
        (status.lastDetectedAtMillis?.toString() ?: "null") + "}"

internal fun evenG2ReadingJson(snapshot: EvenG2ReadingSnapshot): String =
    JSONObject().apply {
        put("enabled", snapshot.enabled)
        put("active", snapshot.active)
        put("mode", snapshot.mode.name.lowercase())
        put("revision", snapshot.revision)
        put("bodyText", snapshot.bodyText ?: JSONObject.NULL)
        put("loading", snapshot.loading)
        put("error", snapshot.error ?: JSONObject.NULL)
        put("doubleTapCount", snapshot.doubleTapCount)
        put("singleTapCount", snapshot.singleTapCount)
    }.toString()

/**
 * Localhost bridge for the Even Hub WebView running on the same Android phone.
 * It is deliberately bound to loopback so no LAN or Tailnet peer can read or control app state.
 */
internal class EvenG2BridgeServer(
    private val statusProvider: () -> DoubleTapStatus,
    private val readingProvider: () -> EvenG2ReadingSnapshot,
    private val onAdvance: (Long) -> EvenG2AdvanceResponse,
    private val port: Int = DEFAULT_PORT,
) : Closeable {
    private val running = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    private var serverThread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        serverThread = Thread({ runServer() }, "EvenG2Bridge").apply {
            isDaemon = true
            start()
        }
    }

    private fun runServer() {
        try {
            val socket = ServerSocket(port, BACKLOG, InetAddress.getByName(LOOPBACK_ADDRESS))
            serverSocket = socket
            Log.i(TAG, "Even G2 bridge listening on $LOOPBACK_ADDRESS:$port")
            while (running.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching { client.use(::handleClient) }
                    .onFailure { Log.w(TAG, "Even G2 bridge request failed", it) }
            }
        } catch (error: Exception) {
            if (running.get()) Log.e(TAG, "Even G2 bridge failed to start", error)
        } finally {
            serverSocket = null
            running.set(false)
        }
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = CLIENT_TIMEOUT_MS
        val reader = client.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
        val requestLine = reader.readLine().orEmpty()
        var contentLength = 0
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
            if (header.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = header.substringAfter(':').trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
            }
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty().substringBefore('?')
        val requestBody = if (contentLength > 0) {
            CharArray(contentLength).also { buffer ->
                var offset = 0
                while (offset < buffer.size) {
                    val read = reader.read(buffer, offset, buffer.size - offset)
                    if (read <= 0) break
                    offset += read
                }
            }.concatToString()
        } else {
            ""
        }
        when {
            method == "OPTIONS" -> writeResponse(client, 204, "No Content", "")
            method == "GET" && path == STATUS_PATH ->
                writeResponse(client, 200, "OK", evenG2DoubleTapJson(statusProvider()))
            method == "GET" && path == READING_PATH -> {
                EvenG2ReadingSession.markClientSeen()
                writeResponse(client, 200, "OK", evenG2ReadingJson(readingProvider()))
            }
            method == "POST" && path == ADVANCE_PATH -> {
                val revision = runCatching {
                    JSONObject(requestBody).getLong("revision")
                }.getOrNull()
                if (revision == null) {
                    writeResponse(client, 400, "Bad Request", "{\"error\":\"invalid_revision\"}")
                } else {
                    val result = onAdvance(revision)
                    writeResponse(
                        client,
                        result.code,
                        result.reason,
                        "{\"accepted\":${result.accepted}}",
                    )
                }
            }
            else -> writeResponse(client, 404, "Not Found", "{\"error\":\"not_found\"}")
        }
    }

    private fun writeResponse(client: Socket, code: Int, reason: String, body: String) {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type\r\n")
            append("Access-Control-Allow-Private-Network: true\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        client.getOutputStream().use { output ->
            output.write(headers)
            output.write(bodyBytes)
            output.flush()
        }
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverThread?.interrupt()
        serverThread = null
    }

    companion object {
        const val DEFAULT_PORT = 8787
        const val STATUS_PATH = "/api/v1/double-tap"
        const val READING_PATH = "/api/v1/reading"
        const val ADVANCE_PATH = "/api/v1/reading/advance"
        private const val TAG = "EvenG2Bridge"
        private const val LOOPBACK_ADDRESS = "127.0.0.1"
        private const val BACKLOG = 4
        private const val CLIENT_TIMEOUT_MS = 1_000
    }
}
