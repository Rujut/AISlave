package com.aislave.agent.agent

import android.content.Context
import com.aislave.agent.BuildConfig

class AiSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("ai_settings", Context.MODE_PRIVATE)

    fun backendUrl(): String = preferences.getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL).orEmpty().ifBlank { DEFAULT_BACKEND_URL }

    fun save(backendUrl: String) {
        preferences.edit()
            .putString(KEY_BACKEND_URL, backendUrl.trim().trimEnd('/').ifBlank { DEFAULT_BACKEND_URL })
            .apply()
    }

    companion object {
        const val DEFAULT_BACKEND_URL = BuildConfig.DEFAULT_BACKEND_URL
        private const val KEY_BACKEND_URL = "backend_url"
    }
}
