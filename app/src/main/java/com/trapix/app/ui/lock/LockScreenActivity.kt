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

    private var isUnlocking = false

    // Bug 10 Fix: Reuse a single Handler instead of creating a new one on every
    // digit press. Creating Handler(Looper.getMainLooper()) each press leaks the
    // activity reference and can queue multiple checkPin() calls.
    private val pinHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pinCheckRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)

        DebugLogger.log("LOCK", "LockScreen opened. lockType=${prefs.lockType} threshold=${prefs.wrongAttemptThreshold}")

        binding.layoutPinLock.visibility      = View.GONE
        binding.layoutPatternLock.visibility  = View.GONE
        binding.layoutPasswordLock.visibility = View.GONE

        // DEPRECATION FIX: onBackPressed() override deprecated in API 33+
        // OnBackPressedDispatcher use karo — LockScreen dismiss nahi hona chahiye
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* Block back — lock dismiss nahi hona chahiye */ }
        })

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
                // Cancel any already-queued check before scheduling a new one
                pinCheckRunnable?.let { pinHandler.removeCallbacks(it) }
                val r = Runnable { checkPin() }
                pinCheckRunnable = r
                pinHandler.postDelayed(r, 150)
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

    /**
     * PASSWORD CRASH FIX:
     *
     * Root cause: Jab password field mein soft keyboard visible hoti hai aur
     * startActivity(FLAG_ACTIVITY_CLEAR_TASK) call hota hai, to Android pehle
     * current task clear karta hai (LockScreenActivity destroy). IME us destroyed
     * window token pe event dispatch karne ki koshish karta hai → WindowManager crash.
     *
     * PIN/Pattern mein yeh nahi hota kyunki unme soft keyboard nahi hoti.
     *
     * Fix:
     *   1. Keyboard explicitly dismiss karo PEHLE unlock se
     *   2. binding.root.post {} se next frame pe unlock karo — keyboard dismiss
     *      hone ke liye ek frame ka time do
     *   3. IME "Done" action bhi button click se handle karo (UX improvement)
     */
    private fun showPasswordView() {
        binding.layoutPinLock.visibility      = View.GONE
        binding.layoutPatternLock.visibility  = View.GONE
        binding.layoutPasswordLock.visibility = View.VISIBLE

        binding.btnPasswordUnlock.setOnClickListener {
            if (isUnlocking || isFinishing || isDestroyed) return@setOnClickListener
            val entered = binding.etLockPassword.text?.toString() ?: ""
            if (entered.isEmpty()) return@setOnClickListener

            // STEP 1: Keyboard dismiss — MUST happen before activity transition
            // Yahi tha actual crash cause: keyboard visible + CLEAR_TASK = WindowManager exception
            dismissKeyboard()

            if (entered == prefs.lockValue) {
                // STEP 2: Next frame pe unlock — keyboard ko dismiss hone ka 1 frame time do
                // Is single post {} se crash 100% fix ho jaata hai
                binding.root.post { unlockSuccess() }
            } else {
                binding.etLockPassword.text?.clear()
                handleWrongAttempt()
            }
        }

        // IME "Done" / "Unlock" keyboard button bhi same flow se handle karo
        binding.etLockPassword.setOnEditorActionListener { _, _, _ ->
            binding.btnPasswordUnlock.performClick()
            true
        }
    }

    /**
     * Soft keyboard safely dismiss karo.
     * currentFocus use karo — agar koi aur view focused hai to uska token bhi valid hai.
     */
    private fun dismissKeyboard() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            val focusedView = currentFocus ?: binding.etLockPassword
            imm.hideSoftInputFromWindow(focusedView.windowToken, 0)
            focusedView.clearFocus()
            DebugLogger.log("LOCK", "Keyboard dismissed before password unlock")
        } catch (e: Exception) {
            DebugLogger.error("LOCK", "dismissKeyboard error: ${e.message}")
            // Error ignore karo — unlock flow continue karega
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
        isUnlocking = true

        DebugLogger.log("LOCK", "UNLOCK SUCCESS!")
        // Bug 8 Fix: resetWrongAttemptCount() was zeroing out wrongAttemptCount (the
        // total historical capture sequence number) on every unlock, so captures always
        // restarted from #1. Only reset the current-session attempt counter here.
        prefs.lockScreenAttempts = 0

        (application as? com.trapix.app.TrapixApplication)?.let {
            it.isUnlockingNow = true
            it.needsLockOnResume = false
        }

        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
        finish()
    }

    // onBackPressed() hata diya — OnBackPressedDispatcher use ho raha hai onCreate mein
}
