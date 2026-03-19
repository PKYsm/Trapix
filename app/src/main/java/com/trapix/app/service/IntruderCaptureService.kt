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
import android.util.Log
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
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
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
        
        @Volatile
        var isCapturing = false // Prevent duplicate captures
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
        
        com.trapix.app.util.DebugLogger.log("CAMERA", "onStartCommand called. isCapturing=$isCapturing, attempt=${ intent?.getIntExtra(EXTRA_ATTEMPT_NUMBER, 1) }")
        // Prevent duplicate captures running simultaneously
        if (isCapturing) {
            stopSelf()
            return START_NOT_STICKY
        }
        isCapturing = true
        
        val attemptNumber = intent?.getIntExtra(EXTRA_ATTEMPT_NUMBER, 1) ?: 1

        // Start foreground with a silent notification briefly
        startForeground(NOTIF_ID_CAPTURE, buildCaptureForegroundNotif())

        captureIntruder(attemptNumber)
        return START_NOT_STICKY
    }

    private fun captureIntruder(attemptNumber: Int) {
        val useFront = prefs.captureFrontCamera
        val useRear = prefs.captureRearCamera

        if (!useFront && !useRear) {
            stopSelf()
            return
        }

        serviceScope.launch {
            val location = getLastKnownLocation()
            com.trapix.app.util.DebugLogger.log("CAMERA", "Capture start: front=$useFront rear=$useRear location=${location?.latitude},${location?.longitude}")

            if (useFront) {
                captureWithCamera(CameraSelector.DEFAULT_FRONT_CAMERA, "front", attemptNumber, location)
                // Small delay between cameras to allow proper unbind/rebind
                kotlinx.coroutines.delay(500)
            }
            if (useRear) {
                captureWithCamera(CameraSelector.DEFAULT_BACK_CAMERA, "rear", attemptNumber, location)
            }

            stopSelf()
        }
    }

    private suspend fun captureWithCamera(
        cameraSelector: CameraSelector,
        cameraLabel: String,
        attemptNumber: Int,
        location: Location?
    ) = suspendCancellableCoroutine<Unit> { continuation ->

        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            if (!hasCamera()) {
                continuation.resume(Unit) {}
                return@post
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(this, cameraSelector, imageCapture)

                    val outputFile = createImageFile(cameraLabel)
                    val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

                    imageCapture.takePicture(
                        outputOptions,
                        ContextCompat.getMainExecutor(this),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                serviceScope.launch {
                                    com.trapix.app.util.DebugLogger.log("CAMERA", "Photo saved: ${outputFile.absolutePath} size=${outputFile.length()/1024}KB")
                                    val log = IntruderLog(
                                        imagePath = outputFile.absolutePath,
                                        timestamp = System.currentTimeMillis(),
                                        latitude = location?.latitude ?: 0.0,
                                        longitude = location?.longitude ?: 0.0,
                                        locationAddress = "",
                                        attemptNumber = attemptNumber,
                                        cameraUsed = cameraLabel,
                                        deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}"
                                    )
                                    db.intruderDao().insert(log)

                                    if (prefs.saveToGallery) {
                                        saveToGallery(outputFile)
                                    }

                                    if (prefs.notificationEnabled) {
                                        sendIntruderNotification(attemptNumber, outputFile)
                                    }

                                    Log.d(TAG, "Intruder captured: ${outputFile.absolutePath}")
                                    cameraProvider.unbindAll()
                                    continuation.resume(Unit) {}
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                com.trapix.app.util.DebugLogger.error("CAMERA", "Capture FAILED: ${exception.message} code=${exception.imageCaptureError}")
                                Log.e(TAG, "Capture error: ${exception.message}")
                                cameraProvider.unbindAll()
                                continuation.resume(Unit) {}
                            }
                        }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Camera bind error: ${e.message}")
                    continuation.resume(Unit) {}
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun createImageFile(cameraLabel: String): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = File(filesDir, "intruder_photos")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "INTRUDER_${cameraLabel}_${timeStamp}.jpg")
    }

    private fun saveToGallery(file: File) {
        MediaScannerConnection.scanFile(
            this,
            arrayOf(file.absolutePath),
            arrayOf("image/jpeg"),
            null
        )
    }

    private fun getLastKnownLocation(): Location? {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return null

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            null
        }
    }

    private fun hasCamera(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun sendIntruderNotification(attemptNumber: Int, imageFile: File) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        var notifBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("⚠️ Intruder Detected!")
            .setContentText("Wrong password attempt #$attemptNumber captured")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Wrong unlock attempt #$attemptNumber detected and photo captured. Tap to view."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 300, 200, 300))

        // Show image in notification if possible
        try {
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath)
            if (bitmap != null) {
                notifBuilder = notifBuilder.setLargeIcon(bitmap)
                    .setStyle(NotificationCompat.BigPictureStyle()
                        .bigPicture(bitmap)
                        .bigLargeIcon(null as Bitmap?))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Notification image error: ${e.message}")
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIF_ID_ALERT + attemptNumber, notifBuilder.build())
    }

    private fun buildCaptureForegroundNotif(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Trapix")
            .setContentText("Security active...")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Trapix Security",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Intruder detection notifications"
            enableVibration(true)
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        isCapturing = false
        serviceScope.cancel()
        super.onDestroy()
    }
}
