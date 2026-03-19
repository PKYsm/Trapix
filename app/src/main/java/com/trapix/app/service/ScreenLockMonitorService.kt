package com.trapix.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.trapix.app.data.prefs.AppPrefs
import com.trapix.app.util.DebugLogger

class ScreenLockMonitorService : AccessibilityService() {

    companion object {
        const val TAG = "ScreenLockMonitor"

        private val KEYGUARD_PACKAGES = setOf(
            "com.android.systemui", "com.android.keyguard",
            "com.samsung.android.keyguard", "com.miui.securitycore",
            "com.oneplus.keyguard", "com.oppo.keyguard",
            "com.realme.keyguard", "com.huawei.systemui"
        )

        private val WRONG_ATTEMPT_HINTS = listOf(
            "incorrect",          // Samsung One UI primary
            "wrong pin", "wrong password", "wrong pattern",
            "incorrect pin", "incorrect password", "incorrect pattern",
            "try again",          // lockout timer pe bhi hota hai — isliye detection cooldown zaroori hai
            "attempts remaining", "last attempt", "too many attempts",
            "phone will be wiped", "failed attempt", "security lockout",
            "galat", "गलत", "गलत पासवर्ड", "गलत पिन",
            "잘못된"
        )

        // BUG 3 FIX: "try again" lockout timer pe hamesha screen pe rehta hai
        // Isliye 2 alag cooldowns:
        // - DETECTION_COOLDOWN: ek wrong attempt ko sirf ek baar count karo (15s)
        // - CAPTURE_COOLDOWN: capture ke baad itne time tak dobara capture mat karo (90s)
        private const val DETECTION_COOLDOWN_MS = 15_000L   // Ek wrong attempt = 1 count
        private const val CAPTURE_COOLDOWN_MS   = 90_000L   // Capture ke baad 90s cooldown
        private const val STATUS_LOG_THROTTLE_MS = 8_000L   // Spam log throttle
    }

    private lateinit var prefs: AppPrefs
    private lateinit var keyguardManager: KeyguardManager

    // BUG 2 FIX: Threshold counter — prefs.wrongAttemptThreshold attempts ke baad hi capture
    private var detectionCount = 0

    // Timers
    private var lastDetectionTime = 0L  // Last individual wrong attempt count time
    private var lastCaptureTime   = 0L  // Last actual capture trigger time
    private var lastStatusLogTime = 0L

    // Text-clear detection (Samsung PIN)
    private var hadTypingActivity = false
    private var lastTypingTime    = 0L
    private const val TYPING_WINDOW_MS = 5_000L

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
            notificationTimeout = 100
        }
        serviceInfo = info
        DebugLogger.log(TAG, "Service connected! threshold=${prefs.wrongAttemptThreshold}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg.startsWith("com.trapix")) return

        // Phone unlocked hai to detection reset karo
        if (!keyguardManager.isKeyguardLocked) {
            if (detectionCount > 0) {
                DebugLogger.log(TAG, "Phone unlocked — resetting detection count (was $detectionCount)")
                detectionCount = 0
                hadTypingActivity = false
            }
            return
        }

        if (!KEYGUARD_PACKAGES.contains(pkg)) return

        val now = System.currentTimeMillis()

        // Status log throttle (spam fix)
        if (now - lastStatusLogTime > STATUS_LOG_THROTTLE_MS) {
            DebugLogger.log(TAG, "Monitoring... count=$detectionCount/${prefs.wrongAttemptThreshold} pkg=$pkg")
            lastStatusLogTime = now
        }

        // ── METHOD 1: Event text ──────────────────────────────────────────────
        val eventText   = event.text?.joinToString(" ")?.lowercase() ?: ""
        val contentDesc = event.contentDescription?.toString()?.lowercase() ?: ""
        val combined    = "$eventText $contentDesc".trim()
        if (combined.length > 1) {
            val matched = WRONG_ATTEMPT_HINTS.find { combined.contains(it) }
            if (matched != null) {
                DebugLogger.log(TAG, "Event text match: '$matched'")
                recordDetection(now)
                return
            }
        }

        // ── METHOD 2: TYPE_VIEW_TEXT_CHANGED → text clear = wrong attempt ─────
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val text = event.text?.joinToString("") ?: ""
            if (text.isNotEmpty()) {
                hadTypingActivity = true; lastTypingTime = now
            } else if (hadTypingActivity && now - lastTypingTime < TYPING_WINDOW_MS) {
                hadTypingActivity = false
                DebugLogger.log(TAG, "Text-clear detection → wrong attempt")
                recordDetection(now)
                return
            }
        }

        // ── METHOD 3: Window scan ─────────────────────────────────────────────
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            scanAllWindows(now)
        }
    }

    private fun scanAllWindows(now: Long) {
        if (now - lastCaptureTime < CAPTURE_COOLDOWN_MS) return
        try {
            var matched: String? = null

            // rootInActiveWindow
            rootInActiveWindow?.let { root ->
                if (KEYGUARD_PACKAGES.contains(root.packageName?.toString() ?: "")) {
                    matched = findErrorText(root, 0)
                }
                try { root.recycle() } catch (_: Exception) {}
            }

            // Samsung fix: iterate all windows
            if (matched == null) {
                try {
                    windows?.forEach { win ->
                        if (matched != null) return@forEach
                        val root = try { win.root } catch (_: Exception) { null } ?: return@forEach
                        if (KEYGUARD_PACKAGES.contains(root.packageName?.toString() ?: "")) {
                            val result = findErrorText(root, 0)
                            if (result != null) {
                                matched = result
                                DebugLogger.log(TAG, "Found error in window type=${win.type}")
                            }
                        }
                        try { root.recycle() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    DebugLogger.error(TAG, "windows() error: ${e.message}")
                }
            }

            if (matched != null) {
                DebugLogger.log(TAG, "WRONG ATTEMPT [window scan]! hint='$matched'")
                recordDetection(now)
            }
        } catch (e: Exception) {
            DebugLogger.error(TAG, "scanAllWindows error: ${e.message}")
        }
    }

    /**
     * BUG 2 + 3 FIX:
     * - DETECTION_COOLDOWN: ek galat attempt ko multiple baar count hone se roko
     *   (Samsung lockout timer pe "try again" screen pe rehta hai → har window scan isko detect karta)
     * - Threshold check: sirf N detections ke baad capture trigger karo
     * - CAPTURE_COOLDOWN: capture ke baad 90s tak dobara capture nahi
     */
    private fun recordDetection(now: Long) {
        // Detection cooldown check — same attempt ko multiple baar count mat karo
        if (now - lastDetectionTime < DETECTION_COOLDOWN_MS) {
            DebugLogger.logThrottled(TAG, "Detection ignored (cooldown, ${(now - lastDetectionTime)/1000}s < 15s)", 5000)
            return
        }

        // Capture ke baad ka cooldown
        if (now - lastCaptureTime < CAPTURE_COOLDOWN_MS) {
            DebugLogger.logThrottled(TAG, "Capture cooldown active (${(now - lastCaptureTime)/1000}s < 90s)", 10000)
            return
        }

        lastDetectionTime = now
        detectionCount++
        val threshold = prefs.wrongAttemptThreshold
        DebugLogger.log(TAG, "Wrong attempt #$detectionCount / threshold=$threshold")

        if (detectionCount < threshold) {
            DebugLogger.log(TAG, "Not yet at threshold, waiting... ($detectionCount/$threshold)")
            return
        }

        // Threshold reached → CAPTURE!
        detectionCount = 0
        lastCaptureTime = now
        hadTypingActivity = false
        prefs.wrongAttemptCount++
        DebugLogger.log(TAG, "THRESHOLD REACHED! TRIGGERING CAPTURE! total count=${prefs.wrongAttemptCount}")

        val intent = android.content.Intent(this, IntruderCaptureService::class.java).apply {
            action = IntruderCaptureService.ACTION_CAPTURE
            putExtra(IntruderCaptureService.EXTRA_ATTEMPT_NUMBER, prefs.wrongAttemptCount)
        }
        startForegroundService(intent)
    }

    private fun findErrorText(node: AccessibilityNodeInfo?, depth: Int): String? {
        if (node == null || depth > 10) return null
        val candidates = buildList {
            node.text?.toString()?.lowercase()?.let { if (it.isNotBlank()) add(it) }
            node.contentDescription?.toString()?.lowercase()?.let { if (it.isNotBlank()) add(it) }
            try { node.error?.toString()?.lowercase()?.let { if (it.isNotBlank()) add(it) } } catch (_: Exception) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try { node.hintText?.toString()?.lowercase()?.let { if (it.isNotBlank()) add(it) } } catch (_: Exception) {}
            }
        }
        for (candidate in candidates) {
            val hint = WRONG_ATTEMPT_HINTS.find { candidate.contains(it) }
            if (hint != null) return hint
        }
        for (i in 0 until node.childCount) {
            val result = findErrorText(try { node.getChild(i) } catch (_: Exception) { null }, depth + 1)
            if (result != null) return result
        }
        return null
    }

    override fun onInterrupt() {
        DebugLogger.log(TAG, "Service interrupted!")
    }
}
