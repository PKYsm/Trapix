package com.trapix.app.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationManager
import android.media.MediaScannerConnection
import android.os.*
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.trapix.app.R
import com.trapix.app.data.db.AppDatabase
import com.trapix.app.data.model.IntruderLog
import com.trapix.app.data.prefs.AppPrefs
import com.trapix.app.ui.gallery.MainActivity
import com.trapix.app.util.DebugLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class IntruderCaptureService : LifecycleService() {

    companion object {
        const val TAG = "IntruderCapture"
        const val CHANNEL_ID = "trapix_capture_channel"
        const val NOTIF_ID_CAPTURE = 1001
        const val NOTIF_ID_ALERT = 1002
        const val ACTION_CAPTURE = "com.trapix.app.ACTION_CAPTURE"
        const val EXTRA_ATTEMPT_NUMBER = "attempt_number"

        @Volatile var isCapturing = false
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var prefs: AppPrefs
    private lateinit var db: AppDatabase

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        db = AppDatabase.getInstance(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val attemptNumber = intent?.getIntExtra(EXTRA_ATTEMPT_NUMBER, 1) ?: 1
        DebugLogger.log(TAG, "onStartCommand: isCapturing=$isCapturing attempt=$attemptNumber")

        if (isCapturing) {
            DebugLogger.log(TAG, "Already capturing, skipping.")
            stopSelf()
            return START_NOT_STICKY
        }

        isCapturing = true
        startForeground(NOTIF_ID_CAPTURE, buildCaptureForegroundNotif())
        startCaptureSequence(attemptNumber)
        return START_NOT_STICKY
    }

    /**
     * BUG 3 FIX: Nested callbacks ki jagah coroutines use karo.
     * suspendCancellableCoroutine ensure karta hai ki front complete hone ke BAAD
     * hi rear start ho. Pehle wali callback chain mein rear kabhi kabhi miss ho jaati thi.
     */
    private fun startCaptureSequence(attemptNumber: Int) {
        serviceScope.launch {
            val useFront = prefs.captureFrontCamera
            val useRear = prefs.captureRearCamera
            val location = withContext(Dispatchers.IO) { getLastKnownLocation() }

            DebugLogger.log(TAG, "Capture sequence: front=$useFront rear=$useRear attempt=$attemptNumber")

            if (!useFront && !useRear) {
                DebugLogger.log(TAG, "No camera selected in settings.")
                finish()
                return@launch
            }

            if (!hasCamera()) {
                DebugLogger.error(TAG, "CAMERA PERMISSION MISSING! Cannot capture.")
                finish()
                return@launch
            }

            // CameraProvider ek baar lo, dono cameras ke liye reuse karo
            val provider = getCameraProvider()
            if (provider == null) {
                DebugLogger.error(TAG, "CameraProvider init failed!")
                finish()
                return@launch
            }

            // ── FRONT CAMERA ──────────────────────────────────────────────────
            if (useFront) {
                DebugLogger.log(TAG, ">>> Starting FRONT camera capture...")
                val frontSuccess = captureSingle(
                    provider = provider,
                    selector = CameraSelector.DEFAULT_FRONT_CAMERA,
                    label = "front",
                    attempt = attemptNumber,
                    location = location
                )
                DebugLogger.log(TAG, "<<< FRONT capture done. success=$frontSuccess")

                // Rear se pehle thoda wait karo (camera release hone ka time)
                if (useRear) {
                    DebugLogger.log(TAG, "Waiting 1500ms before rear camera...")
                    delay(1500L)
                }
            }

            // ── REAR CAMERA ───────────────────────────────────────────────────
            if (useRear) {
                DebugLogger.log(TAG, ">>> Starting REAR camera capture...")
                val rearSuccess = captureSingle(
                    provider = provider,
                    selector = CameraSelector.DEFAULT_BACK_CAMERA,
                    label = "rear",
                    attempt = attemptNumber,
                    location = location
                )
                DebugLogger.log(TAG, "<<< REAR capture done. success=$rearSuccess")
            }

            DebugLogger.log(TAG, "All captures complete. Stopping service.")
            finish()
        }
    }

    /**
     * CameraProvider ko suspend function se await karo.
     * ListenableFuture ke liye manual coroutine bridge (guava dependency nahi chahiye).
     */
    private suspend fun getCameraProvider(): ProcessCameraProvider? =
        suspendCancellableCoroutine(onCancellation = { }) { cont ->
            try {
                val future = ProcessCameraProvider.getInstance(this)
                future.addListener({
                    try {
                        cont.resume(future.get())
                    } catch (e: Exception) {
                        DebugLogger.error(TAG, "CameraProvider.get() failed: ${e.message}")
                        if (cont.isActive) cont.resume(null)
                    }
                }, ContextCompat.getMainExecutor(this))
                cont.invokeOnCancellation { future.cancel(true) }
            } catch (e: Exception) {
                DebugLogger.error(TAG, "getCameraProvider exception: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }

    /**
     * Single camera capture — returns true on success, false on any failure.
     * suspendCancellableCoroutine ensure karta hai ki caller tab tak wait kare
     * jab tak photo save nahi ho jaata.
     */
    private suspend fun captureSingle(
        provider: ProcessCameraProvider,
        selector: CameraSelector,
        label: String,
        attempt: Int,
        location: Location?
    ): Boolean = suspendCancellableCoroutine(onCancellation = { }) { cont ->
        fun safeResume(value: Boolean) {
            if (cont.isActive) cont.resume(value)
        }

        try {
            // Pehle wali binding hato
            provider.unbindAll()

            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            // Camera bind karo
            try {
                provider.bindToLifecycle(this@IntruderCaptureService, selector, imageCapture)
                DebugLogger.log(TAG, "$label: camera bound successfully")
            } catch (e: Exception) {
                DebugLogger.error(TAG, "$label: bindToLifecycle FAILED: ${e.message}")
                safeResume(false)
                return@suspendCancellableCoroutine
            }

            // Photo file create karo
            val file = createImageFile(label)
            DebugLogger.log(TAG, "$label: taking photo → ${file.name}")

            imageCapture.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(this@IntruderCaptureService),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                        DebugLogger.log(TAG, "$label photo saved! ✓ size=${file.length() / 1024}KB path=${file.name}")
                        provider.unbindAll()
                        // DB save alag coroutine mein (non-blocking)
                        serviceScope.launch(Dispatchers.IO) {
                            saveToDb(file, label, attempt, location)
                            if (prefs.saveToGallery) saveToGallery(file)
                            if (prefs.notificationEnabled) sendNotification(attempt, file)
                        }
                        safeResume(true)
                    }

                    override fun onError(e: ImageCaptureException) {
                        DebugLogger.error(TAG, "$label capture ERROR: ${e.message} reason=${e.imageCaptureError}")
                        provider.unbindAll()
                        safeResume(false)
                    }
                }
            )
        } catch (e: Exception) {
            DebugLogger.error(TAG, "$label unexpected exception: ${e.message}")
            safeResume(false)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun saveToDb(file: File, label: String, attempt: Int, location: Location?) {
        try {
            val log = IntruderLog(
                imagePath = file.absolutePath,
                timestamp = System.currentTimeMillis(),
                latitude = location?.latitude ?: 0.0,
                longitude = location?.longitude ?: 0.0,
                attemptNumber = attempt,
                cameraUsed = label,
                deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}"
            )
            runBlocking { db.intruderDao().insert(log) }
            DebugLogger.log(TAG, "$label: saved to DB ok")
        } catch (e: Exception) {
            DebugLogger.error(TAG, "DB save error: ${e.message}")
        }
    }

    private fun finish() {
        isCapturing = false
        stopSelf()
    }

    private fun createImageFile(label: String): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = File(filesDir, "intruder_photos").also { it.mkdirs() }
        return File(dir, "INTRUDER_${label}_${ts}.jpg")
    }

    private fun saveToGallery(file: File) =
        MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)

    private fun getLastKnownLocation(): Location? {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            null
        }
    }

    private fun hasCamera() =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun sendNotification(attempt: Int, file: File) {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        var b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("⚠️ Intruder!")
            .setContentText("Attempt #$attempt captured")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setVibrate(longArrayOf(0, 300, 200, 300))
        try {
            val bmp = BitmapFactory.decodeFile(file.absolutePath)
            if (bmp != null) b = b.setLargeIcon(bmp)
                .setStyle(NotificationCompat.BigPictureStyle().bigPicture(bmp).bigLargeIcon(null as Bitmap?))
        } catch (_: Exception) {}
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID_ALERT + attempt, b.build())
    }

    private fun buildCaptureForegroundNotif() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_shield)
        .setContentTitle("Trapix")
        .setContentText("Security active...")
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Trapix Security", NotificationManager.IMPORTANCE_HIGH)
            .apply { enableVibration(true) }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    override fun onDestroy() {
        isCapturing = false
        serviceScope.cancel()
        super.onDestroy()
    }
}
