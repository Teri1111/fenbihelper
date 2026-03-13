package com.example.myapplication

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.myapplication.capture.ScreenCapturePermissionStore
import com.example.myapplication.service.AutoSendAccessibilityHelper
import com.example.myapplication.service.FloatingWindowService
import com.example.myapplication.service.ShizukuAccessibilityKeeper
import com.example.myapplication.util.PermissionHelper
import java.text.DateFormat
import java.util.Date
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider

class MainActivity : AppCompatActivity() {

    private val logTag = "MainActivity"

    private lateinit var permissionStore: ScreenCapturePermissionStore
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private lateinit var overlayStatusView: TextView
    private lateinit var overlayStatusRow: View
    private lateinit var notificationStatusView: TextView
    private lateinit var notificationStatusRow: View
    private lateinit var captureStatusView: TextView
    private lateinit var captureStatusRow: View
    private lateinit var autoSendStatusView: TextView
    private lateinit var autoSendStatusRow: View
    private lateinit var shizukuStatusView: TextView
    private lateinit var shizukuStatusRow: View
    private lateinit var guardStatusView: TextView
    private lateinit var serviceStatusView: TextView
    private lateinit var lastPermissionGrantView: TextView
    private lateinit var requestNotificationButton: Button
    private lateinit var requestCapturePermissionButton: Button
    private lateinit var requestShizukuPermissionButton: Button
    private lateinit var toggleServiceButton: Button
    private lateinit var accessibilityGuardSwitch: Switch
    private var permissionAlertColor: Int = 0

    private var isUpdatingGuardSwitch = false
    private var skipAutoBootstrapOnce = false
    private var lastCapturePermissionRequestAtMillis = 0L
    private var pendingShizukuPermissionRequest = false

    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            refreshStatus()
            if (!pendingShizukuPermissionRequest) {
                return@runOnUiThread
            }
            pendingShizukuPermissionRequest = false
            requestShizukuPermissionInternal()
        }
    }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != SHIZUKU_PERMISSION_REQUEST_CODE) {
            return@OnRequestPermissionResultListener
        }
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            toast(getString(R.string.shizuku_permission_granted))
        } else {
            toast(getString(R.string.shizuku_permission_denied))
        }
        refreshStatus()
    }

    private var pendingCaptureRequest: CaptureRequest = CaptureRequest.None

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshStatus()
        }

    private val screenCapturePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                Log.d(logTag, "Screen capture permission granted. pendingRequest=$pendingCaptureRequest")
                handlePermissionGranted(result.resultCode, data)
            } else if (result.resultCode == Activity.RESULT_OK) {
                Log.w(logTag, "Screen capture permission result was OK but data intent was null.")
                toast(getString(R.string.capture_permission_incomplete))
            } else {
                Log.d(logTag, "Screen capture permission denied or canceled. resultCode=${result.resultCode}")
                toast(getString(R.string.capture_permission_denied))
            }
            skipAutoBootstrapOnce = true
            pendingCaptureRequest = CaptureRequest.None
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        permissionStore = ScreenCapturePermissionStore(this)
        mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        bindViews()
        bindActions()
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        ensureShizukuBinder()
        refreshStatus()
        handlePermissionShortcutIntent(intent)
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePermissionShortcutIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (skipAutoBootstrapOnce) {
            skipAutoBootstrapOnce = false
            Log.d(logTag, "Skip auto bootstrap once after capture permission result.")
            return
        }
        autoBootstrapFlow()
    }

    private fun bindViews() {
        overlayStatusRow = findViewById(R.id.overlayStatusRow)
        overlayStatusView = findViewById(R.id.overlayStatusValue)
        notificationStatusRow = findViewById(R.id.notificationStatusRow)
        notificationStatusView = findViewById(R.id.notificationStatusValue)
        captureStatusRow = findViewById(R.id.captureStatusRow)
        captureStatusView = findViewById(R.id.captureStatusValue)
        autoSendStatusRow = findViewById(R.id.autoSendStatusRow)
        autoSendStatusView = findViewById(R.id.autoSendStatusValue)
        shizukuStatusRow = findViewById(R.id.shizukuStatusRow)
        shizukuStatusView = findViewById(R.id.shizukuStatusValue)
        guardStatusView = findViewById(R.id.guardStatusValue)
        serviceStatusView = findViewById(R.id.serviceStatusValue)
        lastPermissionGrantView = findViewById(R.id.lastGrantValue)
        requestNotificationButton = findViewById(R.id.requestNotificationButton)
        requestCapturePermissionButton = findViewById(R.id.requestCapturePermissionButton)
        requestShizukuPermissionButton = findViewById(R.id.requestShizukuPermissionButton)
        toggleServiceButton = findViewById(R.id.toggleServiceButton)
        accessibilityGuardSwitch = findViewById(R.id.accessibilityGuardSwitch)
        permissionAlertColor = ContextCompat.getColor(this, android.R.color.holo_red_dark)
    }

    private fun bindActions() {
        findViewById<View>(R.id.openOverlayPermissionButton).setOnClickListener {
            startActivity(PermissionHelper.buildOverlayPermissionIntent(this))
        }

        findViewById<View>(R.id.openAccessibilityServiceButton).setOnClickListener {
            startActivity(AutoSendAccessibilityHelper.buildAccessibilitySettingsIntent())
        }

        accessibilityGuardSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingGuardSwitch) {
                return@setOnCheckedChangeListener
            }
            AutoSendAccessibilityHelper.setGuardEnabled(this, isChecked)
            toast(
                if (isChecked) getString(R.string.guard_enabled_toast)
                else getString(R.string.guard_disabled_toast),
            )
            refreshStatus()
        }

        requestNotificationButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        requestCapturePermissionButton.setOnClickListener {
            if (permissionStore.getStatus() == ScreenCapturePermissionStore.Status.READY) {
                toast(getString(R.string.capture_session_ready))
                return@setOnClickListener
            }
            launchCapturePermission(CaptureRequest.PrepareSession)
        }

        requestShizukuPermissionButton.setOnClickListener {
            requestShizukuPermission()
        }

        toggleServiceButton.setOnClickListener {
            if (FloatingWindowService.isRunning) {
                FloatingWindowService.stop(this)
                refreshStatus()
                return@setOnClickListener
            }

            if (!PermissionHelper.canDrawOverlays(this)) {
                toast(getString(R.string.overlay_permission_required))
                return@setOnClickListener
            }
            if (permissionStore.getStatus() == ScreenCapturePermissionStore.Status.READY) {
                FloatingWindowService.start(this)
            } else {
                launchCapturePermission(CaptureRequest.StartOverlayService)
            }
            refreshStatus()
        }

        findViewById<View>(R.id.clearCapturePermissionButton).setOnClickListener {
            FloatingWindowService.stop(this)
            permissionStore.clear()
            toast(getString(R.string.capture_permission_cleared))
            refreshStatus()
        }
    }

    private fun handlePermissionGranted(resultCode: Int, data: android.content.Intent) {
        when (pendingCaptureRequest) {
            CaptureRequest.PrepareSession -> {
                FloatingWindowService.startSession(this, resultCode, data)
                toast(getString(R.string.capture_session_ready))
            }

            CaptureRequest.StartOverlayService -> {
                FloatingWindowService.startWithPermission(this, resultCode, data)
                toast(getString(R.string.capture_permission_granted))
            }

            CaptureRequest.None -> {
                toast(getString(R.string.capture_permission_granted))
            }
        }
    }

    private fun launchCapturePermission(request: CaptureRequest) {
        val now = System.currentTimeMillis()
        if (now - lastCapturePermissionRequestAtMillis < CAPTURE_PERMISSION_REQUEST_THROTTLE_MS) {
            Log.d(logTag, "Skip duplicate capture permission launch. request=$request")
            return
        }
        lastCapturePermissionRequestAtMillis = now
        pendingCaptureRequest = request
        screenCapturePermissionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun handlePermissionShortcutIntent(intent: Intent?) {
        val target = intent?.getStringExtra(EXTRA_PERMISSION_TARGET) ?: return
        when (target) {
            PERMISSION_TARGET_OVERLAY -> {
                if (!PermissionHelper.canDrawOverlays(this)) {
                    startActivity(PermissionHelper.buildOverlayPermissionIntent(this))
                    toast(getString(R.string.overlay_permission_required))
                }
            }

            PERMISSION_TARGET_CAPTURE -> {
                if (permissionStore.getStatus() != ScreenCapturePermissionStore.Status.READY &&
                    pendingCaptureRequest == CaptureRequest.None
                ) {
                    launchCapturePermission(CaptureRequest.StartOverlayService)
                }
            }
        }
        intent.removeExtra(EXTRA_PERMISSION_TARGET)
    }

    private fun autoBootstrapFlow() {
        if (pendingCaptureRequest != CaptureRequest.None || FloatingWindowService.isRunning) {
            return
        }

        if (!PermissionHelper.canDrawOverlays(this)) {
            startActivity(PermissionHelper.buildOverlayPermissionIntent(this))
            return
        }

        if (permissionStore.getStatus() == ScreenCapturePermissionStore.Status.READY) {
            FloatingWindowService.start(this)
            refreshStatus()
            return
        }

        launchCapturePermission(CaptureRequest.StartOverlayService)
    }

    private fun refreshStatus() {
        val overlayGranted = PermissionHelper.canDrawOverlays(this)
        val notificationGranted = PermissionHelper.hasNotificationPermission(this)
        val captureStatus = permissionStore.getStatus()
        val autoSendEnabled = AutoSendAccessibilityHelper.isAutoSendServiceEnabled(this)
        val shizukuReady = ShizukuAccessibilityKeeper.canUseShizuku()
        val guardEnabled = AutoSendAccessibilityHelper.isGuardEnabled(this)
        val lastGrantedAt = permissionStore.getLastGrantedAtMillis()

        updatePermissionStatusRow(
            row = overlayStatusRow,
            valueView = overlayStatusView,
            enabled = overlayGranted,
            disabledText = getString(R.string.status_overlay_missing),
        )

        updatePermissionStatusRow(
            row = notificationStatusRow,
            valueView = notificationStatusView,
            enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) notificationGranted else true,
            disabledText = getString(R.string.status_notification_recommended),
        )

        updatePermissionStatusRow(
            row = captureStatusRow,
            valueView = captureStatusView,
            enabled = captureStatus == ScreenCapturePermissionStore.Status.READY,
            disabledText = when (captureStatus) {
                ScreenCapturePermissionStore.Status.READY -> ""
                ScreenCapturePermissionStore.Status.STALE -> getString(R.string.status_capture_stale)
                ScreenCapturePermissionStore.Status.MISSING -> getString(R.string.status_capture_missing)
            },
        )

        updatePermissionStatusRow(
            row = autoSendStatusRow,
            valueView = autoSendStatusView,
            enabled = autoSendEnabled,
            disabledText = getString(R.string.status_auto_send_missing),
        )

        updatePermissionStatusRow(
            row = shizukuStatusRow,
            valueView = shizukuStatusView,
            enabled = shizukuReady,
            disabledText = getString(R.string.status_shizuku_missing),
        )

        guardStatusView.text = if (guardEnabled) {
            getString(R.string.status_guard_enabled)
        } else {
            getString(R.string.status_guard_disabled)
        }

        isUpdatingGuardSwitch = true
        accessibilityGuardSwitch.isChecked = guardEnabled
        isUpdatingGuardSwitch = false

        requestCapturePermissionButton.text = when (captureStatus) {
            ScreenCapturePermissionStore.Status.READY -> getString(R.string.action_capture_permission_ready)
            ScreenCapturePermissionStore.Status.STALE -> getString(R.string.action_reauthorize_capture_permission)
            ScreenCapturePermissionStore.Status.MISSING -> getString(R.string.action_request_capture_permission)
        }

        serviceStatusView.text = if (FloatingWindowService.isRunning) {
            getString(R.string.status_service_running)
        } else {
            getString(R.string.status_service_stopped)
        }

        lastPermissionGrantView.text = lastGrantedAt?.let {
            getString(R.string.status_last_grant_prefix) + DateFormat.getDateTimeInstance().format(Date(it))
        } ?: getString(R.string.status_last_grant_prefix) + getString(R.string.status_unknown)

        requestNotificationButton.visibility =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) View.VISIBLE else View.GONE
        requestNotificationButton.isEnabled = !notificationGranted
        requestShizukuPermissionButton.visibility = View.VISIBLE
        requestShizukuPermissionButton.text = if (shizukuReady) {
            getString(R.string.action_recheck_shizuku_permission)
        } else {
            getString(R.string.action_request_shizuku_permission)
        }
        val serviceRunning = FloatingWindowService.isRunning
        toggleServiceButton.isEnabled = serviceRunning || overlayGranted
        toggleServiceButton.text = if (serviceRunning) {
            getString(R.string.action_stop_service)
        } else {
            getString(R.string.action_start_service)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun requestShizukuPermission() {
        pendingShizukuPermissionRequest = true
        ensureShizukuBinder()
        if (Shizuku.pingBinder()) {
            pendingShizukuPermissionRequest = false
            requestShizukuPermissionInternal()
            return
        }

        toast(getString(R.string.shizuku_connecting))
        Handler(Looper.getMainLooper()).postDelayed({
            if (Shizuku.pingBinder()) {
                pendingShizukuPermissionRequest = false
                requestShizukuPermissionInternal()
            } else {
                openShizukuApp()
                toast(getString(R.string.shizuku_open_app_tip))
            }
        }, SHIZUKU_BINDER_RETRY_DELAY_MS)
    }

    private fun requestShizukuPermissionInternal() {
        if (!Shizuku.pingBinder()) {
            toast(getString(R.string.shizuku_not_running))
            return
        }
        Log.d(
            logTag,
            "Requesting Shizuku permission explicitly. checkSelf=${Shizuku.checkSelfPermission()} rationale=${Shizuku.shouldShowRequestPermissionRationale()}",
        )
        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
    }

    private fun ensureShizukuBinder() {
        if (Shizuku.pingBinder()) {
            return
        }
        runCatching {
            ShizukuProvider.requestBinderForNonProviderProcess(this)
        }.onFailure {
            Log.w(logTag, "Requesting Shizuku binder failed.", it)
        }
    }

    private fun openShizukuApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            ?: packageManager.getLaunchIntentForPackage("moe.shizuku.manager")
            ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(launchIntent) }
    }

    private fun updatePermissionStatusRow(
        row: View,
        valueView: TextView,
        enabled: Boolean,
        disabledText: String,
    ) {
        if (enabled) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        valueView.setTextColor(permissionAlertColor)
        valueView.text = disabledText
    }

    private sealed interface CaptureRequest {
        data object None : CaptureRequest
        data object PrepareSession : CaptureRequest
        data object StartOverlayService : CaptureRequest
    }

    companion object {
        private const val CAPTURE_PERMISSION_REQUEST_THROTTLE_MS = 1500L
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
        private const val SHIZUKU_BINDER_RETRY_DELAY_MS = 1200L
        private const val EXTRA_PERMISSION_TARGET = "permission_target"
        private const val PERMISSION_TARGET_OVERLAY = "overlay"
        private const val PERMISSION_TARGET_CAPTURE = "capture"

        fun buildOverlayPermissionShortcutIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_PERMISSION_TARGET, PERMISSION_TARGET_OVERLAY)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

        fun buildCapturePermissionShortcutIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_PERMISSION_TARGET, PERMISSION_TARGET_CAPTURE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
    }
}



