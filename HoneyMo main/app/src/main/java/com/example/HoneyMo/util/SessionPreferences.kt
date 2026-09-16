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

    private const val KEY_FACE_CAM_ENABLED = "key_face_cam_enabled"

    fun setFaceCamEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_FACE_CAM_ENABLED, enabled).apply()
    }

    fun isFaceCamEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_FACE_CAM_ENABLED, true)
    }

    const val CAMERA_FACING_FRONT = "front"
    const val CAMERA_FACING_BACK = "back"
    private const val KEY_CAMERA_FACING = "key_camera_facing"

    fun setCameraFacing(context: Context, facing: String) {
        getPrefs(context).edit().putString(KEY_CAMERA_FACING, facing).apply()
    }

    fun getCameraFacing(context: Context): String {
        return getPrefs(context).getString(KEY_CAMERA_FACING, CAMERA_FACING_FRONT) ?: CAMERA_FACING_FRONT
    }
}
