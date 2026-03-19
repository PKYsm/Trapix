#!/bin/bash
echo "Creating all updated files in ~/Trapix..."
cd ~/Trapix

# ─────────────────────────────────────────────────────────────
# File 1: IntruderCaptureService.kt
# ─────────────────────────────────────────────────────────────
cat > app/src/main/java/com/trapix/app/service/IntruderCaptureService.kt << 'EOF'
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
import com.trapix.app.util.DebugLogger
import kotlinx.coroutines.*
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
    private val mainHandler = Handler(Looper.getMainLooper())

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
        if (isCapturing) { stopSelf(); return START_NOT_STICKY }
        isCapturing = true
        startForeground(NOTIF_ID_CAPTURE, buildCaptureForegroundNotif())
        startCaptureSequence(attemptNumber)
        return START_NOT_STICKY
    }

    private fun startCaptureSequence(attemptNumber: Int) {
        val useFront = prefs.captureFrontCamera
        val useRear = prefs.captureRearCamera
        val location = getLastKnownLocation()
        DebugLogger.log(TAG, "Capture: front=$useFront rear=$useRear")
        if (!useFront && !useRear) { finish(); return }
        if (useFront) {
            captureCamera(CameraSelector.DEFAULT_FRONT_CAMERA, "front", attemptNumber, location) {
                if (useRear) {
                    mainHandler.postDelayed({
                        captureCamera(CameraSelector.DEFAULT_BACK_CAMERA, "rear", attemptNumber, location) { finish() }
                    }, 2000)
                } else finish()
            }
        } else {
            captureCamera(CameraSelector.DEFAULT_BACK_CAMERA, "rear", attemptNumber, location) { finish() }
        }
    }

    private fun captureCamera(selector: CameraSelector, label: String, attempt: Int, location: Location?, onDone: () -> Unit) {
        if (!hasCamera()) { DebugLogger.error(TAG, "$label: no permission"); onDone(); return }
        DebugLogger.log(TAG, "Starting $label camera...")
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                provider.unbindAll()
                DebugLogger.log(TAG, "$label: cams=${provider.availableCameraInfos.size}")
                val imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                try { provider.bindToLifecycle(this, selector, imageCapture); DebugLogger.log(TAG, "$label bound OK") }
                catch (e: Exception) { DebugLogger.error(TAG, "$label bind failed: ${e.message}"); onDone(); return@addListener }
                val file = createImageFile(label)
                imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                            DebugLogger.log(TAG, "$label saved! size=${file.length()/1024}KB")
                            provider.unbindAll()
                            serviceScope.launch(Dispatchers.IO) {
                                saveToDb(file, label, attempt, location)
                                if (prefs.saveToGallery) saveToGallery(file)
                                if (prefs.notificationEnabled) sendNotification(attempt, file)
                            }
                            mainHandler.postDelayed({ onDone() }, 300)
                        }
                        override fun onError(e: ImageCaptureException) {
                            DebugLogger.error(TAG, "$label ERROR: ${e.message}")
                            provider.unbindAll(); onDone()
                        }
                    })
            } catch (e: Exception) { DebugLogger.error(TAG, "$label exception: ${e.message}"); onDone() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun saveToDb(file: File, label: String, attempt: Int, location: Location?) {
        try {
            val log = IntruderLog(imagePath = file.absolutePath, timestamp = System.currentTimeMillis(),
                latitude = location?.latitude ?: 0.0, longitude = location?.longitude ?: 0.0,
                attemptNumber = attempt, cameraUsed = label, deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}")
            runBlocking { db.intruderDao().insert(log) }
        } catch (e: Exception) { DebugLogger.error(TAG, "DB error: ${e.message}") }
    }

    private fun finish() { isCapturing = false; stopSelf() }
    private fun createImageFile(label: String): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(File(filesDir, "intruder_photos").also { it.mkdirs() }, "INTRUDER_${label}_${ts}.jpg")
    }
    private fun saveToGallery(file: File) = MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
    private fun getLastKnownLocation(): Location? {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        return try { val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) { null }
    }
    private fun hasCamera() = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun sendNotification(attempt: Int, file: File) {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        var b = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("⚠️ Intruder!").setContentText("Attempt #$attempt captured")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(pi).setVibrate(longArrayOf(0,300,200,300))
        try { val bmp = BitmapFactory.decodeFile(file.absolutePath); if (bmp != null) b = b.setLargeIcon(bmp).setStyle(NotificationCompat.BigPictureStyle().bigPicture(bmp).bigLargeIcon(null as Bitmap?)) } catch (e: Exception) {}
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID_ALERT + attempt, b.build())
    }
    private fun buildCaptureForegroundNotif() = NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_shield).setContentTitle("Trapix").setContentText("Security active...").setPriority(NotificationCompat.PRIORITY_MIN).build()
    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Trapix Security", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }
    override fun onDestroy() { isCapturing = false; serviceScope.cancel(); super.onDestroy() }
}
EOF
echo "✓ IntruderCaptureService.kt"

# ─────────────────────────────────────────────────────────────
# File 2: PatternView.kt
# ─────────────────────────────────────────────────────────────
cat > app/src/main/java/com/trapix/app/ui/lock/PatternView.kt << 'EOF'
package com.trapix.app.ui.lock

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

class PatternView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyle: Int = 0) : View(context, attrs, defStyle) {

    interface OnPatternListener {
        fun onPatternStart()
        fun onPatternComplete(pattern: List<Int>)
    }

    private val GRID_SIZE = 3
    private val NODE_COUNT = GRID_SIZE * GRID_SIZE
    private var cellSize = 0f; private var dotRadius = 0f; private var selectedRadius = 0f; private var touchRadius = 0f
    private var offsetX = 0f; private var offsetY = 0f
    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 6f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val nodePositions = Array(NODE_COUNT) { PointF() }
    private val selectedNodes = mutableListOf<Int>()
    private var currentTouchX = 0f; private var currentTouchY = 0f
    private var isDrawing = false; private var isError = false; private var isSuccess = false
    var onPatternListener: OnPatternListener? = null
    var onTooFewNodes: (() -> Unit)? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = if (w > 0 && h > 0) min(w, h) else (300 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH); recalculate(w, h)
        com.trapix.app.util.DebugLogger.log("PATTERN", "onSizeChanged: w=$w h=$h cellSize=$cellSize touchRadius=$touchRadius")
    }

    private fun recalculate(w: Int, h: Int) {
        val size = min(w, h).toFloat(); cellSize = size / GRID_SIZE.toFloat()
        dotRadius = cellSize * 0.12f; selectedRadius = cellSize * 0.18f; touchRadius = cellSize * 0.4f
        offsetX = (w - size) / 2f; offsetY = (h - size) / 2f
        for (i in 0 until NODE_COUNT) {
            val col = i % GRID_SIZE; val row = i / GRID_SIZE
            nodePositions[i] = PointF(offsetX + cellSize * col + cellSize / 2f, offsetY + cellSize * row + cellSize / 2f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cellSize == 0f) recalculate(width, height)
        val activeColor = when { isError -> 0xFFFF1744.toInt(); isSuccess -> 0xFF00E676.toInt(); else -> 0xFF00E5FF.toInt() }
        val activeDim = when { isError -> 0x44FF1744.toInt(); isSuccess -> 0x4400E676.toInt(); else -> 0x4400E5FF.toInt() }
        if (selectedNodes.size > 1) {
            linePaint.color = activeColor; linePaint.alpha = 160
            for (i in 0 until selectedNodes.size - 1) { val f = nodePositions[selectedNodes[i]]; val t = nodePositions[selectedNodes[i+1]]; canvas.drawLine(f.x, f.y, t.x, t.y, linePaint) }
        }
        if (isDrawing && selectedNodes.isNotEmpty()) {
            linePaint.color = activeColor; linePaint.alpha = 80
            val last = nodePositions[selectedNodes.last()]; canvas.drawLine(last.x, last.y, currentTouchX, currentTouchY, linePaint)
        }
        for (i in 0 until NODE_COUNT) {
            val pos = nodePositions[i]
            if (selectedNodes.contains(i)) {
                glowPaint.color = activeDim; canvas.drawCircle(pos.x, pos.y, selectedRadius * 2f, glowPaint)
                outerRingPaint.color = activeColor; outerRingPaint.alpha = 200; canvas.drawCircle(pos.x, pos.y, selectedRadius, outerRingPaint)
                selectedPaint.color = activeColor; canvas.drawCircle(pos.x, pos.y, dotRadius, selectedPaint)
            } else {
                outerRingPaint.color = 0x44FFFFFF.toInt(); canvas.drawCircle(pos.x, pos.y, dotRadius + 8f, outerRingPaint)
                normalPaint.color = 0xAAFFFFFF.toInt(); canvas.drawCircle(pos.x, pos.y, dotRadius, normalPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isError = false; isSuccess = false; selectedNodes.clear(); isDrawing = true
                currentTouchX = event.x; currentTouchY = event.y
                com.trapix.app.util.DebugLogger.log("PATTERN", "Touch DOWN at x=${event.x.toInt()} y=${event.y.toInt()}, viewSize=${width}x${height}, touchRadius=$touchRadius")
                onPatternListener?.onPatternStart(); checkNodeHit(event.x, event.y); invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> { currentTouchX = event.x; currentTouchY = event.y; checkNodeHit(event.x, event.y); invalidate(); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDrawing = false
                com.trapix.app.util.DebugLogger.log("PATTERN", "Touch UP, nodes: ${selectedNodes.size} -> $selectedNodes")
                if (selectedNodes.size >= 4) { onPatternListener?.onPatternComplete(selectedNodes.toList()) }
                else if (selectedNodes.isNotEmpty()) { setError(); onTooFewNodes?.invoke() }
                invalidate(); return true
            }
        }
        return false
    }

    private fun checkNodeHit(x: Float, y: Float) {
        for (i in 0 until NODE_COUNT) {
            if (selectedNodes.contains(i)) continue
            val pos = nodePositions[i]; val dx = x - pos.x; val dy = y - pos.y
            val dist = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            if (dist <= touchRadius) {
                com.trapix.app.util.DebugLogger.log("PATTERN", "Node $i HIT! dist=${"%.1f".format(dist)}")
                selectedNodes.add(i); invalidate()
            }
        }
    }

    fun clearPattern() { selectedNodes.clear(); isDrawing = false; isError = false; isSuccess = false; invalidate() }
    fun setError() { isError = true; invalidate(); Handler(Looper.getMainLooper()).postDelayed({ clearPattern() }, 900) }
    fun setSuccess() { isSuccess = true; invalidate(); Handler(Looper.getMainLooper()).postDelayed({ clearPattern() }, 400) }
}
EOF
echo "✓ PatternView.kt"

echo ""
echo "✅ All files created!"
echo "Now run: git add . && git commit -m 'fix: pattern, rear camera, map, gallery' && git push"

