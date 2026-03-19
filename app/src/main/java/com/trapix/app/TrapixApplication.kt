package com.trapix.app

import android.app.Application
import com.trapix.app.util.AppUtils

class TrapixApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.trapix.app.util.DebugLogger.init(this)
        com.trapix.app.util.DebugLogger.log("APP", "Trapix started - version 1.1.0")
        AppUtils.createNotificationChannels(this)
    }
}
