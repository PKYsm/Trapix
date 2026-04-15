package com.trapix.app.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import com.trapix.app.R
import com.trapix.app.data.prefs.AppPrefs
import com.trapix.app.service.IntruderCaptureService
import com.trapix.app.ui.gallery.MainActivity
import com.trapix.app.util.DebugLogger
import java.io.File

/**
 * BUG 4 FIX: Screen unlock hone pe pending notification send karo.
 * ACTION_USER_PRESENT = user ne screen unlock kar liya (PIN/Pattern/Face/Fingerprint).
 */
class ScreenUnlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_PRESENT) return

        val prefs = AppPrefs(context)
        val attempt = prefs.pendingNotifAttempt
        val path    = prefs.pendingNotifPath

        if (attempt == -1 || path.isEmpty()) return  // Koi pending notification nahi

        DebugLogger.log("ScreenUnlockReceiver", "Screen unlocked — sending pending notification attempt=$attempt")
        prefs.clearPendingNotif()

        val file = File(path)
        sendNotification(context, attempt, file)
    }

    private fun sendNotification(context: Context, attempt: Int, file: File) {
        val channelId = IntruderCaptureService.CHANNEL_ID
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Bug 6 Fix: Channel is already created in TrapixApplication.onCreate() and
        // IntruderCaptureService.onCreate(). No need to recreate it here every unlock.

        val pi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        var b = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("⚠️ Intruder Detected!")
            .setContentText("Attempt #$attempt captured while you were away")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setVibrate(longArrayOf(0, 300, 200, 300))

        if (file.exists()) {
            try {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) b = b.setLargeIcon(bmp)
                    .setStyle(NotificationCompat.BigPictureStyle().bigPicture(bmp).bigLargeIcon(null as android.graphics.Bitmap?))
            } catch (_: Exception) {}
        }

        nm.notify(IntruderCaptureService.NOTIF_ID_ALERT + attempt, b.build())
    }
}
