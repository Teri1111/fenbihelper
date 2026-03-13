package com.example.myapplication.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.FileProvider
import com.example.myapplication.data.CaptureOutcome
import com.example.myapplication.data.CaptureResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenCaptureManager(
    context: Context,
) {
    private val logTag = "ScreenCaptureManager"
    private val appContext = context.applicationContext
    private val sessionLock = Any()
    private var activeSession: CaptureSession? = null

    fun startSession(mediaProjection: MediaProjection): CaptureOutcome.Failure? {
        return ensureSession(mediaProjection)
    }

    fun endSession() {
        synchronized(sessionLock) {
            releaseSessionLocked(reason = "endSession")
        }
    }

    suspend fun captureScreen(mediaProjection: MediaProjection?): CaptureOutcome = withContext(Dispatchers.IO) {
        val activeProjection = mediaProjection
            ?: return@withContext CaptureOutcome.Failure(
                message = "截图会话未就绪，请重新授权并启动服务。",
                requiresReauthorization = true,
            )

        startSession(activeProjection)?.let { failure ->
            return@withContext failure
        }

        val imageReader = synchronized(sessionLock) { activeSession?.imageReader }
            ?: return@withContext CaptureOutcome.Failure(
                message = "截图会话已失效，请重新授权并启动服务。",
                requiresReauthorization = true,
            )

        try {
            Log.d(logTag, "Starting screen capture using active service session. sdk=${Build.VERSION.SDK_INT}")

            drainImageReader(imageReader)
            val image = waitForImage(imageReader)
                ?: return@withContext CaptureOutcome.Failure(
                    message = "截图超时，请重试。",
                )

            image.use {
                val bitmap = image.toBitmap()
                val file = saveBitmap(bitmap)
                bitmap.recycle()
                val uri = FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    file,
                )
                CaptureOutcome.Success(
                    CaptureResult(
                        uri = uri,
                        file = file,
                        createdAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
        } catch (securityException: SecurityException) {
            Log.e(logTag, "SecurityException while capturing screen with active MediaProjection session.", securityException)
            endSession()
            CaptureOutcome.Failure(
                message = "截图会话已失效，请重新授权并启动服务。",
                requiresReauthorization = true,
            )
        } catch (exception: Exception) {
            Log.e(logTag, "Unexpected exception while capturing screen.", exception)
            CaptureOutcome.Failure(
                message = exception.message ?: "截图失败，请稍后重试。",
            )
        }
    }

    private fun ensureSession(mediaProjection: MediaProjection): CaptureOutcome.Failure? {
        synchronized(sessionLock) {
            val currentSession = activeSession
            if (currentSession != null) {
                if (currentSession.projection === mediaProjection) {
                    return null
                }
                releaseSessionLocked(reason = "projection_replaced")
            }

            return try {
                val metrics = getDisplayMetrics()
                val width = metrics.widthPixels
                val height = metrics.heightPixels
                val densityDpi = metrics.densityDpi
                val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                val virtualDisplay = mediaProjection.createVirtualDisplay(
                    "doubao_capture_display",
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.surface,
                    null,
                    null,
                ) ?: return CaptureOutcome.Failure(
                    message = "截图初始化失败，请重试。",
                )
                activeSession = CaptureSession(mediaProjection, imageReader, virtualDisplay)
                Log.d(logTag, "Capture session started. VirtualDisplay created once and will be reused.")
                null
            } catch (securityException: SecurityException) {
                Log.e(logTag, "SecurityException while starting capture session.", securityException)
                releaseSessionLocked(reason = "start_session_security_exception")
                CaptureOutcome.Failure(
                    message = "截图会话已失效，请重新授权并启动服务。",
                    requiresReauthorization = true,
                )
            } catch (exception: Exception) {
                Log.e(logTag, "Unexpected exception while starting capture session.", exception)
                releaseSessionLocked(reason = "start_session_exception")
                CaptureOutcome.Failure(
                    message = exception.message ?: "截图初始化失败，请重试。",
                )
            }
        }
    }

    private fun releaseSessionLocked(reason: String) {
        val session = activeSession ?: return
        Log.d(logTag, "Releasing capture session. reason=$reason")
        activeSession = null
        runCatching { session.virtualDisplay.release() }
        runCatching { session.imageReader.close() }
    }

    private fun drainImageReader(imageReader: ImageReader) {
        while (true) {
            val staleImage = imageReader.acquireLatestImage() ?: break
            runCatching { staleImage.close() }
        }
    }

    private suspend fun waitForImage(imageReader: ImageReader): Image? {
        delay(INITIAL_CAPTURE_DELAY_MS)
        repeat(POLL_ATTEMPTS) {
            imageReader.acquireLatestImage()?.let { return it }
            delay(POLL_INTERVAL_MS)
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun getDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = appContext.resources.configuration.densityDpi
        } else {
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private fun saveBitmap(bitmap: Bitmap): File {
        val captureDirectory = File(appContext.cacheDir, CAPTURE_DIRECTORY).apply { mkdirs() }
        val timestamp = SimpleDateFormat(FILE_NAME_PATTERN, Locale.getDefault()).format(Date())
        val outputFile = File(captureDirectory, "capture_${timestamp}.png")

        FileOutputStream(outputFile).use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            outputStream.flush()
        }

        return outputFile
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes.first()
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val paddedBitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888,
        )
        paddedBitmap.copyPixelsFromBuffer(buffer)
        val croppedBitmap = Bitmap.createBitmap(paddedBitmap, 0, 0, width, height)
        paddedBitmap.recycle()
        return croppedBitmap
    }

    private fun <T : AutoCloseable?, R> T.use(block: (T) -> R): R {
        return try {
            block(this)
        } finally {
            runCatching { this?.close() }
        }
    }

    companion object {
        private const val CAPTURE_DIRECTORY = "captures"
        private const val FILE_NAME_PATTERN = "yyyyMMdd_HHmmss"
        private const val INITIAL_CAPTURE_DELAY_MS = 250L
        private const val POLL_INTERVAL_MS = 100L
        private const val POLL_ATTEMPTS = 15
    }

    private data class CaptureSession(
        val projection: MediaProjection,
        val imageReader: ImageReader,
        val virtualDisplay: VirtualDisplay,
    )
}


