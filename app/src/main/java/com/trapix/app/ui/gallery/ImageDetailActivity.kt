package com.trapix.app.ui.gallery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.trapix.app.R
import com.trapix.app.data.db.AppDatabase
import com.trapix.app.data.model.IntruderLog
import com.trapix.app.databinding.ActivityImageDetailBinding
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ImageDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LOG_ID = "log_id"
    }

    private lateinit var binding: ActivityImageDetailBinding
    private var currentLog: IntruderLog? = null
    private val dao by lazy { AppDatabase.getInstance(this).intruderDao() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val logId = intent.getLongExtra(EXTRA_LOG_ID, -1L)
        if (logId == -1L) { finish(); return }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Capture Detail"

        loadLog(logId)
        setupButtons()
    }

    private fun loadLog(id: Long) {
        lifecycleScope.launch {
            val logs = dao.getAllLogsList()
            currentLog = logs.find { it.id == id }
            currentLog?.let { displayLog(it) }
        }
    }

    private fun displayLog(log: IntruderLog) {
        val file = File(log.imagePath)
        if (file.exists()) {
            Glide.with(this)
                .load(file)
                .fitCenter()
                .error(R.drawable.ic_broken_image)
                .into(binding.ivFullImage)
        } else {
            binding.ivFullImage.setImageResource(R.drawable.ic_broken_image)
        }

        // Click image to zoom/fullscreen
        binding.ivFullImage.setOnClickListener {
            if (file.exists()) {
                val intent = Intent(this, FullscreenImageActivity::class.java)
                intent.putExtra("image_path", file.absolutePath)
                startActivity(intent)
            }
        }

        val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault())
        binding.tvDetailTimestamp.text = "📅 ${sdf.format(Date(log.timestamp))}"
        binding.tvDetailCamera.text = "📷 ${if (log.cameraUsed == "front") "Front Camera" else "Rear Camera"}"
        binding.tvDetailAttempt.text = "🔢 Attempt #${log.attemptNumber}"
        binding.tvDetailDevice.text = "📱 ${log.deviceInfo}"

        if (log.latitude != 0.0 && log.longitude != 0.0) {
            binding.tvDetailLocation.text = "📍 ${String.format("%.5f", log.latitude)}, ${String.format("%.5f", log.longitude)}"
            binding.tvDetailLocation.visibility = View.VISIBLE
            binding.btnOpenMaps.visibility = View.VISIBLE
        } else {
            binding.tvDetailLocation.text = "📍 Location unavailable"
        }

        binding.tvImagePath.text = log.imagePath
    }

    private fun setupButtons() {
        binding.btnSaveGallery.setOnClickListener { saveToGallery() }
        binding.btnShare.setOnClickListener { shareImage() }
        binding.btnShareText.setOnClickListener { shareAsText() }
        binding.btnCopyClipboard.setOnClickListener { copyToClipboard() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
        binding.btnOpenMaps.setOnClickListener { openInMaps() }
    }

    private fun saveToGallery() {
        val log = currentLog ?: return
        val file = File(log.imagePath)
        if (!file.exists()) { Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show(); return }
        MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/jpeg")) { _, _ ->
            runOnUiThread { Toast.makeText(this, "Saved to Gallery ✅", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun shareImage() {
        val log = currentLog ?: return
        val file = File(log.imagePath)
        if (!file.exists()) { Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show(); return }
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share via"))
    }

    private fun shareAsText() {
        val log = currentLog ?: return
        val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault())
        val text = buildString {
            appendLine("🔐 Trapix Intruder Alert")
            appendLine("━━━━━━━━━━━━━━━━━━")
            appendLine("📅 Time: ${sdf.format(Date(log.timestamp))}")
            appendLine("📷 Camera: ${if (log.cameraUsed == "front") "Front" else "Rear"}")
            appendLine("🔢 Attempt: #${log.attemptNumber}")
            appendLine("📱 Device: ${log.deviceInfo}")
            if (log.latitude != 0.0) appendLine("📍 Location: ${log.latitude}, ${log.longitude}")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share info"))
    }

    private fun copyToClipboard() {
        val log = currentLog ?: return
        val file = File(log.imagePath)
        if (!file.exists()) { Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show(); return }
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newUri(contentResolver, "Intruder Image", uri)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Image URI copied to clipboard 📋", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete Capture?")
            .setMessage("This will permanently delete this intruder photo.")
            .setPositiveButton("Delete") { _, _ ->
                currentLog?.let {
                    lifecycleScope.launch {
                        dao.delete(it)
                        File(it.imagePath).delete()
                        runOnUiThread {
                            Toast.makeText(this@ImageDetailActivity, "Deleted", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openInMaps() {
        val log = currentLog ?: return
        val uri = Uri.parse("geo:${log.latitude},${log.longitude}?q=${log.latitude},${log.longitude}(Intruder+Location)")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        if (intent.resolveActivity(packageManager) != null) startActivity(intent)
        else Toast.makeText(this, "No maps app found", Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
