package com.example.myapplication.service

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import rikka.shizuku.ShizukuProvider

object ShizukuAccessibilityKeeper {

    private const val LOG_TAG = "ShizukuA11yKeeper"

    sealed class RestoreResult {
        data object Success : RestoreResult()
        data class Failure(val reason: String) : RestoreResult()
    }

    fun canUseShizuku(): Boolean {
        return Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }

    fun warmUpBinder(context: Context): Boolean {
        if (Shizuku.pingBinder()) {
            return true
        }
        runCatching {
            ShizukuProvider.requestBinderForNonProviderProcess(context.applicationContext)
        }.onFailure {
            Log.w(LOG_TAG, "Failed to request Shizuku binder for process.", it)
        }
        return Shizuku.pingBinder()
    }

    suspend fun tryRestoreAccessibilityService(context: Context): RestoreResult {
        if (!waitForBinderReady(context)) {
            return RestoreResult.Failure("shizuku_binder_not_ready")
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return RestoreResult.Failure("shizuku_permission_not_granted")
        }

        val targetService = AutoSendAccessibilityHelper.getAutoSendServiceComponentName(context)
        val currentEnabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        val mergedServices = AutoSendAccessibilityHelper.mergeWithEnabledServices(
            current = currentEnabledServices,
            serviceToAdd = targetService,
        )

        val writeEnabledResult = runSettingsPutSecure(
            key = "enabled_accessibility_services",
            value = mergedServices,
        )
        if (!writeEnabledResult.success) {
            return RestoreResult.Failure("set_enabled_services_failed:${writeEnabledResult.message}")
        }

        val writeSwitchResult = runSettingsPutSecure(
            key = "accessibility_enabled",
            value = "1",
        )
        if (!writeSwitchResult.success) {
            return RestoreResult.Failure("set_accessibility_enabled_failed:${writeSwitchResult.message}")
        }

        Log.d(LOG_TAG, "Accessibility service restore command executed through Shizuku.")
        return RestoreResult.Success
    }

    private suspend fun waitForBinderReady(context: Context): Boolean {
        if (Shizuku.pingBinder()) {
            return true
        }

        repeat(BINDER_RETRY_COUNT) {
            warmUpBinder(context)
            if (Shizuku.pingBinder()) {
                return true
            }
            delay(BINDER_RETRY_DELAY_MS)
        }
        return Shizuku.pingBinder()
    }

    private data class CommandResult(
        val success: Boolean,
        val message: String,
    )

    private fun runSettingsPutSecure(key: String, value: String): CommandResult {
        val process = runCatching {
            createRemoteProcess(arrayOf("settings", "put", "secure", key, value))
        }.getOrElse {
            return CommandResult(false, "spawn_failed:${it.javaClass.simpleName}")
        }

        val stderr = runCatching {
            process.errorStream.bufferedReader().use { it.readText() }
        }.getOrDefault("")
        val stdout = runCatching {
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrDefault("")
        val exitCode = runCatching { process.waitFor() }.getOrDefault(-1)

        return if (exitCode == 0) {
            CommandResult(true, "ok")
        } else {
            val message = (stderr.ifBlank { stdout }).ifBlank { "exit=$exitCode" }
            CommandResult(false, message)
        }
    }

    private fun createRemoteProcess(cmd: Array<String>): ShizukuRemoteProcess {
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        val result = method.invoke(null, cmd, null, null)
        return result as ShizukuRemoteProcess
    }

    private const val BINDER_RETRY_COUNT = 6
    private const val BINDER_RETRY_DELAY_MS = 300L
}


