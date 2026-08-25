package com.g150446.voiceharness

import org.json.JSONArray
import org.json.JSONObject

data class OpenRouterModel(
    val id: String,
    val name: String,
    val contextLength: Int = 0,
    val promptPrice: String? = null,
    val completionPrice: String? = null,
    val modality: String? = null,
    val inputModalities: Set<String> = emptySet(),
    val supportedParameters: Set<String> = emptySet(),
) {
    val isFree: Boolean
        get() {
            val p = promptPrice?.toDoubleOrNull() ?: return id.endsWith(":free")
            val c = completionPrice?.toDoubleOrNull() ?: 0.0
            return p == 0.0 && c == 0.0 || id.endsWith(":free")
        }

    val supportsImage: Boolean
        get() = inputModalities.any { it.equals("image", ignoreCase = true) } ||
            modality?.contains("image", ignoreCase = true) == true

    val supportsTools: Boolean
        get() = supportedParameters.any { it.equals("tools", ignoreCase = true) }
}

object OpenRouterModelCatalog {
    const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L

    fun parseModelsJson(body: String): List<OpenRouterModel> {
        val root = JSONObject(body)
        val data = root.optJSONArray("data") ?: JSONArray()
        val out = ArrayList<OpenRouterModel>(data.length())
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            if (id.isEmpty()) continue
            val pricing = item.optJSONObject("pricing")
            val arch = item.optJSONObject("architecture")
            val inputMods = linkedSetOf<String>()
            arch?.optJSONArray("input_modalities")?.let { arr ->
                for (j in 0 until arr.length()) {
                    arr.optString(j)?.takeIf { it.isNotBlank() }?.let { inputMods += it }
                }
            }
            val supported = linkedSetOf<String>()
            item.optJSONArray("supported_parameters")?.let { arr ->
                for (j in 0 until arr.length()) {
                    arr.optString(j)?.takeIf { it.isNotBlank() }?.let { supported += it }
                }
            }
            out += OpenRouterModel(
                id = id,
                name = item.optString("name").ifBlank { id },
                contextLength = item.optInt("context_length", 0),
                promptPrice = pricing?.optString("prompt")?.takeIf { it.isNotBlank() },
                completionPrice = pricing?.optString("completion")?.takeIf { it.isNotBlank() },
                modality = arch?.optString("modality")?.takeIf { it.isNotBlank() },
                inputModalities = inputMods,
                supportedParameters = supported,
            )
        }
        return out
    }

    fun filter(models: List<OpenRouterModel>, query: String): List<OpenRouterModel> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return models
        return models.filter {
            it.id.lowercase().contains(q) || it.name.lowercase().contains(q)
        }
    }

    fun isCacheFresh(cachedAt: Long, now: Long = System.currentTimeMillis()): Boolean =
        cachedAt > 0L && now - cachedAt < CACHE_TTL_MS
}
