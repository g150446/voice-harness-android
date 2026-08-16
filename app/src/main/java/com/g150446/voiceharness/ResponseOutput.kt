package com.g150446.voiceharness

import android.content.Context

enum class ResponseOutputTarget {
    PHONE_AUDIO,
    SMART_GLASSES
}

internal class ResponseOutputPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun target(): ResponseOutputTarget {
        val saved = preferences.getString(KEY_TARGET, null)
        return parseResponseOutputTarget(saved)
    }

    fun setTarget(target: ResponseOutputTarget) {
        preferences.edit().putString(KEY_TARGET, target.name).apply()
    }

    private companion object {
        private const val PREFERENCES_NAME = "response_output"
        private const val KEY_TARGET = "target"
    }
}

internal fun parseResponseOutputTarget(saved: String?): ResponseOutputTarget =
    ResponseOutputTarget.entries.firstOrNull { it.name == saved }
        ?: ResponseOutputTarget.PHONE_AUDIO

internal sealed interface SmartGlassesDisplayResult {
    data object Started : SmartGlassesDisplayResult
    data class Failed(val message: String, val cause: Throwable? = null) : SmartGlassesDisplayResult
}

internal data class ResponseDeliveryDecision(
    val useSmartGlasses: Boolean,
    val fallbackMessage: String? = null
)

internal fun decideResponseDelivery(
    target: ResponseOutputTarget,
    glassesResult: SmartGlassesDisplayResult?
): ResponseDeliveryDecision = when {
    target == ResponseOutputTarget.PHONE_AUDIO -> ResponseDeliveryDecision(useSmartGlasses = false)
    glassesResult is SmartGlassesDisplayResult.Started -> {
        ResponseDeliveryDecision(useSmartGlasses = true)
    }
    else -> ResponseDeliveryDecision(
        useSmartGlasses = false,
        fallbackMessage = SMART_GLASSES_FALLBACK_MESSAGE
    )
}

internal const val SMART_GLASSES_FALLBACK_MESSAGE =
    "Z100に表示できなかったため音声で再生します"
