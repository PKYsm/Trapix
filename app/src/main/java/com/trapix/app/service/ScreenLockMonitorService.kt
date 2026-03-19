package com.trapix.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
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
            "com.realme.keyguard",
            "com.huawei.systemui"
        )

        // EXPANDED: Samsung shows "Incorrect" (just that word), One UI uses short strings
        private val WRONG_ATTEMPT_HINTS = listOf(
            // Short matches first (Samsung One UI uses very short strings)
            "incorrect",          // Samsung One UI primary error
            "wrong pin",
            "wrong password",
            "wrong pattern",
            "try again",
            "attempts remaining",
            "last attempt",
            "too many attempts",
            "incorrect pin",
            "incorrect password",
            "incorrect pattern",
            "phone will be wiped",
            "incorrect attempt",
            "failed attempt",
            "security lockout",
            "check your",
            // Hindi / Hinglish
            "galat", "गलत", "फिर कोशिश", "गलत पासवर्ड", "गलत पिन",
            // Korean (Samsung devices in Korea)
            "잘못된", "다시 시도",
            // Generic
            "wrong", "failed"
        )
    }

    private lateinit var prefs: AppPrefs
    private lateinit var keyguardManager: KeyguardManager

    // Capture cooldown
    private var lastCaptureTime = 0L
    private val CAPTURE_COOLDOWN_MS = 5000L

    // Log throttle: "Phone IS locked" spam ko rok
    private var lastStatusLogTime = 0L
    private val STATUS_LOG_THROTTLE_MS = 5000L

    // Text-clear detection: typing ke baad clear = wrong attempt (Samsung)
    private var hadTypingActivity = false
    private var lastTypingTime = 0L
    private val TYPING_WINDOW_MS = 4000L

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
        DebugLogger.log(TAG, "Service connected! Samsung-compatible monitoring active.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return

        // Trapix ko ignore karo
        if (pkg.startsWith("com.trapix")) return

        // Sirf locked state mein kaam karo
        if (!keyguardManager.isKeyguardLocked) return

        // Sirf keyguard packages se events process karo
        if (!KEYGUARD_PACKAGES.contains(pkg)) return

        val now = System.currentTimeMillis()

        // STATUS LOG THROTTLE: Har event pe log mat karo (type=2048 spam fix)
        if (now - lastStatusLogTime > STATUS_LOG_THROTTLE_MS) {
            DebugLogger.log(TAG, "Monitoring locked screen... pkg=$pkg type=${event.eventType}")
            lastStatusLogTime = now
        }

        // Cooldown check
        if (now - lastCaptureTime < CAPTURE_COOLDOWN_MS) return

        // ── METHOD 1: Event text mein directly error check ──
        val eventText = event.text?.joinToString(" ")?.lowercase() ?: ""
        val contentDesc = event.contentDescription?.toString()?.lowercase() ?: ""
        val combined = "$eventText $contentDesc".trim()
        if (combined.length > 1) {
            val matched = WRONG_ATTEMPT_HINTS.find { combined.contains(it) }
            if (matched != null) {
                DebugLogger.log(TAG, "WRONG ATTEMPT [event text]! hint='$matched' in: '$combined'")
                triggerCapture(now)
                return
            }
        }

        // ── METHOD 2: TYPE_VIEW_TEXT_CHANGED → text clear detection (Samsung PIN) ──
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val text = event.text?.joinToString("") ?: ""
            if (text.isNotEmpty()) {
                // User PIN type kar raha hai
                hadTypingActivity = true
                lastTypingTime = now
                DebugLogger.logThrottled(TAG, "PIN typing detected...", 2000)
            } else if (hadTypingActivity && now - lastTypingTime < TYPING_WINDOW_MS) {
                // Text clear ho gaya after typing = wrong attempt!
                hadTypingActivity = false
                DebugLogger.log(TAG, "WRONG ATTEMPT [text-clear]! PIN cleared after typing → wrong attempt detected")
                triggerCapture(now)
                return
            }
        }

        // ── METHOD 3: Window content scan (state/content changed events) ──
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            scanAllWindowsForErrors(now)
        }
    }

    /**
     * Samsung Fix: rootInActiveWindow ke baad windows list bhi scan karo.
     * Samsung pe keyguard window rootInActiveWindow mein available nahi hoti,
     * lekin windows() list mein milti hai.
     */
    private fun scanAllWindowsForErrors(now: Long) {
        if (now - lastCaptureTime < CAPTURE_COOLDOWN_MS) return
        try {
            var matched: String? = null

            // METHOD 3A: rootInActiveWindow (works on many devices)
            rootInActiveWindow?.let { root ->
                val rootPkg = root.packageName?.toString() ?: ""
                if (KEYGUARD_PACKAGES.contains(rootPkg)) {
                    matched = findErrorText(root, 0)
                }
                try { root.recycle() } catch (_: Exception) {}
            }

            // METHOD 3B: Iterate ALL windows — Samsung ke liye critical!
            // Samsung pe keyguard window "active" nahi hoti, sirf windows list mein hoti hai
            if (matched == null) {
                try {
                    val wins = windows
                    if (wins != null) {
                        for (win in wins) {
                            if (matched != null) break
                            val root = try { win.root } catch (_: Exception) { null } ?: continue
                            val rootPkg = root.packageName?.toString() ?: ""
                            if (KEYGUARD_PACKAGES.contains(rootPkg)) {
                                matched = findErrorText(root, 0)
                                if (matched != null) {
                                    DebugLogger.log(TAG, "Found error in window pkg=$rootPkg type=${win.type}")
                                }
                            }
                            try { root.recycle() } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    DebugLogger.error(TAG, "windows() scan error: ${e.message}")
                }
            }

            if (matched != null) {
                DebugLogger.log(TAG, "WRONG ATTEMPT [window scan]! hint='$matched'")
                triggerCapture(now)
            }
        } catch (e: Exception) {
            DebugLogger.error(TAG, "scanAllWindows error: ${e.message}")
        }
    }

    /**
     * Node tree recursively scan karo — text, desc, error, hintText sab check karo.
     * Samsung ke liye depth 10 tak (deeper UI hierarchy).
     */
    private fun findErrorText(node: AccessibilityNodeInfo?, depth: Int): String? {
        if (node == null || depth > 10) return null

        // Samsung alag alag fields mein error show karta hai
        val candidates = buildList {
            node.text?.toString()?.lowercase()?.let { if (it.isNotBlank()) add(it) }
            node.contentDescription?.toString()?.lowercase()?.let { if (it.isNotBlank()) add(it) }
            // error text (TextInputLayout, EditText error)
            try {
                node.error?.toString()?.lowercase()?.let { if (it.isNotBlank()) add(it) }
            } catch (_: Exception) {}
            // hintText (API 26+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    node.hintText?.toString()?.lowercase()?.let { if (it.isNotBlank()) add(it) }
                } catch (_: Exception) {}
            }
            // tooltipText (API 28+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    node.tooltipText?.toString()?.lowercase()?.let { if (it.isNotBlank()) add(it) }
                } catch (_: Exception) {}
            }
        }

        for (candidate in candidates) {
            val hint = WRONG_ATTEMPT_HINTS.find { candidate.contains(it) }
            if (hint != null) return hint
        }

        // Child nodes recursively scan karo
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            val result = findErrorText(child, depth + 1)
            if (result != null) return result
        }
        return null
    }

    private fun triggerCapture(now: Long) {
        if (now - lastCaptureTime < CAPTURE_COOLDOWN_MS) return
        lastCaptureTime = now
        hadTypingActivity = false  // reset typing state
        prefs.wrongAttemptCount++
        DebugLogger.log(TAG, "TRIGGERING CAPTURE! total count=${prefs.wrongAttemptCount}")
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
