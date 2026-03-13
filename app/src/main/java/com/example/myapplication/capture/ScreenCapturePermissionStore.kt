package com.example.myapplication.capture

import android.content.Context

class ScreenCapturePermissionStore(context: Context) {

    enum class Status {
        READY,
        STALE,
        MISSING,
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun markSessionStarted() {
        isSessionActive = true
        prefs.edit()
            .putLong(KEY_LAST_GRANTED_AT, System.currentTimeMillis())
            .apply()
    }

    fun markSessionEnded() {
        isSessionActive = false
    }

    fun getStatus(): Status = when {
        isSessionActive -> Status.READY
        prefs.contains(KEY_LAST_GRANTED_AT) -> Status.STALE
        else -> Status.MISSING
    }

    fun getLastGrantedAtMillis(): Long? =
        prefs.getLong(KEY_LAST_GRANTED_AT, 0L).takeIf { it > 0L }

    fun clear() {
        isSessionActive = false
        prefs.edit().remove(KEY_LAST_GRANTED_AT).apply()
    }

    companion object {
        private const val PREFS_NAME = "screen_capture_permission_store"
        private const val KEY_LAST_GRANTED_AT = "last_granted_at"

        @Volatile
        private var isSessionActive: Boolean = false
    }
}

