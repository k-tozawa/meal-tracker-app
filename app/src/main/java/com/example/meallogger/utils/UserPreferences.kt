package com.example.meallogger.utils

import android.content.Context
import android.content.SharedPreferences

class UserPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE
    )

    fun saveUserId(userId: String) {
        prefs.edit().putString(KEY_USER_ID, userId).apply()
    }

    fun getUserId(): String? {
        return prefs.getString(KEY_USER_ID, null)
    }

    fun isLoggedIn(): Boolean {
        return getUserId() != null
    }

    fun logout() {
        prefs.edit().remove(KEY_USER_ID).apply()
    }

    fun setDebugRecordingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEBUG_RECORDING, enabled).apply()
    }

    fun isDebugRecordingEnabled(): Boolean {
        return prefs.getBoolean(KEY_DEBUG_RECORDING, false)
    }

    fun setCameraRotation(rotation: Int) {
        prefs.edit().putInt(KEY_CAMERA_ROTATION, rotation).apply()
    }

    fun getCameraRotation(): Int {
        return prefs.getInt(KEY_CAMERA_ROTATION, android.view.Surface.ROTATION_270)
    }

    fun setVoiceVolume(volume: Float) {
        prefs.edit().putFloat(KEY_VOICE_VOLUME, volume).apply()
    }

    fun getVoiceVolume(): Float {
        return prefs.getFloat(KEY_VOICE_VOLUME, 1.0f)
    }

    fun setServerUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply()
    }

    fun getServerUrl(): String {
        return prefs.getString(KEY_SERVER_URL, "http://192.168.3.8:8000") ?: "http://192.168.3.8:8000"
    }

    companion object {
        private const val PREFS_NAME = "meal_logger_prefs"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_DEBUG_RECORDING = "debug_recording"
        private const val KEY_CAMERA_ROTATION = "camera_rotation"
        private const val KEY_VOICE_VOLUME = "voice_volume"
        private const val KEY_SERVER_URL = "server_url"
    }
}
