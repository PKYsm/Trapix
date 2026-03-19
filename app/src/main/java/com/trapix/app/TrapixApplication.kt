package com.trapix.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.trapix.app.data.prefs.AppPrefs
import com.trapix.app.util.AppUtils
import com.trapix.app.util.DebugLogger

class TrapixApplication : Application() {

    // App background detection for minimize→lock feature
    private var activityCount = 0
    var needsLockOnResume = false

    // Activities that should NOT trigger lock when resumed
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
                // App came back from background
                if (activityCount == 1 && needsLockOnResume) {
                    val activityName = activity.javaClass.simpleName
                    val prefs = AppPrefs(activity)
                    if (prefs.lockOnMinimize && activityName !in lockExemptActivities) {
                        needsLockOnResume = false
                        DebugLogger.log("APP", "App resumed from background → showing lock screen")
                        val intent = android.content.Intent(activity, com.trapix.app.ui.lock.LockScreenActivity::class.java)
                        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                        activity.startActivity(intent)
                    } else {
                        needsLockOnResume = false
                    }
                }
            }

            override fun onActivityStopped(activity: Activity) {
                activityCount--
                if (activityCount == 0) {
                    // App went to background
                    val prefs = AppPrefs(activity)
                    if (prefs.lockOnMinimize) {
                        needsLockOnResume = true
                        DebugLogger.log("APP", "App went to background → lock on resume enabled")
                    }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
