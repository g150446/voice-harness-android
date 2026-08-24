package com.g150446.voiceharness.assistant

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

object AssistantRoleManager {
    fun isAvailable(context: Context): Boolean =
        context.getSystemService(RoleManager::class.java)?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true

    fun isHeld(context: Context): Boolean =
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true

    fun requestIntent(context: Context): Intent =
        requireNotNull(context.getSystemService(RoleManager::class.java))
            .createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)

    fun fallbackSettingsIntent(): Intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
}
