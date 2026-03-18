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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)

        // Reset wrong attempt count on every fresh app open
        prefs.resetWrongAttemptCount()

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
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                dotCount = (dotCount + 1) % 4
                binding.tvLoading.text = "Initializing" + ".".repeat(dotCount)
                handler.postDelayed(this, 400)
            }
        }
        handler.post(runnable)
    }

    private fun navigateNext() {
        val intent = if (!prefs.isSetupDone) {
            Intent(this, SetupLockActivity::class.java)
        } else {
            Intent(this, LockScreenActivity::class.java)
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        finish()
    }
}
