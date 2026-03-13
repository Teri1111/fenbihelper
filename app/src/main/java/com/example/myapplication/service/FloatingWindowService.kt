package com.example.myapplication.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.myapplication.MainActivity
import com.example.myapplication.R
import com.example.myapplication.capture.ScreenCaptureManager
import com.example.myapplication.capture.ScreenCapturePermissionStore
import com.example.myapplication.data.CaptureOutcome
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import com.example.myapplication.share.ShareManager
import com.example.myapplication.util.NotificationHelper
import com.example.myapplication.util.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import java.util.Locale

class FloatingWindowService : Service() {

	private val logTag = "FloatingWindowService"

	private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

	private lateinit var windowManager: WindowManager
	private lateinit var mediaProjectionManager: MediaProjectionManager
	private lateinit var permissionStore: ScreenCapturePermissionStore
	private lateinit var screenCaptureManager: ScreenCaptureManager
	private lateinit var shareManager: ShareManager

	private var floatingContainer: LinearLayout? = null
	private var captureButton: Button? = null
	private var layoutParams: WindowManager.LayoutParams? = null
	private var isProcessing = false
	private var mediaProjection: MediaProjection? = null
	private var mediaProjectionCallback: MediaProjection.Callback? = null
	private val isStoppingSession = AtomicBoolean(false)
	private var guardMonitorJob: Job? = null
	private var lastGuardNotificationAtMillis: Long = 0L
	private var lastShizukuRestoreAttemptAtMillis: Long = 0L

	override fun onCreate() {
		super.onCreate()
		windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
		mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
		permissionStore = ScreenCapturePermissionStore(this)
		screenCaptureManager = ScreenCaptureManager(this)
		shareManager = ShareManager(this)
		ShizukuAccessibilityKeeper.warmUpBinder(this)
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		val action = intent?.action ?: ACTION_SHOW_OVERLAY
		Log.d(logTag, "onStartCommand action=$action, hasOverlayPermission=${PermissionHelper.canDrawOverlays(this)}")

		NotificationHelper.ensureServiceChannel(this)
		startForegroundCompat()
		isRunning = true
		startGuardMonitorIfNeeded()

		when (action) {
			ACTION_START_SESSION -> {
				if (!ensureProjectionSession(intent)) {
					toast(getString(R.string.service_capture_reauth_required))
					stopSelf()
					return START_NOT_STICKY
				}

				if (intent?.getBooleanExtra(EXTRA_SHOW_OVERLAY, false) == true) {
					if (!ensureOverlayVisible()) {
						stopSelf()
						return START_NOT_STICKY
					}
				}
			}

			ACTION_CAPTURE_ONCE -> {
				val stopSelfWhenFinished = intent?.getBooleanExtra(EXTRA_STOP_WHEN_FINISHED, floatingContainer == null)
					?: (floatingContainer == null)
				if (!ensureProjectionSession(intent)) {
					toast(getString(R.string.service_capture_reauth_required))
					if (stopSelfWhenFinished && floatingContainer == null) {
						stopSelf()
					}
					return START_NOT_STICKY
				}
				performCaptureAndShare(stopSelfWhenFinished = stopSelfWhenFinished)
			}

			else -> {
				if (mediaProjection == null) {
					toast(getString(R.string.service_capture_reauth_required))
					stopSelf()
					return START_NOT_STICKY
				}
				if (!ensureOverlayVisible()) {
					stopSelf()
					return START_NOT_STICKY
				}
			}
		}
		return START_STICKY
	}

	override fun onDestroy() {
		val hadOverlay = floatingContainer != null
		removeFloatingButton()
		stopProjectionSession(reason = "service_destroyed")
		guardMonitorJob?.cancel()
		guardMonitorJob = null
		serviceScope.cancel()
		isRunning = false
		if (hadOverlay) {
			toast(getString(R.string.service_stopped))
		}
		super.onDestroy()
	}

	override fun onBind(intent: Intent?): IBinder? = null

	private fun ensureProjectionSession(intent: Intent?): Boolean {
		mediaProjection?.let { return true }

		val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
		val permissionData = intent?.getIntentExtraCompat(EXTRA_PERMISSION_DATA)
		if (resultCode == Int.MIN_VALUE || permissionData == null) {
			Log.w(logTag, "Missing screen capture permission data when trying to start session.")
			return false
		}

		val projection = mediaProjectionManager.getMediaProjection(resultCode, permissionData)
		if (projection == null) {
			Log.w(logTag, "MediaProjectionManager returned null while starting session.")
			return false
		}

		isStoppingSession.set(false)
		val callback = object : MediaProjection.Callback() {
			override fun onStop() {
				Log.d(logTag, "MediaProjection callback onStop invoked for service session.")
				stopProjectionSession(reason = "projection_callback_onStop")
				stopSelf()
			}
		}
		projection.registerCallback(callback, Handler(Looper.getMainLooper()))
		screenCaptureManager.startSession(projection)?.let { failure ->
			Log.w(logTag, "Failed to initialize capture session while starting projection: ${failure.message}")
			runCatching { projection.unregisterCallback(callback) }
			runCatching { projection.stop() }
			return false
		}
		mediaProjection = projection
		mediaProjectionCallback = callback
		permissionStore.markSessionStarted()
		Log.d(logTag, "Projection session started successfully.")
		return true
	}

	private fun ensureOverlayVisible(): Boolean {
		if (!PermissionHelper.canDrawOverlays(this)) {
			toast(getString(R.string.service_missing_overlay_permission))
			return false
		}

		if (floatingContainer == null) {
			showFloatingButton()
			toast(getString(R.string.service_started))
		}
		return true
	}

	private fun showFloatingButton() {
		val container = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
		}

		val screenshotButton = Button(this).apply {
			text = getString(R.string.service_overlay_button_text)
			textSize = 14f
			isAllCaps = false
			minWidth = 0
			minHeight = 0
			minimumWidth = 0
			minimumHeight = 0
			setPadding(compactButtonPaddingPx, compactButtonPaddingPx, compactButtonPaddingPx, compactButtonPaddingPx)
			setOnClickListener {
				performCaptureAndShare()
			}
			setOnLongClickListener {
				stopSelf()
				true
			}
		}

		val backToFenbiButton = Button(this).apply {
			text = getString(R.string.service_overlay_back_to_fenbi_text)
			textSize = 14f
			isAllCaps = false
			minWidth = 0
			minHeight = 0
			minimumWidth = 0
			minimumHeight = 0
			setPadding(compactButtonPaddingPx, compactButtonPaddingPx, compactButtonPaddingPx, compactButtonPaddingPx)
			setOnClickListener {
				returnToFenbiApp()
			}
		}

		val buttonLayoutParams = LinearLayout.LayoutParams(
			compactButtonSizePx,
			compactButtonSizePx,
		).apply {
			bottomMargin = compactButtonSpacingPx
		}
		container.addView(screenshotButton, buttonLayoutParams)
		container.addView(
			backToFenbiButton,
			LinearLayout.LayoutParams(
				compactButtonSizePx,
				compactButtonSizePx,
			),
		)

		val params = WindowManager.LayoutParams(
			WindowManager.LayoutParams.WRAP_CONTENT,
			WindowManager.LayoutParams.WRAP_CONTENT,
			WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
			WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
			PixelFormat.TRANSLUCENT,
		).apply {
			gravity = Gravity.TOP or Gravity.END
			x = 40
			y = 240
		}

		installTouchHandler(container, params)
		installTouchHandler(screenshotButton, params)
		installTouchHandler(backToFenbiButton, params)
		windowManager.addView(container, params)
		floatingContainer = container
		captureButton = screenshotButton
		layoutParams = params
	}

	@SuppressLint("ClickableViewAccessibility")
	private fun installTouchHandler(target: View, params: WindowManager.LayoutParams) {
		target.setOnTouchListener(object : View.OnTouchListener {
			private var initialX = 0
			private var initialY = 0
			private var initialTouchX = 0f
			private var initialTouchY = 0f

			override fun onTouch(view: View, event: MotionEvent): Boolean {
				when (event.actionMasked) {
					MotionEvent.ACTION_DOWN -> {
						initialX = params.x
						initialY = params.y
						initialTouchX = event.rawX
						initialTouchY = event.rawY
						return true
					}

					MotionEvent.ACTION_MOVE -> {
						params.x = initialX - (event.rawX - initialTouchX).toInt()
						params.y = initialY + (event.rawY - initialTouchY).toInt()
						val overlayView = floatingContainer ?: view
						windowManager.updateViewLayout(overlayView, params)
						return true
					}

					MotionEvent.ACTION_UP -> {
						val movedX = abs(event.rawX - initialTouchX)
						val movedY = abs(event.rawY - initialTouchY)
						if (movedX < CLICK_THRESHOLD_PX && movedY < CLICK_THRESHOLD_PX) {
							view.performClick()
						}
						return true
					}

					MotionEvent.ACTION_CANCEL -> {
						return true
					}
				}
				return false
			}
		})
	}

	private fun performCaptureAndShare(stopSelfWhenFinished: Boolean = false) {
		if (isProcessing) {
			return
		}
		if (!AutoSendAccessibilityHelper.isAutoSendServiceEnabled(this)) {
			toast(getString(R.string.auto_send_accessibility_required_redirect))
			openAccessibilitySettings()
			if (stopSelfWhenFinished && floatingContainer == null) {
				stopSelf()
			}
			return
		}
		val activeProjection = mediaProjection
		if (activeProjection == null) {
			toast(getString(R.string.service_capture_reauth_required))
			openCapturePermissionScreen()
			if (stopSelfWhenFinished && floatingContainer == null) {
				stopSelf()
			}
			return
		}

		isProcessing = true
		updateButtonState(isEnabled = false, text = getString(R.string.service_overlay_working_text))

		serviceScope.launch {
			try {
				setOverlayVisibleForCapture(isVisible = false)
				delay(OVERLAY_HIDE_BEFORE_CAPTURE_DELAY_MS)
				Log.d(logTag, "Starting capture from service. stopSelfWhenFinished=$stopSelfWhenFinished")
				when (val captureOutcome = screenCaptureManager.captureScreen(activeProjection)) {
					is CaptureOutcome.Success -> {
						when (val shareOutcome = shareManager.shareImage(captureOutcome.result.uri)) {
							ShareManager.ShareOutcome.SharedToDoubao -> {
								DoubaoAutoSendCoordinator.markPending()
								toast(getString(R.string.shared_to_doubao))
								if (!AutoSendAccessibilityHelper.isAutoSendServiceEnabled(this@FloatingWindowService)) {
									toast(getString(R.string.auto_send_accessibility_not_enabled))
								}
							}

							ShareManager.ShareOutcome.SharedWithChooser -> {
								toast(getString(R.string.shared_with_chooser))
							}

							is ShareManager.ShareOutcome.Failure -> {
								toast(shareOutcome.message.ifBlank { getString(R.string.service_share_failed) })
							}
						}
					}

					is CaptureOutcome.Failure -> {
						if (captureOutcome.requiresReauthorization) {
							stopProjectionSession(reason = "capture_requires_reauthorization")
							toast(getString(R.string.service_capture_reauth_required))
							openCapturePermissionScreen()
							if (floatingContainer != null) {
								stopSelf()
							}
						} else {
							toast(captureOutcome.message)
						}
					}
				}
			} finally {
				delay(OVERLAY_RESTORE_AFTER_CAPTURE_DELAY_MS)
				setOverlayVisibleForCapture(isVisible = true)
				isProcessing = false
				updateButtonState(isEnabled = true, text = getString(R.string.service_overlay_button_text))
				if (stopSelfWhenFinished && floatingContainer == null) {
					stopSelf()
				}
			}
		}
	}

	private fun setOverlayVisibleForCapture(isVisible: Boolean) {
		floatingContainer?.let { container ->
			container.visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
			container.alpha = if (isVisible) 1f else 0f
		}
	}

	private fun returnToFenbiApp() {
		val launchIntent = findFenbiLaunchIntent()
		if (launchIntent == null) {
			toast(getString(R.string.service_fenbi_not_found))
			return
		}
		launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		runCatching {
			startActivity(launchIntent)
		}.onFailure {
			Log.e(logTag, "Failed to open Fenbi app from floating overlay.", it)
			toast(getString(R.string.service_fenbi_open_failed))
		}
	}

	private fun findFenbiLaunchIntent(): Intent? {
		knownFenbiPackages.firstNotNullOfOrNull { packageName ->
			packageManager.getLaunchIntentForPackage(packageName)
		}?.let { return it }

		val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
		val resolvedActivities = packageManager.queryIntentActivities(launcherIntent, 0)
		val matched = resolvedActivities.firstOrNull { resolveInfo ->
			matchesFenbi(resolveInfo)
		} ?: return null

		return Intent(launcherIntent).apply {
			setClassName(matched.activityInfo.packageName, matched.activityInfo.name)
		}
	}

	private fun matchesFenbi(resolveInfo: ResolveInfo): Boolean {
		val packageName = resolveInfo.activityInfo.packageName.lowercase(Locale.ROOT)
		val activityName = resolveInfo.activityInfo.name.lowercase(Locale.ROOT)
		if (packageName.contains("fenbi") || activityName.contains("fenbi")) {
			return true
		}

		val activityLabel = resolveInfo.loadLabel(packageManager).toString()
		val applicationLabel = runCatching {
			resolveInfo.activityInfo.applicationInfo.loadLabel(packageManager).toString()
		}.getOrDefault("")
		return activityLabel.contains("粉笔") || applicationLabel.contains("粉笔")
	}

	private fun stopProjectionSession(reason: String) {
		if (!isStoppingSession.compareAndSet(false, true)) {
			return
		}
		Log.d(logTag, "Stopping projection session. reason=$reason")
		permissionStore.markSessionEnded()
		screenCaptureManager.endSession()
		val callback = mediaProjectionCallback
		val projection = mediaProjection
		mediaProjectionCallback = null
		mediaProjection = null
		runCatching {
			if (callback != null && projection != null) {
				projection.unregisterCallback(callback)
			}
		}
		runCatching { projection?.stop() }
	}

	private fun startForegroundCompat() {
		val notification = NotificationHelper.buildServiceNotification(this)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			startForeground(
				NotificationHelper.SERVICE_NOTIFICATION_ID,
				notification,
				ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
			)
		} else {
			startForeground(NotificationHelper.SERVICE_NOTIFICATION_ID, notification)
		}
	}

	private fun startGuardMonitorIfNeeded() {
		if (guardMonitorJob?.isActive == true) {
			return
		}
		guardMonitorJob = serviceScope.launch {
			while (true) {
				if (AutoSendAccessibilityHelper.isGuardEnabled(this@FloatingWindowService) &&
					!AutoSendAccessibilityHelper.isAutoSendServiceEnabled(this@FloatingWindowService)
				) {
					val restoreNow = System.currentTimeMillis() - lastShizukuRestoreAttemptAtMillis >= SHIZUKU_RESTORE_COOLDOWN_MS
					if (restoreNow) {
						lastShizukuRestoreAttemptAtMillis = System.currentTimeMillis()
						when (val restoreResult = ShizukuAccessibilityKeeper.tryRestoreAccessibilityService(this@FloatingWindowService)) {
							is ShizukuAccessibilityKeeper.RestoreResult.Success -> {
								Log.d(logTag, "Shizuku restored accessibility service successfully.")
								delay(1000L)
								continue
							}

							is ShizukuAccessibilityKeeper.RestoreResult.Failure -> {
								Log.d(logTag, "Shizuku restore failed: ${restoreResult.reason}")
							}
						}
					}

					val now = System.currentTimeMillis()
					if (now - lastGuardNotificationAtMillis >= GUARD_NOTIFY_COOLDOWN_MS) {
						lastGuardNotificationAtMillis = now
						NotificationHelper.notifyGuardDisabled(this@FloatingWindowService)
					}
				}
				delay(GUARD_CHECK_INTERVAL_MS)
			}
		}
	}

	private fun updateButtonState(isEnabled: Boolean, text: String) {
		captureButton?.let { button ->
			button.isEnabled = isEnabled
			button.text = text
		}
	}

	private fun removeFloatingButton() {
		floatingContainer?.let { container ->
			runCatching { windowManager.removeView(container) }
		}
		floatingContainer = null
		captureButton = null
		layoutParams = null
	}

	private fun toast(message: String) {
		Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
	}

	private fun openCapturePermissionScreen() {
		runCatching {
			startActivity(MainActivity.buildCapturePermissionShortcutIntent(this))
		}.onFailure {
			Log.e(logTag, "Failed to open capture permission shortcut screen.", it)
		}
	}

	private fun openAccessibilitySettings() {
		runCatching {
			startActivity(AutoSendAccessibilityHelper.buildAccessibilitySettingsIntent())
		}.onFailure {
			Log.e(logTag, "Failed to open accessibility settings.", it)
		}
	}

	@Suppress("DEPRECATION")
	private fun Intent.getIntentExtraCompat(name: String): Intent? {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			getParcelableExtra(name, Intent::class.java)
		} else {
			getParcelableExtra(name)
		}
	}

	companion object {
		private const val CLICK_THRESHOLD_PX = 12
		private const val ACTION_START_SESSION = "com.example.myapplication.action.START_SESSION"
		private const val ACTION_SHOW_OVERLAY = "com.example.myapplication.action.SHOW_OVERLAY"
		private const val ACTION_CAPTURE_ONCE = "com.example.myapplication.action.CAPTURE_ONCE"
		private const val EXTRA_RESULT_CODE = "extra_result_code"
		private const val EXTRA_PERMISSION_DATA = "extra_permission_data"
		private const val EXTRA_SHOW_OVERLAY = "extra_show_overlay"
		private const val EXTRA_STOP_WHEN_FINISHED = "extra_stop_when_finished"
		private const val compactButtonPaddingDp = 12
		private const val compactButtonSpacingDp = 4
		private const val compactButtonSizeDp = 56
		private const val OVERLAY_HIDE_BEFORE_CAPTURE_DELAY_MS = 120L
		private const val OVERLAY_RESTORE_AFTER_CAPTURE_DELAY_MS = 120L
		private const val GUARD_CHECK_INTERVAL_MS = 15_000L
		private const val SHIZUKU_RESTORE_COOLDOWN_MS = 60_000L
		private const val GUARD_NOTIFY_COOLDOWN_MS = 10 * 60 * 1000L
		private val knownFenbiPackages = listOf(
			"com.fenbi.android.solar",
			"com.fenbi.android.leo",
		)

		@Volatile
		var isRunning: Boolean = false
			private set

		private val compactButtonPaddingPx: Int
			get() = (compactButtonPaddingDp * appDensity).toInt()

		private val compactButtonSpacingPx: Int
			get() = (compactButtonSpacingDp * appDensity).toInt()

		private val compactButtonSizePx: Int
			get() = (compactButtonSizeDp * appDensity).toInt()

		private val appDensity: Float
			get() = Resources.getSystem().displayMetrics.density

		fun startWithPermission(context: Context, resultCode: Int, data: Intent) {
			val intent = Intent(context, FloatingWindowService::class.java).apply {
				action = ACTION_START_SESSION
				putExtra(EXTRA_RESULT_CODE, resultCode)
				putExtra(EXTRA_PERMISSION_DATA, data)
				putExtra(EXTRA_SHOW_OVERLAY, true)
			}
			ContextCompat.startForegroundService(context, intent)
		}

		fun startSession(context: Context, resultCode: Int, data: Intent) {
			val intent = Intent(context, FloatingWindowService::class.java).apply {
				action = ACTION_START_SESSION
				putExtra(EXTRA_RESULT_CODE, resultCode)
				putExtra(EXTRA_PERMISSION_DATA, data)
				putExtra(EXTRA_SHOW_OVERLAY, false)
			}
			ContextCompat.startForegroundService(context, intent)
		}

		fun start(context: Context) {
			val intent = Intent(context, FloatingWindowService::class.java).apply {
				action = ACTION_SHOW_OVERLAY
			}
			ContextCompat.startForegroundService(context, intent)
		}

		fun captureOnce(context: Context) {
			val intent = Intent(context, FloatingWindowService::class.java).apply {
				action = ACTION_CAPTURE_ONCE
				putExtra(EXTRA_STOP_WHEN_FINISHED, false)
			}
			ContextCompat.startForegroundService(context, intent)
		}

		fun captureOnce(context: Context, resultCode: Int, data: Intent) {
			val intent = Intent(context, FloatingWindowService::class.java).apply {
				action = ACTION_CAPTURE_ONCE
				putExtra(EXTRA_RESULT_CODE, resultCode)
				putExtra(EXTRA_PERMISSION_DATA, data)
				putExtra(EXTRA_STOP_WHEN_FINISHED, true)
			}
			ContextCompat.startForegroundService(context, intent)
		}

		fun stop(context: Context) {
			context.stopService(Intent(context, FloatingWindowService::class.java))
		}
	}
}

