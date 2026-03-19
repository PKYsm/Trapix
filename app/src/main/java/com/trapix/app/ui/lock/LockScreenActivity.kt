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
import com.trapix.app.util.DebugLogger

class LockScreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockScreenBinding
    private lateinit var prefs: AppPrefs
    private var enteredPin = ""

    // BUG 6 FIX: Read persisted wrong attempt count from prefs.
    // This survives activity recreation (screen rotation, system kill, minimize→resume).
    // Local var removed — all state lives in prefs.lockScreenAttempts.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)

        DebugLogger.log("LOCK", "LockScreen opened. lockType=${prefs.lockType} threshold=${prefs.wrongAttemptThreshold} currentAttempts=${prefs.lockScreenAttempts}")
        setupLockUI()
        setupBiometric()
    }

    private fun setupLockUI() {
        when (prefs.lockType) {
            AppPrefs.LOCK_TYPE_PIN      -> showPinView()
            AppPrefs.LOCK_TYPE_PATTERN  -> showPatternView()
            AppPrefs.LOCK_TYPE_PASSWORD -> showPasswordView()
            else                        -> unlockSuccess()
        }
    }

    // ─── PIN ──────────────────────────────────────────────────────────────────

    private fun showPinView() {
        binding.layoutPinLock.visibility      = View.VISIBLE
        binding.layoutPatternLock.visibility  = View.GONE
        binding.layoutPasswordLock.visibility = View.GONE

        listOf(
            binding.lockBtn1, binding.lockBtn2, binding.lockBtn3,
            binding.lockBtn4, binding.lockBtn5, binding.lockBtn6,
            binding.lockBtn7, binding.lockBtn8, binding.lockBtn9
        ).forEachIndexed { i, btn -> btn.setOnClickListener { appendLockPin((i + 1).toString()) } }
        binding.lockBtn0.setOnClickListener    { appendLockPin("0") }
        binding.lockBtnBack.setOnClickListener { backspaceLockPin() }
        binding.lockBtnOk.setOnClickListener   { checkPin() }
        updateLockPinDots()
    }

    private fun appendLockPin(digit: String) {
        if (enteredPin.length < 6) {
            enteredPin += digit
            updateLockPinDots()
            if (enteredPin.length == prefs.lockValue.length && prefs.lockValue.isNotEmpty()) {
                Handler(Looper.getMainLooper()).postDelayed({ checkPin() }, 150)
            }
        }
    }

    private fun backspaceLockPin() {
        if (enteredPin.isNotEmpty()) { enteredPin = enteredPin.dropLast(1); updateLockPinDots() }
    }

    private fun updateLockPinDots() {
        binding.tvLockPinDots.text = "●".repeat(enteredPin.length) + "○".repeat(6 - enteredPin.length)
    }

    private fun checkPin() {
        if (enteredPin == prefs.lockValue) {
            unlockSuccess()
        } else {
            enteredPin = ""; updateLockPinDots()
            handleWrongAttempt()
        }
    }

    // ─── Pattern ──────────────────────────────────────────────────────────────

    private fun showPatternView() {
        binding.layoutPinLock.visibility      = View.GONE
        binding.layoutPatternLock.visibility  = View.VISIBLE
        binding.layoutPasswordLock.visibility = View.GONE

        binding.lockPatternView.onTooFewNodes = {
            Toast.makeText(this, "Draw at least 4 dots", Toast.LENGTH_SHORT).show()
        }

        binding.lockPatternView.onPatternListener = object : PatternView.OnPatternListener {
            override fun onPatternStart() {}
            override fun onPatternComplete(pattern: List<Int>) {
                if (pattern.size < 4) {
                    binding.lockPatternView.setError()
                    Toast.makeText(this@LockScreenActivity, "Draw at least 4 dots", Toast.LENGTH_SHORT).show()
                    return
                }
                val patternStr = pattern.joinToString(",")
                DebugLogger.log("LOCK", "Pattern entered=$patternStr expected=${prefs.lockValue}")
                if (patternStr == prefs.lockValue) {
                    binding.lockPatternView.setSuccess(); unlockSuccess()
                } else {
                    binding.lockPatternView.setError(); handleWrongAttempt()
                }
            }
        }
    }

    // ─── Password ─────────────────────────────────────────────────────────────

    private fun showPasswordView() {
        binding.layoutPinLock.visibility      = View.GONE
        binding.layoutPatternLock.visibility  = View.GONE
        binding.layoutPasswordLock.visibility = View.VISIBLE

        binding.etLockPassword.requestFocus()
        binding.etLockPassword.post {
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .showSoftInput(binding.etLockPassword, android.view.inputmethod.InputMethodManager.SHOW_FORCED)
        }

        binding.etLockPassword.setOnEditorActionListener { _, _, _ -> checkPassword(); true }
        binding.btnPasswordUnlock.setOnClickListener { checkPassword() }
        binding.etLockPassword.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if ((s?.length ?: 0) == prefs.lockValue.length && prefs.lockValue.isNotEmpty()) {
                    Handler(Looper.getMainLooper()).postDelayed({ checkPassword() }, 150)
                }
            }
        })
    }

    private fun checkPassword() {
        val entered = binding.etLockPassword.text.toString()
        if (entered.isEmpty()) return
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(binding.etLockPassword.windowToken, 0)
        if (entered == prefs.lockValue) unlockSuccess()
        else { binding.etLockPassword.text?.clear(); handleWrongAttempt() }
    }

    // ─── Biometric ────────────────────────────────────────────────────────────

    private fun setupBiometric() {
        if (!prefs.isBiometricEnabled) { binding.btnBiometric.visibility = View.GONE; return }
        val bm = BiometricManager.from(this)
        val ok = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)
        if (ok != BiometricManager.BIOMETRIC_SUCCESS) { binding.btnBiometric.visibility = View.GONE; return }
        binding.btnBiometric.visibility = View.VISIBLE
        binding.btnBiometric.setOnClickListener { showBiometricPrompt() }
        Handler(Looper.getMainLooper()).postDelayed({ showBiometricPrompt() }, 500)
    }

    private fun showBiometricPrompt() {
        val cb = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlockSuccess() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {}
            override fun onAuthenticationFailed() {
                Toast.makeText(this@LockScreenActivity, "Biometric not recognized", Toast.LENGTH_SHORT).show()
            }
        }
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Trapix Unlock").setSubtitle("Use biometric to unlock")
            .setNegativeButtonText("Use Password").build()
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), cb).authenticate(promptInfo)
    }

    // ─── Wrong Attempt ────────────────────────────────────────────────────────

    private fun handleWrongAttempt() {
        // BUG 6 FIX: Increment persisted counter so it survives activity recreation
        prefs.lockScreenAttempts++
        val attempts  = prefs.lockScreenAttempts
        val threshold = prefs.wrongAttemptThreshold
        val remaining = threshold - attempts

        DebugLogger.log("LOCK", "WRONG ATTEMPT #$attempts / threshold=$threshold remaining=$remaining")

        binding.root.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this, R.anim.shake))

        val message = if (remaining > 0)
            "Wrong! $remaining attempt${if (remaining == 1) "" else "s"} before capture 📸"
        else "⚠️ Capturing intruder photo!"
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

        if (attempts >= threshold) {
            prefs.wrongAttemptCount++
            prefs.lockScreenAttempts = 0  // Reset for next cycle
            triggerCapture()
        }
    }

    private fun triggerCapture() {
        DebugLogger.log("LOCK", "TRIGGERING CAPTURE! attemptCount=${prefs.wrongAttemptCount}")
        startForegroundService(Intent(this, IntruderCaptureService::class.java).apply {
            action = IntruderCaptureService.ACTION_CAPTURE
            putExtra(IntruderCaptureService.EXTRA_ATTEMPT_NUMBER, prefs.wrongAttemptCount)
        })
    }

    // ─── Unlock ───────────────────────────────────────────────────────────────

    private fun unlockSuccess() {
        DebugLogger.log("LOCK", "UNLOCK SUCCESS! Resetting all attempt counters.")
        prefs.resetWrongAttemptCount()  // Resets both DB count and lockScreenAttempts
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
        finish()
    }

    @Deprecated("Deprecated")
    override fun onBackPressed() { /* Lock screen cannot be dismissed */ }
}
