package com.example.myapplication.share

import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.util.Log
import java.util.Locale

object DoubaoAppMatcher {

	private const val logTag = "DoubaoAppMatcher"

	private val knownPackageNames = listOf(
		"com.larus.nova",
		"com.bytedance.doubao",
	)

	fun findTargetResolveInfo(
		packageManager: PackageManager,
		resolvedActivities: List<ResolveInfo>,
	): ResolveInfo? {
		if (resolvedActivities.isEmpty()) {
			Log.d(logTag, "No share targets available for matching.")
			return null
		}

		resolvedActivities.forEach { resolveInfo ->
			val packageName = resolveInfo.activityInfo.packageName
			val activityName = resolveInfo.activityInfo.name
			val activityLabel = resolveInfo.loadLabel(packageManager).toString()
			val applicationLabel = runCatching {
				resolveInfo.activityInfo.applicationInfo.loadLabel(packageManager).toString()
			}.getOrDefault("")
			Log.d(
				logTag,
				"Share target candidate package=$packageName, activity=$activityName, activityLabel=$activityLabel, applicationLabel=$applicationLabel",
			)
		}

		resolvedActivities.firstOrNull { it.activityInfo.packageName in knownPackageNames }
			?.let {
				Log.d(logTag, "Matched Doubao by known package: ${it.activityInfo.packageName}")
				return it
			}

		resolvedActivities.firstOrNull { resolveInfo ->
			val packageName = resolveInfo.activityInfo.packageName.lowercase(Locale.ROOT)
			val activityName = resolveInfo.activityInfo.name.lowercase(Locale.ROOT)
			packageName.contains("doubao") ||
				packageName.contains("nova") ||
				activityName.contains("doubao") ||
				activityName.contains("nova")
		}?.let {
			Log.d(logTag, "Matched Doubao by package/activity keyword: ${it.activityInfo.packageName}/${it.activityInfo.name}")
			return it
		}

		resolvedActivities.firstOrNull { resolveInfo ->
			val activityLabel = resolveInfo.loadLabel(packageManager).toString()
			val applicationLabel = runCatching {
				resolveInfo.activityInfo.applicationInfo.loadLabel(packageManager).toString()
			}.getOrDefault("")
			activityLabel.contains("豆包") || applicationLabel.contains("豆包")
		}?.let {
			Log.d(logTag, "Matched Doubao by Chinese label: ${it.activityInfo.packageName}/${it.activityInfo.name}")
			return it
		}

		Log.d(logTag, "No Doubao share target matched from ${resolvedActivities.size} candidates.")
		return null
	}
}

