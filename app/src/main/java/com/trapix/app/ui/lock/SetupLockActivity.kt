package com.trapix.app.ui.lock

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.trapix.app.R
import com.trapix.app.data.prefs.AppPrefs
import com.trapix.app.databinding.ActivitySetupLockBinding
import com.trapix.app.ui.gallery.MainActivity

class SetupLockActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupLockBinding
    private lateinit var prefs: AppPrefs
    private var selectedLockType = AppPrefs.LOCK_TYPE_PIN
    private var firstEntry = ""
    private var isConfirming = false
    private var enteredPin = ""
    private var patternPoints = mutableListOf<Int>()
    private var firstPatternPoints = mutableListOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupLockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)

        setupLockTypeSelector()
        setupPinInput()
        setupPatternInput()
        setupPasswordInput()
        showLockTypeView(AppPrefs.LOCK_TYPE_PIN)
    }

    private fun setupLockTypeSelector() {
        binding.btnTypePIN.setOnClickListener {
            selectedLockType = AppPrefs.LOCK_TYPE_PIN
            showLockTypeView(AppPrefs.LOCK_TYPE_PIN)
            updateTypeButtons()
        }
        binding.btnTypePattern.setOnClickListener {
            selectedLockType = AppPrefs.LOCK_TYPE_PATTERN
            showLockTypeView(AppPrefs.LOCK_TYPE_PATTERN)
            updateTypeButtons()
        }
        binding.btnTypePassword.setOnClickListener {
            selectedLockType = AppPrefs.LOCK_TYPE_PASSWORD
            showLockTypeView(AppPrefs.LOCK_TYPE_PASSWORD)
            updateTypeButtons()
        }
        updateTypeButtons()
    }

    private fun updateTypeButtons() {
        binding.btnTypePIN.isSelected = selectedLockType == AppPrefs.LOCK_TYPE_PIN
        binding.btnTypePattern.isSelected = selectedLockType == AppPrefs.LOCK_TYPE_PATTERN
        binding.btnTypePassword.isSelected = selectedLockType == AppPrefs.LOCK_TYPE_PASSWORD
    }

    private fun showLockTypeView(type: String) {
        binding.layoutPin.visibility = if (type == AppPrefs.LOCK_TYPE_PIN) View.VISIBLE else View.GONE
        binding.layoutPattern.visibility = if (type == AppPrefs.LOCK_TYPE_PATTERN) View.VISIBLE else View.GONE
        binding.layoutPassword.visibility = if (type == AppPrefs.LOCK_TYPE_PASSWORD) View.VISIBLE else View.GONE
        resetState()
    }

    private fun resetState() {
        isConfirming = false
        firstEntry = ""
        enteredPin = ""
        patternPoints.clear()
        firstPatternPoints.clear()
        binding.tvPinDots.text = ""
        binding.tvSetupHint.text = "Set your lock"
        binding.etPassword.text?.clear()
        binding.etConfirmPassword.text?.clear()
        updatePinDots()
    }

    // ─── PIN Setup ───────────────────────────────────────────────────────────
    private fun setupPinInput() {
        val numBtns = listOf(
            binding.layoutNumpad.btn0, binding.layoutNumpad.btn1, binding.layoutNumpad.btn2, binding.layoutNumpad.btn3,
            binding.layoutNumpad.btn4, binding.layoutNumpad.btn5, binding.layoutNumpad.btn6, binding.layoutNumpad.btn7,
            binding.layoutNumpad.btn8, binding.layoutNumpad.btn9
        )
        numBtns.forEachIndexed { index, btn ->
            btn.setOnClickListener { appendPin(index.toString()) }
        }
        // DUPLICATE FIX: btn0 ka listener loop mein already set ho gaya tha (index=0 → "0")
        // Duplicate line hata di — warna listener override hota tha (same effect tha, but confusing)
        binding.layoutNumpad.btnBackspace.setOnClickListener { backspacePin() }
        binding.layoutNumpad.btnPinOk.setOnClickListener { confirmPin() }
    }

    private fun appendPin(digit: String) {
        if (enteredPin.length < 6) {
            enteredPin += digit
            updatePinDots()
        }
    }

    private fun backspacePin() {
        if (enteredPin.isNotEmpty()) {
            enteredPin = enteredPin.dropLast(1)
            updatePinDots()
        }
    }

    private fun updatePinDots() {
        val filled = "●".repeat(enteredPin.length)
        val empty = "○".repeat(6 - enteredPin.length)
        binding.tvPinDots.text = "$filled$empty"
    }

    private fun confirmPin() {
        if (enteredPin.length < 4) {
            Toast.makeText(this, "Minimum 4 digits required", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isConfirming) {
            firstEntry = enteredPin
            enteredPin = ""
            isConfirming = true
            binding.tvSetupHint.text = "Confirm your PIN"
            updatePinDots()
        } else {
            if (enteredPin == firstEntry) {
                saveLock(AppPrefs.LOCK_TYPE_PIN, enteredPin)
            } else {
                Toast.makeText(this, "PINs don't match. Try again.", Toast.LENGTH_SHORT).show()
                resetState()
            }
        }
    }

    // ─── Pattern Setup ───────────────────────────────────────────────────────
    private fun setupPatternInput() {
        binding.patternView.onPatternListener = object : com.trapix.app.ui.lock.PatternView.OnPatternListener {
            override fun onPatternComplete(pattern: List<Int>) {
                handlePatternInput(pattern)
            }
            override fun onPatternStart() {}
        }
        binding.btnPatternReset.setOnClickListener {
            binding.patternView.clearPattern()
            resetState()
        }
    }

    private fun handlePatternInput(pattern: List<Int>) {
        if (pattern.size < 4) {
            Toast.makeText(this, "Connect at least 4 dots", Toast.LENGTH_SHORT).show()
            binding.patternView.setError()
            return
        }
        if (!isConfirming) {
            firstPatternPoints = pattern.toMutableList()
            isConfirming = true
            binding.tvSetupHint.text = "Draw pattern again to confirm"
            binding.patternView.clearPattern()
        } else {
            if (pattern == firstPatternPoints) {
                saveLock(AppPrefs.LOCK_TYPE_PATTERN, pattern.joinToString(","))
            } else {
                Toast.makeText(this, "Patterns don't match. Try again.", Toast.LENGTH_SHORT).show()
                binding.patternView.setError()
                resetState()
            }
        }
    }

    // ─── Password Setup ──────────────────────────────────────────────────────
    private fun setupPasswordInput() {
        // Auto-focus password field and show native keyboard
        binding.etPassword.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(binding.etPassword, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }

        // IME action on confirm field submits
        binding.etConfirmPassword.setOnEditorActionListener { _, _, _ ->
            validateAndSavePassword()
            true
        }

        binding.btnPasswordOk.setOnClickListener { validateAndSavePassword() }
    }

    private fun validateAndSavePassword() {
        val pass = binding.etPassword.text.toString()
        val confirm = binding.etConfirmPassword.text.toString()
        if (pass.length < 4) {
            Toast.makeText(this, "Minimum 4 characters required", Toast.LENGTH_SHORT).show()
            return
        }
        if (pass != confirm) {
            Toast.makeText(this, "Passwords don't match", Toast.LENGTH_SHORT).show()
            return
        }

        // CRASH FIX: LockScreenActivity wala same bug yahan bhi tha.
        // Keyboard dismiss ke SAME frame mein FLAG_ACTIVITY_CLEAR_TASK se startActivity karo
        // to IME window ka token invalid ho jaata hai → WindowManager crash.
        // Fix: keyboard dismiss karo, phir NEXT FRAME pe saveLock call karo
        // taaki IME window properly detach ho sake.
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val focused = currentFocus ?: binding.etConfirmPassword
        imm.hideSoftInputFromWindow(focused.windowToken, 0)
        focused.clearFocus()

        binding.root.post { saveLock(AppPrefs.LOCK_TYPE_PASSWORD, pass) }
    }

    // ─── Save & Navigate ─────────────────────────────────────────────────────
    private fun saveLock(type: String, value: String) {
        prefs.lockType = type
        prefs.lockValue = value
        prefs.isSetupDone = true

        Toast.makeText(this, "Lock set successfully! 🔐", Toast.LENGTH_SHORT).show()

        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
        finish()
    }
}
