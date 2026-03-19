package com.trapix.app.ui.gallery

import android.app.Application
import androidx.lifecycle.*
import com.trapix.app.data.db.AppDatabase
import com.trapix.app.data.model.IntruderLog
import kotlinx.coroutines.launch
import java.util.Calendar

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getInstance(application).intruderDao()

    // Feature 7: Filter state
    enum class CameraFilter { ALL, FRONT, REAR }
    enum class DateFilter { ALL, TODAY, WEEK, MONTH }

    private val _cameraFilter = MutableLiveData(CameraFilter.ALL)
    private val _dateFilter   = MutableLiveData(DateFilter.ALL)
    private val _searchQuery  = MutableLiveData("")

    val cameraFilter: LiveData<CameraFilter> = _cameraFilter
    val dateFilter: LiveData<DateFilter>     = _dateFilter

    // Raw all logs from DB
    private val _allRawLogs = dao.getAllLogs()
    val totalCount          = dao.getCount()

    // Filtered logs — derived from raw + filter state via MediatorLiveData
    val allLogs: LiveData<List<IntruderLog>> = MediatorLiveData<List<IntruderLog>>().also { mediator ->
        fun recompute() {
            val raw    = _allRawLogs.value ?: return
            val cam    = _cameraFilter.value ?: CameraFilter.ALL
            val date   = _dateFilter.value   ?: DateFilter.ALL
            val query  = _searchQuery.value  ?: ""
            mediator.value = applyFilters(raw, cam, date, query)
        }
        mediator.addSource(_allRawLogs)   { recompute() }
        mediator.addSource(_cameraFilter) { recompute() }
        mediator.addSource(_dateFilter)   { recompute() }
        mediator.addSource(_searchQuery)  { recompute() }
    }

    private fun applyFilters(
        logs: List<IntruderLog>,
        cam: CameraFilter,
        date: DateFilter,
        query: String
    ): List<IntruderLog> {
        var result = logs

        // Camera filter
        result = when (cam) {
            CameraFilter.FRONT -> result.filter { it.cameraUsed == "front" }
            CameraFilter.REAR  -> result.filter { it.cameraUsed == "rear" }
            CameraFilter.ALL   -> result
        }

        // Date filter
        val fromMs = when (date) {
            DateFilter.TODAY -> {
                val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
                cal.timeInMillis
            }
            DateFilter.WEEK -> {
                val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -7) }
                cal.timeInMillis
            }
            DateFilter.MONTH -> {
                val cal = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }
                cal.timeInMillis
            }
            DateFilter.ALL -> 0L
        }
        if (fromMs > 0) result = result.filter { it.timestamp >= fromMs }

        // Text search — match attempt number or date string
        if (query.isNotBlank()) {
            val q = query.lowercase()
            result = result.filter {
                it.attemptNumber.toString().contains(q) ||
                it.cameraUsed.contains(q) ||
                it.deviceInfo.lowercase().contains(q) ||
                java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())
                    .format(java.util.Date(it.timestamp)).lowercase().contains(q)
            }
        }

        return result
    }

    fun setCameraFilter(f: CameraFilter) { _cameraFilter.value = f }
    fun setDateFilter(f: DateFilter)     { _dateFilter.value = f }
    fun setSearchQuery(q: String)        { _searchQuery.value = q }
    fun clearFilters() {
        _cameraFilter.value = CameraFilter.ALL
        _dateFilter.value   = DateFilter.ALL
        _searchQuery.value  = ""
    }

    fun delete(log: IntruderLog)          = viewModelScope.launch { dao.delete(log) }
    fun deleteByIds(ids: List<Long>)      = viewModelScope.launch { dao.deleteByIds(ids) }
    fun deleteAll()                       = viewModelScope.launch { dao.deleteAll() }
}
