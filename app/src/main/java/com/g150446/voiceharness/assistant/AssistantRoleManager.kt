package com.g150446.voiceharness.assistant

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

object AssistantRoleManager {
    fun isHeld(context: Context): Boolean =
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true

    fun settingsIntent(): Intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
}
