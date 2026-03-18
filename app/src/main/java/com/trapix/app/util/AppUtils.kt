package com.trapix.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object AppUtils {

    fun createNotificationChannels(context: Context) {
        val channel = NotificationChannel(
            "trapix_capture_channel",
            "Trapix Security Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Intruder capture notifications"
            enableVibration(true)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    fun formatTimestamp(millis: Long): String {
        val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(millis))
    }

    fun getAppStorageDir(context: Context): File {
        val dir = File(context.filesDir, "intruder_photos")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getTotalStorageUsed(context: Context): String {
        val dir = getAppStorageDir(context)
        val totalBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return when {
            totalBytes < 1024 -> "${totalBytes}B"
            totalBytes < 1024 * 1024 -> "${totalBytes / 1024}KB"
            else -> String.format("%.1fMB", totalBytes / (1024.0 * 1024.0))
        }
    }
}
