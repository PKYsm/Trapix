package com.trapix.app.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class AppPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("trapix_prefs", Context.MODE_PRIVATE)

    companion object {
        const val LOCK_TYPE_NONE     = "none"
        const val LOCK_TYPE_PIN      = "pin"
        const val LOCK_TYPE_PATTERN  = "pattern"
        const val LOCK_TYPE_PASSWORD = "password"

        private const val KEY_LOCK_TYPE               = "lock_type"
        private const val KEY_LOCK_VALUE              = "lock_value"
        private const val KEY_IS_SETUP_DONE           = "setup_done"
        private const val KEY_BIOMETRIC_ENABLED       = "biometric_enabled"
        private const val KEY_WRONG_ATTEMPT_THRESHOLD = "wrong_attempt_threshold"
        private const val KEY_CAPTURE_FRONT           = "capture_front"
        private const val KEY_CAPTURE_REAR            = "capture_rear"
        private const val KEY_HIDE_FROM_LAUNCHER      = "hide_from_launcher"
        private const val KEY_NOTIFICATION_ENABLED    = "notification_enabled"
        private const val KEY_WRONG_ATTEMPT_COUNT     = "wrong_attempt_count"
        private const val KEY_SAVE_TO_GALLERY         = "save_to_gallery"
        private const val KEY_THEME_DARK              = "theme_dark"
        private const val KEY_LOCK_ON_MINIMIZE        = "lock_on_minimize"
        // BUG 6 FIX: Persist LockScreen wrong attempts across recreations
        private const val KEY_LOCK_SCREEN_ATTEMPTS    = "lock_screen_attempts"
        // BUG 4: Deferred notification
        private const val KEY_PENDING_NOTIF_ATTEMPT   = "pending_notif_attempt"
        private const val KEY_PENDING_NOTIF_PATH      = "pending_notif_path"
    }

    var lockType: String
        get()      = prefs.getString(KEY_LOCK_TYPE, LOCK_TYPE_NONE) ?: LOCK_TYPE_NONE
        set(value) = prefs.edit { putString(KEY_LOCK_TYPE, value) }

    var lockValue: String
        get()      = prefs.getString(KEY_LOCK_VALUE, "") ?: ""
        set(value) = prefs.edit { putString(KEY_LOCK_VALUE, value) }

    var isSetupDone: Boolean
        get()      = prefs.getBoolean(KEY_IS_SETUP_DONE, false)
        set(value) = prefs.edit { putBoolean(KEY_IS_SETUP_DONE, value) }

    var isBiometricEnabled: Boolean
        get()      = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_BIOMETRIC_ENABLED, value) }

    var wrongAttemptThreshold: Int
        get()      = prefs.getInt(KEY_WRONG_ATTEMPT_THRESHOLD, 3)
        set(value) = prefs.edit { putInt(KEY_WRONG_ATTEMPT_THRESHOLD, value) }

    var captureFrontCamera: Boolean
        get()      = prefs.getBoolean(KEY_CAPTURE_FRONT, true)
        set(value) = prefs.edit { putBoolean(KEY_CAPTURE_FRONT, value) }

    var captureRearCamera: Boolean
        get()      = prefs.getBoolean(KEY_CAPTURE_REAR, false)
        set(value) = prefs.edit { putBoolean(KEY_CAPTURE_REAR, value) }

    var hideFromLauncher: Boolean
        get()      = prefs.getBoolean(KEY_HIDE_FROM_LAUNCHER, false)
        set(value) = prefs.edit { putBoolean(KEY_HIDE_FROM_LAUNCHER, value) }

    var notificationEnabled: Boolean
        get()      = prefs.getBoolean(KEY_NOTIFICATION_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFICATION_ENABLED, value) }

    var wrongAttemptCount: Int
        get()      = prefs.getInt(KEY_WRONG_ATTEMPT_COUNT, 0)
        set(value) = prefs.edit { putInt(KEY_WRONG_ATTEMPT_COUNT, value) }

    var saveToGallery: Boolean
        get()      = prefs.getBoolean(KEY_SAVE_TO_GALLERY, true)
        set(value) = prefs.edit { putBoolean(KEY_SAVE_TO_GALLERY, value) }

    var isDarkTheme: Boolean
        get()      = prefs.getBoolean(KEY_THEME_DARK, true)
        set(value) = prefs.edit { putBoolean(KEY_THEME_DARK, value) }

    // Feature 2: Lock on minimize
    var lockOnMinimize: Boolean
        get()      = prefs.getBoolean(KEY_LOCK_ON_MINIMIZE, false)
        set(value) = prefs.edit { putBoolean(KEY_LOCK_ON_MINIMIZE, value) }

    // BUG 6 FIX: Persist wrong attempts in LockScreen across activity recreations
    var lockScreenAttempts: Int
        get()      = prefs.getInt(KEY_LOCK_SCREEN_ATTEMPTS, 0)
        set(value) = prefs.edit { putInt(KEY_LOCK_SCREEN_ATTEMPTS, value) }

    // Deferred notification
    var pendingNotifAttempt: Int
        get()      = prefs.getInt(KEY_PENDING_NOTIF_ATTEMPT, -1)
        set(value) = prefs.edit { putInt(KEY_PENDING_NOTIF_ATTEMPT, value) }

    var pendingNotifPath: String
        get()      = prefs.getString(KEY_PENDING_NOTIF_PATH, "") ?: ""
        set(value) = prefs.edit { putString(KEY_PENDING_NOTIF_PATH, value) }

    fun clearPendingNotif() {
        prefs.edit { putInt(KEY_PENDING_NOTIF_ATTEMPT, -1); putString(KEY_PENDING_NOTIF_PATH, "") }
    }

    fun resetWrongAttemptCount() {
        wrongAttemptCount = 0
        lockScreenAttempts = 0
    }
}
