package com.trapix.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.trapix.app.data.prefs.AppPrefs
import com.trapix.app.util.DebugLogger

class ScreenLockMonitorService : AccessibilityService() {

    companion object {
        const val TAG = "ScreenLockMonitor"
        private val KEYGUARD_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.keyguard",
            "com.samsung.android.keyguard",
            "com.miui.securitycore",
            "com.oneplus.keyguard",
            "com.oppo.keyguard",
            "com.realme.keyguard"
        )
        private val WRONG_ATTEMPT_HINTS = listOf(
            "wrong password", "wrong pin", "wrong pattern",
            "incorrect password", "incorrect pin",
            "try again", "attempts remaining",
            "galat password", "galat pin",
            "last attempt", "phone will be wiped",
            "too many attempts", "incorrect attempt"
        )
    }

    private lateinit var prefs: AppPrefs
    private lateinit var keyguardManager: KeyguardManager
    private var lastCaptureTime = 0L
    private val CAPTURE_COOLDOWN_MS = 4000L

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = AppPrefs(this)
        keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 200
        }
        serviceInfo = info
        DebugLogger.log(TAG, "Service connected! Ready to monitor.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        // Ignore Trapix itself
        if (pkg.startsWith("com.trapix")) return

        // CRITICAL: Only process when phone is ACTUALLY locked
        val isLocked = keyguardManager.isKeyguardLocked
        if (!isLocked) {
            // Phone is unlocked - ignore all events
            return
        }

        // Only care about keyguard/systemui packages
        if (!KEYGUARD_PACKAGES.contains(pkg)) return

        val now = System.currentTimeMillis()
        if (now - lastCaptureTime < CAPTURE_COOLDOWN_MS) return

        DebugLogger.log(TAG, "Phone IS locked. Event from $pkg type=${event.eventType}")

        // Check event text
        val eventText = event.text?.joinToString(" ")?.lowercase() ?: ""
        val contentDesc = event.contentDescription?.toString()?.lowercase() ?: ""
        val combined = "$eventText $contentDesc"

        if (combined.isNotBlank() && combined.length > 2) {
            val matched = WRONG_ATTEMPT_HINTS.find { combined.contains(it) }
            if (matched != null) {
                DebugLogger.log(TAG, "WRONG ATTEMPT via text! matched='$matched' in '$combined'")
                triggerCapture()
                lastCaptureTime = now
                return
            }
        }

        // Scan window content
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            scanWindowForErrors(now)
        }
    }

    private fun scanWindowForErrors(now: Long) {
        try {
            val root = rootInActiveWindow ?: return
            val matched = findErrorText(root, 0)
            root.recycle()
            if (matched != null && now - lastCaptureTime >= CAPTURE_COOLDOWN_MS) {
                DebugLogger.log(TAG, "WRONG ATTEMPT via window scan! matched='$matched'")
                triggerCapture()
                lastCaptureTime = now
            }
        } catch (e: Exception) {
            DebugLogger.error(TAG, "scanWindow error: ${e.message}")
        }
    }

    private fun findErrorText(node: AccessibilityNodeInfo?, depth: Int): String? {
        if (node == null || depth > 6) return null
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val combined = "$text $desc"
        val matched = WRONG_ATTEMPT_HINTS.find { combined.contains(it) }
        if (matched != null) return matched
        for (i in 0 until node.childCount) {
            val result = findErrorText(node.getChild(i), depth + 1)
            if (result != null) return result
        }
        return null
    }

    private fun triggerCapture() {
        prefs.wrongAttemptCount++
        DebugLogger.log(TAG, "Triggering capture! total count=${prefs.wrongAttemptCount}")
        val intent = Intent(this, IntruderCaptureService::class.java).apply {
            action = IntruderCaptureService.ACTION_CAPTURE
            putExtra(IntruderCaptureService.EXTRA_ATTEMPT_NUMBER, prefs.wrongAttemptCount)
        }
        startForegroundService(intent)
    }

    override fun onInterrupt() {
        DebugLogger.log(TAG, "Service interrupted!")
    }
}

