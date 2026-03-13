package com.example.myapplication.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import java.util.Locale

object AutoSendAccessibilityHelper {

    private const val PREFS_NAME = "auto_send_guard"
    private const val KEY_GUARD_ENABLED = "guard_enabled"

    fun buildAccessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun isAutoSendServiceEnabled(context: Context): Boolean {
        val expectedComponents = buildServiceComponentNames(context)

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        return enabledServices
            .split(':')
            .map { it.trim().lowercase() }
            .any { it in expectedComponents }
    }

    fun getAutoSendServiceComponentName(context: Context): String {
        val componentName = ComponentName(
            context.packageName,
            "${context.packageName}.service.DoubaoAutoSendAccessibilityService",
        )
        return componentName.flattenToString()
    }

    fun mergeWithEnabledServices(current: String?, serviceToAdd: String): String {
        val canonicalToAdd = serviceToAdd.trim()
        val normalizedToAdd = canonicalToAdd.lowercase(Locale.ROOT)
        val merged = linkedMapOf<String, String>()

        current
            ?.split(':')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.forEach { entry ->
                merged[entry.lowercase(Locale.ROOT)] = entry
            }

        if (!merged.containsKey(normalizedToAdd)) {
            merged[normalizedToAdd] = canonicalToAdd
        }
        return merged.values.joinToString(":")
    }

    fun isGuardEnabled(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_GUARD_ENABLED, false)
    }

    fun setGuardEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GUARD_ENABLED, enabled)
            .apply()
    }

    private fun buildServiceComponentNames(context: Context): Set<String> {
        val componentName = ComponentName(
            context.packageName,
            "${context.packageName}.service.DoubaoAutoSendAccessibilityService",
        )
        return setOf(
            componentName.flattenToString().lowercase(),
            componentName.flattenToShortString().lowercase(),
        )
    }
}

