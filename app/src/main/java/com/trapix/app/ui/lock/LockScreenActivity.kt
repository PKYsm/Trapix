package com.trapix.app.ui.lock

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.trapix.app.R
import com.trapix.app.data.prefs.AppPrefs
import com.trapix.app.databinding.ActivityLockScreenBinding
import com.trapix.app.service.IntruderCaptureService
import com.trapix.app.ui.gallery.MainActivity

class LockScreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockScreenBinding
    private lateinit var prefs: AppPrefs
    private var enteredPin = ""
    private var wrongAttempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)

        wrongAttempts = 0

        setupLockUI()
        setupBiometric()
    }

    private fun setupLockUI() {
        when (prefs.lockType) {
            AppPrefs.LOCK_TYPE_PIN -> showPinView()
            AppPrefs.LOCK_TYPE_PATTERN -> showPatternView()
            AppPrefs.LOCK_TYPE_PASSWORD -> showPasswordView()
            else -> unlockSuccess()
        }
    }

    // ─── PIN ─────────────────────────────────────────────────────────────────
    private fun showPinView() {
        binding.layoutPinLock.visibility = View.VISIBLE
        binding.layoutPatternLock.visibility = View.GONE
        binding.layoutPasswordLock.visibility = View.GONE

        val buttons = listOf(
            binding.lockBtn0, binding.lockBtn1, binding.lockBtn2, binding.lockBtn3,
            binding.lockBtn4, binding.lockBtn5, binding.lockBtn6, binding.lockBtn7,
            binding.lockBtn8, binding.lockBtn9
        )
        buttons.forEachIndexed { index, btn ->
            btn.setOnClickListener { appendLockPin(index.toString()) }
        }
        binding.lockBtn0.setOnClickListener { appendLockPin("0") }
        binding.lockBtnBack.setOnClickListener { backspaceLockPin() }
        binding.lockBtnOk.setOnClickListener { checkPin() }

        updateLockPinDots()
    }

    private fun appendLockPin(digit: String) {
        if (enteredPin.length < 6) {
            enteredPin += digit
            updateLockPinDots()
        }
    }

    private fun backspaceLockPin() {
        if (enteredPin.isNotEmpty()) {
            enteredPin = enteredPin.dropLast(1)
            updateLockPinDots()
        }
    }

    private fun updateLockPinDots() {
        val filled = "●".repeat(enteredPin.length)
        val empty = "○".repeat(6 - enteredPin.length)
        binding.tvLockPinDots.text = "$filled$empty"
    }

    private fun checkPin() {
        if (enteredPin == prefs.lockValue) {
            unlockSuccess()
        } else {
            enteredPin = ""
            updateLockPinDots()
            handleWrongAttempt()
        }
    }

    // ─── Pattern ─────────────────────────────────────────────────────────────
    private fun showPatternView() {
        binding.layoutPinLock.visibility = View.GONE
        binding.layoutPatternLock.visibility = View.VISIBLE
        binding.layoutPasswordLock.visibility = View.GONE

        binding.lockPatternView.onPatternListener = object : PatternView.OnPatternListener {
            override fun onPatternStart() {}
            override fun onPatternComplete(pattern: List<Int>) {
                val patternStr = pattern.joinToString(",")
                if (patternStr == prefs.lockValue) {
                    binding.lockPatternView.setSuccess()
                    unlockSuccess()
                } else {
                    binding.lockPatternView.setError()
                    handleWrongAttempt()
                }
            }
        }
    }

    // ─── Password ─────────────────────────────────────────────────────────────
    private fun showPasswordView() {
        binding.layoutPinLock.visibility = View.GONE
        binding.layoutPatternLock.visibility = View.GONE
        binding.layoutPasswordLock.visibility = View.VISIBLE

        binding.btnPasswordUnlock.setOnClickListener {
            val entered = binding.etLockPassword.text.toString()
            if (entered == prefs.lockValue) {
                unlockSuccess()
            } else {
                binding.etLockPassword.text?.clear()
                handleWrongAttempt()
            }
        }
    }

    // ─── Biometric ─────────────────────────────────────────────────────────
    private fun setupBiometric() {
        if (!prefs.isBiometricEnabled) {
            binding.btnBiometric.visibility = View.GONE
            return
        }

        val biometricManager = BiometricManager.from(this)
        val canAuth = biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        )

        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            binding.btnBiometric.visibility = View.GONE
            return
        }

        binding.btnBiometric.visibility = View.VISIBLE
        binding.btnBiometric.setOnClickListener { showBiometricPrompt() }

        // Auto show biometric prompt on open
        Handler(Looper.getMainLooper()).postDelayed({ showBiometricPrompt() }, 500)
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                unlockSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // User cancelled or error — just show lock
            }

            override fun onAuthenticationFailed() {
                Toast.makeText(this@LockScreenActivity,
                    "Biometric not recognized", Toast.LENGTH_SHORT).show()
            }
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Trapix Unlock")
            .setSubtitle("Use biometric to unlock")
            .setNegativeButtonText("Use Password")
            .build()

        BiometricPrompt(this, executor, callback).authenticate(promptInfo)
    }

    // ─── Wrong Attempt Handler ────────────────────────────────────────────────
    private fun handleWrongAttempt() {
        wrongAttempts++
        prefs.wrongAttemptCount++
        val threshold = prefs.wrongAttemptThreshold
        val remaining = threshold - wrongAttempts

        // Shake animation on UI
        binding.root.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this, R.anim.shake))

        // Toast with remaining attempts info
        val message = if (remaining > 0) {
            "Wrong! $remaining attempt${if (remaining == 1) "" else "s"} remaining before capture 📸"
        } else {
            "⚠️ Capturing intruder photo!"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

        // Capture if threshold reached
        if (wrongAttempts >= threshold) {
            triggerCapture()
            wrongAttempts = 0 // Reset counter after capture so it keeps counting in next rounds
        }
    }

    private fun triggerCapture() {
        val intent = Intent(this, IntruderCaptureService::class.java).apply {
            action = IntruderCaptureService.ACTION_CAPTURE
            putExtra(IntruderCaptureService.EXTRA_ATTEMPT_NUMBER, prefs.wrongAttemptCount)
        }
        startForegroundService(intent)
    }

    // ─── Unlock ─────────────────────────────────────────────────────────────
    private fun unlockSuccess() {
        prefs.resetWrongAttemptCount()
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
        finish()
    }

    // Back press should NOT dismiss the lock screen
    override fun onBackPressed() {
        // Do nothing — user cannot dismiss lock
    }
}
