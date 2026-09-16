package com.example.HoneyMo.util

import android.content.Context
import android.content.SharedPreferences

object SessionPreferences {
    private const val PREFS_NAME = "honeymo_session_prefs"
    private const val KEY_WAS_RECORDING_ACTIVE = "key_was_recording_active"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Marks whether screen recording is actively running.
     * When the device suddenly shuts down or reboots while this is true,
     * the app knows the recording was interrupted unexpectedly.
     */
    fun setRecordingActive(context: Context, isActive: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_WAS_RECORDING_ACTIVE, isActive).apply()
    }

    /**
     * Returns true if recording was active when the app was last running.
     */
    fun wasRecordingActive(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_WAS_RECORDING_ACTIVE, false)
    }
}
