package com.trapix.app

import android.app.Application
import com.trapix.app.util.AppUtils

class TrapixApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppUtils.createNotificationChannels(this)
    }
}
