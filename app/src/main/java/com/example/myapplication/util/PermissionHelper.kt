package com.example.myapplication.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionHelper {

	fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

	fun buildOverlayPermissionIntent(context: Context): Intent =
		Intent(
			Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
			Uri.parse("package:${context.packageName}"),
		).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

	fun hasNotificationPermission(context: Context): Boolean {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
			return true
		}
		return ContextCompat.checkSelfPermission(
			context,
			Manifest.permission.POST_NOTIFICATIONS,
		) == android.content.pm.PackageManager.PERMISSION_GRANTED
	}
}

