package com.example.myapplication.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.R

object NotificationHelper {

	const val SERVICE_CHANNEL_ID = "floating_capture_service"
	const val SERVICE_NOTIFICATION_ID = 1001
	const val GUARD_CHANNEL_ID = "accessibility_guard"
	const val GUARD_NOTIFICATION_ID = 1002

	fun ensureServiceChannel(context: Context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			return
		}

		val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
		val channel = NotificationChannel(
			SERVICE_CHANNEL_ID,
			context.getString(R.string.service_channel_name),
			NotificationManager.IMPORTANCE_LOW,
		).apply {
			description = context.getString(R.string.service_channel_description)
		}
		manager.createNotificationChannel(channel)
	}

	fun ensureGuardChannel(context: Context) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			return
		}

		val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
		val channel = NotificationChannel(
			GUARD_CHANNEL_ID,
			context.getString(R.string.guard_channel_name),
			NotificationManager.IMPORTANCE_DEFAULT,
		).apply {
			description = context.getString(R.string.guard_channel_description)
		}
		manager.createNotificationChannel(channel)
	}

	fun buildServiceNotification(context: Context): Notification {
		val contentIntent = PendingIntent.getActivity(
			context,
			0,
			Intent(context, MainActivity::class.java).apply {
				flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
			},
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
		)

		return NotificationCompat.Builder(context, SERVICE_CHANNEL_ID)
			.setSmallIcon(R.mipmap.ic_launcher)
			.setContentTitle(context.getString(R.string.service_notification_title))
			.setContentText(context.getString(R.string.service_notification_text))
			.setContentIntent(contentIntent)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.build()
	}

	fun notifyGuardDisabled(context: Context) {
		ensureGuardChannel(context)
		val contentIntent = PendingIntent.getActivity(
			context,
			1,
			Intent(context, MainActivity::class.java).apply {
				flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
			},
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
		)

		val notification = NotificationCompat.Builder(context, GUARD_CHANNEL_ID)
			.setSmallIcon(R.mipmap.ic_launcher)
			.setContentTitle(context.getString(R.string.guard_notification_title))
			.setContentText(context.getString(R.string.guard_notification_text))
			.setContentIntent(contentIntent)
			.setAutoCancel(true)
			.setPriority(NotificationCompat.PRIORITY_DEFAULT)
			.build()

		runCatching {
			NotificationManagerCompat.from(context).notify(GUARD_NOTIFICATION_ID, notification)
		}
	}
}

