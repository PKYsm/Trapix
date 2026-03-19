package com.trapix.app.service

import android.Manifest
import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.location.LocationManager
import android.os.*
import android.provider.MediaStore
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
        const val TAG             = "IntruderCapture"
        const val CHANNEL_ID      = "trapix_capture_channel"
        const val NOTIF_ID_CAPTURE = 1001
        const val NOTIF_ID_ALERT  = 1002
        const val ACTION_CAPTURE  = "com.trapix.app.ACTION_CAPTURE"
        const val EXTRA_ATTEMPT_NUMBER = "attempt_number"

        @Volatile var isCapturing = false
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var prefs: AppPrefs
    private lateinit var db: AppDatabase

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        db    = AppDatabase.getInstance(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val attemptNumber = intent?.getIntExtra(EXTRA_ATTEMPT_NUMBER, 1) ?: 1
        DebugLogger.log(TAG, "onStartCommand: isCapturing=$isCapturing attempt=$attemptNumber")

        if (isCapturing) {
            DebugLogger.log(TAG, "Already capturing, skip.")
            stopSelf(); return START_NOT_STICKY
        }

        isCapturing = true
        startForeground(NOTIF_ID_CAPTURE, buildCaptureForegroundNotif())
        startCaptureSequence(attemptNumber)
        return START_NOT_STICKY
    }

    private fun startCaptureSequence(attemptNumber: Int) {
        serviceScope.launch {
            val useFront = prefs.captureFrontCamera
            val useRear  = prefs.captureRearCamera
            val location = withContext(Dispatchers.IO) { getLastKnownLocation() }

            DebugLogger.log(TAG, "Capture sequence: front=$useFront rear=$useRear attempt=$attemptNumber")

            if (!useFront && !useRear) { DebugLogger.log(TAG, "No camera selected."); finish(); return@launch }
            if (!hasCamera())          { DebugLogger.error(TAG, "CAMERA PERMISSION MISSING!"); finish(); return@launch }

            val provider = getCameraProvider()
            if (provider == null) { DebugLogger.error(TAG, "CameraProvider init failed!"); finish(); return@launch }

            // ── FRONT ─────────────────────────────────────────────────────────
            var lastSavedFile: File? = null
            if (useFront) {
                DebugLogger.log(TAG, ">>> FRONT capture start")
                val file = captureSingle(provider, CameraSelector.DEFAULT_FRONT_CAMERA, "front", attemptNumber, location)
                DebugLogger.log(TAG, "<<< FRONT done. saved=${file != null}")
                if (file != null) lastSavedFile = file
                if (useRear) { DebugLogger.log(TAG, "Waiting 1500ms..."); delay(1500L) }
            }

            // ── REAR ──────────────────────────────────────────────────────────
            // BUG 6 FIX: rear off hone par front dobara nahi leni chahiye
            // ab ye sirf useRear=true hone par hi chalega
            if (useRear) {
                DebugLogger.log(TAG, ">>> REAR capture start")
                val file = captureSingle(provider, CameraSelector.DEFAULT_BACK_CAMERA, "rear", attemptNumber, location)
                DebugLogger.log(TAG, "<<< REAR done. saved=${file != null}")
                if (file != null) lastSavedFile = file
            }

            // BUG 4 FIX: Screen locked hai to notification defer karo
            // Screen unlock hone pe ScreenUnlockReceiver send karega
            if (prefs.notificationEnabled && lastSavedFile != null) {
                val km = getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
                if (km.isKeyguardLocked) {
                    DebugLogger.log(TAG, "Screen locked — deferring notification until unlock")
                    prefs.pendingNotifAttempt = attemptNumber
                    prefs.pendingNotifPath    = lastSavedFile.absolutePath
                } else {
                    sendNotification(attemptNumber, lastSavedFile)
                }
            }

            DebugLogger.log(TAG, "All captures complete.")
            finish()
        }
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider? =
        suspendCancellableCoroutine { cont ->
            try {
                val future = ProcessCameraProvider.getInstance(this)
                future.addListener({
                    try {
                        cont.resume(future.get()) {}
                    } catch (e: Exception) {
                        DebugLogger.error(TAG, "CameraProvider.get() failed: ${e.message}")
                        if (cont.isActive) cont.resume(null) {}
                    }
                }, ContextCompat.getMainExecutor(this))
                cont.invokeOnCancellation { future.cancel(true) }
            } catch (e: Exception) {
                DebugLogger.error(TAG, "getCameraProvider exception: ${e.message}")
                if (cont.isActive) cont.resume(null) {}
            }
        }

    /**
     * Returns: File if success, null if failed.
     */
    private suspend fun captureSingle(
        provider: ProcessCameraProvider,
        selector: CameraSelector,
        label: String,
        attempt: Int,
        location: Location?
    ): File? = suspendCancellableCoroutine { cont ->
        fun resume(file: File?) { if (cont.isActive) cont.resume(file) {} }
        try {
            provider.unbindAll()
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                provider.bindToLifecycle(this, selector, imageCapture)
                DebugLogger.log(TAG, "$label: camera bound OK")
            } catch (e: Exception) {
                DebugLogger.error(TAG, "$label: bind FAILED: ${e.message}")
                resume(null); return@suspendCancellableCoroutine
            }

            val file = createImageFile(label)
            DebugLogger.log(TAG, "$label: taking photo → ${file.name}")
            imageCapture.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(this),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                        DebugLogger.log(TAG, "$label photo saved! ✓ size=${file.length() / 1024}KB")
                        provider.unbindAll()
                        serviceScope.launch(Dispatchers.IO) {
                            saveToDb(file, label, attempt, location)
                            // BUG 7 FIX: Gallery save properly karo (MediaStore API)
                            if (prefs.saveToGallery) saveToGalleryMediaStore(file)
                        }
                        resume(file)
                    }
                    override fun onError(e: ImageCaptureException) {
                        DebugLogger.error(TAG, "$label ERROR: ${e.message}")
                        provider.unbindAll(); resume(null)
                    }
                }
            )
        } catch (e: Exception) {
            DebugLogger.error(TAG, "$label exception: ${e.message}"); resume(null)
        }
    }

    /**
     * BUG 7 FIX: filesDir images gallery mein nahi dikhte.
     * MediaStore API se properly gallery mein copy karo.
     */
    private fun saveToGalleryMediaStore(file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+: MediaStore
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Trapix")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    DebugLogger.log(TAG, "Gallery save OK (MediaStore API 29+): ${file.name}")
                } else {
                    DebugLogger.error(TAG, "Gallery save: MediaStore insert returned null")
                }
            } else {
                // API < 29: External storage copy + MediaScanner
                val picturesDir = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_PICTURES), "Trapix"
                ).also { it.mkdirs() }
                val dest = File(picturesDir, file.name)
                file.copyTo(dest, overwrite = true)
                android.media.MediaScannerConnection.scanFile(
                    this, arrayOf(dest.absolutePath), arrayOf("image/jpeg"), null)
                DebugLogger.log(TAG, "Gallery save OK (copy+scan): ${dest.absolutePath}")
            }
        } catch (e: Exception) {
            DebugLogger.error(TAG, "Gallery save FAILED: ${e.message}")
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun saveToDb(file: File, label: String, attempt: Int, location: Location?) {
        try {
            val log = IntruderLog(
                imagePath   = file.absolutePath,
                timestamp   = System.currentTimeMillis(),
                latitude    = location?.latitude ?: 0.0,
                longitude   = location?.longitude ?: 0.0,
                attemptNumber = attempt,
                cameraUsed  = label,
                deviceInfo  = "${Build.MANUFACTURER} ${Build.MODEL}"
            )
            runBlocking { db.intruderDao().insert(log) }
            DebugLogger.log(TAG, "$label: saved to DB ok")
        } catch (e: Exception) {
            DebugLogger.error(TAG, "DB save error: ${e.message}")
        }
    }

    private fun finish() { isCapturing = false; stopSelf() }

    private fun createImageFile(label: String): File {
        val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = File(filesDir, "intruder_photos").also { it.mkdirs() }
        return File(dir, "INTRUDER_${label}_${ts}.jpg")
    }

    private fun getLastKnownLocation(): Location? {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) { null }
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
            .setAutoCancel(true).setContentIntent(pi)
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
        .setSmallIcon(R.drawable.ic_shield).setContentTitle("Trapix")
        .setContentText("Security active...").setPriority(NotificationCompat.PRIORITY_MIN).build()

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Trapix Security", NotificationManager.IMPORTANCE_HIGH)
            .apply { enableVibration(true) }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    override fun onDestroy() { isCapturing = false; serviceScope.cancel(); super.onDestroy() }
}
