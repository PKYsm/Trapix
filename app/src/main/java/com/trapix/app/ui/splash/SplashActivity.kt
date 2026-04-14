package com.trapix.app.ui.splash

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.AnimationUtils
import androidx.appcompat.app.AppCompatActivity
import com.trapix.app.R
import com.trapix.app.data.prefs.AppPrefs
import com.trapix.app.databinding.ActivitySplashBinding
import com.trapix.app.ui.lock.LockScreenActivity
import com.trapix.app.ui.lock.SetupLockActivity

class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private lateinit var prefs: AppPrefs

    // MEMORY LEAK FIX: Handler reference rakhni chahiye taaki onDestroy mein cancel ho sake
    private val dotsHandler = Handler(Looper.getMainLooper())
    private var dotsRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)

        com.trapix.app.util.DebugLogger.log("SPLASH", "App opened. isSetupDone=${prefs.isSetupDone}, lockType=${prefs.lockType}")
        // Reset wrong attempt count on every fresh app open
        prefs.resetWrongAttemptCount()
        com.trapix.app.util.DebugLogger.log("SPLASH", "wrongAttemptCount reset to 0")

        // Animate logo
        val fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in)
        val slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up)

        binding.ivLogo.startAnimation(fadeIn)
        binding.tvAppName.startAnimation(slideUp)
        binding.tvTagline.startAnimation(slideUp)

        // Skeleton loading dots animation
        animateDots()

        Handler(Looper.getMainLooper()).postDelayed({
            navigateNext()
        }, 2200)
    }

    private fun animateDots() {
        var dotCount = 0
        dotsRunnable = object : Runnable {
            override fun run() {
                dotCount = (dotCount + 1) % 4
                binding.tvLoading.text = "Initializing" + ".".repeat(dotCount)
                dotsHandler.postDelayed(this, 400)
            }
        }
        dotsHandler.post(dotsRunnable!!)
    }

    override fun onDestroy() {
        super.onDestroy()
        // MEMORY LEAK FIX: activity destroy hone pe handler cancel karo
        // Warna runnable views reference hold karta raha aur crash possible tha
        dotsRunnable?.let { dotsHandler.removeCallbacks(it) }
    }

    private fun navigateNext() {
        val intent = if (!prefs.isSetupDone) {
            com.trapix.app.util.DebugLogger.log("SPLASH", "Navigating to SetupLock")
            Intent(this, SetupLockActivity::class.java)
        } else {
            com.trapix.app.util.DebugLogger.log("SPLASH", "Navigating to LockScreen")
            Intent(this, LockScreenActivity::class.java)
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        finish()
    }
}
