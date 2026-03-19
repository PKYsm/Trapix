package com.trapix.app.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.trapix.app.databinding.ActivityDebugBinding
import com.trapix.app.util.DebugLogger

class DebugActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDebugBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "🐛 Debug Logs"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        loadLogs()

        binding.btnRefresh.setOnClickListener { loadLogs() }

        binding.btnCopy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Trapix Logs", binding.tvLogs.text))
            Toast.makeText(this, "Logs copied! 📋", Toast.LENGTH_SHORT).show()
        }

        binding.btnClear.setOnClickListener {
            DebugLogger.clearLogs()
            loadLogs()
            Toast.makeText(this, "Cleared!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadLogs() {
        val logs = DebugLogger.getLogs()
        binding.tvLogs.text = logs
        // Scroll to bottom
        binding.scrollView.post {
            binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
