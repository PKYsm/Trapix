package com.trapix.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.trapix.app.data.prefs.AppPrefs
import com.trapix.app.util.AppUtils
import com.trapix.app.util.DebugLogger

class TrapixApplication : Application() {

    private var activityCount = 0
    var needsLockOnResume = false

    // Ye flag unlockSuccess() set karta hai taaki onActivityStopped override na kare
    var isUnlockingNow = false

    private val lockExemptActivities = setOf(
        "LockScreenActivity", "SplashActivity", "SetupLockActivity"
    )

    override fun onCreate() {
        super.onCreate()
        DebugLogger.init(this)
        DebugLogger.log("APP", "Trapix started - version 1.2.0")
        AppUtils.createNotificationChannels(this)
        registerAppLifecycle()
    }

    private fun registerAppLifecycle() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {

            override fun onActivityStarted(activity: Activity) {
                activityCount++
                val activityName = activity.javaClass.simpleName

                if (activityCount == 1 && needsLockOnResume && !isUnlockingNow) {
                    val prefs = AppPrefs(activity)
                    // Lock-exempt activities pe lock mat lagao
                    if (prefs.lockOnMinimize && activityName !in lockExemptActivities) {
                        needsLockOnResume = false
                        DebugLogger.log("APP", "App resumed → showing lock")
                        try {
                            val intent = android.content.Intent(
                                activity,
                                com.trapix.app.ui.lock.LockScreenActivity::class.java
                            ).apply {
                                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                            }
                            activity.startActivity(intent)
                        } catch (e: Exception) {
                            DebugLogger.error("APP", "Lock start failed: ${e.message}")
                        }
                    } else {
                        needsLockOnResume = false
                    }
                }
            }

            override fun onActivityStopped(activity: Activity) {
                activityCount--
                val activityName = activity.javaClass.simpleName

                // FIX: Unlock flow mein LockScreenActivity stop hoti hai —
                // us waqt needsLockOnResume set NAHI karna chahiye.
                // Isliye isUnlockingNow check karo.
                // Aur lock-exempt activities (LockScreen itself) ke stop pe bhi set mat karo.
                if (activityCount == 0
                    && !isUnlockingNow
                    && activityName !in lockExemptActivities
                ) {
                    val prefs = AppPrefs(activity)
                    if (prefs.lockOnMinimize) {
                        needsLockOnResume = true
                        DebugLogger.log("APP", "App went to background → lock on resume")
                    }
                }
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {
                // isUnlockingNow ek transaction ke liye hi — clear karo
                if (a.javaClass.simpleName == "LockScreenActivity") {
                    isUnlockingNow = false
                }
            }
        })
    }
}
