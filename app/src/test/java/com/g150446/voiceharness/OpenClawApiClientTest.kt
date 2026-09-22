package com.g150446.voiceharness

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenClawApiClientTest {
    private val request = ChatRequest(
        conversationHistory = listOf(ConversationTurn("user", "hello")),
    )

    @Test
    fun `harness and app turns share a stable session and secret is bearer only`() {
        MockWebServer().use { server ->
            repeat(2) {
                server.enqueue(
                    MockResponse().setBody(
                        """{"choices":[{"message":{"content":"hi"}}]}""",
                    ),
                )
            }
            val client = client(server, "voice-harness:stable")

            client.chat(request.copy(conversationHistory = listOf(ConversationTurn("user", "BLE voice"))))
            client.chat(request.copy(conversationHistory = listOf(ConversationTurn("user", "typed text"))))

            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("voice-harness:stable", first.getHeader("x-openclaw-session-key"))
            assertEquals(first.getHeader("x-openclaw-session-key"), second.getHeader("x-openclaw-session-key"))
            assertEquals("Bearer gateway-secret", first.getHeader("Authorization"))
            assertTrue(first.body.readUtf8().contains("openclaw/default"))
            assertTrue(second.body.readUtf8().contains("typed text"))
        }
    }

    @Test
    fun `harbor requests use an isolated stable session`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"choices":[{"message":{"content":"ok"}}]}""",
                ),
            )
            val client = client(server, "voice-harness:stable")

            client.chat(request.copy(harborToolEnabled = true))

            assertEquals(
                "voice-harness:stable:harbor",
                server.takeRequest().getHeader("x-openclaw-session-key"),
            )
        }
    }

    @Test
    fun `cancel aborts an active HTTP call`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setBody("""{"choices":[{"message":{"content":"late"}}]}""")
                    .setBodyDelay(30, TimeUnit.SECONDS),
            )
            val client = client(server, "voice-harness:stable")
            val executor = Executors.newSingleThreadExecutor()
            val future = executor.submit<ChatResult> { client.chat(request) }
            server.takeRequest(5, TimeUnit.SECONDS)

            client.cancel()

            val failed = runCatching { future.get(5, TimeUnit.SECONDS) }
            executor.shutdownNow()
            assertTrue(failed.isFailure)
        }
    }

    @Test
    fun `history uses tools invoke with bearer auth and target session key`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"ok":true,"result":{"details":{"messages":[{"role":"user","content":"hi"}]}}}""",
                ),
            )

            val history = client(server, "voice-harness:stable").fetchHistory("main", limit = 50)

            val recorded = server.takeRequest()
            val body = recorded.body.readUtf8()
            assertEquals("/tools/invoke", recorded.path)
            assertEquals("Bearer gateway-secret", recorded.getHeader("Authorization"))
            assertTrue(body.contains("\"sessions_history\""))
            assertTrue(body.contains("\"sessionKey\":\"main\""))
            assertTrue(body.contains("\"limit\":50"))
            // The caller's own session rides along so a multi-agent Gateway can attribute it.
            assertTrue(body.contains("\"sessionKey\":\"voice-harness:stable\""))
            assertEquals(listOf(OpenClawHistoryMessage("user", "hi")), history)
        }
    }

    @Test
    fun `history 404 explains tool policy without echoing the response body`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("Bearer gateway-secret leaked"))

            val failure = runCatching { client(server, "k").fetchHistory("main") }.exceptionOrNull()

            assertTrue(failure?.message?.contains("tools.allow") == true)
            assertTrue(failure?.message?.contains("gateway-secret") == false)
        }
    }

    @Test
    fun `history fetch does not disturb cancel of the chat call`() {
        MockWebServer().use { server ->
            // Served in request order: the chat call hangs first, the history call answers at once.
            server.enqueue(
                MockResponse()
                    .setBody("""{"choices":[{"message":{"content":"late"}}]}""")
                    .setBodyDelay(30, TimeUnit.SECONDS),
            )
            server.enqueue(MockResponse().setBody("""{"ok":true,"result":{"details":{"messages":[]}}}"""))
            val client = client(server, "voice-harness:stable")
            val executor = Executors.newSingleThreadExecutor()
            val started = System.nanoTime()
            val future = executor.submit<ChatResult> { client.chat(request) }
            server.takeRequest(5, TimeUnit.SECONDS)
            client.fetchHistory("main")

            client.cancel()

            val failed = runCatching { future.get(5, TimeUnit.SECONDS) }
            executor.shutdownNow()
            assertTrue(failed.isFailure)
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 20)
        }
    }

    @Test
    fun `each workspace gets its own interpretation session`() {
        MockWebServer().use { server ->
            repeat(2) {
                server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
            }
            val client = client(server, "voice-harness:stable")

            client.chat(request.copy(forceHarborCommand = true, harborContext = context("ws-a")))
            client.chat(request.copy(forceHarborCommand = true, harborContext = context("ws-b")))

            assertEquals(
                "voice-harness:stable:harbor:ws-a",
                server.takeRequest().getHeader("x-openclaw-session-key"),
            )
            assertEquals(
                "voice-harness:stable:harbor:ws-b",
                server.takeRequest().getHeader("x-openclaw-session-key"),
            )
        }
    }

    @Test
    fun `interpretation hangs off the app session even when chat uses a shared one`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
            val client = OpenClawApiClient(
                baseUrl = server.url("/").toString(),
                token = "gateway-secret",
                sessionKey = "browser-session",
                httpClient = OkHttpClient(),
                harborSessionBase = "voice-harness:app",
            )

            client.chat(request.copy(forceHarborCommand = true, harborContext = context("ws-a")))

            assertEquals(
                "voice-harness:app:harbor:ws-a",
                server.takeRequest().getHeader("x-openclaw-session-key"),
            )
        }
    }

    @Test
    fun `interpretation targets the configured agent and pins the harbor tool`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
            val client = OpenClawApiClient(
                baseUrl = server.url("/").toString(),
                token = "gateway-secret",
                sessionKey = "voice-harness:stable",
                httpClient = OkHttpClient(),
                harborModel = "openclaw/harbor-voice",
            )

            client.chat(request.copy(forceHarborCommand = true, harborContext = context("ws-a")))

            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("openclaw/harbor-voice"))
            assertTrue(body.contains("\"tool_choice\""))
            assertTrue(body.contains(HARBOR_COMMAND_TOOL_NAME))
            assertTrue(body.contains("shift-tab"))
        }
    }

    @Test
    fun `a named agent scopes both the chat and the interpretation session`() {
        MockWebServer().use { server ->
            repeat(2) {
                server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
            }
            val client = OpenClawApiClient(
                baseUrl = server.url("/").toString(),
                token = "gateway-secret",
                sessionKey = "voice-harness:stable",
                httpClient = OkHttpClient(),
                model = "openclaw/main",
                harborModel = "openclaw/harbor-voice",
            )

            client.chat(request)
            client.chat(request.copy(forceHarborCommand = true, harborContext = context("ws-a")))

            assertEquals(
                "agent:main:voice-harness:stable",
                server.takeRequest().getHeader("x-openclaw-session-key"),
            )
            assertEquals(
                "agent:harbor-voice:voice-harness:stable:harbor:ws-a",
                server.takeRequest().getHeader("x-openclaw-session-key"),
            )
        }
    }

    @Test
    fun `an ordinary chat still goes to the default agent`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"hi"}}]}"""))
            val client = OpenClawApiClient(
                baseUrl = server.url("/").toString(),
                token = "gateway-secret",
                sessionKey = "voice-harness:stable",
                httpClient = OkHttpClient(),
                harborModel = "openclaw/harbor-voice",
            )

            client.chat(request)

            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("openclaw/default"))
            assertTrue(!body.contains("openclaw/harbor-voice"))
        }
    }

    private fun context(workspaceId: String) = HarborInterpretContext(
        workspaceId = workspaceId,
        workspaceName = workspaceId,
        conversation = "",
        availableWorkspaces = listOf(workspaceId),
    )

    private fun client(server: MockWebServer, sessionKey: String) = OpenClawApiClient(
        baseUrl = server.url("/").toString(),
        token = "gateway-secret",
        sessionKey = sessionKey,
        httpClient = OkHttpClient(),
    )
}
