package com.trapix.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.trapix.app.data.prefs.AppPrefs

class ScreenLockMonitorService : AccessibilityService() {

    companion object {
        const val TAG = "ScreenLockMonitor"
        // Packages that handle lock screen on various manufacturers
        private val LOCK_SCREEN_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.keyguard",
            "com.samsung.android.keyguard",
            "com.miui.securitycore",
            "com.oneplus.keyguard",
            "com.oppo.keyguard",
            "com.realme.keyguard"
        )
        // Text hints that indicate wrong attempt on lock screen
        private val WRONG_ATTEMPT_HINTS = setOf(
            "wrong password", "wrong pin", "wrong pattern",
            "incorrect password", "incorrect pin",
            "try again", "attempts remaining",
            "galat password", "galat pin",
            "last attempt", "phone will be wiped",
            "too many attempts"
        )
    }

    private lateinit var prefs: AppPrefs
    private var lastCaptureTime = 0L
    private val CAPTURE_COOLDOWN_MS = 3000L // 3 seconds between captures

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = AppPrefs(this)
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        serviceInfo = info
Log.d(TAG, "ScreenLockMonitorService connected")
        com.trapix.app.util.DebugLogger.log("MONITOR", "AccessibilityService connected!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        // Ignore Trapix own package - prevent self-triggering
        if (pkg.startsWith("com.trapix")) return
        com.trapix.app.util.DebugLogger.log("MONITOR", "Event from pkg=$pkg type=${event.eventType}")

        // Only care about lock screen packages
        if (!LOCK_SCREEN_PACKAGES.contains(pkg)) return

        val now = System.currentTimeMillis()
        if (now - lastCaptureTime < CAPTURE_COOLDOWN_MS) return

        // Check event text for wrong attempt hints
        val eventText = event.text?.joinToString(" ")?.lowercase() ?: ""
        val contentDesc = event.contentDescription?.toString()?.lowercase() ?: ""
        val combined = "$eventText $contentDesc"

        val isWrongAttempt = WRONG_ATTEMPT_HINTS.any { combined.contains(it) }

        if (!isWrongAttempt) {
            // Also scan window content for error messages
            scanWindowForErrors(event)
            return
        }

Log.d(TAG, "Wrong lock attempt detected via event text: $combined")
        com.trapix.app.util.DebugLogger.log("MONITOR", "WRONG ATTEMPT DETECTED! text=$combined")
        triggerCapture()
        lastCaptureTime = now
    }

    private fun scanWindowForErrors(event: AccessibilityEvent) {
        try {
            val root = rootInActiveWindow ?: return
            val found = findErrorText(root)
            root.recycle()
            if (found) {
                val now = System.currentTimeMillis()
                if (now - lastCaptureTime >= CAPTURE_COOLDOWN_MS) {
                    Log.d(TAG, "Wrong lock attempt detected via window scan")
                    triggerCapture()
                    lastCaptureTime = now
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "scanWindowForErrors error: ${e.message}")
        }
    }

    private fun findErrorText(node: AccessibilityNodeInfo?): Boolean {
        node ?: return false
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val combined = "$text $desc"
        if (WRONG_ATTEMPT_HINTS.any { combined.contains(it) }) return true
        for (i in 0 until node.childCount) {
            if (findErrorText(node.getChild(i))) return true
        }
        return false
    }

    private fun triggerCapture() {
        prefs.wrongAttemptCount++
        val intent = Intent(this, IntruderCaptureService::class.java).apply {
            action = IntruderCaptureService.ACTION_CAPTURE
            putExtra(IntruderCaptureService.EXTRA_ATTEMPT_NUMBER, prefs.wrongAttemptCount)
        }
        startForegroundService(intent)
    }

    override fun onInterrupt() {
        Log.d(TAG, "ScreenLockMonitorService interrupted")
    }
}
