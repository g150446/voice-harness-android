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

    private fun client(server: MockWebServer, sessionKey: String) = OpenClawApiClient(
        baseUrl = server.url("/").toString(),
        token = "gateway-secret",
        sessionKey = sessionKey,
        httpClient = OkHttpClient(),
    )
}
