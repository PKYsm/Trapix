package com.trapix.app.ui.gallery

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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
import com.trapix.app.util.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ImageDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_LOG_ID = "log_id"
        private const val TAG = "ImageDetail"
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
            Glide.with(this).load(file).fitCenter()
                .error(R.drawable.ic_broken_image).into(binding.ivFullImage)
        } else {
            binding.ivFullImage.setImageResource(R.drawable.ic_broken_image)
        }

        binding.ivFullImage.setOnClickListener {
            if (file.exists()) {
                startActivity(Intent(this, FullscreenImageActivity::class.java)
                    .putExtra("image_path", file.absolutePath))
            }
        }

        val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault())
        binding.tvDetailTimestamp.text = "📅 ${sdf.format(Date(log.timestamp))}"
        binding.tvDetailCamera.text    = "📷 ${if (log.cameraUsed == "front") "Front Camera" else "Rear Camera"}"
        binding.tvDetailAttempt.text   = "🔢 Attempt #${log.attemptNumber}"
        binding.tvDetailDevice.text    = "📱 ${log.deviceInfo}"

        if (log.latitude != 0.0 && log.longitude != 0.0) {
            binding.tvDetailLocation.text       = "📍 ${"%.5f".format(log.latitude)}, ${"%.5f".format(log.longitude)}"
            binding.tvDetailLocation.visibility = View.VISIBLE
            binding.btnOpenMaps.visibility      = View.VISIBLE
        } else {
            binding.tvDetailLocation.text = "📍 Location unavailable"
            binding.btnOpenMaps.visibility = View.GONE
        }

        binding.tvImagePath.text = log.imagePath
    }

    private fun setupButtons() {
        binding.btnSaveGallery.setOnClickListener { saveToGallery() }
        binding.btnShare.setOnClickListener       { shareImage() }
        binding.btnShareText.setOnClickListener   { shareAsText() }
        binding.btnCopyClipboard.setOnClickListener { copyToClipboard() }
        binding.btnDelete.setOnClickListener      { confirmDelete() }
        binding.btnOpenMaps.setOnClickListener    { openInMaps() }
    }

    /**
     * BUG 7 FIX: Gallery save - MediaStore API use karo (works on all Android versions)
     * Pehle wala MediaScannerConnection sirf public external storage ke liye kaam karta tha,
     * filesDir (private internal storage) ke liye nahi.
     */
    private fun saveToGallery() {
        val log  = currentLog ?: return
        val file = File(log.imagePath)
        if (!file.exists()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // API 29+: MediaStore
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Trapix")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use { out ->
                            file.inputStream().use { it.copyTo(out) }
                        }
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                        DebugLogger.log(TAG, "Manual gallery save OK (MediaStore): ${file.name}")
                        true
                    } else false
                } else {
                    // API < 29: External storage copy
                    val picturesDir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Trapix"
                    ).also { it.mkdirs() }
                    val dest = File(picturesDir, file.name)
                    file.copyTo(dest, overwrite = true)
                    android.media.MediaScannerConnection.scanFile(
                        this@ImageDetailActivity, arrayOf(dest.absolutePath), arrayOf("image/jpeg"), null)
                    DebugLogger.log(TAG, "Manual gallery save OK (copy): ${dest.absolutePath}")
                    true
                }

                withContext(Dispatchers.Main) {
                    if (success) Toast.makeText(this@ImageDetailActivity, "Saved to Gallery ✅", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(this@ImageDetailActivity, "Gallery save failed ❌", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                DebugLogger.error(TAG, "Manual gallery save error: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ImageDetailActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun shareImage() {
        val log  = currentLog ?: return
        val file = File(log.imagePath)
        if (!file.exists()) { Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show(); return }
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share via"
        ))
    }

    private fun shareAsText() {
        val log = currentLog ?: return
        val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.getDefault())
        val text = buildString {
            appendLine("🔐 Trapix Intruder Alert"); appendLine("━━━━━━━━━━━━━━━━━━")
            appendLine("📅 Time: ${sdf.format(Date(log.timestamp))}")
            appendLine("📷 Camera: ${if (log.cameraUsed == "front") "Front" else "Rear"}")
            appendLine("🔢 Attempt: #${log.attemptNumber}")
            appendLine("📱 Device: ${log.deviceInfo}")
            if (log.latitude != 0.0) appendLine("📍 Location: ${log.latitude}, ${log.longitude}")
        }
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }, "Share info"
        ))
    }

    private fun copyToClipboard() {
        val log  = currentLog ?: return
        val file = File(log.imagePath)
        if (!file.exists()) { Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show(); return }
        val uri  = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val clip = ClipData.newUri(contentResolver, "Intruder Image", uri)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        Toast.makeText(this, "Image URI copied 📋", Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete Capture?")
            .setMessage("This will permanently delete this intruder photo.")
            .setPositiveButton("Delete") { _, _ ->
                currentLog?.let { log ->
                    lifecycleScope.launch {
                        dao.delete(log)
                        File(log.imagePath).delete()
                        runOnUiThread {
                            Toast.makeText(this@ImageDetailActivity, "Deleted", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    /**
     * BUG 5 FIX: Android 11+ pe resolveActivity() null return karta hai (package visibility restriction).
     * try-catch se directly startActivity karo + browser fallback.
     */
    private fun openInMaps() {
        val log = currentLog ?: return
        if (log.latitude == 0.0 && log.longitude == 0.0) {
            Toast.makeText(this, "Location not available", Toast.LENGTH_SHORT).show()
            return
        }

        // Try 1: geo: URI (Google Maps / any maps app)
        try {
            val geoUri = Uri.parse("geo:${log.latitude},${log.longitude}?q=${log.latitude},${log.longitude}(Intruder+Location)")
            startActivity(Intent(Intent.ACTION_VIEW, geoUri))
            return
        } catch (_: Exception) {}

        // Try 2: Google Maps browser link
        try {
            val browserUri = Uri.parse("https://maps.google.com/?q=${log.latitude},${log.longitude}")
            startActivity(Intent(Intent.ACTION_VIEW, browserUri))
            return
        } catch (_: Exception) {}

        Toast.makeText(this, "No maps or browser found", Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed(); return true
    }
}
