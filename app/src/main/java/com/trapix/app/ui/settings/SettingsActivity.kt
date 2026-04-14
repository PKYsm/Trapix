package com.trapix.app.ui.settings

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.trapix.app.R
import com.trapix.app.data.prefs.AppPrefs
import com.trapix.app.databinding.ActivitySettingsBinding
import com.trapix.app.ui.lock.SetupLockActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPrefs

    // Feature 3: Track unsaved changes
    private var hasUnsavedChanges = false
    private fun markChanged() { hasUnsavedChanges = true; binding.btnSaveSettings.text = "💾 Save Settings*" }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        updatePermissionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Settings"

        loadCurrentSettings()
        setupListeners()
        setupBackPressWarning()
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        updatePermissionStatus()
    }

    private fun loadCurrentSettings() {
        binding.switchBiometric.isChecked     = prefs.isBiometricEnabled
        binding.switchFrontCamera.isChecked   = prefs.captureFrontCamera
        binding.switchRearCamera.isChecked    = prefs.captureRearCamera
        binding.switchNotifications.isChecked = prefs.notificationEnabled
        binding.switchSaveGallery.isChecked   = prefs.saveToGallery
        binding.switchHideLauncher.isChecked  = prefs.hideFromLauncher
        binding.switchDarkTheme.isChecked     = prefs.isDarkTheme
        binding.switchLockOnMinimize.isChecked = prefs.lockOnMinimize
        updateThresholdDisplay()
        updateLockTypeDisplay()
        updateServiceStatus()
        updatePermissionStatus()
    }

    private fun updateThresholdDisplay() {
        binding.tvThresholdValue.text = "${prefs.wrongAttemptThreshold} wrong attempts"
    }

    private fun updateLockTypeDisplay() {
        binding.tvCurrentLockType.text = "Current: ${when (prefs.lockType) {
            AppPrefs.LOCK_TYPE_PIN      -> "PIN"
            AppPrefs.LOCK_TYPE_PATTERN  -> "Pattern"
            AppPrefs.LOCK_TYPE_PASSWORD -> "Password"
            else -> "None"
        }}"
    }

    private fun updateServiceStatus() {
        val isActive = isAccessibilityServiceEnabled()
        binding.tvServiceStatus.text = if (isActive) "✅ Screen Monitor: Active" else "❌ Screen Monitor: Inactive — tap button below to enable"
        binding.tvServiceStatus.setTextColor(if (isActive) 0xFF00E676.toInt() else 0xFFFF5252.toInt())
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val enabled = AndroidSettings.Secure.getInt(contentResolver, AndroidSettings.Secure.ACCESSIBILITY_ENABLED, 0)
            if (enabled == 1) {
                val services = AndroidSettings.Secure.getString(contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                services.contains(packageName, ignoreCase = true)
            } else false
        } catch (_: Exception) { false }
    }

    // Feature 4: Permission status
    private fun updatePermissionStatus() {
        val hasCam  = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasLoc  = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else true

        binding.tvPermCamera.text = if (hasCam) "✅ Camera" else "❌ Camera (required for capture)"
        binding.tvPermLocation.text = if (hasLoc) "✅ Location" else "❌ Location (optional)"
        binding.tvPermNotification.text = if (hasNotif) "✅ Notifications" else "❌ Notifications (optional)"

        binding.btnGrantCamera.visibility    = if (!hasCam)   android.view.View.VISIBLE else android.view.View.GONE
        binding.btnGrantLocation.visibility  = if (!hasLoc)   android.view.View.VISIBLE else android.view.View.GONE
        binding.btnGrantNotif.visibility     = if (!hasNotif) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun setupListeners() {
        // Switches — save instantly but mark changed for UI feedback
        binding.switchBiometric.setOnCheckedChangeListener    { _, v -> prefs.isBiometricEnabled = v;  markChanged() }
        binding.switchFrontCamera.setOnCheckedChangeListener  { _, v -> prefs.captureFrontCamera = v;  markChanged() }
        binding.switchRearCamera.setOnCheckedChangeListener   { _, v -> prefs.captureRearCamera = v;   markChanged() }
        binding.switchNotifications.setOnCheckedChangeListener { _, v -> prefs.notificationEnabled = v; markChanged() }
        binding.switchSaveGallery.setOnCheckedChangeListener  { _, v -> prefs.saveToGallery = v;       markChanged() }
        binding.switchDarkTheme.setOnCheckedChangeListener    { _, v -> prefs.isDarkTheme = v;         markChanged() }
        binding.switchLockOnMinimize.setOnCheckedChangeListener { _, v -> prefs.lockOnMinimize = v;    markChanged() }

        binding.switchHideLauncher.setOnCheckedChangeListener { _, checked ->
            prefs.hideFromLauncher = checked
            toggleLauncherIcon(!checked)
            markChanged()
        }

        // Threshold
        binding.btnThresholdMinus.setOnClickListener {
            if (prefs.wrongAttemptThreshold > 1) { prefs.wrongAttemptThreshold--; updateThresholdDisplay(); markChanged() }
        }
        binding.btnThresholdPlus.setOnClickListener {
            if (prefs.wrongAttemptThreshold < 20) { prefs.wrongAttemptThreshold++; updateThresholdDisplay(); markChanged() }
        }

        binding.btnChangeLock.setOnClickListener { startActivity(Intent(this, SetupLockActivity::class.java)) }
        binding.btnEnableScreenMonitor.setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Enable 'Trapix' in Accessibility Services 🔍", Toast.LENGTH_LONG).show()
        }

        // Feature 4: Grant permission buttons
        binding.btnGrantCamera.setOnClickListener {
            permLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
        binding.btnGrantLocation.setOnClickListener {
            permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        binding.btnGrantNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            } else {
                // Open app settings for older Android
                startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }

        // Feature 3: Save button
        binding.btnSaveSettings.setOnClickListener {
            hasUnsavedChanges = false
            binding.btnSaveSettings.text = "✅ Saved!"
            updateServiceStatus()
            updatePermissionStatus()
            Toast.makeText(this, "Settings Saved!", Toast.LENGTH_SHORT).show()
            binding.btnSaveSettings.postDelayed({ binding.btnSaveSettings.text = "Save Settings" }, 2000)
        }

        binding.btnOpenGithub.setOnClickListener    { openUrl("https://github.com/PKYsm") }
        binding.btnOpenInstagram.setOnClickListener { openUrl("https://instagram.com/RasaVedic") }
        binding.tvGithub.setOnClickListener         { openUrl("https://github.com/PKYsm") }
        binding.tvInstagram.setOnClickListener      { openUrl("https://instagram.com/RasaVedic") }

        binding.btnOpenDebugLogs.setOnClickListener {
            startActivity(Intent(this, com.trapix.app.ui.debug.DebugActivity::class.java))
        }

        binding.btnResetSettings.setOnClickListener {
            AlertDialog.Builder(this).setTitle("Reset All Settings?")
                .setMessage("This will reset all settings to default but keep your captures.")
                .setPositiveButton("Reset") { _, _ -> resetSettings(); Toast.makeText(this, "Settings reset", Toast.LENGTH_SHORT).show() }
                .setNegativeButton("Cancel", null).show()
        }
    }

    // Feature 3: Warn on back press if unsaved changes
    private fun setupBackPressWarning() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (hasUnsavedChanges) {
                    // UX FIX: Pehle message contradictory tha —
                    // "unsaved changes" aur "already saved" ek saath likha tha.
                    // Sab settings turant SharedPreferences mein save hoti hain,
                    // "Save" button sirf visual confirmation hai.
                    AlertDialog.Builder(this@SettingsActivity)
                        .setTitle("Settings Saved")
                        .setMessage("All your changes have been saved automatically.")
                        .setPositiveButton("OK") { _, _ ->
                            hasUnsavedChanges = false
                            finish()
                        }
                        .setNegativeButton("Stay", null).show()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun toggleLauncherIcon(show: Boolean) {
        val component = ComponentName(this, "com.trapix.app.ui.splash.SplashActivity")
        packageManager.setComponentEnabledSetting(
            component,
            if (show) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        if (!show) Toast.makeText(this, "⚠️ App hidden from launcher!", Toast.LENGTH_LONG).show()
    }

    private fun resetSettings() {
        prefs.isBiometricEnabled    = false
        prefs.captureFrontCamera    = true
        prefs.captureRearCamera     = false
        prefs.notificationEnabled   = true
        prefs.saveToGallery         = true
        prefs.wrongAttemptThreshold = 3
        prefs.hideFromLauncher      = false
        prefs.lockOnMinimize        = false
        loadCurrentSettings()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed(); return true
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { Toast.makeText(this, "Browser not found!", Toast.LENGTH_SHORT).show() }
    }
}
