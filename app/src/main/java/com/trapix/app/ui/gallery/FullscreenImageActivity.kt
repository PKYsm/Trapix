package com.trapix.app.ui.gallery

import android.os.Bundle
import android.view.ScaleGestureDetector
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.trapix.app.databinding.ActivityFullscreenImageBinding
import java.io.File

class FullscreenImageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFullscreenImageBinding
    private var scaleFactor = 1f
    private lateinit var scaleDetector: ScaleGestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFullscreenImageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Full screen immersive
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        )

        val imagePath = intent.getStringExtra("image_path") ?: run { finish(); return }
        val file = File(imagePath)

        Glide.with(this)
            .load(file)
            .fitCenter()
            .into(binding.ivFullscreen)

        // Pinch to zoom
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(0.5f, 5f)
                binding.ivFullscreen.scaleX = scaleFactor
                binding.ivFullscreen.scaleY = scaleFactor
                return true
            }
        })

        binding.ivFullscreen.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            v.performClick()
            true
        }

        // Tap to close
        binding.ivFullscreen.setOnClickListener {
            if (!scaleDetector.isInProgress) finish()
        }

        binding.btnClose.setOnClickListener { finish() }
    }
}
