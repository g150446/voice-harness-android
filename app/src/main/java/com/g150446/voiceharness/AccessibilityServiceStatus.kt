package com.g150446.voiceharness

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Flattened component id for [RingAccessibilityService] in Secure settings. */
internal fun ringAccessibilityComponentFlat(context: Context): String =
    ComponentName(context, RingAccessibilityService::class.java).flattenToString()

/**
 * Whether [enabledServicesSetting] (colon-separated Secure setting value) lists [componentFlat].
 * Pure string compare so unit tests do not need Android ComponentName stubs.
 */
internal fun isAccessibilityServiceListed(
    enabledServicesSetting: String?,
    componentFlat: String,
): Boolean {
    if (enabledServicesSetting.isNullOrBlank() || componentFlat.isBlank()) return false
    val expected = componentFlat.trim()
    val expectedSlash = expected.replace('/', '.')
    return enabledServicesSetting.split(':').any { entry ->
        val name = entry.trim()
        if (name.isEmpty()) return@any false
        name.equals(expected, ignoreCase = true) ||
            name.replace('/', '.').equals(expectedSlash, ignoreCase = true)
    }
}

/** True when the user has enabled [RingAccessibilityService] in system settings. */
internal fun isRingAccessibilityEnabled(context: Context): Boolean {
    val setting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    )
    return isAccessibilityServiceListed(setting, ringAccessibilityComponentFlat(context))
}

/**
 * Opens the system accessibility settings list.
 *
 * Avoid ACTION_ACCESSIBILITY_DETAILS_SETTINGS: several OEMs (incl. Motorola)
 * crash Settings or the caller when the detail intent is used via
 * Activity Result APIs.
 */
internal fun accessibilitySettingsIntent(@Suppress("UNUSED_PARAMETER") context: Context): Intent =
    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
