package com.trapix.app.ui.settings

import android.content.ComponentName
import android.provider.Settings as AndroidSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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

    private fun loadCurrentSettings() {
        binding.switchBiometric.isChecked = prefs.isBiometricEnabled
        binding.switchFrontCamera.isChecked = prefs.captureFrontCamera
        binding.switchRearCamera.isChecked = prefs.captureRearCamera
        binding.switchNotifications.isChecked = prefs.notificationEnabled
        binding.switchSaveGallery.isChecked = prefs.saveToGallery
        binding.switchHideLauncher.isChecked = prefs.hideFromLauncher
        binding.switchDarkTheme.isChecked = prefs.isDarkTheme

        updateThresholdDisplay()

        val lockType = when (prefs.lockType) {
            AppPrefs.LOCK_TYPE_PIN -> "PIN"
            AppPrefs.LOCK_TYPE_PATTERN -> "Pattern"
            AppPrefs.LOCK_TYPE_PASSWORD -> "Password"
            else -> "None"
        }
        binding.tvCurrentLockType.text = "Current: $lockType"
    }

    private fun updateThresholdDisplay() {
        binding.tvThresholdValue.text = "${prefs.wrongAttemptThreshold} wrong attempts"
    }

    private fun setupListeners() {
        binding.switchBiometric.setOnCheckedChangeListener { _, checked ->
            prefs.isBiometricEnabled = checked
        }
        binding.switchFrontCamera.setOnCheckedChangeListener { _, checked ->
            prefs.captureFrontCamera = checked
        }
        binding.switchRearCamera.setOnCheckedChangeListener { _, checked ->
            prefs.captureRearCamera = checked
        }
        binding.switchNotifications.setOnCheckedChangeListener { _, checked ->
            prefs.notificationEnabled = checked
        }
        binding.switchSaveGallery.setOnCheckedChangeListener { _, checked ->
            prefs.saveToGallery = checked
        }
        binding.switchDarkTheme.setOnCheckedChangeListener { _, checked ->
            prefs.isDarkTheme = checked
        }

        // Threshold picker
        binding.btnThresholdMinus.setOnClickListener {
            val cur = prefs.wrongAttemptThreshold
            if (cur > 1) {
                prefs.wrongAttemptThreshold = cur - 1
                updateThresholdDisplay()
            }
        }
        binding.btnThresholdPlus.setOnClickListener {
            val cur = prefs.wrongAttemptThreshold
            if (cur < 10) {
                prefs.wrongAttemptThreshold = cur + 1
                updateThresholdDisplay()
            }
        }

        // Change lock
        binding.btnChangeLock.setOnClickListener {
            startActivity(Intent(this, SetupLockActivity::class.java))
        }

        // Hide from launcher
        binding.switchHideLauncher.setOnCheckedChangeListener { _, checked ->
            prefs.hideFromLauncher = checked
            toggleLauncherIcon(!checked)
        }

        // Screen lock monitor toggle
        binding.btnEnableScreenMonitor.setOnClickListener {
            // Open accessibility settings so user can enable the service
            val intent = android.content.Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            android.widget.Toast.makeText(this,
                "Trapix dhundho aur enable karo 🔍", android.widget.Toast.LENGTH_LONG).show()
        }


        // Developer social links
        binding.btnOpenGithub.setOnClickListener { openUrl("https://github.com/PKYsm") }
        binding.btnOpenInstagram.setOnClickListener { openUrl("https://instagram.com/RasaVedic") }
        binding.tvGithub.setOnClickListener { openUrl("https://github.com/PKYsm") }
        binding.tvInstagram.setOnClickListener { openUrl("https://instagram.com/RasaVedic") }

        // Reset all settings
        binding.btnOpenDebugLogs.setOnClickListener {
            startActivity(android.content.Intent(this, com.trapix.app.ui.debug.DebugActivity::class.java))
        }

        binding.btnResetSettings.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reset All Settings?")
                .setMessage("This will reset all settings to default but keep your captures.")
                .setPositiveButton("Reset") { _, _ ->
                    resetSettings()
                    Toast.makeText(this, "Settings reset", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun toggleLauncherIcon(show: Boolean) {
        val component = ComponentName(this, "com.trapix.app.ui.splash.SplashActivity")
        val newState = if (show) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        packageManager.setComponentEnabledSetting(
            component, newState, PackageManager.DONT_KILL_APP
        )
        if (!show) {
            Toast.makeText(this, "⚠️ App hidden from launcher! Open via file manager or re-enable in settings.", Toast.LENGTH_LONG).show()
        }
    }

    private fun resetSettings() {
        prefs.isBiometricEnabled = false
        prefs.captureFrontCamera = true
        prefs.captureRearCamera = false
        prefs.notificationEnabled = true
        prefs.saveToGallery = true
        prefs.wrongAttemptThreshold = 3
        prefs.hideFromLauncher = false
        loadCurrentSettings()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun openUrl(url: String) {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Browser nahi mila!", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

}