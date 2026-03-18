package com.trapix.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.trapix.app.data.prefs.AppPrefs

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = AppPrefs(context)
            if (prefs.isSetupDone) {
                // App is set up - security is active passively
                // No additional service needed as lock happens when app is opened
            }
        }
    }
}
