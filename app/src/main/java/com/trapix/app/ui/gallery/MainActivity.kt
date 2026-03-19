package com.trapix.app.ui.gallery

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.chip.Chip
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
        if (!permissions.values.all { it }) showPermissionRationale()
        updatePermissionChips()
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
        setupSearch()
        setupFilterChips()
    }

    // ── Permissions ────────────────────────────────────────────────────────────

    private fun requestRequiredPermissions() {
        val missing = getRequiredPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun getRequiredPermissions(): List<String> {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.READ_MEDIA_IMAGES
            list += Manifest.permission.POST_NOTIFICATIONS
        } else {
            list += Manifest.permission.READ_EXTERNAL_STORAGE
            list += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        return list
    }

    private fun updatePermissionChips() {
        val hasCam  = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasLoc  = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        binding.chipPermCamera.isChecked = hasCam
        binding.chipPermCamera.text = if (hasCam) "📷 Camera ✓" else "📷 Camera ✗"
        binding.chipPermLocation.isChecked = hasLoc
        binding.chipPermLocation.text = if (hasLoc) "📍 Location ✓" else "📍 Location ✗"

        binding.chipPermCamera.setOnClickListener {
            if (!hasCam) permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
        binding.chipPermLocation.setOnClickListener {
            if (!hasLoc) permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    private fun showPermissionRationale() {
        com.google.android.material.snackbar.Snackbar.make(
            binding.root, "Some permissions required for full functionality",
            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        ).setAction("Grant") { requestRequiredPermissions() }.show()
    }

    // ── RecyclerView ───────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = IntruderAdapter(
            onItemClick      = { log -> showImageDetail(log) },
            onItemLongClick  = { log -> adapter.toggleSelection(log.id) }
        )
        binding.rvIntruders.layoutManager = GridLayoutManager(this, 2)
        binding.rvIntruders.adapter = adapter
        binding.rvIntruders.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator()
    }

    private fun setupViewModel() {
        viewModel = ViewModelProvider(this)[GalleryViewModel::class.java]

        viewModel.allLogs.observe(this) { logs ->
            adapter.submitList(logs)
            val total  = viewModel.totalCount.value ?: 0
            val shown  = logs.size
            binding.tvCaptureCount.text = if (shown == total) "$total captures" else "$shown / $total"
            binding.tvEmptyState.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.totalCount.observe(this) { total ->
            val shown = viewModel.allLogs.value?.size ?: total
            binding.tvCaptureCount.text = if (shown == total) "$total captures" else "$shown / $total"
        }
    }

    // ── Search ─────────────────────────────────────────────────────────────────

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s?.toString() ?: "")
                binding.ivClearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.ivClearSearch.setOnClickListener {
            binding.etSearch.text?.clear()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        }
    }

    // ── Filter Chips ───────────────────────────────────────────────────────────

    private fun setupFilterChips() {
        // Camera filter
        binding.chipCamAll.isChecked = true
        binding.chipGroupCamera.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when {
                checkedIds.contains(R.id.chipCamFront) -> GalleryViewModel.CameraFilter.FRONT
                checkedIds.contains(R.id.chipCamRear)  -> GalleryViewModel.CameraFilter.REAR
                else                                    -> GalleryViewModel.CameraFilter.ALL
            }
            viewModel.setCameraFilter(filter)
        }

        // Date filter
        binding.chipDateAll.isChecked = true
        binding.chipGroupDate.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when {
                checkedIds.contains(R.id.chipDateToday) -> GalleryViewModel.DateFilter.TODAY
                checkedIds.contains(R.id.chipDateWeek)  -> GalleryViewModel.DateFilter.WEEK
                checkedIds.contains(R.id.chipDateMonth) -> GalleryViewModel.DateFilter.MONTH
                else                                    -> GalleryViewModel.DateFilter.ALL
            }
            viewModel.setDateFilter(filter)
        }

        updatePermissionChips()
    }

    // ── FAB ────────────────────────────────────────────────────────────────────

    private fun setupFab() {
        binding.fabSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
        }
        binding.fabSettings.setOnLongClickListener {
            startActivity(Intent(this, com.trapix.app.ui.debug.DebugActivity::class.java))
            true
        }
    }

    private fun showImageDetail(log: com.trapix.app.data.model.IntruderLog) {
        startActivity(Intent(this, ImageDetailActivity::class.java)
            .putExtra(ImageDetailActivity.EXTRA_LOG_ID, log.id))
        overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out)
    }

    // ── Menu ───────────────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java)); true
        }
        R.id.action_delete_all -> { confirmDeleteAll(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun confirmDeleteAll() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Delete All Captures?")
            .setMessage("This will permanently delete all intruder photos.")
            .setPositiveButton("Delete All") { _, _ -> viewModel.deleteAll() }
            .setNegativeButton("Cancel", null).show()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionChips()
    }
}
