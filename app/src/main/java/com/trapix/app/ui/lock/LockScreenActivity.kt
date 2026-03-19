package com.trapix.app.ui.lock

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
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

    // Double-submit / double-unlock crash fix
    private var isUnlocking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)

        DebugLogger.log("LOCK", "LockScreen opened. lockType=${prefs.lockType} threshold=${prefs.wrongAttemptThreshold}")

        // Sab pehle hide karo
        binding.layoutPinLock.visibility      = View.GONE
        binding.layoutPatternLock.visibility  = View.GONE
        binding.layoutPasswordLock.visibility = View.GONE

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
        ).forEachIndexed { i, btn ->
            btn.setOnClickListener { appendLockPin((i + 1).toString()) }
        }
        binding.lockBtn0.setOnClickListener    { appendLockPin("0") }
        binding.lockBtnBack.setOnClickListener { backspaceLockPin() }
        binding.lockBtnOk.setOnClickListener   { checkPin() }
        updateLockPinDots()
    }

    private fun appendLockPin(digit: String) {
        if (isUnlocking) return
        if (enteredPin.length < 6) {
            enteredPin += digit
            updateLockPinDots()
            if (enteredPin.length == prefs.lockValue.length && prefs.lockValue.isNotEmpty()) {
                Handler(Looper.getMainLooper()).postDelayed({ checkPin() }, 150)
            }
        }
    }

    private fun backspaceLockPin() {
        if (enteredPin.isNotEmpty()) {
            enteredPin = enteredPin.dropLast(1)
            updateLockPinDots()
        }
    }

    private fun updateLockPinDots() {
        binding.tvLockPinDots.text = "●".repeat(enteredPin.length) + "○".repeat(6 - enteredPin.length)
    }

    private fun checkPin() {
        if (isUnlocking || isFinishing || isDestroyed) return
        if (enteredPin == prefs.lockValue) {
            unlockSuccess()
        } else {
            enteredPin = ""
            updateLockPinDots()
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
                if (isUnlocking || isFinishing || isDestroyed) return
                if (pattern.size < 4) {
                    binding.lockPatternView.setError()
                    Toast.makeText(this@LockScreenActivity, "Draw at least 4 dots", Toast.LENGTH_SHORT).show()
                    return
                }
                val entered = pattern.joinToString(",")
                if (entered == prefs.lockValue) {
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
        binding.layoutPinLock.visibility      = View.GONE
        binding.layoutPatternLock.visibility  = View.GONE
        binding.layoutPasswordLock.visibility = View.VISIBLE

        binding.etLockPassword.requestFocus()
        binding.etLockPassword.post {
            if (!isFinishing && !isDestroyed) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(binding.etLockPassword, InputMethodManager.SHOW_FORCED)
            }
        }

        binding.etLockPassword.setOnEditorActionListener { _, _, _ ->
            checkPassword()
            true
        }

        binding.btnPasswordUnlock.setOnClickListener { checkPassword() }

        // Auto-submit jab length match kare
        binding.etLockPassword.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (isUnlocking) return
                val len = s?.length ?: 0
                if (len > 0 && len == prefs.lockValue.length && prefs.lockValue.isNotEmpty()) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        checkPassword()
                    }, 200)
                }
            }
        })
    }

    private fun checkPassword() {
        // CRASH FIX: isUnlocking flag + isFinishing check
        if (isUnlocking || isFinishing || isDestroyed) return

        val entered = binding.etLockPassword.text?.toString() ?: return
        if (entered.isEmpty()) return

        // Keyboard hide karo
        try {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(binding.etLockPassword.windowToken, 0)
        } catch (_: Exception) {}

        if (entered == prefs.lockValue) {
            unlockSuccess()
        } else {
            binding.etLockPassword.text?.clear()
            handleWrongAttempt()
        }
    }

    // ─── Biometric ────────────────────────────────────────────────────────────

    private fun setupBiometric() {
        if (!prefs.isBiometricEnabled) { binding.btnBiometric.visibility = View.GONE; return }
        val canAuth = BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        )
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) { binding.btnBiometric.visibility = View.GONE; return }

        binding.btnBiometric.visibility = View.VISIBLE
        binding.btnBiometric.setOnClickListener { showBiometricPrompt() }
        Handler(Looper.getMainLooper()).postDelayed({ showBiometricPrompt() }, 500)
    }

    private fun showBiometricPrompt() {
        if (isFinishing || isDestroyed) return
        val cb = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) { unlockSuccess() }
            override fun onAuthenticationError(code: Int, msg: CharSequence) {}
            override fun onAuthenticationFailed() {
                Toast.makeText(this@LockScreenActivity, "Biometric not recognized", Toast.LENGTH_SHORT).show()
            }
        }
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), cb).authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Trapix Unlock")
                .setSubtitle("Biometric se unlock karo")
                .setNegativeButtonText("Use Password")
                .build()
        )
    }

    // ─── Wrong Attempt ────────────────────────────────────────────────────────

    private fun handleWrongAttempt() {
        if (isFinishing || isDestroyed) return

        prefs.lockScreenAttempts++
        val attempts  = prefs.lockScreenAttempts
        val threshold = prefs.wrongAttemptThreshold
        val remaining = threshold - attempts

        DebugLogger.log("LOCK", "WRONG #$attempts / threshold=$threshold remaining=$remaining")

        try {
            binding.root.startAnimation(
                android.view.animation.AnimationUtils.loadAnimation(this, R.anim.shake)
            )
        } catch (_: Exception) {}

        Toast.makeText(this,
            if (remaining > 0) "Wrong! $remaining attempt${if (remaining == 1) "" else "s"} before capture 📸"
            else "⚠️ Capturing intruder photo!",
            Toast.LENGTH_SHORT
        ).show()

        if (attempts >= threshold) {
            prefs.wrongAttemptCount++
            prefs.lockScreenAttempts = 0
            DebugLogger.log("LOCK", "CAPTURE! total=${prefs.wrongAttemptCount}")
            try {
                startForegroundService(Intent(this, IntruderCaptureService::class.java).apply {
                    action = IntruderCaptureService.ACTION_CAPTURE
                    putExtra(IntruderCaptureService.EXTRA_ATTEMPT_NUMBER, prefs.wrongAttemptCount)
                })
            } catch (e: Exception) {
                DebugLogger.error("LOCK", "Capture failed: ${e.message}")
            }
        }
    }

    // ─── Unlock ───────────────────────────────────────────────────────────────

    private fun unlockSuccess() {
        if (isUnlocking || isFinishing || isDestroyed) return
        isUnlocking = true  // Double call se bachao

        DebugLogger.log("LOCK", "UNLOCK SUCCESS!")
        prefs.resetWrongAttemptCount()
        (application as? com.trapix.app.TrapixApplication)?.needsLockOnResume = false

        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
        finish()
    }

    override fun onBackPressed() {
        // Dismiss nahi hona chahiye
    }
}
