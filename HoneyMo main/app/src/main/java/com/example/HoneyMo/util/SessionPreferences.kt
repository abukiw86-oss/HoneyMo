package com.example.HoneyMo.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

object SessionPreferences {
    private const val PREFS_NAME = "honeymo_session_prefs"
    private const val KEY_WAS_RECORDING_ACTIVE = "key_was_recording_active"

    private fun getPrefs(context: Context): SharedPreferences {
        val safeContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val deContext = context.createDeviceProtectedStorageContext()
            // Migrate from CE to DE if DE preferences don't exist yet
            try {
                if (!deContext.moveSharedPreferencesFrom(context, PREFS_NAME)) {
                    // Already moved or not present in CE
                }
            } catch (e: Exception) {
                // Ignore migration error if already moved
            }
            deContext
        } else {
            context
        }
        return safeContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Marks whether screen recording is actively running.
     * Uses synchronous commit() to ensure the state is persisted to disk immediately,
     * surviving sudden power-offs and reboots.
     */
    fun setRecordingActive(context: Context, isActive: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_WAS_RECORDING_ACTIVE, isActive).commit()
    }

    /**
     * Returns true if recording was active when the app was last running.
     * Safe to call during Direct Boot before the device is unlocked.
     */
    fun wasRecordingActive(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_WAS_RECORDING_ACTIVE, false)
    }

    private const val KEY_FACE_CAM_ENABLED = "key_face_cam_enabled"

    fun setFaceCamEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_FACE_CAM_ENABLED, enabled).commit()
    }

    fun isFaceCamEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_FACE_CAM_ENABLED, true)
    }

    const val CAMERA_FACING_FRONT = "front"
    const val CAMERA_FACING_BACK = "back"
    private const val KEY_CAMERA_FACING = "key_camera_facing"

    fun setCameraFacing(context: Context, facing: String) {
        getPrefs(context).edit().putString(KEY_CAMERA_FACING, facing).commit()
    }

    fun getCameraFacing(context: Context): String {
        return getPrefs(context).getString(KEY_CAMERA_FACING, CAMERA_FACING_FRONT) ?: CAMERA_FACING_FRONT
    }

    private const val KEY_USERNAME = "key_username"
    private const val KEY_AGENT_ENABLED = "key_agent_enabled"

    // Username storage
    fun getUsername(context: Context): String {
        return getPrefs(context).getString(KEY_USERNAME, "User") ?: "User"
    }
    fun setUsername(context: Context, username: String) {
        getPrefs(context).edit().putString(KEY_USERNAME, username).commit()
    }
    fun hasUsername(context: Context): Boolean {
        return getPrefs(context).contains(KEY_USERNAME)
    }

    // Agent state
    fun setAgentEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AGENT_ENABLED, enabled).commit()
    }
    fun isAgentEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AGENT_ENABLED, false)
    }
}
