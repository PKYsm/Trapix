package com.trapix.app.ui.settings

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.trapix.app.R
import com.trapix.app.data.prefs.AppPrefs
import com.trapix.app.databinding.ActivitySettingsBinding
import com.trapix.app.ui.lock.SetupLockActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPrefs

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
    }

    override fun onResume() {
        super.onResume()
        // BUG 8 FIX: Accessibility service status refresh karo jab bhi screen pe aao
        updateServiceStatus()
    }

    private fun loadCurrentSettings() {
        binding.switchBiometric.isChecked    = prefs.isBiometricEnabled
        binding.switchFrontCamera.isChecked  = prefs.captureFrontCamera
        binding.switchRearCamera.isChecked   = prefs.captureRearCamera
        binding.switchNotifications.isChecked = prefs.notificationEnabled
        binding.switchSaveGallery.isChecked  = prefs.saveToGallery
        binding.switchHideLauncher.isChecked = prefs.hideFromLauncher
        binding.switchDarkTheme.isChecked    = prefs.isDarkTheme
        updateThresholdDisplay()
        updateLockTypeDisplay()
        updateServiceStatus()
    }

    private fun updateThresholdDisplay() {
        binding.tvThresholdValue.text = "${prefs.wrongAttemptThreshold} wrong attempts"
    }

    private fun updateLockTypeDisplay() {
        val lockType = when (prefs.lockType) {
            AppPrefs.LOCK_TYPE_PIN      -> "PIN"
            AppPrefs.LOCK_TYPE_PATTERN  -> "Pattern"
            AppPrefs.LOCK_TYPE_PASSWORD -> "Password"
            else -> "None"
        }
        binding.tvCurrentLockType.text = "Current: $lockType"
    }

    // BUG 8 FIX: Accessibility service status show karo
    private fun updateServiceStatus() {
        val isActive = isAccessibilityServiceEnabled()
        binding.tvServiceStatus.text = if (isActive) "✅ Screen Monitor: Active" else "❌ Screen Monitor: Inactive"
        binding.tvServiceStatus.setTextColor(
            if (isActive) 0xFF00E676.toInt() else 0xFFFF5252.toInt()
        )
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val enabled = android.provider.Settings.Secure.getInt(
                contentResolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            if (enabled == 1) {
                val services = android.provider.Settings.Secure.getString(
                    contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                services.contains(packageName, ignoreCase = true)
            } else false
        } catch (e: Exception) { false }
    }

    private fun setupListeners() {
        // Switches — instantly save (no pending state)
        binding.switchBiometric.setOnCheckedChangeListener    { _, v -> prefs.isBiometricEnabled = v }
        binding.switchFrontCamera.setOnCheckedChangeListener  { _, v -> prefs.captureFrontCamera = v }
        binding.switchRearCamera.setOnCheckedChangeListener   { _, v -> prefs.captureRearCamera = v }
        binding.switchNotifications.setOnCheckedChangeListener { _, v -> prefs.notificationEnabled = v }
        binding.switchSaveGallery.setOnCheckedChangeListener  { _, v -> prefs.saveToGallery = v }
        binding.switchDarkTheme.setOnCheckedChangeListener    { _, v -> prefs.isDarkTheme = v }

        binding.btnThresholdMinus.setOnClickListener {
            if (prefs.wrongAttemptThreshold > 1) { prefs.wrongAttemptThreshold--; updateThresholdDisplay() }
        }
        binding.btnThresholdPlus.setOnClickListener {
            if (prefs.wrongAttemptThreshold < 20) { prefs.wrongAttemptThreshold++; updateThresholdDisplay() }
        }

        binding.btnChangeLock.setOnClickListener { startActivity(Intent(this, SetupLockActivity::class.java)) }

        binding.switchHideLauncher.setOnCheckedChangeListener { _, checked ->
            prefs.hideFromLauncher = checked
            toggleLauncherIcon(!checked)
        }

        binding.btnEnableScreenMonitor.setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Trapix dhundho aur enable karo 🔍", Toast.LENGTH_LONG).show()
        }

        // BUG 8 FIX: Save button — confirm + status refresh
        binding.btnSaveSettings.setOnClickListener {
            updateServiceStatus()
            Toast.makeText(this, "✅ Settings Saved!", Toast.LENGTH_SHORT).show()
            binding.btnSaveSettings.text = "✅ Saved!"
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
            AlertDialog.Builder(this)
                .setTitle("Reset All Settings?")
                .setMessage("This will reset all settings to default but keep your captures.")
                .setPositiveButton("Reset") { _, _ -> resetSettings(); Toast.makeText(this, "Settings reset", Toast.LENGTH_SHORT).show() }
                .setNegativeButton("Cancel", null).show()
        }
    }

    private fun toggleLauncherIcon(show: Boolean) {
        val component = ComponentName(this, "com.trapix.app.ui.splash.SplashActivity")
        val newState = if (show) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        packageManager.setComponentEnabledSetting(component, newState, PackageManager.DONT_KILL_APP)
        if (!show) Toast.makeText(this, "⚠️ App hidden from launcher!", Toast.LENGTH_LONG).show()
    }

    private fun resetSettings() {
        prefs.isBiometricEnabled   = false
        prefs.captureFrontCamera   = true
        prefs.captureRearCamera    = false
        prefs.notificationEnabled  = true
        prefs.saveToGallery        = true
        prefs.wrongAttemptThreshold = 3
        prefs.hideFromLauncher     = false
        loadCurrentSettings()
    }

    override fun onSupportNavigateUp(): Boolean { onBackPressedDispatcher.onBackPressed(); return true }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
        catch (e: Exception) { Toast.makeText(this, "Browser nahi mila!", Toast.LENGTH_SHORT).show() }
    }
}
