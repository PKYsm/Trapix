package com.trapix.app.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.*

object DebugLogger {

    private const val PREF_NAME = "trapix_debug_logs"
    private const val KEY_LOGS = "logs"
    private const val MAX_LOGS = 300
    private val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private var prefs: SharedPreferences? = null

    // BUG 4 FIX: Throttle map — same message ko baar baar log hone se roko
    // Key: "tag:message", Value: last log timestamp
    private val throttleMap = mutableMapOf<String, Long>()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /** Normal log — hamesha save karo */
    fun log(tag: String, message: String) {
        val time = sdf.format(Date())
        val entry = "[$time] [$tag] $message"
        android.util.Log.d("TrapixDebug", entry)
        saveLog(entry)
    }

    /** Error log — hamesha save karo */
    fun error(tag: String, message: String) {
        val time = sdf.format(Date()  )
        val entry = "[$time] [ERR/$tag] $message"
        android.util.Log.e("TrapixDebug", entry)
        saveLog(entry)
    }

    /**
     * BUG 4 FIX: Throttled log — same message ko `minIntervalMs` milliseconds mein
     * sirf ek baar log karo. type=2048 spam ke liye use karo.
     *
     * @param minIntervalMs Minimum gap in ms between same logs (default 5s)
     */
    fun logThrottled(tag: String, message: String, minIntervalMs: Long = 5000L) {
        val key = "$tag:$message"
        val now = System.currentTimeMillis()
        val lastTime = throttleMap[key] ?: 0L
        if (now - lastTime < minIntervalMs) return  // Too soon, skip
        throttleMap[key] = now
        log(tag, message)
    }

    private fun saveLog(entry: String) {
        val p = prefs ?: return
        val existing = p.getString(KEY_LOGS, "") ?: ""
        val lines = existing.split("\n").filter { it.isNotEmpty() }.takeLast(MAX_LOGS - 1)
        val updated = (lines + entry).joinToString("\n")
        p.edit { putString(KEY_LOGS, updated) }
    }

    fun getLogs(): String {
        return prefs?.getString(KEY_LOGS, "No logs yet") ?: "Logger not initialized"
    }

    fun clearLogs() {
        prefs?.edit { putString(KEY_LOGS, "") }
        throttleMap.clear()
    }
}
