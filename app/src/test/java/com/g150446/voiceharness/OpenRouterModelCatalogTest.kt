package com.g150446.voiceharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterModelCatalogTest {
    private val sampleJson = """
        {
          "data": [
            {
              "id": "openai/gpt-4o",
              "name": "GPT-4o",
              "context_length": 128000,
              "pricing": { "prompt": "0.000005", "completion": "0.000015" },
              "architecture": {
                "modality": "text+image->text",
                "input_modalities": ["text", "image"]
              },
              "supported_parameters": ["tools", "temperature"]
            },
            {
              "id": "meta-llama/llama-3.1-8b-instruct:free",
              "name": "Llama 3.1 8B Free",
              "context_length": 8192,
              "pricing": { "prompt": "0", "completion": "0" },
              "architecture": { "modality": "text->text", "input_modalities": ["text"] },
              "supported_parameters": ["temperature"]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses models and capability badges`() {
        val models = OpenRouterModelCatalog.parseModelsJson(sampleJson)
        assertEquals(2, models.size)
        val gpt = models.first { it.id == "openai/gpt-4o" }
        assertTrue(gpt.supportsImage)
        assertTrue(gpt.supportsTools)
        assertFalse(gpt.isFree)
        val free = models.first { it.id.contains(":free") }
        assertTrue(free.isFree)
        assertFalse(free.supportsImage)
        assertFalse(free.supportsTools)
    }

    @Test
    fun `filter searches id and name`() {
        val models = OpenRouterModelCatalog.parseModelsJson(sampleJson)
        assertEquals(1, OpenRouterModelCatalog.filter(models, "gpt-4o").size)
        assertEquals(1, OpenRouterModelCatalog.filter(models, "Llama").size)
        assertEquals(2, OpenRouterModelCatalog.filter(models, "").size)
    }

    @Test
    fun `cache freshness is 24 hours`() {
        val now = 1_000_000L
        assertTrue(OpenRouterModelCatalog.isCacheFresh(now - 1000, now))
        assertFalse(
            OpenRouterModelCatalog.isCacheFresh(
                now - OpenRouterModelCatalog.CACHE_TTL_MS - 1,
                now,
            )
        )
    }

    @Test
    fun `detects removed selected model`() {
        val models = OpenRouterModelCatalog.parseModelsJson(sampleJson)
        assertTrue(models.none { it.id == "gone/model" })
        assertTrue(models.any { it.id == "openai/gpt-4o" })
    }
}
