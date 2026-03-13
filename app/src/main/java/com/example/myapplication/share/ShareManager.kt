package com.example.myapplication.share

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.util.Log

class ShareManager(context: Context) {

	private val logTag = "ShareManager"

	sealed interface ShareOutcome {
		data object SharedToDoubao : ShareOutcome
		data object SharedWithChooser : ShareOutcome
		data class Failure(val message: String) : ShareOutcome
	}

	private val appContext = context.applicationContext

	fun shareImage(uri: Uri): ShareOutcome {
		val baseIntent = buildBaseShareIntent(uri)
		val resolvers = queryActivities(baseIntent)
		Log.d(logTag, "Found ${resolvers.size} share targets for image share.")
		if (resolvers.isEmpty()) {
			return ShareOutcome.Failure("未找到可用的分享应用。")
		}

		grantReadPermission(uri, resolvers)

		return try {
			val packageManager = appContext.packageManager
			val doubaoTarget = DoubaoAppMatcher.findTargetResolveInfo(packageManager, resolvers)
			if (doubaoTarget != null) {
				Log.d(
					logTag,
					"Launching direct share target ${doubaoTarget.activityInfo.packageName}/${doubaoTarget.activityInfo.name}",
				)
				appContext.startActivity(
					Intent(baseIntent).apply {
						setClassName(
							doubaoTarget.activityInfo.packageName,
							doubaoTarget.activityInfo.name,
						)
					},
				)
				ShareOutcome.SharedToDoubao
			} else {
				Log.d(logTag, "Doubao not matched, falling back to system chooser.")
				appContext.startActivity(
					Intent.createChooser(baseIntent, appContext.getString(com.example.myapplication.R.string.app_name))
						.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
				)
				ShareOutcome.SharedWithChooser
			}
		} catch (_: ActivityNotFoundException) {
			ShareOutcome.Failure("未找到可以处理分享的应用。")
		} catch (exception: Exception) {
			ShareOutcome.Failure(exception.message ?: "分享失败，请稍后重试。")
		}
	}

	private fun buildBaseShareIntent(uri: Uri): Intent =
		Intent(Intent.ACTION_SEND).apply {
			type = "image/*"
			putExtra(Intent.EXTRA_STREAM, uri)
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		}

	@Suppress("DEPRECATION")
	private fun queryActivities(intent: Intent): List<ResolveInfo> {
		val packageManager = appContext.packageManager
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			packageManager.queryIntentActivities(
				intent,
				PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
			)
		} else {
			packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
		}
	}

	private fun grantReadPermission(uri: Uri, resolvers: List<ResolveInfo>) {
		resolvers.forEach { resolveInfo ->
			appContext.grantUriPermission(
				resolveInfo.activityInfo.packageName,
				uri,
				Intent.FLAG_GRANT_READ_URI_PERMISSION,
			)
		}
	}
}

