package com.trapix.app.ui.gallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import com.trapix.app.R
import com.trapix.app.data.prefs.AppPrefs
import com.trapix.app.databinding.ActivityMainBinding
import com.trapix.app.ui.settings.SettingsActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: GalleryViewModel
    private lateinit var adapter: IntruderAdapter
    private lateinit var prefs: AppPrefs

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            // Show which permissions are missing
            showPermissionRationale()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = ""

        requestRequiredPermissions()
        setupRecyclerView()
        setupViewModel()
        setupFab()
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun showPermissionRationale() {
        com.google.android.material.snackbar.Snackbar.make(
            binding.root,
            "Some permissions are required for full functionality",
            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        ).setAction("Grant") { requestRequiredPermissions() }.show()
    }

    private fun setupRecyclerView() {
        adapter = IntruderAdapter(
            onItemClick = { log -> showImageDetail(log) },
            onItemLongClick = { log -> adapter.toggleSelection(log.id) }
        )
        binding.rvIntruders.layoutManager = GridLayoutManager(this, 2)
        binding.rvIntruders.adapter = adapter

        // Staggered animation
        binding.rvIntruders.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator()
    }

    private fun setupViewModel() {
        viewModel = ViewModelProvider(this)[GalleryViewModel::class.java]

        viewModel.allLogs.observe(this) { logs ->
            adapter.submitList(logs)
            binding.tvEmptyState.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
            binding.tvCaptureCount.text = "${logs.size} captures"
        }

        viewModel.totalCount.observe(this) { count ->
            binding.tvCaptureCount.text = "$count captures"
        }
    }

    private fun setupFab() {
        binding.fabSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
        }
    }

    private fun showImageDetail(log: com.trapix.app.data.model.IntruderLog) {
        val intent = Intent(this, ImageDetailActivity::class.java)
        intent.putExtra(ImageDetailActivity.EXTRA_LOG_ID, log.id)
        startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_delete_all -> {
                confirmDeleteAll()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun confirmDeleteAll() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete All Captures?")
            .setMessage("This will permanently delete all intruder photos.")
            .setPositiveButton("Delete All") { _, _ ->
                viewModel.deleteAll()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // Refresh list on resume
    }
}
